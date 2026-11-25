# 影梭 · 路线巡航分支（GoGoGo fork）

这是 [**ZCShou/GoGoGo**](https://github.com/ZCShou/GoGoGo)（应用名「影梭」，Android 模拟位置 App）的一个 fork，
在原作基础上加了 **自动巡航** 和 **路线记录 / 路线记忆** 两块功能。

> **版权与出处**
> - 上游仓库：<https://github.com/ZCShou/GoGoGo>
> - 原作者：**ZCShou**（<https://github.com/ZCShou>）
> - 许可证：**GNU GPL v3**（见本仓库 `LICENSE`，与上游一致、未改动）
> - 本仓库的全部改动都是在原作者代码上做增量修改，核心代码版权归原作者所有；
>   依照 GPL v3，本 fork 同样以 GPL v3 发布，并保留原作者署名与许可证全文。
> - 上游 README 原文保留在 `README.upstream.md` / `README.upstream.en.md`。

---

## 一、这个分支加了什么（v1.12.7-route / versionCode 1127）

1. **路线记录**：点地图上的路线按钮 →「记录路线」，开始按 ≥2 m 的点距记录当前移动轨迹，
   停止后弹框命名，存进本地 SQLite（`RouteStore.db`，表 `Route`）。
2. **路线记忆**：路线列表显示「名称 · 点数 · 录制速度」，点击即开始巡航，长按可重命名 / 删除。
3. **自动巡航**：沿路线按 Haversine 距离逐点推进，可设为循环；**速度就是录制这条路线时的平均速度**，不用手填。
4. **巡航驱动摇杆（关键设计）**：巡航不再自己写坐标，而是驱动作者原本的 `JoyStick`，
   和手动摇杆共用一条通道 —— 手一碰摇杆立刻接管，松手 1.5 秒后巡航自动接回，不再「抢方向盘」。
5. **后台保活**：`foregroundServiceType="location"` + `PARTIAL_WAKE_LOCK` + 每秒刷新前台通知
   （`巡航中 · 1.3 m/s · 已走 42 米`），并引导加入电池优化白名单；额外给 `FUSED_PROVIDER` 也注入一份。
6. **地图叠加巡航路线**：用蓝色 Polyline 把正在跑的路线画在地图上。

### 与原版不同的文件

| 文件 | 改动 |
| --- | --- |
| `app/src/main/java/com/zcshou/utils/RoutePilot.java` | 新增：巡航线数学（Haversine、方位角、逐帧步进） |
| `app/src/main/java/com/zcshou/utils/RouteStore.java` | 新增：路线 SQLite 存取（`ID/NAME/POINTS/CREATED/UPDATED/SPEED`） |
| `app/src/main/java/com/zcshou/joystick/JoyStick.java` | 加 `isUserActive()` / `pulseDrive()` / `cancelManualMove()` |
| `app/src/main/java/com/zcshou/service/ServiceGo.java` | 巡航驱动、轨迹录制、前台通知刷新、Fused 注入、WakeLock |
| `app/src/main/java/com/zcshou/gogogo/MainActivity.java` | 路线菜单、地图画线、电池优化引导 |
| `app/src/main/res/layout/main_map.xml` / `drawable/ic_route.xml` / `values/strings.xml` | 路线按钮与 21 条 `route_*` 文案 |
| `app/src/main/AndroidManifest.xml` | `foregroundServiceType="location"`、请求忽略电池优化权限 |
| `app/build.gradle` | 版本号；**签名改为可选**（找不到 keystore 就退回 debug 签名） |

> 说明：上游的 GitHub Actions（`.github/`）没有随 fork 一起带过来，因为它们的 release 工作流依赖作者自己的签名密钥。

---

## 二、构建

环境（实测通过）：

- JDK **21**（AGP 8.12.1 + Gradle 8.13）
- Android SDK：`platforms;android-32`、`build-tools;36.0.0`、`platform-tools`
- NDK `26.2.11394342`（沿用上游 `ndkVersion`，不编 native 代码，但配置要能解析）

步骤：

```bash
# 1. 指一下 SDK
echo sdk.dir=/path/to/android-sdk > local.properties

# 2. 填你自己的百度地图 AK（见下一节）

# 3. 编译
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/Go_1.12.7-route_arm64-v8a_release.apk
```

### 百度地图 AK（必须自己申请）

本仓库 **不包含任何 AK**：原作者那把 AK 已从 `strings.xml` 移除（空串）。

1. 到 <https://lbsyun.baidu.com/> 申请 **Android 定位 SDK + 地图 SDK** 的 AK；
2. 包名填 `com.zcshou.gogogo`，签名 SHA1 填你自己的签名（debug 包用 `~/.android/debug.keystore` 的 SHA1）；
3. 把 AK 写进 `app/src/main/res/values/strings.xml` 的 `maps_api_key`。

不加 AK 也能编译安装，但地图是灰的、定位初始化会失败。

### 签名

- 仓库里 **没有** 任何 keystore（原作者私钥当然不会公开）。
- 不放 keystore 时自动用 debug 签名，功能可用，但**签名与官方版不同，不能覆盖安装官方版**，需要先卸载。
- 想自己出 release 包：放一个 keystore 到 `keystore/gogogo.jks`，再用环境变量给出
  `GOGO_STORE_PASS` / `GOGO_KEY_ALIAS` / `GOGO_KEY_PASS`（或改用你自己的 `signingConfigs`）。

---

## 三、使用

1. 安装后打开 App → 设置里选「模拟位置」模式 → 打开服务（给定位权限）。
2. 第一次开始巡航时会请求「忽略电池优化」，建议允许，否则切到别的 App 后可能被系统冻结。
3. 点地图右侧的路线按钮：
   - 「记录路线」→ 走一段 →「停止并保存」→ 起个名字；
   - 之后点列表里的路线即可开始巡航（速度用录制时的平均速度）；
   - 长按列表项可重命名 / 删除。
4. 系统「开发者选项」→「选择模拟位置信息应用」→ 选 **影梭**。

巡航中你随时可以动摇杆：手一碰就由你接管，松手 1.5 秒后巡航自动接回。

---

## 四、已知限制

- 部分 App 会检查 `Location.isFromMockProvider()` 或做反作弊，模拟定位可能被识别。
- 只支持 **arm64-v8a**（沿用上游 `abiFilters`）。
- 路线是「点与点之间直线插值」，不贴道路；点太稀会显得折线化，录制时慢一点走更平滑。
- iOS 侧没有等价实现：系统沙箱不允许 App 修改全局定位（只能用电脑端 USB 工具或 Xcode + GPX）。

---

## 五、免责声明

本项目仅供 **个人学习与测试**。使用它伪造位置可能违反你所用第三方 App 的服务条款，
由此带来的账号风险、法律风险由使用者自行承担；原作者与本 fork 维护者不承担任何责任。

**请不要用于考试打卡、考勤代打、刷单等欺骗性用途。**

---

## 六、致谢

- 原作者 **ZCShou**：定位模拟的核心实现、百度 SDK 集成、摇杆交互都来自上游。
- 上游使用与引用的第三方组件（百度地图 / 定位 SDK、AndroidX、OkHttp、xLog、Markwon 等）版权归各自所有者。
