# 授权计算、委派与失效一致性设计

版本：1.1-design · 2026-10-24 · 状态：T08-a 已实现单资源 Allow/Deny、时间窗与平台交集判定（纯函数）；继承/资源树展开、实库来源、缓存与 ≤5 秒撤销仍待实现。MySQL主库权威核验及5秒撤销目标须经M0多副本故障测试；Redis命中率不是授权正确性证据。

## 1. 两个独立权限平面

控制台功能权限是ptmcp:*菜单/接口动作，管理范围是平台管理员显式委派的部门和资源范围；业务数据权限是主体对物理资源的META/READ。具有后台管理员、若依superadmin或audit:read并不自动READ业务表。组织授权管理员只能在委派交集内维护授权，不能修改自身委派、不能给范围外用户/角色授予权限。

一期动作仅META和READ，effect为ALLOW/DENY。列/行权限二期，仅预留不接受未生效规则。物理目标为数据源→命名空间→对象世代三级，include_descendants=true才向下展开。对象删除重建有新generation，旧授权stale不继承。平台scope为同样资源树的能力上限，但不等于用户grant。

## 2. 主体展开与动作关系

主体集合S(user)包括：本人USER；sys_user_role中的有效ROLE（status=0、del_flag=0）；直属有效DEPT；配置明确启用部门继承时的所有有效上级DEPT。部门树从parent_id权威边重建/校验，无环；ancestors只能缓存。每个部门只在自身status=0、del_flag=0时贡献主体；停用/软删某节点只排除该节点，继续检查其上有效祖先，不能连同上级DENY截断。直属无效时deptId为空，USER与有效ROLE仍参与。继承开关变化提升policy_epoch。岗位不进入一期表ACL。

业务授权读取以活动sys_user_profile为锚点，在单条有界递归查询内校验直属归属和parent_id链，statement超时5秒。最多64个实际节点（含直属），仅parent_id=0为正常根；自环、多节点环、缺直属、非零缺父、负父ID或需要第65节点均拒绝SECURITY_STATE_UNAVAILABLE，不能返回部分主体。继承关闭仍校验完整链，但只贡献有效直属部门，这是对旧读取路径的可用性收紧。当前AuthorizationReader和AclDirectory共用DepartmentSubjectReader；AclDirectory单参构造器及生产网关均默认继承，布尔构造器用于显式关闭且统一作用于scope、grants与preview。本片不提供部署开关/开关变更epoch，不证明角色、grants、平台范围等跨语句整体快照一致，也不代表管理委派或部门写树校验已完成。验证状态见[网关收尾](../../tasks/work-items/DEV-20261028-gateway-completion.md)。

对每条grant先验证主体有效、目标世代有效、状态ACTIVE，按树展开include_descendants，再按UTC时刻t选择valid_from<=t且(valid_to为空或t<valid_to)。时间区间半开[valid_from,valid_to)，没有“当天结束”模糊语义。

| 规则 | META有效 | READ有效 |
| --- | --- | --- |
| META ALLOW | 允许目录 | 不自动允许数据/列 |
| READ ALLOW | 隐含META ALLOW | 允许数据候选 |
| META DENY | 拒绝目录 | 同时拒绝READ |
| READ DENY | 不自动拒绝META | 拒绝数据 |

不按个人优先于角色/部门绕过DENY。所有命中来源合并后DENY优先，显式ALLOW不能“覆盖”祖先DENY。平台/数据源/用户/客户端停用优先，资源不存在/世代stale拒绝。

~~~text
A_meta = META_ALLOW ∪ READ_ALLOW （展开后的物理资源集合）
A_read = READ_ALLOW
D_meta = META_DENY
D_read = READ_DENY
UserMeta = A_meta - D_meta
UserRead = A_read - D_read - D_meta
ResourceRead = UserRead ∩ PlatformRead ∩ ActiveResources
ResourceMeta = UserMeta ∩ (PlatformMeta ∪ PlatformRead) ∩ ActiveResources
CanQuery = ResourceRead ∩ TokenScope(mcp:query)
CanSchema = ResourceRead ∩ TokenScope(mcp:schema)
CanCatalog = ResourceMeta ∩ TokenScope(mcp:catalog)
~~~

