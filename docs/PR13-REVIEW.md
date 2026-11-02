# PR #11 / #13 审查记录

**0.3.13 / 10038** 已合入 #13 及下列修正，#11 由 #13 包含并关闭，#12 已完成。前置测试版本为 `test/pr13-charge-flow` 的 **0.3.13-test1 / 10037**，测试包和复现记录保留供追溯。

[正式版](https://github.com/chorusfruit-233/ColorDuo/releases/tag/v0.3.13) · [模块仓](https://github.com/Xposed-Modules-Repo/io.github.chorusfruit_233.colorduo/releases/tag/10038-0.3.13)。

审查基线：[#13 @ 6974bd7](https://github.com/chorusfruit-233/ColorDuo/pull/13/commits/6974bd79b38bb6b923093c75798ada016aac7a86)。#13 已包含 #11 的协议配色，两者的 `ChargeSpeed` 和 `ColorOsChargeSpeed` 实现相同，测试以 #13 为基础。原 PR 与上游的 workflow 差异为零。

## 发现并修正的问题

| 优先级 | 原代码位置 | 触发与影响 | 测试分支的修正 |
| --- | --- | --- | --- |
| P1 | [preview_layouts.py:169](https://github.com/jahn2007/ColorDuo/blob/6974bd79b38bb6b923093c75798ada016aac7a86/scripts/ci/preview_layouts.py#L169) | 正式签名流程生成布局参考图时没有编译新增 `ChargeSpeed`，出现 `cannot find symbol`，无法完成打包。普通 APK 构建不执行此步骤。 | 将枚举纳入参考图编译，修正快充参考场景的协议值，并实际运行整个参考图生成步骤。 |
| P2 | [PartControls.kt:774](https://github.com/jahn2007/ColorDuo/blob/6974bd79b38bb6b923093c75798ada016aac7a86/app/src/main/java/io/github/yixing233/hyperduo/ui/PartControls.kt#L774)、[TrioRenderer.java:1791](https://github.com/jahn2007/ColorDuo/blob/6974bd79b38bb6b923093c75798ada016aac7a86/app/src/main/java/io/github/yixing233/hyperduo/TrioRenderer.java#L1791) | #12 的反馈可复现：环内组件和闪电编辑同一组颜色，绘制也共用配色，无法独立调整。 | 闪电新增独立的四档浅深色；升级时一次性继承旧配色，之后分别保存。组件设置页使用不同颜色项，普通圆环、分离椭圆和胶囊均检查实际像素颜色。 |
| P2 | [SettingsRepository.kt:445](https://github.com/jahn2007/ColorDuo/blob/6974bd79b38bb6b923093c75798ada016aac7a86/app/src/main/java/io/github/yixing233/hyperduo/ui/SettingsRepository.kt#L445)、[resetRoleColors:376](https://github.com/jahn2007/ColorDuo/blob/6974bd79b38bb6b923093c75798ada016aac7a86/app/src/main/java/io/github/yixing233/hyperduo/ui/SettingsRepository.kt#L376) | 框架缺席时修改配置后重连，完整同步遗漏 5 个动画参数和 6 个协议颜色；恢复默认的远端写入也遗漏新协议颜色。广播暂时掩盖差异，SystemUI 重启后可能恢复旧值。 | 框架持久同步直接复用 `TrioSettings.toBundle()` 的完整快照；恢复默认也调用同一路径。新增远端保存后重新读取全部配置的检查。 |
| P2 | [ColorOsChargeSpeed.java:107–122](https://github.com/jahn2007/ColorDuo/blob/6974bd79b38bb6b923093c75798ada016aac7a86/app/src/main/java/io/github/yixing233/hyperduo/ColorOsChargeSpeed.java#L107) | sysfs 不可读、需要原生协议回退时，非公开模型类的 public getter 会因反射访问限制被忽略；夹具返回 `unavailable`，无法识别 SVOOC。 | 缓存 getter 时设置反射可访问性，覆盖跨包的非公开模型夹具。 |
| P2 | [TrioRenderer.java:1799](https://github.com/jahn2007/ColorDuo/blob/6974bd79b38bb6b923093c75798ada016aac7a86/app/src/main/java/io/github/yixing233/hyperduo/TrioRenderer.java#L1799) | 关闭状态颜色后，原有 SVOOC 快充闪电由快充色退回前景色；原回退分支只处理 QUICK，遗漏 FLASH / SUPER。 | 状态配色关闭时保留快充闪电的原有颜色；电量环仍使用前景色，暂停灰度范围继续独立生效。 |
| P2 | [ColorOsHooks.java:1009](https://github.com/jahn2007/ColorDuo/blob/6974bd79b38bb6b923093c75798ada016aac7a86/app/src/main/java/io/github/yixing233/hyperduo/ColorOsHooks.java#L1009) | PR 把 `battery_charge_reverse` 当成第二层充电闪电，未充电或模块接管闪电时一并隐藏。参考固件 SystemUI 17.99.02 的 `stat_system_battery.xml` 明确将它绑定到 `stat_charge_wireless_reverse`，实际是无线反向充电标识。原生闪电的强制隐藏也违背“系统原生”的归还语义。 | 保留系统对无线反向充电图标的控制；归还充电闪电后由原生 binder 决定其显隐。宿主回归覆盖充电、未充电、原生归还和模块接管情形。 |

## 验证范围

[下载签名测试包](https://github.com/chorusfruit-233/ColorDuo/actions/runs/41487628930/artifacts/13162443769)（解压后安装 `ColorDuo-0.3.13-test1.apk`）。[构建与检查](https://github.com/chorusfruit-233/ColorDuo/actions/runs/41487628930)。 已核对包名、版本、正式签名与 APK SHA256。

- 本地 13 组回归检查及 Android 构建通过；16 项单元测试通过。
- 保留原有 3240 项绘制断言、49 项协议检查、189651 项流光绘制与范围断言；新增 350 项配色独立、迁移、快照重放和原生回退检查，以及 2 项颜色设置页单元测试。
- 审查前复现了颜色联动、非公开模型读取失败、关闭配色后的 SVOOC 闪电回退，以及签名参考图编译失败；修正后对应检查通过。
- 协议枚举与 [OnePlus SM8750 驱动头文件](https://github.com/OnePlusOSS/android_kernel_modules_and_devicetree_oneplus_sm8750/blob/oneplus/sm8750_b_16.0.0_oneplus_13/vendor/oplus/kernel/charger/v2/include/oplus_chg.h) 对照一致。此来源不能代替 ColorOS 17 实机上的节点权限和实际返回值验证。
- 已查看普通圆环、分离椭圆和胶囊的生产代码参考帧；参考帧是模拟数据，不是实机截图。

实机步骤见 [测试说明](READABILITY-TEST.md)。实际协议识别、动画流畅度、功耗及面板过渡尚无量测记录，继续按实机反馈维护。原生反向充电标识的资源用途已按参考固件核对，实机显隐仍需验证。全局“状态颜色”开关和“恢复默认”作用于全部状态配色，电量环与闪电的具体颜色分别保存。
