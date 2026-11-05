# PR #15 审查与整合

审查对象：jahn2007/ColorDuo `9a3811e0586bf59cf1d88ba948f456e4540dde8c`，基于本仓库 `98369c2`。保留作者的网络切换、设置分组和滑块合并实现；API 102 入口及正式签名流程保持同一路径。

已修正的具体问题：

- VPN 底层列表里存在无法读取或无法识别的网络时，原实现跳过该项，可能将混合路径误判成移动数据并隐藏 Wi-Fi。现在回退到 UNKNOWN，保留关联状态判断；USB 等其他默认网络也纳入分类。
- 原 Wi-Fi 采样会用枚举到的首个网络替换主连接，在双 Wi-Fi 下可能读到副连接。现在优先使用明确的主连接，或 WifiManager 的主连接快照；缺少主标识且存在多个候选时，不猜测标准号。
- 滑块延迟写入队列按稳定参数对象隔离，避免同名同范围的控件复用队列；保留编辑器退出和应用退后台时补写最终值。
- 恢复本项目版本与更新记录结构：0.3.14 / 10047，不采用 fork 的版本名称。

Wi-Fi 标准显示参考 LuckyTool 的 [WiFiDataIconRelated.kt](https://github.com/luckyzyx/LuckyTool/blob/ba6ade6e2780c702a83835ef326e9128b54156a9/app/src/main/java/com/luckyzyx/luckytool/hook/scopes/systemui/WiFiDataIconRelated.kt)：读取真实 WifiInfo，并补齐 11n/11ac 的 4/5。ColorDuo 在自身图标内绘制标准号，支持 4、5、6、6E、7；11be 的 Android 常量是 8，11ad 的常量 7 不得误报为 Wi-Fi 7。未知标准不显示。新开关默认关闭，纳入复制、配置增量、完整重连和恢复默认。

验证包含实际生产 Wi-Fi 观察器和路由代码的独立框架夹具、真实绘制代码，以及 Android 单元测试。审查原版的 InternetRoute 和 WifiPresence 分别触发 VPN 误判和主连接等级回归失败，修正后通过。新增绘制场景覆盖三种布局、弱信号、全部标准及 60%–300% 尺寸；尚未测量真机帧耗时。

Wi-Fi 标准采用右下角紧凑标记，6E 的 E 使用小号肩标，不缩小或偏移原来的 Wi-Fi 图形。本地验证通过 14 组回归、30 项 Android 单元测试，其中 Wi-Fi 观察与路由 21 项，绘制场景 3785 项。
