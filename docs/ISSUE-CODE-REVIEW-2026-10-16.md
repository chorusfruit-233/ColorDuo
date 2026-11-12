# Issue 对应代码检查（2026-10-16）

> 上游历史资料：本文继承自 HyperDuo，内容对应原项目当时的环境与实现。ColorDuo 面向 ColorOS 15–17（15/16 适配待实机验证）；当前说明见 [ColorDuo 文档索引](README.md) 和 [ColorOS 开发指南](COLOROS17-DEVELOPMENT.md)。

检查基线：`68a8dcc`，叠加检查开始时工作区中已有的绘制分页改动。以下区分代码缺陷、Issue 实机根因和功能请求。代码测试通过只证明受测行为，不等于报告者的设备已复测通过。

## 本轮修复

| 代码位置 | 触发与后果 | 修复 |
| --- | --- | --- |
| `TrioState.refresh` | 单排信号、图标链始终未给读数，telephony 首次成功后实例 `mobileLevel` 变为非负，后续不再轮询，信号停留在旧值 | 用原始来源 `sMobileLevel` 判断是否需要兜底，保留原有两秒限频 |
| `TrioHooks.hookMeterTint` | `0 < intensity < 1` 被转成整数 0，与正常绘制的反色分支相反 | 保留浮点强度 |
| `TrioHooks.hookMeterTint` | 原生图标隐藏后停止上色，旧 `ROW_INK` 始终压过新的 meter 颜色；网络类型关闭时还会跳过整个回调 | meter 每次上色均更新其所属行的缓存，刷新该行的环外读数、宿主与独立窗口 |
| `TrioOverlay.sync` | `updateViewLayout` 失败前已修改 `params`，但仍宣称覆盖成功；下一轮因参数相同跳过更新，宿主也被清空 | 定位成功后才接管绘制；失败隐藏窗口、交还宿主，并重试相同目标参数 |
| `TrioOverlay.showAgain` | 动画结束绕过 Insets、窗口可见性、超级岛等条件，短暂重现本应隐藏的独立窗口 | 与普通帧共用 `sync` 判定 |
| `TrioOverlay.hideNow/sync` | watcher 中定位失败或恢复时，宿主未重绘，旧宿主帧可能短暂空白或与窗口双绘 | 接管与交还绘制时均 invalidate 宿主；稳定状态不重复触发 |
| `DrawingScreen` | `dualSim=false` 被当成预览有 0 张卡，而画面实际使用两条合成读数，导致双卡选项不可达 | 求解器与绘制器共用 `scene.previewSlots`，从读数统计 SIM 数 |
| `TrioZones.candidates` | 候选遗漏已有的环外网络类型；选择底部信号也会把环外 5G 关掉或挪入环内 | 当前已开启环外类型时将其纳入候选，保留与请求无关的设置 |