TokenScope表示token获对应scope且客户端/平台仍允许；三个scope各自独立，不互相隐含。mcp:schema与ResourceRead同时才可字段结构，不能因没有mcp:query就失去该用户READ资格。平台READ范围隐含平台META范围，用户READ隐含META，最终Tool再按对应scope判定。本文EffectiveRead/EffectiveMeta指ResourceRead/ResourceMeta，authorized目录标记为该用户在平台交集内的READ资格，不冒充当前token一定获query能力。无表SELECT也要求mcp:query及有效平台/数据源，不因为没有表而绕开来源/预算/审计。

## 3. 目录模式与隐私

DISCOVER：在平台允许META/READ范围和token catalog能力内可列表名、表注释，authorized标实际EffectiveRead。无用户Allow也可被目录披露，但任何显式META DENY仍隐藏；系统命名空间/停用对象不披露。HIDE：只列EffectiveMeta或EffectiveRead对象。两种模式下get_table_schema严格要求READ+mcp:schema，字段、键、字段注释均不能靠META取得；execute要求READ+mcp:query。

批量get_table_schema含无权对象则整次拒绝，避免把部分返回作为越权枚举。错误只显示调用者可见的对象名；无META对象用泛化拒止。表注释/业务字典是组织输入，也可能含敏感文本，需要管理员治理和输出长度限制，不能视为安全指令。

## 4. 权限快照和缓存

PolicySnapshot包括userId/authVersion/identityVersion、有效role/dept列表及版本、platformId/securityVersion/scopeVersion、datasourceId/configVersion/resourceVersion/snapshotId、globalPolicyEpoch、evaluatedAt、nextBoundaryAt、authoritativelyCheckedAt、effective META/READ及拒止原因。键至少包含用户、平台、数据源、相关版本，不能只按userId缓存跨平台权限。

有效权限缓存最多300秒，TTL=min(300秒,最近未来时间边界-now)。边界从所有可能影响候选资源的相关grant的未来valid_from和valid_to计算，必须包括尚未生效的DENY、祖先范围grant以及主体/委派状态时间，不能先过滤成“当前有效grant”再求边界。边界到达即使Redis条目还存在也按nextBoundaryAt绝对时间拒用/重算，秒级TTL取整不会延长授权；边界小于1秒直接不缓存。缓存命中仍比较当前安全版本、状态与当前时刻，不能用滑动TTL无限续命。

Schema缓存只读已发布snapshot，过期/缺失拒绝；不能缓存不存在直到跨DROP/CREATE变同名对象。授权集合有资源generation，快照变化使相关条目失效。多对象权限查询必须来自同一一致性快照，不把分别命中的新旧角色/平台缓存拼起来。

## 5. 已提交撤销对新查询≤5秒的机制

安全真源是MySQL主库，Redis只提供加速/通知。设计约束：每gateway本地安全状态缓存最多1秒；任何新查询使用的权威安全快照自authoritativelyCheckedAt起不得陈旧超过5秒。authoritativelyCheckedAt只能由成功读主库的新鲜一致快照产生，不能由Redis命中、事件心跳或复制缓存重置。

gateway进入请求读取/核验安全快照；排队出队时和JDBC执行提交前再核验。为确保5秒目标，每次复查若本地缓存≤1秒且原authoritativelyCheckedAt≤5秒可用；否则必须查主库。主库查询本身超时/异常、节点时钟异常、epoch不一致时拒绝SECURITY_STATE_UNAVAILABLE，不能以Redis旧值后备。主库读不能使用有延迟的只读副本。使用单调时钟衡量本进程缓存年龄，UTC衡量绝对授权时间；跨进程缓存带原权威时间并校验可信签名/来源，未知时间拒绝。

