# 蓝牙水控器 · Android 移植版

[celesWuff/waterctl](https://github.com/celesWuff/waterctl) 的原生 Android (Kotlin) 移植版。

原项目是一个纯 Web 应用（TypeScript + Web Bluetooth），本工程把它的**协议逻辑、密钥推导
（原本是手写 WebAssembly）、蓝牙状态机和界面**全部翻译成 Android 原生实现 —— 不套 WebView，
不需要联网，也不需要微信。

> [!NOTE]
> **本项目由 AI 移植。** 代码由 **DeepSeek**（[chat.deepseek.com](https://chat.deepseek.com)）
> 在人工指导下完成 —— 需求提出、真机测试、方案取舍由人负责；协议翻译、BLE 封装、界面实现与
> 调试由 AI 编写。**尚未在“新固件”水控器上验证**，详见[已知限制](#已知限制)。

## 功能

- 连接宿舍里的“蓝牙水控器”（服务 UUID `0xF1F0`），一键开启 / 停止用水
- 兼容新旧两代固件（旧固件直接开；新固件先做 `AE`/`AF` 密钥质询应答）
- 记住上一次连接成功的设备，**退出重进后仍在**，可一键直连（免扫描）
- 扫描约 3 秒完成，结果 60 秒内可复用
- 内置调试日志面板，同时镜像到 `adb logcat`（TAG `waterctl`）
- 完全离线，无广告、无统计、无网络权限

## 界面

界面居中，三排按钮，风格统一（同宽同高同字号）：

| 排 | 按钮 | 未连接 | 已连接 | 使用中 |
|----|------|--------|--------|--------|
| — | **刷新**（状态卡片内右上角的 ⟳ 图标） | 可点 | 隐藏 | 隐藏 |
| 1 | **连接 / 断开** | `连接水控器` | `断开连接` | `断开连接` |
| 2 | **连接上次设备** | `连接到上次设备 <名字>` | 隐藏 | 隐藏 |
| 3 | **开启 / 停止** | `开启`（**灰色不可点**） | `开启` | `停止` |

第三排是实心主操作，前两排是描边次要操作。第二排只在“未连接且成功连过至少一次”时出现。

**刷新按钮**放在状态卡片的右上角，用来手动重搜：一次扫描有可能什么都没搜到（水控器刚上电、
或链路被别人占着），这时点一下 ⟳ 立刻重来，不用退出界面。**搜索期间它会变灰**（扫描已经在
进行，没什么可刷新的），连接或使用中则隐藏（那两种状态下扫描帮不上忙）。「没有找到设备」
的弹窗里也直接给了「重新搜索」按钮，可以连续点着重试。

**两个按钮的语义是分开的**，这是和原版最大的差别：

- **停止** —— 结束用水，但**保持连接**。可以再点“开启”重新开始，不用重连。
- **断开连接** —— **无论在不在用水，都会先把水控器停掉**（发结束帧 → 等 `B3` 确认 → 回 `B4`），
  然后才断开。绝不会把正在计费的会话直接掐掉。
- **未连接时“开启”不可点** —— 没有链路就没有计费会话。

扫描期间第 1 排会置灰并显示 `正在搜索设备中…`；扫描结束后**不会自动弹窗**，状态栏提示
「搜索到 N 个设备」，点「连接水控器」才列出设备让用户选。**不会自动连接任何设备**：
宿舍走廊里通常同时有好几台水控器，只有用户知道哪台是自己的。

## 构建

需要 JDK 17+ 和 Android SDK（compileSdk 37）。

```bash
# Linux / macOS —— 单元测试不需要真机
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug        # 产出 app/build/outputs/apk/debug/app-debug.apk

# Windows
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:assembleDebug
```

安装到已连接的手机：`adb install -r app/build/outputs/apk/debug/app-debug.apk`

环境：Gradle 9.5.0 / AGP 9.3.3 / Kotlin（AGP 内置）/ minSdk 24 / targetSdk 37。

### Release 构建

`release` 开启了 R8 代码压缩与资源压缩（`isMinifyEnabled = true`、`isShrinkResources = true`），
所以产物体积只有 debug 的 1/5 左右（**1.4 MB vs 6.8 MB**）：

```bash
gradlew.bat :app:assembleRelease   # -> app/build/outputs/apk/release/app-release-unsigned.apk
```

**输出是 `-unsigned`，不能直接安装** —— 项目里没有配置签名。要用 Android Studio 的
`Build > Generate Signed App Bundle / APK` 生成一个 keystore，或者自己在 `build.gradle.kts`
里加 `signingConfigs`，然后把 `signingConfig = signingConfigs.getByName("release")` 赋给
`buildTypes.release`。

压缩规则在 [app/proguard-rules.pro](app/proguard-rules.pro)。**本工程不需要任何业务 keep 规则**
（没有反射、没有 JNI、没有序列化），所以里面只有标准的保留行号等设置。注意不要把它和
`app/src/main/keepRules/rules.keep` 搞混：后者是 AGP 9 的新约定，本项目未使用。

## 测试

24 项单元测试全部通过，覆盖协议层（不需要真机）：

| 测试 | 内容 |
|------|------|
| `SolversTest` | 从上游 `src/solvers.spec.ts` 原样抄来的 **7 组密钥应答测试向量** |
| `AlgorithmsTest` | 两个 CRC-16、BCD 转换、固定帧、`deputy` 查找表锚点 |
| `RxdFrameParserTest` | 通知分片重组、前导 `0xFD` 丢失修复、异常帧丢弃 |

`SolversTest` 是移植正确性的关键：**`deputy.wat` 的 Kotlin 翻译错一个位运算，这 7 条就会全红**。

## 已知限制

> [!WARNING]
> **新固件的 `AE`/`AF` 密钥握手尚未在真机上验证。** 开发时使用的水控器是旧固件，直接回 `B0`，
> 没有走到密钥认证分支。该分支仅由上游的 7 组测试向量保障（见 `SolversTest`），
> **没有在走 `AE` 的真机上跑过**。如果你用的是新固件水控器，欢迎反馈日志
> （会打印 `KEY: unlock response ...`）。
>
> **实测过的组合**：Android 16 (API 36) + 旧固件水控器 `Water25980`，开关水全流程跑通。

其他：

- 设备名与协议强相关（密钥用名字后 4 位、启动帧校验和用名字后 5 位），
  所以**改设备名会导致连不上**。
- 首次连接前需要授予“附近的设备”权限（Android 12+）。
- 重装 App 或在系统设置里「清除数据」会丢掉“上次设备”记录，这是 Android 机制。

## 与原版的差异

| 位置 | 差异 | 原因 |
|------|------|------|
| 连接与开启分成两步 | 新增 `CONNECTED` 状态 | 浏览器里设备选择器本身就是一次点击；Android 上照搬会导致**未经同意就开始计费** |
| 设备发现 | 自己扫描 + 列表让用户选，不自动连接 | Web Bluetooth 的设备选择器由浏览器提供 |
| 扫描策略 | 发现水控器后 2 秒即结束扫描 | 原版固定等满窗口，设备早已拿到却还在转圈 |
| `RxdFrameParser` | 新增 | Android BLE 通知经常分片，原版按单次通知解析会丢帧 |
| `Solvers.makeDatetimeArray` | 用设备本地时区 | 原版硬编码 `Asia/Shanghai`，手机在别的时区就不对了 |

除上述几点外，协议行为（**包括原版刻意保留的几个 bug**）都保持一致；代码里凡是
`bug-for-bug compatible` 的地方都有注释说明。

## 项目结构

```
app/src/main/java/com/badghost/watercontrol/
├── MainActivity.kt          界面：连接/断开、开启/停止、设备选择、错误对话框
├── WaterSession.kt          协议状态机（对应原版 src/bluetooth.ts）
├── LastDeviceStore.kt       记住上一次连接成功的设备
├── Logger.kt                日志（对应 src/logger.ts）
├── bluetooth/
│   └── WaterBleClient.kt    Android BLE 封装：扫描 / 连接 / GATT / 通知
└── protocol/
    ├── Deputy.kt            ★ deputy.wat (WebAssembly) 的 Kotlin 翻译 + 2096 字节查找表
    ├── Solvers.kt           密钥应答、启动帧生成（对应 src/solvers.ts）
    ├── Algorithms.kt        CRC-16/ChangGong、CRC-16/CGAEAF（对应 src/algorithms.ts）
    ├── Payloads.kt          固定帧（对应 src/payloads.ts）
    ├── WaterUtils.kt        BCD 转换、十六进制打印（对应 src/utils.ts）
    └── RxdFrameParser.kt    BLE 通知分片重组（本移植版新增）

tools/make_icon.py          图标生成脚本（几何定义 → 矢量 + 各密度位图）
```

### 关于 `Deputy.kt`

原版的密钥推导是手写的 WebAssembly 模块 `src/deputy.wat`：一段 **2096 字节查找表**加一个
`makeKey` 函数。Android 上跑 WASM 需要额外依赖，所以这里**逐指令翻译成了 Kotlin**：
查找表逐字节照搬、所有偏移量保持原值，位运算、状态更新和“把 32 位结果按大端拆成 4 字节”
的行为完全一致。查找表经过脚本逐字节比对验证（2096/2096 匹配）。

### 图标

图标（白色水滴 + 镂空蓝牙符号 + 蓝色渐变）由脚本生成，改参数即可重新出图：

```bash
python tools/make_icon.py
```

脚本内置两条断言：**安全区检查**（自适应图标只有中间 72×72 保证可见）和
**小尺寸可读性**（96px 以下不画镂空符号，否则会糊成一团）。

## 许可

[MIT](LICENSE)。本项目是衍生作品，包含上游代码的翻译，因此保留上游版权声明：

- Copyright (c) 2021 [celesWuff](https://github.com/celesWuff) —— 原项目 `waterctl`
- Copyright (c) 2021-2024 celesWuff, Deputy —— 原 `deputy` WebAssembly 模块

## 免责声明

本项目仅供学习与技术研究，与设备厂商、学校均无任何关联。使用风险自负。

> [!CAUTION]
> 如果水控器提示拒绝启动（`err41` / `err43`），**不要反复重试** —— 多次失败可能导致水控器
> 锁定，锁定时它会拒绝一切传入连接，需要在通电状态下等待约一小时才能恢复。

使用前请确认符合你所在学校 / 单位关于水控设备的规定与资费条款。

## 致谢

- [celesWuff/waterctl](https://github.com/celesWuff/waterctl) —— 原项目，协议与密钥推导全部来自这里
- **Deputy** —— 原 `deputy` WebAssembly 密钥推导模块的作者
- **DeepSeek**（[chat.deepseek.com](https://chat.deepseek.com)）—— 本 Android 移植版的代码由其编写
