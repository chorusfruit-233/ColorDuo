# ColorDuo 开发指南

本项目完全由 Codex 维护，开发目标是 **ColorOS 17**。当前参考一加 13 / PJZ110、Android 17、SystemUI 17.99.02。设备、固件和验收进展见 [适配记录](COLOROS17.md)。

## 项目标识

| 项目 | 当前值 |
| --- | --- |
| 应用名 | ColorDuo |
| 仓库 | `chorusfruit-233/ColorDuo` |
| 应用 ID | `io.github.chorusfruit_233.colorduo` |
| 源码 namespace | `io.github.yixing233.hyperduo`，沿用共享源码的命名 |
| 当前版本 | `0.2.6` / `10015` |
| compileSdk / targetSdk / minSdk | 37 / 36 / 29 |
| 框架 API | libxposed 102 |
| 作用域 | `com.android.systemui` |

源码 namespace 和保留的 MIUI Hook 属于继承代码；本项目的适配目标与测试范围为 ColorOS 17。SDK 构建参数不代表对其他 Android 或系统版本已经完成验证。

## 构建

准备完整 JDK 21、Android SDK Platform `37.0`、Build Tools `37.0.0`，并通过 `ANDROID_HOME` 或本机 `local.properties` 指定 SDK。仓库已提供 Gradle Wrapper 9.8.0。

```sh
./gradlew --no-daemon :app:assembleDebug
```

Windows 可使用 `gradlew.bat --no-daemon :app:assembleDebug`。首次构建需要联网下载依赖，`.tools/maven` 中保留了 libxposed 依赖。

产物路径为 `app/build/outputs/apk/debug/app-debug.apk`。debug 沿用开发签名；release 通过 `COLORDUO_KEYSTORE_PATH`、`COLORDUO_KEYSTORE_PASSWORD`、`COLORDUO_KEY_ALIAS`、`COLORDUO_KEY_PASSWORD` 环境变量接入私有签名，缺少签名配置时产出未签名 APK。发布配置见 [构建与模块仓发布](PUBLISHING.md)。

版本可通过 `-PcolorduoVersionName=...` 和 `-PcolorduoVersionCode=...` 注入；旧的 `hyperduoVersionName/Code` 参数作为兼容别名保留。

## 源码职责

源码目录：[app/src/main/java/io/github/yixing233/hyperduo](../app/src/main/java/io/github/yixing233/hyperduo/)。

| 文件 | 职责 |
| --- | --- |
| `HyperDuoModule.java` | 框架入口，选择系统适配器 |
| `ColorOsHooks.java` | ColorOS 视图接管、原生更新跟踪、状态流捕获与恢复 |
| `ColorOsPolicy.java` | 模型解析、信号换算、参考固件电流单位及 slot 所有权规则 |
| `ColorOsBattery.java` | 只读检测 Oplus 串联电芯数量、选择整包电压并归一化功率输入 |
| `ColorOsCharger.java` / `ColorOsChargerClient.java` | Oplus AIDL 只读查询、数据校验、异步缓存及厂商串并联配置 |
| `ColorOsCharge.java` | 限频读取原生 PLC 旁路状态，向共享充电规则提供已确认或未知的读数 |
| `DuoPolicy.java` / `ColorOsRadio.java` | Duo 状态规则、默认数据卡顺序及各状态栏实例共享的限频电话服务采样 |
| `ColorOsLayout.java` | 主状态栏、锁屏与通知/QS 头部的本地容器关联，隔离各行图标 |
| `ColorOsReadings.java` | 在 ColorOS 原生布局中挂载环外读数组，复用共享绘制与排版函数 |
| `BatteryPresence.java` / `WifiPresence.java` | 框架状态采样；电流在采样入口进行 ROM 单位适配 |
| `Prefs.java` / `TrioSettings.java` / `TrioConfig.java` | 配置定义、传输和热更新 |
| `TrioAppearance.java` / `TrioZones.java` / `TrioGeometry.java` / `TrioRenderer.java` | 共享外观、组件排序、几何与绘制 |
| `TrioPreviewView.java` / `ui/` | 设置界面与模拟预览 |
| `TrioHooks.java` / `GlyphDrawable.java` | 保留的 MIUI 接入及部分共享辅助函数 |

绘图由每个原生电池实例的 `battery_icon_view` 承载，独立 Canvas layer 限定共享渲染器的清屏范围。alpha5 与上游的按类接入方式对齐，覆盖主状态栏、锁屏和下拉通知/QS 头部。系统写入更新各自原生快照，模块关闭或该实例接管失败时恢复对应视图。

图标只在电池最近的 `system_icons` 或 `quick_qs_status_icons` 行内查找，找不到或存在歧义时保留原生图标。QS 使用 ConstraintLayout，环外读数挂在电池内部的 LinearLayout 中，由电池宽度为其占位；普通状态栏与锁屏使用同一行的兄弟视图。原生时钟隐藏仍限定主状态栏，不接管锁屏和面板的大时钟。

