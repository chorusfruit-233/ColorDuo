# ColorDuo 文档

ColorDuo 是完全由 Codex 维护的 HyperDuo fork，面向 **ColorOS 15–17**（15/16 适配待实机验证）。本目录同时保留项目现行说明和继承的上游历史资料。

## 当前文档

| 文档 | 内容 |
| --- | --- |
| [项目首页](../README.md) | 定位、适配范围、安装、下载和问题反馈 |
| [配置说明](CONFIGURATION.md) | 组件优先顺序、双卡、无 Wi-Fi 切换、预览与环外读数 |
| [系统兼容性](COLOROS-COMPATIBILITY.md) | ColorOS 15/16/17 差异、APK 证据与验证边界 |
| [ColorOS 开发指南](COLOROS17-DEVELOPMENT.md) | 本仓库构建、源码职责、检查和分发 |
| [构建与模块仓发布](PUBLISHING.md) | 工作流、签名、Release 草稿和上架条件 |
| [适配与验证记录](COLOROS17.md) | 参考固件、SystemUI 接入、单位适配、已知边界与真机验收 |
| [更新记录](CHANGELOG.md) | ColorDuo 自身的版本记录 |
| [模块简介](../module-README.md) | ColorDuo 的精简介绍 |

下载、构建和问题跟踪使用 [ColorDuo 仓库](https://github.com/chorusfruit-233/ColorDuo)。测试 APK 由 [Build ColorDuo](https://github.com/chorusfruit-233/ColorDuo/actions/workflows/build.yml) 产出；发布准备流程见 [构建与模块仓发布](PUBLISHING.md)。

## 上游历史资料

以下资料继承自 [HyperDuo](https://github.com/yixing233/HyperDuo)，用于了解共享绘图实现和演进过程。其中的机型、HyperOS/MIUI 接入点、版本、验证结论与发布命令属于上游当时的环境。ColorDuo 的平台支持与验证结果记录在当前文档中。

| 文档或目录 | 历史内容 |
| --- | --- |
| [DEVELOPMENT.md](DEVELOPMENT.md) | HyperDuo 开发文档与历次工作记录 |
| [SWITCHES-REVIEW.md](SWITCHES-REVIEW.md) | 上游开关及绘制规则复盘 |
| [CAPSULE-INTEGRATION.md](CAPSULE-INTEGRATION.md) | 上游胶囊接入与验证 |
| [ISSUE-CODE-REVIEW-2026-10-16.md](ISSUE-CODE-REVIEW-2026-10-16.md) | 上游问题审查 |
| [ISSUES-2026-10-22.md](ISSUES-2026-10-22.md) | 上游当时的问题清单 |
| [UI-REDESIGN-PROPOSAL.md](UI-REDESIGN-PROPOSAL.md) | 上游界面设计提案 |
| [RELEASE-NOTES-STYLE.md](RELEASE-NOTES-STYLE.md) | 上游发布说明写作资料 |
| [history/1003-todo.md](history/1003-todo.md) | 上游历史交接记录 |
| [release-notes/](release-notes/) | HyperDuo 1.x 发布说明，ColorDuo 的版本见 [CHANGELOG.md](CHANGELOG.md) |
| [validation/](validation/) | 上游各轮验证与测量记录 |
| [images/](images/)、[wifi-presence/](wifi-presence/)、[wifi-refresh-recovery/](wifi-refresh-recovery/) | 上游截图和复现素材 |
| [ref-5ga.png](ref-5ga.png) | 原始 A 后缀比例参考图 |

`images/states.png` 是上游旧版自动布局示意，最后更新于 2026-10-01。当前区域优先列表的配置方式见 [配置说明](CONFIGURATION.md)。这些旧图保留其来源，不作为 ColorOS 17 的真机效果证明。

## 继承脚本

`scripts/` 中的 PowerShell 工具来自上游，包含原作者机器路径、HyperDuo 仓库及模块仓发布目标。ColorDuo 当前的构建入口是 [Gradle Wrapper](../gradlew) 和 [GitHub Actions 工作流](../.github/workflows/build.yml)，具体步骤见 [开发指南](COLOROS17-DEVELOPMENT.md)。
