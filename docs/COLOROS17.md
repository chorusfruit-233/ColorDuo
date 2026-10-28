# ColorDuo：ColorOS 17 适配与验证

ColorDuo 是完全由 Codex 维护、专注 ColorOS 17 的 HyperDuo fork。本页记录本仓库的系统接入、参考固件证据与验证进展。各 alpha 章节保留对应版本的修复记录；当前安装和配置见 [项目首页](../README.md)、[配置说明](CONFIGURATION.md)，本项目版本历史见 [更新记录](CHANGELOG.md)。

## 参考固件

- 设备：一加 13 / PJZ110（用户提供）。
- 指纹：`OnePlus/PJZ110/OP5D0DL1:17/CP2A.260605.016/V.487427a-2227f32-224bc4c:user/release-keys`。
- SystemUI：17.99.02 / 179902，API 37，9 个 DEX。
- 参考 APK SHA-256：`a06bf8e0046a5e6a19347e09064e6ce8f79e0e963c78c8ce65f0fbf56d770e62`。
- 框架：模块要求 libxposed API 102。用户安装的官方 LSPosed 2.2.1（7933）已在提供的加载日志中确认 API 102 和 Hook 注入。

## 接入方式

`HyperDuoModule` 按 `StatBatteryMeterView` 是否存在选择 ColorOS 适配器；原有 MIUI 适配作为继承代码保留。ColorDuo 当前的适配与验收范围为 ColorOS 17。

| 功能 | 接入点 |
| --- | --- |
| 电池 View | `com.oplus.systemui.statusbar.pipeline.battery.ui.view.StatBatteryMeterView` |
| 绘制 | `battery_icon_view` 的 ImageView drawable |
| 原生视图更新 | `BatteryViewBinder.bind*`；外层调用前恢复原生状态，调用结束后保存最新原生状态并重装模块 drawable；按实例过滤的框架 setter 同时跟踪被内联的系统更新 |
| 电池状态 | `BatteryRepositoryImpl.getBatteryLevel/getChargeState/isSavingMode` 的 StateFlow |
| 每个 View 的颜色 | `LocationBasedBatteryViewModel.getBatteryColor` 与 `StatBatteryIcon.getTintColor` |
| Wi-Fi | `WifiIcon.Companion.fromModel` 的实时模型，仓库 StateFlow 和框架连接状态兜底 |
| 分卡信号 | `OplusCellularIconViewModelImpl` / `OplusMobileIconViewModel` 构造时捕获 backing StateFlow；getter 用于兼容没有内联调用的固件 |
| 网络类型 | `Os17Binding.bindNetworkTypeText` 的实际模型；Interactor 原生映射用于提供可见性过滤前的制式文字 |
| 充电功率 | 优先采用 Oplus `ICharger.queryChargeInfo()` 的有效电流、电压对；框架接口和已验证节点回退，复用原有功率公式 |
| 原生图标 | `StatusIconContainer.mIgnoredSlots`；仅撤销模块自己添加的 slot |

alpha5 接管 `StatBatteryMeterView` 的各个实例，包括主状态栏、锁屏、下拉通知栏与控制面板头部。原生 Drawable、尺寸、ScaleType 和子视图可见性按实例保存，系统更新完成后重新记录；某个实例异常时只恢复它的原生显示。

原生图标与电池的关联限定在最近的 `system_icons` 或 `quick_qs_status_icons` 内，支持 QS 图标位于 `icons` 子容器、电池位于 ConstraintLayout 另一侧的结构。查找会跳过其他嵌套图标行，存在多个候选时保留原生图标。通知栏动画把整行移入 fake container 后，仍使用该行自己的图标；重新绑定时释放旧行所有权和网络类型可见性。

主状态栏/锁屏在普通 LinearLayout 行内为读数组保留空间；QS 的环外读数组挂在电池内部的 LinearLayout 中，随电池一起测量、显隐。原生时钟隐藏只作用于主状态栏小时钟，面板和锁屏自己的大时钟保持系统布局。

