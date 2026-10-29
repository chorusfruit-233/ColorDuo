# ColorDuo

面向 **ColorOS 17** 的仿 iPhone Duo 状态栏模块，将电池、Wi-Fi 和移动信号整合为一个可配置的图标。

完全由 Codex 维护，fork 自 [HyperDuo](https://github.com/yixing233/HyperDuo)。

[下载](https://github.com/chorusfruit-233/ColorDuo/releases) · [配置说明](docs/CONFIGURATION.md) · [更新记录](docs/CHANGELOG.md) · [问题反馈](https://github.com/chorusfruit-233/ColorDuo/issues)

## 功能

- 自定义电池圆环、Wi-Fi、双卡信号和网络类型的布局与配色。
- 支持组件优先顺序、环外信号、充电功率和电池温度。
- 设置实时生效。

## 使用

需要 root 和支持 libxposed API 102 的 LSPosed。目前以一加 13 的 ColorOS 17 为参考适配。

1. 从 Releases 下载并安装 APK。
2. 在 LSPosed 中启用 **ColorDuo**，作用域勾选 **系统界面（com.android.systemui）**。
3. 重启 SystemUI 或手机，在绘图页添加所需组件。

首次升级需重启 SystemUI；后续支持框架热重载，更新未生效时重启。同签名覆盖安装保留配置。

## 文档

[适配记录](docs/COLOROS17.md) · [开发指南](docs/COLOROS17-DEVELOPMENT.md) · [文档索引](docs/README.md)

## 许可证

[GPL-3.0-only](LICENSE)。感谢 yixing233 与 HyperDuo 贡献者提供原始绘图、布局和设置界面。