通过主库新建短事务取得sys_security_state epoch及用户/family/client/platform状态，epoch变更即重取该一致快照内策略数据，不能只比较用户版本遗漏角色/部门事件。authoritativelyCheckedAt保守取主库短事务快照起始时刻，完成时若快照年龄已超过5秒，拒绝或有界重读；不能用查询完成时刻把慢读旧快照刷新为新鲜。REPEATABLE READ同一快照二读epoch只能说明内部一致，不证明期间无其他提交；不作此证明。执行前新鲜核验使用新的主库短事务/current read取得新状态，而不是继续旧事务snapshot。若撤销刚在主库提交、当前查询使用提交前不足1秒的快照，最多容忍目标窗口内启动；严格零时延撤销不在一期承诺。已进入DB在途请求尽力取消，已返回数据不能收回。

5秒目标不是“30秒补扫”推导而来：撤销事务同时写版本+outbox；Redis Pub/Sub快速失效；每30秒补扫未消费事件补偿消息丢失。补扫即使停机一分钟也不能延长新查询授权，因为权威状态必须≤5秒；消息丢失只影响缓存命中率/在途取消速度。所有服务暴露安全快照年龄指标，超过阈值不继续执行。

## 6. 所有变化的闭环矩阵

| 事件 | 同事务权威变化 | 缓存/令牌行为 |
| --- | --- | --- |
| 用户停用/删除 | status/del_flag、authVersion、identityVersion、epoch、事件；族/PAT/session标记 | 所有新认证拒绝，清缓存/尽力取消在途；重启用不恢复旧凭证 |
| 用户改密/重置 | password、authVersion、epoch、撤销记录、事件 | 所有登录态/族/PAT失效；账号保持ACTIVE |
| 角色增删/停用、成员变化 | role/成员数据、subject policyVersion、用户identityVersion按需、epoch、事件 | 重新展开角色和grant；不靠用户本人修改事件 |
| 部门停用/删除、迁移、用户换部门 | parent/tree/membership、相关主体version、epoch、事件 | 重算新旧父链与继承，不只删当前部门缓存 |
| ACL增删改/撤销 | grant/version、subject version、epoch、事件 | 当前epoch失配重算；未来grant也更新nextBoundary |
| 授权自然生效/到期 | 无需后台写事件 | nextBoundaryAt即时使缓存不可用，不能等30秒扫描 |
| 平台Scope/目录模式/Tier/proof变更 | platform版本、epoch、事件 | scope重新相交，proof重新验证；必要时撤销客户端族 |
| 客户端停用/回调/认证方式变化 | client版本、族撤销、epoch、事件 | 旧token不能继续凭签名有效使用 |
| PAT/family撤销/refresh重放 | status/version、epoch/安全事件 | PAT拒绝/整族JWT拒绝 |
| 数据源停用/换凭证、Schema发布/删除重建/视图定义变化 | config/resource版本、epoch、事件 | 旧ApprovedQuery拒绝/重守门，旧池排空，旧对象grant不自动迁移 |
| 委派撤销/到期 | delegation状态/version、epoch、事件 | 后台写权限重算；不自动改变被委派者已依法授予的业务grant，需另行撤销策略 |

外部脚本直接写若依表会跳过闭环，一期只允许经PTMCP管理域写入；数据库凭证禁止通用后台直接写组织表。已有若依接口必须包装/禁用，不能保留旁路。紧急运维SQL需同事务更新版本/outbox并有审核日志及恢复校验。

## 7. 管理范围委派

admin_delegation是平台管理员给组织授权管理员的管理授权，包含userId、departmentRootId、resourceType/resourceId/generation、allowedActions、validFrom/validTo、status/version。服务端把多个委派分别校验，不能把“委派A的部门”与“委派B的资源”交叉拼出没有授予的新组合。管理命令必须由至少一个完整委派覆盖该命令的所有目标及动作；批量多目标逐项校验，任一失败整个事务不提交。

