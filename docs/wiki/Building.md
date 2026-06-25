# 构建指南

## 环境要求

| 项目 | 要求 |
|------|------|
| JDK | Java 21（必须使用 Android Studio 内置 JBR） |
| Gradle | 8.x（通过 Wrapper 管理） |
| SDK | compileSdk 35, minSdk 26, targetSdk 35 |
| NDK | arm64-v8a + armeabi-v7a |
| IDE | Android Studio（推荐最新稳定版） |

> **重要**: 系统 JDK 25+ 与 Gradle 8.x 不兼容（`JavaVersion.parse("25")` 会失败）。必须使用 Android Studio 内置的 JBR（Java 21）。

---

## 构建步骤

### 1. 克隆仓库

```bash
git clone https://github.com/HaohanHe/Opedrgent.git
cd Opedrgent
```

### 2. 放置 Sherpa-ONNX AAR

Sherpa-ONNX AAR 需手动下载放入 `app/libs/` 目录：

```
Opedrgent/app/libs/sherpa-onnx-1.13.2.aar
```

> 当前仓库使用 stub 编译，实际使用语音功能需要真实 AAR。

### 3. 设置 JAVA_HOME

**Windows (PowerShell):**
```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
```

**macOS/Linux:**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

### 4. 构建 Debug APK

```bash
./gradlew assembleDebug
```

构建产物位于：
```
app/build/outputs/apk/debug/app-debug.apk
```

### 5. 仅编译 Kotlin（快速验证）

```bash
./gradlew compileDebugKotlin
```

---

## 代理配置

如果在防火墙后面，设置 HTTP 代理：

```powershell
$env:HTTP_PROXY="http://127.0.0.1:7897"
$env:HTTPS_PROXY="http://127.0.0.1:7897"
```

---

## 常见问题

### Q: `Could not determine java version from '25'`
A: 系统 JDK 版本太高。使用 Android Studio 内置 JBR：
```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
```

### Q: Sherpa-ONNX 相关编译错误
A: 确保 `app/libs/sherpa-onnx-1.13.2.aar` 文件存在。如果是 stub 编译，部分语音功能不可用。

### Q: Health Connect 权限请求不生效
A: 检查以下几点：
1. 设备是否安装了 Health Connect 应用
2. `AndroidManifest.xml` 中 `health_permissions_privacy_policy` 是否指向有效的隐私政策 URL
3. `HealthConnectPermissionsRationaleActivity` 是否设置了 `exported="true"`
4. 是否有 `ACTIVITY_RECOGNITION` 运行时权限

### Q: 日历工具不工作
A: 检查：
1. `AndroidManifest.xml` 中是否声明了 `READ_CALENDAR` 和 `WRITE_CALENDAR` 权限
2. 运行时是否已授予日历权限
3. 设备是否有可写的日历账户

---

## 项目结构

```
Opedrgent/
├── app/
│   ├── libs/                    # Sherpa-ONNX AAR
│   └── src/main/
│       ├── java/top/hsyscn/opedrgent/
│       │   ├── ui/              # Compose UI
│       │   ├── network/         # 网络层
│       │   ├── tools/           # 工具系统
│       │   ├── stt/             # 语音识别
│       │   ├── tts/             # 语音合成
│       │   ├── interview/       # 面试模式
│       │   ├── insight/         # 发芽引擎
│       │   ├── note/            # 笔记系统
│       │   ├── storage/         # 存储层
│       │   ├── health/          # 健康数据
│       │   ├── calendar/        # 日历操作
│       │   └── ...
│       ├── res/                 # 资源文件
│       └── AndroidManifest.xml
├── docs/wiki/                   # Wiki 文档
├── PRIVACY.md                   # 隐私政策
├── README.md                    # 项目说明
├── ROADMAP.md                   # 路线图
└── build.gradle.kts             # 构建配置
```
