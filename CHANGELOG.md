# 更新日志

本项目版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## 1.0.0（2026-10）

首个公开版本。

### 新增

- 目标应用内嵌选点窗口：腾讯地图选点 + 地名搜索，确认后立即生效。
- 音量键快捷入口：音量+ 呼出/恢复窗口，音量− 隐藏；窗口关闭时音量− 不拦截。
- 双通道定位数据接入：同时覆盖系统 `LocationManager` 与腾讯定位 SDK（`com.tencent.map.geolocation.sapp.*`）。
- Android 16 回调适配：重写 `LocationManager$LocationListenerTransport`，支持 `Location`、`List<Location>` 与 LocationResult 容器。
- 全精度坐标：广播与首选项使用字符串传递 `double` 坐标，避免 `float` 精度损失。
- NMEA 语句改写（RMC/GGA/GLL）并重算校验和；限制 GNSS 原始测量与导航电文注册。
- WGS-84 / GCJ-02 双向转换，按消费方选择坐标系，修正二次转换导致的数百米偏移。
- 首帧位置处理：注册后立即用 SDK 缓存对象推送配置坐标，避免先显示缓存位置再跳回。
- 环境一致性处理：精度 8 m、`provider=gps`、`isMock()` / `isFromMockProvider()` 恒为 false、卫星数固定。
- 目标应用内嵌地图的 `Context` 与 IMEI 读取兜底（`DeviceIdGuard`），避免初始化崩溃。
- 模块 App：手动坐标、腾讯地图拾取、照片注入、总开关与生效自检。
- 纯 JVM 单元测试：坐标转换、NMEA 改写、回调参数重写、Hook 参数解析等。

### 变更

- 模块包名更改为 `write.LocationGPS`。
- 腾讯地图 key 不再硬编码，改为构建时从 `local.properties` 注入。

### 修复

- 修复模块自检因包名不一致导致「插件启动失败」误报。
- 总开关状态持久化到首选项，重启目标应用后保持。

### 已知问题

- 目标应用升级可能改变内部类名与方法名，需要重新适配。
- 目标应用前台期间「音量+」被模块占用，通话中无法用音量键调音量。
