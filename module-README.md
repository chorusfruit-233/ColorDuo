# ColorDuo

面向 **ColorOS 15–17** 的仿 iPhone Duo 状态栏模块，完全由 Codex 维护，fork 自 [HyperDuo](https://github.com/yixing233/HyperDuo)。

支持电池、Wi-Fi、双卡信号和网络类型的组合布局，以及充电功率、温度和自定义配色。

ColorOS 17 以一加 13 为实机参考；15/16 从 `0.4.2-test1` 起为实验性适配，尚待实机验证。

## 使用

需要 root 和 LSPosed：支持 libxposed API 102 选 **modern**，传统 Xposed API（82 起）选 **legacy**。安装后启用模块，作用域选择 **系统界面（com.android.systemui）**，重启 SystemUI 或手机，再添加所需组件。

两版功能相同、同包名同签名，可覆盖切换保留配置；切换版本或更新 legacy 后重启 SystemUI。

[项目与下载](https://github.com/chorusfruit-233/ColorDuo) · [配置说明](https://github.com/chorusfruit-233/ColorDuo/blob/main/docs/CONFIGURATION.md) · [问题反馈](https://github.com/chorusfruit-233/ColorDuo/issues)

## 许可证

[GPL-3.0-only](https://github.com/chorusfruit-233/ColorDuo/blob/main/LICENSE)。感谢 yixing233 与 HyperDuo 贡献者。
