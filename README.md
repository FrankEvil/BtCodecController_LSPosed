# BtCodecController (LSPosed) v0.2

针对 Redmi K30 Pro 移植 ColorOS 16 的个人测试模块。

## 设计

- 总开关 OFF：模块完全不干预系统蓝牙 Codec。
- 总开关 ON：按当前 A2DP 耳机 MAC 记录目标 Codec。
- 四个选项：
  - SBC
  - AAC
  - LHDC V5
  - LHDC V3（实验）
- 蓝牙重新开启、耳机重新连接、Active Device 变化后，会重新应用已记录 Codec。
- Codec type 不在配置中写死：
  - SBC/AAC 走系统 type；
  - LHDC 优先读取 `BluetoothCodecType` 的扩展 name / codecId；
  - 若厂商只暴露两个同名 LHDC 条目，则用运行时 capability + priority 区分 V5/V3；
  - UI 显示运行时识别出的 V5/V3 type，方便核对。
- LHDC V3 切换后会读回实际 Codec：
  - 成功：记录结果；
  - 失败：优先退回上一次成功的 AAC/SBC，否则 AAC，再否则 SBC，并提示。
- 不修改 `/vendor`、不修改 A2DP Offload、不写系统属性。

## LSPosed

只需要作用域：

```text
com.android.bluetooth
```

模块使用 libxposed API 101。

## 构建环境

- JDK 17
- Android SDK 36
- Gradle 8.13
- Android Gradle Plugin 8.13.2

依赖：

```text
io.github.libxposed:api:101.0.1
```

## Android Studio 构建

直接用 Android Studio 打开项目，等待 Gradle Sync，然后：

```bash
gradle wrapper --gradle-version 8.13
./gradlew :app:assembleRelease
```

APK：

```text
app/build/outputs/apk/release/app-release.apk
```

## 第一次安装

1. 安装 APK。
2. LSPosed 中启用模块。
3. 作用域勾选 `com.android.bluetooth`。
4. 第一次启用模块后需要重启一次蓝牙进程或手机，让 LSPosed 将模块注入蓝牙进程。
5. 后续切换 SBC/AAC/LHDC V5/V3 不需要整机重启；断开/重连或开关蓝牙时会自动再次应用。

## 调试

```bash
adb logcat -v time BtCodecCtrl:V LSPosedFramework:I '*:S'
```

重点看：

```text
Bluetooth codec controller ready
LHDC V3 failed
```

## 说明

LHDC V3 在当前设备的运行日志中存在于 LocalCapabilities，但此前不在当前耳机的 SelectableCapabilities 中，因此 V3 属于实验选项。模块会真实尝试并读回确认，不会把“调用成功”误报成“切换成功”。


## v0.2 修复

v0.1 会直接把 capability 中的原始 `BluetoothCodecConfig` 传给
`A2dpService.setCodecConfigPreference()`。

在目标 ROM 中观察到的优先级大致是：

- LHDC V5: 9002
- AAC: 2001
- SBC: 1001

Android A2DP 的选择逻辑会比较 requested codec 与所有 selectable codec 的 priority，
所以 v0.1 选择 AAC/SBC 时，LHDC V5 仍然优先，表现就是“只有 V5 能切换”。

v0.2：
- 动态复制目标 Codec；
- 将本次请求 priority 提升为系统 `CODEC_PRIORITY_HIGHEST`；
- 保留 sample rate / bits / channel / codecSpecific1~4 / extended codec type；
- AAC/SBC/V5 走正常 A2dpService API；
- V3 如果只存在 LocalCapabilities、不在 SelectableCapabilities：
  - 仅在用户明确选择 V3 时做一次 experimental native preference；
  - 读回实际 Codec；
  - 失败立即回退上一次成功项 / AAC / SBC。