网络类型应从 ColorOS 原生映射模型取值，按真实订阅与卡槽匹配。功率优先采用校验通过的厂商电流、电压对，电流按该接口的 mA 口径归一化。平台回退中的电流单位例外仍限定已验证完整指纹；扩展接口时记录原始值及单位证据，保持 µA 输入、电压 mV 与共享功率公式一致。

## 检查

```sh
python3 tests/upstream-parity.py
python3 tests/duo-policy.py
sh tests/coloros-policy.sh
sh tests/coloros-settings.sh
python3 tests/coloros-power.py
python3 tests/coloros-hosts.py
python3 tests/release-artifacts.py
python3 tests/module-publish.py
./gradlew --no-daemon :app:assembleDebug
```

八组离线检查覆盖未演进的继承源码、Duo 状态与电话服务采样、原生模型与图标交接、配置和实际绘图、整包功率、多页面宿主恢复，以及两组发布检查。警告布局检查执行生产空间分配和可见性方法，覆盖窄宽度、零宽度、重排序及恢复。跨页面检查执行生产 Host 接管、恢复与读数挂载；不模拟真实 GPU、SystemUI 动画或 ROM 生命周期。Python 检查需要 Python 3，Java 检查使用 JDK 21。

来源基线为 HyperDuo `e9dffbcc8f65a5f3dc379c0b25ccdc0462a8a28d`。`upstream-parity.py` 保护 19 个未演进文件，8 个 Duo 行为文件在明确列出的范围内自主维护，并由行为和绘图回归覆盖；默认值、旧配置迁移及共享功率公式继续保留。对齐依据和设备验证边界见 [适配记录](COLOROS17.md#020duo-状态语义)。

`tests/` 中其他 PowerShell 脚本继承自上游，记录了不同时间的接口与环境。ColorDuo 当前检查入口为以上检查和 [CI 工作流](../.github/workflows/build.yml)。源码或构建检查通过后，仍需按 [真机验收项目](COLOROS17.md#第一轮真机验收) 记录目标固件表现。

## 调试与反馈

参考设备已确认官方 LSPosed 2.2.1（7933）以 API 102 加载模块。日志标识为 `ColorDuo`，可用下面的命令查看相关输出：

```sh
adb logcat -v time -s LSPosedFramework | rg 'ColorDuo|io\.github\.chorusfruit233\.colorduo'
```

设备信息应包含机型、完整固件指纹、SystemUI、Android、LSPosed 和模块版本。记录组件顺序、连接与充电状态，再与实际状态栏对照；预览使用固定示例数据。

电池日志包含框架原始电流、单位、归一化电流、广播电压、电芯配置及来源、功率计算电压、厂商接口状态及两路电压；数据来源或接口状态变化时始终记录，调试模式额外记录读数变化。网络日志包含模型捕获情况、数据卡槽、信号等级和制式文字。反馈入口是 [ColorDuo Issues](https://github.com/chorusfruit-233/ColorDuo/issues)。

## 构建产物与分发

[Build ColorDuo](../.github/workflows/build.yml) 在 main 代码推送、PR 和手动触发时运行共用检查，再构建测试 APK，上传 `ColorDuo-<version>-debug-<commit>` artifact；文档修改不触发构建。产物打包步骤读取 APK 的实际版本，校验签名、现代 Xposed 入口及作用域，并附校验文件和构建信息。

[Prepare release](../.github/workflows/release.yml) 手动构建私有签名的 release APK，通过版本、更新记录、签名和模块结构校验后创建 Release 草稿。具体 secrets、模块仓素材及申请状态见 [构建与模块仓发布](PUBLISHING.md)。

应用内更新地址指向 ColorDuo，仅接受 ColorDuo 命名的 APK。Actions artifact 与 GitHub Release 是不同分发入口；应用内更新依赖可用的稳定 Release APK。

`scripts/release.ps1` 和 `scripts/release-module.ps1` 继承自上游，仍包含 HyperDuo 的仓库、包名、模块仓及机器路径。它们作为历史工具保留；本仓库现行构建与分发使用 Gradle Wrapper 和上述 Actions 工作流。

## 文档与来源

用户配置见 [CONFIGURATION.md](CONFIGURATION.md)，本项目版本记录见 [CHANGELOG.md](CHANGELOG.md)。继承的 [HyperDuo 开发文档](DEVELOPMENT.md)、旧截图、发布说明和验证清单作为历史资料保留，其环境与结论需要与 ColorDuo 当前验证范围区分。

共享代码保留 yixing233 与 HyperDuo 贡献者署名，ColorOS 17 适配完全由 Codex 维护，许可证为 [GPL-3.0-only](../LICENSE)。