这些修复涉及 [#8](https://github.com/yixing233/HyperDuo/issues/8)、[#12](https://github.com/yixing233/HyperDuo/issues/12) 和独立窗口故障路径，但现有设备日志尚不足以把每一份报告唯一归因于上述缺陷。

## Issue 的判断与后续修复边界

### #28 / #33：权限请求遮挡

`TrioOverlay.markTrustedOverlay` 已在基线中反射添加 `PRIVATE_FLAG_TRUSTED_OVERLAY`；当前未发现这段实现的新缺陷。修复提交晚于 `v1.6.12`，最新正式发布尚未包含它。后续需要发布并在报告设备确认 `dumpsys window` 的 `pfl=TRUSTED_OVERLAY`。

来源：[#28](https://github.com/yixing233/HyperDuo/issues/28)、[#33](https://github.com/yixing233/HyperDuo/issues/33)、[主线修复提交](https://github.com/yixing233/HyperDuo/commit/68a8dcc4b868b7ebe1437f006470fc9dde57ccfc)。

### #4：环外读数与原生图标重叠

有修复方向，但本轮保留现有布局。`reserveOutTypeSpace` 只增加原生 icon row 的 padding，没有原生图标溢出时的额外避让。报告涉及网速、静音、VPN、勿扰，范围超出单一网速 View。

PR #29 的剩余方案需要补齐这些边界再移植：

- 位移与占位应同时计入网络类型、功率/温度和堆叠信号；贡献版本遗漏性能读数。
- LTR/RTL 镜像计算，并以实际视觉坐标计算，包含子项 translation。
- 空间恢复后在同一轮恢复 alpha 并重新求解位置，避免拿自己上一轮隐藏的图标当作原生空位。
- 总开关关闭、组件关闭、宿主移除时恢复模块修改过的 alpha、translation 和 padding。

本地 `work/jadx-out` 的 `MiuiStatusIconContainer.onLayout` 确实从 `width - getPaddingEnd()` 开始摆放。该样本已有 padding 支持，故“所有 MIUI 都忽略 padding”并不成立；它也不代表报告者当前 ROM。需要目标 ROM 上的图标实际边界或对应样本验证。

来源：[#4 的重叠日志](https://github.com/yixing233/HyperDuo/issues/4#issuecomment-6014515224)、[贡献者最终方案说明](https://github.com/yixing233/HyperDuo/issues/4#issuecomment-6108790327)、[PR #29](https://github.com/yixing233/HyperDuo/pull/29)。

### #6 / #27：Insets 始终否决独立窗口

当前 `insetsShowBar` 同时要求状态栏可见与 `top > 0`。两份日志都出现其他可见条件为真、仅 `insets=false`，但未拆出两个子条件，因此尚未确定哪一个与 ROM 语义不符。

本轮保留该条件。直接放开它会影响相机、游戏、沉浸应用和最近任务动画；模块定时主动触发绘制，故“最近画过”也不是独立的屏幕可见证据。下一步需记录 Insets 的 visible/top 原值，并覆盖桌面、全屏、下拉控制中心及超级岛的显示交接。

来源：[#6 最新模块日志](https://github.com/yixing233/HyperDuo/issues/6#issuecomment-6211584158)、[#27 维护者调查](https://github.com/yixing233/HyperDuo/issues/27#issuecomment-6033167356)。

### #31 / #35：MIUI 13/14 背景方块、闪电重叠和类型缺失

`TrioRenderer.drawInto` 以及 overlay 接管分支会执行 CLEAR。旧 ROM 若没有隔离宿主图层，可能影响父背景；但本地反编译样本的电池宿主在 attach 时主动启用硬件层，原生 `onDraw` 自身也使用 CLEAR。当前证据不足以把方块直接归因于 CLEAR，本轮保留这条绘制路径。

其他兼容入口也依赖 ROM 的类名/字段：网络类型来自 `MobileTypeDrawable`，旧日志已出现该类缺失；宿主 detach 清理仍依赖具体类声明 `onDetachedFromWindow`，部分旧 ROM 缺少该声明。后者可单独迁移为 View attach-state listener，并补注册表/窗口清理测试。它与背景方块或充电重叠的根因关系尚待验证。

下一步需要对应 ROM 的类/字段信息与渲染截图，分别检查：图层隔离、原生充电视图交还、容器 ownership 和类型采样入口。现有离线断言没有 Android GPU 渲染环境。

来源：[#31](https://github.com/yixing233/HyperDuo/issues/31)、[#35](https://github.com/yixing233/HyperDuo/issues/35)、[#6 旧版 hook 安装日志](https://github.com/yixing233/HyperDuo/issues/6#issuecomment-6012433665)。

### 功能请求

| Issue | 代码入口与可行性 |
| --- | --- |
| #34 充电时切换百分比 | 可通过外观仲裁与充电配置实现，需要确定闪电、数字与原布局的优先关系 |
| #36 网络类型放在堆叠信号上 | 可在环外 View 布局实现，需要重新计算两者共用占位宽度和行高 |
| #38 隐藏桌面图标 | 已有独立 `MainActivityAlias`；可通过 PackageManager 切换 alias，保留模块设置入口 |
| #32 / #39 下拉动画与详细信息 | 需要按状态栏/控制中心区分显示规则，并处理下拉时的过渡 |
| #37 ColorOS 移植 | 当前 hook 针对 MIUI/HyperOS 类名，需另建 ROM 适配与采样入口 |

上述属于新增功能，本轮完成缺陷修复和代码可行性分析。

## 验证与局限

新增 `tests/issue-regressions.ps1`：51 条执行断言，另有 1 条 Kotlin 预览/求解器接线源码契约。它执行真实 `TrioOverlay` 类，并抽取真实 `refresh`、轮询限频、前景色及 meter tint hook；Android WindowManager、时钟、telephony 与 Xposed 调度由可控替身提供。

- 覆盖定位失败及相同参数重试、真实 watcher 的恢复交接、稳定帧去重、六类隐藏条件、释放与 add 失败恢复。
- 覆盖 telephony 首次成功后的继续采样、两秒限频、正常图标来源下跳过多余采样。
- 覆盖强度 0/0.5/1、按行归档、网络类型关闭时上色、交错的两行颜色和稳定颜色去重。
- 覆盖圆环/矩形下保留环外网络类型，以及预览双卡可达性。

失败对照：修改前的代码分别在 overlay、signal、tint、assignment 四个 suite 触发预期断言；独立复核发现的显示交接遗漏也单独保存快照，触发 `watcher recovery redraws the fallback host` 后修复通过。

仓库七个 PowerShell 测试脚本均通过。最终窗口交接改动后重跑新增回归与 trusted-overlay 测试。完整 Debug/Release APK 通过离线 Gradle 构建（包含 Kotlin 编译与 Release R8）。

测试不覆盖真实 WMS/GPU、ROM 的 hook 安装结果、真实 View 树查找和 Compose 手势操作。未装机，GitHub Issue 保持原状态；代码与检查记录留在工作区供 review。
