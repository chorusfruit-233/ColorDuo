# 0.4.0 双 API 验证

**0.4.0 / 10052 正式版**，同包名、同签名，保留本项目旧版配置。

- [modern APK](https://github.com/chorusfruit-233/ColorDuo/releases/download/v0.4.0/ColorDuo-0.4.0-modern.apk)：libxposed API 102。
- [legacy APK](https://github.com/chorusfruit-233/ColorDuo/releases/download/v0.4.0/ColorDuo-0.4.0-legacy.apk)：传统 Xposed API 82 起。

1. 选择框架支持的 APK 覆盖安装，在管理器确认模块启用且作用域为系统界面，重启 SystemUI 或手机。
2. 检查原有布局、颜色、信号底纹浓度及字体配置是否保留。调整设置，确认状态栏实时变化，重启后仍保留。
3. 检查 Wi-Fi 标准、移动信号刷新、充电功率、旁路供电、锁屏与两个下拉面板。
4. 框架同时支持两种 API 时，可互相覆盖切换，重启后检查功能及配置。应用内版本信息会注明 modern 或 legacy，更新只选择同类型 APK。
5. legacy 的激活状态以 SystemUI 读取设置的连接状态为准，稍候数秒再查看。

自动检查验证了两版构建、回调顺序、异常恢复、持久化读取、升级迁移与下载选择；未将这些结果视为 legacy 的真机验证。
