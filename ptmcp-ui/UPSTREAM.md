# 前端框架来源与裁剪边界

源项目：CrazyLionCat/plus-ui，MIT。

- 当前应用入口参考提交：[045fc006725c014455def9fed19f93c781633d26](https://github.com/CrazyLionCat/plus-ui/tree/045fc006725c014455def9fed19f93c781633d26)，保留Vue/Pinia/Router应用组织、App ConfigProvider/RouterView结构；本地显式导入、中文locale和最小启动壳按PTMCP规范适配。
- ESLint/Prettier配置来源：[cc38d23d1469ad7b684b61b0bf2e9a58a11d3c74](https://github.com/CrazyLionCat/plus-ui/tree/cc38d23d1469ad7b684b61b0bf2e9a58a11d3c74)，已恢复组件命名/Props/类型等校验；当前上游使用oxlint/oxfmt，本项目按用户要求使用ESLint/Prettier。
- 这是最小裁剪骨架，不是原项目全量拷贝。未引入其登录/token持久化、租户、权限拦截器、业务页面、Vxe、编辑器、图表、UnoCSS或演示数据。Tailwind替代本骨架的原子布局工具，按需图标不拉取整套Iconify数据。
- [LICENSE](LICENSE)保留上游MIT声明。实际依赖由本地package.json与锁文件固定，不能把参考提交的完整依赖表当作本项目已引入内容。

接入文档：[Tailwind Vite](https://tailwindcss.com/docs/installation/using-vite)、[Tailwind Preflight](https://tailwindcss.com/docs/preflight)、[unplugin-icons](https://github.com/unplugin/unplugin-icons)、[组件自动导入](https://github.com/unplugin/unplugin-vue-components)、[VueUse Motion](https://motion.vueuse.org/getting-started/installation)、[Animate.css](https://animate.style/)。