部门和主体的范围归属从target的parent_id权威链判定，不使用ancestors缓存。单条有界查询读取完整链，含target最多64节点，只parent_id=0为正常根；缺父、负父、环或需第65节点拒绝SECURITY_STATE_UNAVAILABLE，匹配委派根或target等于根也不能提前结束。未知target返回不覆盖；已存在target先校验完整链，再要求target和命中的根都未软删。停用但未删除的节点仍可受委派管理以重新启用；中间节点停用/软删不改父边拓扑，不能套用业务DEPT主体过滤。SQL故障维持DEPENDENCY_UNAVAILABLE。这一片仅定义单目标范围读取，不代表完整PUT同一委派覆盖新旧父/子树、事务提交前重验、写树或epoch闭环已实现；实际验证见[网关收尾](../../tasks/work-items/DEV-20261028-gateway-completion.md)。

部门完整PUT由同一条有效委派包含ptmcp:department:update且覆盖旧目标、移动后的新父部门，不能拼接不同委派。未移动只需覆盖旧目标，允许委派根自身改名/重新启用；实际提升为根仅平台管理员允许。目标读取快照先与If-Match相等，再判断父边变化，落库CAS仍保留。此入口切片已有真实MySQL/Servlet反例及组合通过证据；整子树委派和提交前授权重验仍待补，验证状态见网关收尾工作项。

部门create/edit/update/move/delete的写命令统一经SecurityChangeTransaction：先锁sys_security_state，
再按dept_id锁定部门快照，从parent_id构造权威拓扑；ancestors不参与防环或确定后代写集。
只校命令涉及的旧链、目的链和移动子树，无关坏分支不阻断。停用/软删桥保持拓扑，移动重建所有真实后代
（含软删行）的缓存并各自提升版本，不改伪造缓存的外部分支。先验证全部旧后代的完整链和版本，
再规划新路径：已有环/缺父/负父/超过64节点为SECURITY_STATE_UNAVAILABLE；拟议环、深度超过64节点
或缓存超过现有VARCHAR(500)为INVALID_ARGUMENT，无写入且不截断。原地编辑可保留软删父边，实际新父须未软删。
目标、后代版本、操作日志、policy_epoch及DEPARTMENT_CHANGED事件原子提交；创建事件绑定真实生成ID/version1。
丢失提交确认返回OPERATION_UNKNOWN，不自动重放。该锁不替代事务内actor/角色/委派重验或整子树授权；
删除的直接子部门/直属用户检查尚未与用户写入共用围栏，引用并发和容量仍待独立验证。

创建Allow的动作/对象/主体范围与最长有效期不能超委派；DENY也需管理权，不允许范围外干扰。全局平台管理员分配委派可选READ/META管理动作并明确是否有撤销权。用户移动需旧/新部门均可管理，部门移动需整棵子树权限、无环；共享角色有范围外成员时禁止组织管理员修改角色授权/成员，以防间接扩权。委派授予管理权不自动给管理员本人业务READ。

组织授权管理员还受“不得自扩业务权限”约束。服务端在同一命令的提交前计算修改前/后本人EffectiveMeta/EffectiveRead，覆盖本人USER、本人所属ROLE/DEPT及上级、成员关系/部门树改变、移除本人命中DENY等间接路径；只要任一资源本人META或READ增加即拒SELF_PRIVILEGE_ESCALATION，不能靠先改role membership再改grant分步绕过。对不易有限展开的祖先范围规则，用策略差异证明或保守拒绝，不只检查subject_id==当前userId。需要给组织管理员自身增权时由平台管理员另行显式操作并审计；平台管理员菜单角色本身仍不自动READ。该检查与委派范围共同执行，不能用其中一项替代另一项。

## 8. 验收不变量

同一输入快照、同一UTC时刻得同一判定；READ Allow隐META、META Deny阻READ、READ Deny保META；平台外永拒；默认无授权；管理员不自动查询；目录不漏列；删除重建不继承；未来Deny到点立刻生效。M0多副本热缓存撤销测试包含丢Pub/Sub/停补扫/Redis不可用/主库超时/时钟跳变/排队三秒/出队前撤销，并记录主库提交到最后一次新查询启动差值≤5秒。测试不通过不能把撤销目标改成“最终一致”。
