# 构建指南

## 环境要求

| 项目 | 要求 |
|------|------|
| JDK | Java 21（必须使用 Android Studio 内置 JBR） |
| Gradle | 8.x（通过 Wrapper 管理） |
| SDK | compileSdk 36, minSdk 26, targetSdk 35 |
| NDK ABI | 仅 arm64-v8a |
| IDE | Android Studio（推荐最新稳定版） |

> **重要**: 系统 JDK 25+ 与 Gradle 8.x 不兼容（`JavaVersion.parse("25")` 会失败）。必须使用 Android Studio 内置的 JBR（Java 21）。

---

## 构建步骤

### 1. 克隆仓库

```bash
git clone https://github.com/HaohanHe/Opedrgent.git
cd Opedrgent
```

### 2. 依赖获取（无需手动放置 AAR）

Sherpa-ONNX 通过 JitPack 坐标 `com.github.k2-fsa:sherpa-onnx:1.13.1` 由 Gradle 自动拉取，**无需手动下载或放置 AAR**，但首次构建需要可访问 Maven Central 与 JitPack（jitpack.io）的网络。

> `app/libs/sherpa-onnx-1.13.2.aar` 是早期本地方案的历史遗留：当前 `app/build.gradle.kts` 并未通过 fileTree 引用它，构建实际使用 JitPack 上的 1.13.1。该文件不参与编译，保留或删除均可。

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

### Q: Sherpa-ONNX 依赖拉取失败 / 相关编译错误
A: Sherpa-ONNX 由 Gradle 从 JitPack（`com.github.k2-fsa:sherpa-onnx:1.13.1`）解析，请确认网络可访问 jitpack.io，必要时在 Android Studio 配置代理后重新 Sync，无需手动放置 AAR。

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
│       │   ├── insight/         # 洞察引擎
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

---

## 零障碍复现（命令行 + 本地 JDK21 + 本地 SDK + init 镜像）

> 适用于无 Android Studio、需在干净机器上纯命令行复现 debug 包的场景。已于 2026-10-02 独立复核：`:app:clean :app:assembleDebug` **BUILD SUCCESSFUL（约 5 分钟）**，产物 `top.hsyscn.opedrgent` versionCode=4 versionName=1.2.1，仅 arm64-v8a，Android Debug 签名校验通过。

### 工具链版本

- JDK：OpenJDK **21.0.2**（系统 JDK 11/25 均不可；必须显式指定 JDK21）
- Gradle：**8.14.5**（直接用解压发行版；亦可改用 `./gradlew`，但须保证 `JAVA_HOME=21`）
- SDK：compileSdk **36** / minSdk **26** / targetSdk **35**，build-tools **36.0.0**，仅 ABI **arm64-v8a**
- AGP 8.13.2 / Kotlin 2.3.0

### 1. 准备 JDK21 与 Android SDK

SDK 组件（均来自 `https://dl.google.com`，实测可达）：

```bash
# cmdline-tools
curl -O https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
# platforms;android-36
curl -O https://dl.google.com/android/repository/platform-36_r02.zip
# 其余用 sdkmanager 拉取并接受许可
yes | sdkmanager --sdk_root=$ANDROID_SDK_ROOT "build-tools;36.0.0" "platform-tools"
```

布局：`android-sdk/{cmdline-tools/latest, platforms/android-36, build-tools/36.0.0, platform-tools, licenses}`。

### 2. 仓库外 Gradle 镜像 init 脚本

新建 `init.gradle`（**不改项目 settings/build 文件**，以 `-I` 注入），把阿里云镜像前置、官方源与 jitpack 作为回退：

```groovy
def GOOGLE = "https://maven.aliyun.com/repository/google"
def CENTRAL = "https://maven.aliyun.com/repository/central"
def PLUGIN = "https://maven.aliyun.com/repository/gradle-plugin"
beforeSettings { s ->
    s.pluginManagement.repositories.maven { url(GOOGLE) }
    s.pluginManagement.repositories.maven { url(CENTRAL) }
    s.pluginManagement.repositories.maven { url(PLUGIN) }
    s.dependencyResolutionManagement.repositories.maven { url(GOOGLE) }
    s.dependencyResolutionManagement.repositories.maven { url(CENTRAL) }
    // jitpack 保持 settings.gradle.kts 内原地址 https://jitpack.io（阿里云 jitpack 代理 401）
}
```

> 项目 `settings.gradle.kts` 已设 `RepositoriesMode.FAIL_ON_PROJECT_REPOS`；该模式只约束 project 级 buildscript 仓库，**不影响** settings 级 `beforeSettings` 前置注入，故配置可成功。

### 3. 环境变量与构建

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT=/path/to/android-sdk
export GRADLE_USER_HOME=/path/to/isolated-gradle-home   # 独立缓存，避免 ~/.gradle 陈旧锁冲突

gradle -I /path/to/init.gradle --no-daemon \
  -Dorg.gradle.java.installations.paths=$JAVA_HOME \
  -Dorg.gradle.java.installations.auto-download=false \
  :app:clean :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

### 4. 校验产物

```bash
$ANDROID_SDK_ROOT/build-tools/36.0.0/aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk \
  | grep -E '^package:|minSdk|targetSdk|native-code'
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E 'classes.*\.dex|lib/arm64-v8a/.*\.so'
$JAVA_HOME/bin/java -jar $ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/debug/app-debug.apk
```

预期：`package: name='top.hsyscn.opedrgent' versionCode='4' versionName='1.2.1'`、`minSdkVersion:'26'`、`targetSdkVersion:'35'`、`native-code: 'arm64-v8a'`；多 dex + `lib/arm64-v8a/*.so`；`Verifies` 且签名者 `CN=Android Debug`。