绘图使用单独的 Canvas layer，避免上游渲染器清屏时影响相邻图标。电话服务采样限制为每秒一次，Wi-Fi 模型与连接通知独立更新缓存，绘制回调不访问电话服务；熄屏时降低刷新频率。系统流缺失时可使用已有平台采样兜底；缺少电量时不隐藏原生图标。

## 共享代码来源与当前规则

代码源自 HyperDuo `e9dffbcc8f65a5f3dc379c0b25ccdc0462a8a28d`。`0.2.0` 起按 iPhone Duo 的公开状态语义自主维护；保留已有布局与配置迁移，允许修正继承的状态判断和绘制规则。ColorOS 接入、数据单位归一化、发布与验证由 Codex 维护。

- 默认值与配置迁移继续继承上游，状态规则、绘制与预览按本项目的回归检查维护。`Prefs` 仅改变独立应用的重载广播名。
- 圆环初始组件区为空，分卡组件默认关闭；不再预设 Wi-Fi、卡一/卡二的优先列表。
- 每个区域每帧只选优先列表中首个可绘制组件。双卡同显由用户将两张卡分别放置在不同区域。
- 预览保持上游固定信号示例，实际读数按真实卡槽传给共享渲染器，不把预览值当作系统值。
- 环外视图复用原有功率温度格式、字体和排版；自动双卡按数据卡优先，手动分卡保留物理身份，空间不足时先退让普通读数。ColorOS 的原生布局为电池旁读数组预留空间。
- 原生时钟容器 `clock_for_fake` 仅在当帧布局真正显示时间时隐藏；模块关闭、布局交还时恢复原生可见性，系统更新同样纳入原生状态跟踪。

`python3 tests/upstream-parity.py` 继续对比固定上游的 19 个未演进文件；8 个明确演进的状态、渲染、布局和设置文件由针对性的行为回归覆盖。应用标识与平台采样桥接仍单独归一化；默认值、配置迁移和功率公式保持原有保护。CI 使用完整提交历史。

## 0.2.6：Oplus 充电接口

