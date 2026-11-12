# 胶囊纳入 main

> 上游历史资料：本文继承自 HyperDuo，内容对应原项目当时的环境与实现。ColorDuo 面向 ColorOS 15–17（15/16 适配待实机验证）；当前说明见 [ColorDuo 文档索引](README.md) 和 [ColorOS 开发指南](COLOROS17-DEVELOPMENT.md)。

来源：`refactor/drawable-glyph`，源分支头 `8c8f449708f7abc1dddbd44d8de4a11184ec4789`。
主分支绘制页及前述修复先保存为 `bf87d47`，再合并源分支完整历史。源工作树保持原样。

## 合入内容

- 三种样式：圆环、矩形、胶囊；旧偏好值 0/1 保持含义，胶囊为 2。
- 胶囊的电量轮廓、左右独立槽位、上下信号点、独立时间字号和字重，以及原生时钟的隐藏/恢复。
- 胶囊依赖的原生 `GlyphDrawable` 槽位绘制。独立窗口、窗口开关及其专用测试退休，由真实
  `GlyphDrawable` 的五种宿主形状测试接替。
- 保留 main 的信号兜底持续采样、浮点 tint、按行颜色缓存、绘制选择与长按、四帧清空和状态提示。

## 整合适配

胶囊在绘制页使用「左侧时间 / 右侧读数 / 边界信号」，分别对应 `TOP / CENTRE / BOTTOM` 的布局专用
含义。时间是固定可编辑部件，不接受清空；右侧和信号可清空。时间控件与尺寸页共用 `PartControls`
的一份滑杆定义。坐标、高亮与实际绘图共用 `designTransform`，胶囊使用自己的墨迹盒和内边界。

设置页使用固定合成时间 `19:41`，和合成电量/SIM 读数一致；SystemUI 由 `ClockTicker` 显示真实时间。

整合时修正两处来源缺陷：单卡胶囊信号点读取 mobileLevel，而非电量百分比；原生时钟反射 setter
失败时保留未成功状态，下一次调用重试。capsule-zones 和 issue-regressions 覆盖这些路径。

## 当前验证入口

在仓库根目录顺序执行 `tests/*.ps1`，随后执行：

```powershell
. .\scripts\env.ps1
gradle :app:assembleDebug :app:assembleRelease --offline --no-daemon --console=plain
```

十二个脚本的受测范围（含后续 OS4 定向修复与自由圆环）：

| 脚本 | 范围 |
| --- | --- |
| battery-presence | 电池采样与读数格式 |
| wifi-presence | Wi-Fi 在场状态与恢复 |
| perf-layout | 环外读数布局 |
| glyph-scale | 缩放与三种样式的 fit |
| glyph-drawable | 真正的 drawable 安装、替换、卸载与五种宿主 |
| capsule | 胶囊比例、轮廓、间隙、文字上限、独立字号/字重 |
| capsule-zones | 左时钟/右读数的真实像素、区域命中、固定时钟、清空、独立 SIM 读数 |
| drawing-zones | 保留的圆环/矩形像素边界和求解性质 |
| issue-regressions | 信号刷新、tint、四帧清空、时钟 setter 失败重试与隐藏/恢复 |
| os4-layout | 生产外置带布局/预留方法、空间压缩、RTL、岛边界及恢复 |
| ring-slots | 自由圆环的配置传输、当前预览迁移、任意部件位置、显示归属和像素边界 |
| ring-handoff | 生产图标隐藏/交还方法、延迟回调、锁屏屏蔽表及岛态刷新 |

合并后的真机复核重点：三种样式切换与宽度恢复、原生时钟隐藏/恢复、真实时间跳分钟、设置页时间
预览、时间独立尺寸、胶囊各区域的点击/长按、充电/无 Wi-Fi 的覆盖提示、退出模块后的原生图标恢复。
构建与桌面渲染通过只证明相应范围；本次整合版本的真机手势与 SystemUI 行为仍需单独核验。

`docs/validation/drawing-2026-10-16/source-manifest.json` 是合并前的历史快照，继续用于旧版复现。
验证当前版本时以实际 main 提交与源码 SHA-256 为基准，记录 APK 哈希并核对安装产物。

后续 OS4 代码修复及验收范围见 [HyperOS 4 验证记录](validation/hyperos4-code-2026-10-17.md)。
自由圆环的最终验证、原生电池样式边界及当前产物见 [自由三槽验证记录](validation/ring-free-2026-10-17.md)。
