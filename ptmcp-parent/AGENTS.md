# 后端框架规则

遵循根AGENTS。父POM是实际构建入口；在本目录运行Wrapper，构建前按环境指南选择Java21。Spotless/Checkstyle/Enforcer已绑定validate。

当前只有标准启动类与原生健康检查，不在框架搭建任务中增加业务接口。新增控制器/SQL/认证须另有工作项与对应合同和测试；不得在鉴权、权限、guard/审计尚未就绪时提供查询旁路。

common/guard保持纯Java；gateway/authz不引入若依或Sa-Token，admin后续独立适配。不要把数据库密码写进application.yml。依赖、构建命令和模块状态以实际POM/[README](README.md)为准。