接口方案参考 [LuckyTool 的只读充电服务调用](https://github.com/luckyzyx/LuckyTool/blob/bb9282be820538cb4c24bd1345b963104d02b6a6/app/src/main/java/com/luckyzyx/luckytool/hook/utils/IChargerUtils.kt)，本项目独立实现 `vendor.oplus.hardware.charger.ICharger/default` 的现代 AIDL 适配。电池广播仅触发刷新，优先使用 `queryChargeInfo()` 中完整且有效的电流、电压对；不把协议瓦数当作实时功率。电流遵循该厂商接口的 mA 口径，电压在互不重叠的有效 mV/µV 范围内归一化；缺字段、重复字段、异常值、矛盾配置或不兼容响应均不接管。

从 `/odm/etc/devices_config/devices_config.json` 读取串并联标记。串联设备优先整包电压，其次两路实测电芯电压求和，再回退原有单节折算；已有整包电压不重复翻倍。并联设备的单支路/总电流口径尚未验证，因此不采用厂商支路读数，也不套用串联倍率。未知配置保留原有电芯检测。

ColorOS 电池采样在后台合并执行，完整显示快照交回主线程。厂商 IPC 单独执行，最多一个查询在途，成功请求间隔至少 3 秒，缓存最长 6 秒；失败限频重试，不等待服务启动，挂起查询不会阻塞平台回退。拔插电源立即失效缓存，并丢弃旧连接的迟到响应。不新增 Root 守护进程，也不修改充电控制。

离线检查覆盖生产解析、功率计算、数据源选择、Binder 查找/断连/重连、异步合并与过期回退及主线程发布。接口依据来自公开代码，仍需目标 ColorOS 17 设备确认服务访问权限、字段单位和硬件配置；源码检查与构建通过不代表真机验收完成。

## 0.2.5：PMX110 功率输入

[#5 的设备数据](https://github.com/chorusfruit-233/ColorDuo/issues/5#issuecomment-6462077076) 来自 `OPPO/PMX110/OP659FL1:17/CP2A.260605.016/C.34208f1-dbc5d7-d921b4:user/release-keys`。Binder 电流的 `fffffffffffffade` 和 `current_now` 均为 -1314，按 mA 上报；`battery/voltage_now` 为 4474 mV，标准电池广播却只有 4 mV。两项错误叠加使旧版显示接近 0W，只修电流仍不能恢复正常读数。

该完整指纹新增 mA 归一化；广播电压不在有效毫伏范围时，只读上述已验证的 mV 节点，不把截断后的整数伏特直接放大。正常广播仍优先使用，节点失效时清除旧值并限频重试，功率保持未知。原有电芯检测继续独立工作，不根据电流单位推断 PMX110 的串联数量，也不把异常规则扩展到未验证固件。

离线回归执行原始数据、生产功率公式和格式化，并覆盖有效广播、节点失效与恢复及电芯折算组合；SystemUI 对该节点的实际读取权限和修复后的显示仍需报告者安装确认。

## 0.2.4：原生旁路供电

参考一加 13 的实测样本中，开启和关闭原生游戏助手旁路供电时，Android 电池 `status` 均为 2；独立的 `/sys/class/oplus_chg/common/plc` 分别为 `status=3` 和 `status=2`。旧版只看前者，因此不会进入灰色状态。

现在由电池采样路径只读、限频读取该节点，接入共享暂停显示规则及功率可见性；不在绘制回调读取，也不调用 su 或写入充电控制。只有有线电源和已确认开启的 PLC 状态触发旁路样式；拔线、无线充电、未知内容或读取失败均回退标准状态。日志记录 PLC 是否成功读取，便于确认目标固件的 SystemUI 读取权限。离线回归使用上述开关样本，实际颜色与节点权限仍需安装后确认。

## 0.2.3：可读的电量数字

上下格文字改用完整格位高度，文字开口扩大到 128°，信号点继续使用 96° 开口和原有锚点。中部文字在相邻格有内容时让出对应空间。默认字号仍为 36，离线生产绘制检查覆盖电量 8、88、100 和描边 4、14、16，均确认默认值不再被上下格缩小；已有自定义字号保留。

设置中的中英文默认值与范围从同一份默认配置和滑杆边界生成，功能介绍按所选区域描述。预览和状态栏复用同一排版，真实字体与 GPU 效果仍需手机确认。

## 0.2.2：填充与时钟交接

修复上游 #58 的短胶囊填充问题：反向直线段按实际长度计入预算，满电重绘完整轨道。ColorOS 时钟交接改为读取同一当帧布局，覆盖空左格、候补组件、充电百分比覆盖和注册清空网络类型。电量字号滑杆复用实际字体与格位拟合上限，保留原有存储值与防重叠限制。

新增离线检查执行生产绘制代码，比较真实绘制的时间与交接结果，覆盖胶囊长度、描边极值及字号最后一步。宿主检查同时验证原生时间隐藏、恢复和总开关关闭；真实 GPU 和 SystemUI 动画仍需手机复测。

## 0.2.1：SIM 停用与注册过渡

用户反馈单卡在系统设置中停用后保留原生图标，启用时短暂闪过 SOS。回归样本复现了 `0.2.0` 把“订阅查询成功但没有活动线路、卡仍存在”归为 UNKNOWN 的缺口。修复后，该情况显示“无服务”；只有明确的物理缺卡才显示“无 SIM 卡”，订阅查询失败仍保持 UNKNOWN。

参考 SystemUI 的 `OplusStatusBarSignalPolicy` 通过 `status_bar_nosim_all/one/two` 使用 `nosim_all`、`nosim_one`、`nosim_two` 三个独立 slot，原版本只处理 mobile/stacked_mobile。现在把这些 slot 纳入同组所有权：模块信号、可见警告或明确的注册留空状态接管时隐藏；未知、空间不足或交还组件时恢复。

新增 REGISTERING 仅作为内部状态，蜂窝信号及网络类型区域暂不绘制，电量和 Wi-Fi 继续显示。系统 WWAN 网络注册信息明确表示仍在搜索时持续留空；结束搜索后，同一线路的无服务或仅紧急呼叫需连续稳定 3 秒再显示，正常服务立即恢复。缺少搜索信息的固件使用同一稳定窗口抑制暂态提示。活动订阅或数据号码变化、查询失败均重置窗口；重新启用时的失效默认订阅 ID 会回退到实际可用线路。

该稳定窗口为本项目的过渡处理规则。日志同时记录 `reported` 原始状态与 `service` 显示状态。测试执行生产采样和绘制方法，覆盖单卡停用/启用、长时间搜索、持续 SOS、读取失败、默认号码滞后和三种样式的蜂窝留空；真机结果仍需安装后核对。

## 0.2.0：Duo 状态语义

参考 [Apple 状态图标说明](https://support.apple.com/zh-cn/guide/iphone/iphef7bb57dc/27/ios/27)，对齐无服务、暂停充电和数据号码优先这三项公开规则；未声称动画或几何已经逐像素还原。

- 所有状态栏实例共用每秒一次的电话服务快照。仅在订阅列表明确为空且卡槽明确缺卡时判定无 SIM；权限失败、初始化和未知读数保留 UNKNOWN。语音或数据任一正常注册即视为有服务，仅紧急呼叫必须来自系统明确状态。
- 模块接管信号时，使用模块 APK 的本地化字符串显示“无 SIM 卡”“无服务”或“SOS”。警告文字参与环外布局并优先保留；尚未完成布局、没有可绘制空间或服务状态未知时，保留原生移动图标。图标交接兼容 Set 和 List 容器，调试日志记录实际应用结果。
- 自动双卡及环外信号按数据号码优先；“卡一”“卡二”始终绑定物理卡槽。胶囊保留点阵原始身份，防止自动排序覆盖手动卡槽选择。
- 充电状态来自保护的电池广播：暂停为已连接电源但系统明确报告未充电，充满独立处理，未知状态和零电流均不推断为暂停。暂停使用灰色充电布局，并在设置中提供同一路径的预览。功率及“仅充电时”的温度按实际正在充电显隐，整包功率计算公式保持不变。
- 修正环外布局退让后的宽度重算，保留最高优先级读数并为最后一项计算实际缩放；空读数组不保留额外占位。

离线检查执行生产电话服务采样、警告布局和原生回退路径，并用实际绘制函数比较暂停/恢复、数据卡切换和手动分卡身份。检查不运行目标手机的真实 SystemUI 生命周期；新增状态的真机显示仍待目标设备确认。

## 第一轮真机验收

1. 启用后应出现上游默认圆环；在绘图页放置电量、Wi-Fi 和信号组件后逐项检查。日志应报告 adapter 安装和 host 激活。
2. 总开关关闭后，检查原生电池、百分比、充电图标和信号恢复；再次打开不应产生重复视图。
3. 修改大小、颜色和组件位置，确认即时更新；清空组件后原生信号应回来。
4. Wi-Fi 关闭、断连、重连；飞行模式；切换默认数据卡、停用 SIM；检查没有残留信号。
5. 插拔充电器、VOOC、低电量、省电模式、深浅色背景、横屏和屏幕熄灭后唤醒。
6. 切换系统电池样式，关闭模块后检查原生样式恢复。
7. 分别展开通知栏和控制面板、进入锁屏、再返回桌面；检查已配置的三合一组件、原生信号交接、配色和环外读数都随所在页面正确更新，关闭模块后各行恢复原生。

记录手机完整系统版本、LSPosed 版本、操作步骤、前后截图及 ColorDuo 的加载/错误日志即可定位问题。

## 当前验证边界

已提供信号换算、slot 所有权释放、默认配置、配置传输和渲染测试，并构建可安装 APK。云端无法连接目标手机，因此这些检查不代表已经通过真机验收。

alpha5 已将代码接管范围扩展到主状态栏、锁屏和下拉通知/QS 头部。夹具检查覆盖生产 Host 接管、原生状态恢复、局部容器与视图迁移，但不运行真实 ROM 的生命周期或 GPU 动画。跨页面的实际配色、展开/收起过渡、胶囊、时钟交接和 ColorOS 特殊充电动画仍需目标手机逐项验收。

测试签名沿用原仓库开发密钥；测试 APK 通过 Actions artifact 分发，工作流不自动发布 Release，不修改用户已有的 SystemUI 草稿附件。

## alpha2：Wi-Fi 状态识别修复

用户日志确认 alpha1 已正常注入并激活电池视图，但 Wi-Fi 未绘制且原生图标仍显示；系统 Wi-Fi 日志同时记录了有效的两格、三格信号。

参考 APK 的 `WifiNetworkModel$Active` DEX 类没有 class annotations。alpha1 使用 `getSimpleName().equals("Active")`，在缺少内部类元数据时会误判这个已连接对象。alpha2 改用通过目标 ClassLoader 找到的实际类型，并对字段/方法取值做范围检查。

同时观察原生图标转换时使用的 Wi-Fi 模型，保留连接状态兜底，避免未订阅仓库的初始快照覆盖有效连接。框架采样采用 NetworkCapabilities 与关联状态，兼容 legacy TYPE_WIFI NetworkInfo 缺失及 VPN 默认路由。Wi-Fi 的缓存刷新独立于电话服务采样限频。

新增回归测试复现没有 InnerClass 元数据的二进制类名，并覆盖冷仓库、断开/重连、未知连接、弱信号、无效等级和 carrier-merged 状态。调试日志只记录连接状态、等级和 slot 接管结果，不记录 SSID/BSSID。

复测：直接覆盖安装 alpha2 后重启 SystemUI，检查连接时环内 Wi-Fi 与原生图标交接、关闭/重连，以及关闭模块后恢复原生 Wi-Fi。

## alpha3：恢复上游默认与共享行为

撤回 alpha1/alpha2 额外添加的默认组件布局及默认开启分卡信号，恢复上游的默认值和配置迁移。已有用户配置保持原样；升级不会猜测并删除用户使用过的布局。可在绘图页清空各区域组件并关闭分卡开关，恢复上游初始布局。

删除 ColorOS 简化版环外绘制，复用上游原有函数；修正单卡在卡槽二、仅显示数据卡、信号尺寸和间距、功率/温度双行、网络类型字号与 A 后缀、自定义字体和环外排序的设置接线。原生百分比与时钟是否隐藏也交给上游条件判断。

验证包含共享源码对比、配置传输、显式空布局、旧配置迁移、双卡独立放置、单卡/双卡/数据卡读数选择、实际绘图以及 alpha2 的 Wi-Fi 回归。覆盖安装 alpha3 后重启 SystemUI，在相同配置和信号条件下对照上游预览；实际双卡、RTL、胶囊和特殊动画仍需真机复测。

## alpha4：网络类型与充电功率

用户在 alpha3 上确认网络类型不显示、充电功率持续为 0。

参考 DEX 中没有对 `OplusCellularIconViewModelImpl.getIcon/getNetworkTypeText` 的调用，原生调用已经内联。仅 Hook 这些 getter 无法获取模型。alpha4 同时观察 Cellular 和包装 ViewModel 的构造，直接记录卡号和 backing flow，并观察参考 DEX 中仍由初始化与 Flow collector 调用的 `Os17Binding.bindNetworkTypeText`。

ColorOS 的网络类型值是 `OplusNetworkTypeTextModel`，含有具体文字模型的 prefix/suffix；alpha3 只接受 CharSequence，拿到模型后仍会丢弃。alpha4 使用原生映射合并前后缀，保留 `5GA`、`4G+` 等文字。原生 UI 可能因 Wi-Fi/数据状态过滤掉标签，适配器同时记录 Interactor 的原始映射，由上游配置决定 ColorDuo 是否显示。无服务、飞行模式和卡号切换会清理当前类型；模块接管类型时，仅跟踪并隐藏原生 `mobile_type_text/mobile_type_image`，交还系统时恢复。

功率单位通过用户的两组只读数据确认：

- `dumpsys battery` 报告电流 -285，电池电压 4465 mV，100% 电量、FULL 且 AC attached；`current_now` 节点读数 -221，`voltage_now` 为 4463000 µV。读数取得时间不同，电流会变化。
- `IBatteryPropertiesRegistrar.getProperty(CURRENT_NOW)` 返回的 long 为 `fffffffffffffe10`，解码后是 -496；该固件在 Android 约定 µA 的接口里报告 mA。
- -496 mA、4465 mV 对应 2.21464 W。alpha3 按 µA 计算成 0.00221464 W，上游格式化后为 `0.0W`。alpha4 在采样入口先乘 1000，再使用原有功率公式和格式化，得到 `2.2W`。

单位配置严格限定本说明的已验证完整固件指纹。其他固件继续使用 Android 的 µA 约定，不根据电流大小猜测单位；未支持值和溢出保持 unknown，真实 0 仍为 0。功率仍表示电池侧瞬时读数；100% 时会随涓流和系统负载变化。

`python3 tests/coloros-power.py` 提取并执行生产代码中的 `wattsOf`、`powerText`、`perfText`，覆盖实际 Binder 样本、单位误差复现、格式化、真实零、unknown、溢出、正负电流、充满但仍插电、拔电和温度双行。网络模型测试覆盖 backing field、接口 getter、原生图标映射、前后缀及空值。共享源码一致性检查保留，功率公式未修改。

覆盖安装 alpha4 后重启 SystemUI，检查已有网络类型组件在 5G/4G 切换、Wi-Fi 切换和飞行模式下更新；插电时功率应随实际电流变化。日志首个 `ColorOS battery` 样本包含原始电流、单位、归一化电流和电压；开启调试日志可持续记录变化。未访问 root 命令或 sysfs 作为运行时数据源。

## 0.1.1：双电芯功率适配

`0.1.0` 及更早版本只校正电流单位，随后直接使用 `ACTION_BATTERY_CHANGED` 电压；未区分单节与整包电压。上面的 `2.21464 W` 是单位修复后按 4465 mV 计算的结果，不能据此证明已统计整包功率。

OnePlus 官方 [SM8750 充电驱动](https://github.com/OnePlusOSS/android_kernel_modules_and_devicetree_oneplus_sm8750/blob/d50b305f7da9e14715a25120a4ac7b1a4b8b97c3/vendor/oplus/kernel/charger/v2/oplus_chg_gki.c) 的 battery `VOLTAGE_NOW` 使用 `GAUGE_ITEM_VOL_MAX`，`CURRENT_NOW` 直接返回 gauge 电流。[gauge 实现](https://github.com/OnePlusOSS/android_kernel_modules_and_devicetree_oneplus_sm8750/blob/d50b305f7da9e14715a25120a4ac7b1a4b8b97c3/vendor/oplus/kernel/charger/v2/mms/oplus_mms_gauge.c) 明确将 `batt_num == 2` 用于 2S 检查。[configfs 实现](https://github.com/OnePlusOSS/android_kernel_modules_and_devicetree_oneplus_sm8750/blob/d50b305f7da9e14715a25120a4ac7b1a4b8b97c3/vendor/oplus/kernel/charger/v2/oplus_configfs.c) 的 `aging_ffc_data` 第二字段即该串联数量。该源码属于厂商公开参考，目标 Android 17 固件仍需与实际接口读数对照。

适配在采样入口读取 `/sys/class/oplus_chg/battery/aging_ffc_data`，缓存检测到的双串联数量。若单节电压不超过 5000 mV，优先使用可读且符合整包范围的 `/sys/class/power_supply/bms/voltage_now`；没有整包读数时折算为两节电压。已经上报整包电压、单电芯和未知拓扑均不重复乘倍数。参考固件可在节点不可读时使用已知硬件配置回退；未知固件保持原有接口口径。

所有文件只读，单次读取最多 512 字节；节点不可读、格式无效或驱动尚未初始化时，最多每分钟重试一次。采样位于原有低频电池刷新路径，绘制回调不做文件访问，也不运行 root 命令。日志增加 `seriesCells`、`cellsSource`、`powerVoltageMilliVolts`、`voltageSource`，可区分硬件检测、固件回退、整包电压和单节折算。

离线测试已覆盖单节、双串联、整包不重复翻倍、BMS 单节/整包区别、并联合计电流不加倍、权限失败、冷启动恢复、unknown/零读数及共享格式化。按串联电流 496 mA、单节电压 4465 mV 的夹具，整包结果为 4.42928 W。

用户进一步连续采集三组 `current_now` → Binder CURRENT_NOW → `current_now`，每组内核前后均为 `-753`，Parcel 为低字 `fffffd0f`、高字 `ffffffff`，解码同样为 `-753`。这确认参考固件接口电流未预先乘双电芯倍率。

随后读取 `aging_ffc_data` 得到 `0,2,0,0,655,4530,4470,4500,4455`，第二字段确认两串联电芯；battery `voltage_now` 为 `4434000` µV，即 4434 mV 单节电压；BMS 整包电压节点不可用。对应归一化电压为 8868 mV。将真实输入作为回归夹具组合时，753 mA 对应 6.677604 W，上游格式显示 `6.7W`；旧版按单节计算为 3.338802 W，显示 `3.3W`。电流与电压来自分开的探测，不将这个组合数值当作同一时刻的实测功率。硬件口径已据此核验，修复后的实际 SystemUI 显示仍需手机运行确认。

## alpha5：对齐跨页面接管范围

上游在 `MiuiBatteryMeterIconView.onDraw` 按类接入绘图，登记各实例并管理它们所在的主状态栏、锁屏、控制中心和 QS 图标行，还专门处理锁屏行的附着与图标折叠。ColorDuo alpha4 额外要求电池具有 `PhoneStatusBarView` 祖先，导致其他页面保留原生图标，也会排除被通知栏动画移入 fake container 的状态栏行。

alpha5 撤回该主状态栏限定，让同类电池实例都能承载共享绘图。图标查找独立于主状态栏祖先，按当前局部行绑定；每个实例保持独立的原生 Drawable、配色输入、尺寸和可见性快照，QS 的 ConstraintLayout 通过电池内部读数组完成占位。电话服务兜底采样改为进程级限频，多个头部实例共用采样结果。

`python3 tests/coloros-hosts.py` 执行生产 `applyInternal`、`restoreBatteryInternal` 和 `syncExtra`，验证四个上下文都接管、单实例异常/卸载/禁用恢复，以及 QS 内部读数组挂载。局部容器夹具覆盖真实资源中的 `system_icons` 和 `quick_qs_status_icons` 关系、整行迁移、嵌套行与歧义候选。alpha4 原代码会在新增的跨页面接管断言中失败。

共享源码一致性检查继续保护绘图和配置规则；它本身不验证平台接入范围，跨页面回归由新增检查和目标设备验收补充。复测 alpha5 时应分别记录桌面、通知栏、控制面板和锁屏的状态，再检查收起/展开过程及关闭模块后的恢复。首个 host 日志现在包含电池 ID、局部行 ID 和所在容器类型。
