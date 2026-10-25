# 前端规则

遵循根AGENTS；先进入项目环境，使用固定pnpm和锁文件。实际检查命令见[README](README.md)。

页面实施、联调或验收时读取[前端指南](../docs/frontend/dev-guide.md)和[页面规格](../docs/frontend/page-spec.md)，接口以服务端源码与相关工作项核对。Element Plus负责组件，Tailwind负责布局；不加载Preflight。图标按需、本地构建，自动安装关闭。

Prettier配置引用仓库统一参数，ESLint为本工程实际入口。生成的components.d.ts由配置和模板触发工具更新；不手工编辑。动效库已就绪，不默认给所有元素加动画，不让两个引擎同时控制同一属性。
