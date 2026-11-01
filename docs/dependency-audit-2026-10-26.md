# 2026-10-26 依赖审计

范围：两个前端当前锁文件的 npm 审计。本轮不把它当作 Java、操作系统和 MinIO 的完整漏洞扫描或生产安全认证。

执行 `npm audit --registry=https://registry.npmjs.org --json`。默认配置的 npm 镜像不支持审计接口（404），因此只为本次命令指定官方 registry，未改全局配置。管理端报告 0 项；小程序最终报告 48 项（低 10、中 15、高 23、严重 0）。小程序兼容范围的 `npm audit fix --registry=https://registry.npmjs.org --json` 返回 added/removed/changed 均为 0；当前依赖树没有可由该命令直接应用的修复。

完整审计条目及建议修复版本见 [脱敏原始审计结果](evidence/dependency-audit-2026-10-26.json)。数量按 npm 的受影响包节点统计，不等于独立漏洞数，也不等于已证明可利用的入口数。

| 依赖链 | 报告涉及的影响 | 本地验收与发布限制 |
| --- | --- | --- |
| `@dcloudio/*` → Vue 3.4.21 / server-renderer、`@intlify/*` 9.1.9 | SSR 属性转义、i18n XSS/原型污染 | 项目源代码未发现 `v-html`、`innerHTML` 或自建 i18n 调用；这不能排除框架内部使用。正式发布前升级整套兼容 uni-app/Vue 并重新验证 H5/微信运行时。 |
| Vite 5.4.21 / esbuild / launch-editor | 开发服务器文件访问、跨源和 Windows 编辑器调用风险 | 本次容器只提供构建静态产物，不运行 Vite 开发服务器。开发服务器仅在可信网络使用；升级需与 uni-app 构建插件联调。 |
| adm-zip 0.5.16、jpeg-js、phin | 构建工具处理 ZIP/图片/重定向时的资源消耗、文件写入或头信息风险 | 不导入不可信构建压缩包或图片插件；随上游工具链升级，不能因静态部署就将这些告警记为修复。 |
| 嵌套 postcss 8.5.6、Babel、source-map-js | CSS/source-map 处理时的文件读取、输出注入或资源消耗 | 项目顶层 postcss 已为 8.5.30，但嵌套固定依赖仍受影响；升级相应上游包后复审。 |
| body-parser、path-to-regexp、send、cookie、ws 8.18.0 | Node 开发/工具服务请求处理、WebSocket 内存和拒绝服务风险 | 容器运行的 Java API 与 Nginx 不直接使用这些 Node 服务；仍需定位 uni-app 打包/预览路径并升级复测。 |

部分 `fixAvailable=true` 表示审计服务知道修复版本，不保证当前父依赖的固定版本约束可以接受它。直接依赖中的 uni-app 为同一预发布版本；审计建议还包括把 `uni-h5` 换成 `0.9.0`、`uni-mp-weixin` 换成 `0.0.973`，以及 Vite 大版本变化。未执行 `--force` 或无证据的全局 overrides，以免破坏当前 Vue 3 小程序组合。

结论：本地功能验收可报告实际通过，但依赖风险未清零，正式发布仍受上述剩余告警限制。升级应统一选择 uni-app 支持的 Vue/Vite 组合，完成类型检查、两种构建、四角色浏览器闭环和手机复测后重新审计。现有 Cloud/Boot 支持生命周期事项保留在 README，不能由本次 npm 结果豁免。
