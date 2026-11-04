/* 接入配置向导与列表共用的选项集合与步骤定义。
   放在这里是因为 Wizard 拆到 ./integrations/ 后两边都要引用同一份，避免重复维护。 */
import {
  BoltIcon,
  CodeIcon,
  GearIcon,
  KeyIcon,
  LockIcon,
  MonitorIcon,
  PuzzleIcon,
  ShieldIcon,
  StreamIcon,
  type IconComponent,
} from '../../../components/Icons';

export const CLIENT_TYPES: { value: string; label: string; desc: string; icon: IconComponent }[] = [
  { value: 'GENERIC', label: '通用客户端', desc: '适用于任何支持 MCP 的客户端', icon: PuzzleIcon },
  { value: 'WORKBUDDY', label: 'WorkBuddy', desc: '企业级 AI 助手', icon: BoltIcon },
  { value: 'CURSOR', label: 'Cursor', desc: 'AI 编程助手', icon: CodeIcon },
  { value: 'CLAUDE_CODE', label: 'Claude Code', desc: 'Anthropic 开发工具', icon: GearIcon },
  { value: 'VSCODE', label: 'VS Code', desc: 'Visual Studio Code', icon: MonitorIcon },
];

/** 与设计稿一致的选项集合。是否可点由服务端 mcp-config 决定，不支持的卡片保留可见但禁用。 */
export const TRANSPORTS: { value: 'streamable-http' | 'stdio'; label: string; desc: string; icon: IconComponent }[] = [
  { value: 'streamable-http', label: 'streamable-http', desc: '通过 HTTP 流式传输与 MCP 服务通信', icon: StreamIcon },
  { value: 'stdio', label: 'stdio', desc: '本地进程通信，仅当服务端声明该传输时可选', icon: CodeIcon },
];
export const AUTHS: { value: 'oauth-pat' | 'apikey' | 'none'; label: string; desc: string; icon: IconComponent }[] = [
  { value: 'oauth-pat', label: 'OAuth / PAT', desc: '推荐使用，支持个人访问令牌（PAT）', icon: ShieldIcon },
  { value: 'apikey', label: 'API Key', desc: '使用服务端声明的 API Key 认证', icon: KeyIcon },
  { value: 'none', label: '无认证', desc: '仅当服务端明确声明测试环境无认证时可选', icon: LockIcon },
];

export const STEPS = ['选择平台', '选择客户端', '连接方式与认证', '获取配置模板'];

/** 完成页只给出与服务端版本对应、需要人工在客户端执行的步骤，不声称已连通。 */
export const DONE_STEPS: { title: string; desc: string; linkLabel?: string; linkHash?: string }[] = [
  { title: '在客户端配置文件中粘贴模板', desc: 'PAT 只替换占位符；OAuth 由客户端完成首次登录与受控重新授权' },
  { title: '重启客户端并发起 MCP 握手', desc: '连接结果以客户端为准，控制台不代替握手也不显示“连接成功”' },
  { title: '在问数客户端发起一次查询验证访问', desc: '本人查询记录可在', linkLabel: '查询历史', linkHash: '/me/history' },
  { title: '核对授权范围并按需调整', desc: '个人可见授权族可在', linkLabel: '我的授权', linkHash: '/me/authorizations' },
];
