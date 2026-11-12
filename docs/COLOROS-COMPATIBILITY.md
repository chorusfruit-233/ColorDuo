# ColorOS 15–17 兼容性

ColorOS 17 是现有实机参考环境。ColorOS 15/16 从 `0.4.2-test1` 加入实验性适配，依据提供的 SystemUI APK 完成接口、字节码和资源布局核对，**尚无 15/16 实机验证**。

## 参考 APK

| 系统 | SystemUI | Android API | 验证范围 |
| --- | --- | --- | --- |
| ColorOS 15 | 15.99.13 / 159913 | 35 | 静态接口、资源布局与离线回归 |
| ColorOS 16 | 16.99.06 / 169906 | 36 | 静态接口、资源布局与离线回归 |
| ColorOS 17 | 17.99.02 / 179902 | 37 | 原有一加 13 实机基线；本轮改动仍需回归实机 |

APK 来自仓库所有者上传的草稿 Release，未提交到源码仓库。SHA-256：

- 15.99.13：`d4d657939c138260daea4b356640e2547042aa0081c8d43612a0170ec7021da8`
- 16.99.06：`28fda9b81662117fea55c2bf4d7428e2f22b0ded4a1f6c8c3c2e9522b2c2a9b3`
- 17.99.02：`a06bf8e0046a5e6a19347e09064e6ce8f79e0e963c78c8ce65f0fbf56d770e62`

## 适配差异

| 接入点 | 15/16 与 17 的区别 | 当前处理 |
| --- | --- | --- |
| 电池 | 三版均有 `StatBatteryMeterView`、相同电池子视图 ID 和仓库状态接口 | 共用原生状态、配色及恢复逻辑 |
| 移动信号 | 15/16 使用 `OplusMobileIconViewModel` 和 `SignalIconModel$Cellular`；17 另有 Cellular provider | 按现有类和字段捕获，按订阅 ID 分卡读取；缺少 provider 不影响旧版 wrapper |
| 网络类型 | 15/16 原生输出图标，17 提供文字模型 | 旧版读取 `NetworkTypeIconModel.name` 的已知制式，未知状态回退电话服务；只在模块使用时保持流订阅 |
| 原生制式 / HD | 15/16 使用 `mobile_type`、部分运营商的 `mobile_type_alone` 及内嵌 `mobile_volte` | 按子视图接管并保存系统最新可见性；关闭对应功能后恢复 |
| 主状态栏时钟 | 15 直接使用 `clock`，16/17 增加 `clock_for_fake` | 优先接管动画容器，缺少时回退小时钟；面板和锁屏的大时钟不接管 |
| Wi-Fi / AOD | 三版的 Wi-Fi 模型、`fromModel` 和 `AodData` 状态入口均存在 | 复用现有逻辑和系统状态回退 |
| 下拉镜像 | 15 没有 `StatusBarFakeFrame`，16/17 有 | 按类是否存在注册镜像处理，保持原有尺寸和裁切恢复 |

关键类名保持稳定，当前使用反射按能力接入即可；没有为这轮适配添加运行时 DexKit 或 DEX 扫描。开发分析使用 dexsql 和 JADX。若后续固件混淆这些入口，再为具体缺失项增加有缓存、可回退的定位规则。

## 检查与实机边界

`tests/coloros-compat.py` 执行生产 Host 的接管、恢复、原生写入拦截和移动模型代码，覆盖三代布局；真实协程测试验证网络类型流的更新与独立释放。现有电量、信号、下拉尺寸、息屏和热重载检查继续运行，modern 与 legacy 共用本轮适配。

可对本地 APK 复查原生方法入口：

```sh
python3 scripts/check-systemui.py --dexsql /path/to/dexsql --aapt2 /path/to/aapt2 \
  --output build/systemui-contracts.json SystemUI-15.99.13.apk SystemUI-16.99.06.apk SystemUI-17.99.02.apk
```

该脚本仅检查已声明方法的类名、名称与参数数量，不证明系统实际调用路径、SELinux 权限、GPU 裁切或硬件电池单位正确。电流单位例外仍限定已验证的完整固件指纹，没有根据 ColorOS 大版本猜测单位或电芯数量。

一加 13 / ColorOS 17 可先回归状态栏、锁屏、两种下拉面板、网络切换、时钟/HD 交还和充电/息屏。15/16 仍需对应系统用户验证上述场景，以及双卡切换、功率和配置热更新；反馈应带完整固件版本与 LSPosed 日志。
