# 构建 AntiGem 简体中文版

使用 JDK 21、Go 1.26.5（见 `agy_ide_bridge/go.mod`）、`xz` 和 Android SDK。
安装 SDK Platform 37.1、Termux:AM 所需的 SDK Platform 36 和 Build Tools 36.0.0，并通过 `ANDROID_HOME` 指定 SDK 路径。
Gradle 版本由仓库中的 Wrapper 固定。

```sh
git clone https://github.com/Soda14615/antigem.git
cd antigem
git switch zh-cn-v2.0.1
git submodule update --init --depth 1 termux-am
sdkmanager 'platforms;android-36' 'platforms;android-37.1' 'build-tools;36.0.0'
```

请完整克隆主仓库。构建会读取 `version.json` 的提交历史来生成应用内的更新记录。
复现某次发布时，请在初始化子模块前检出该 Release 对应的标签。
`v2.0.1` 使用上游官方 APK 的构建提交 `bbb2619` 作为基线。

同步上游时，在第一次克隆后设置 `upstream` 远程。选定新版 Release 对应的构建提交后，更新 `zh-cn` 分支并检查新增的英文资源是否已有对应的 `values-b+zh+Hans` 条目。

```sh
git remote add upstream https://github.com/Santoshkurmi/antigem.git
git fetch upstream --tags
```

将本项目的发布签名密钥保存在仓库外的私有位置，并设置环境变量 `ANTIGEM_KEYSTORE` 指向其绝对路径。
构建沿用项目的发布签名配置，密钥别名为 `antigem`，口令须与 `app/build.gradle.kts` 中的签名配置一致。
请保留同一签名密钥以供后续构建，并妥善备份。严禁将密钥文件或其 Base64 内容提交到 Git 或上传为发布附件。

```sh
ln -s "${ANTIGEM_KEYSTORE:?请指定发布密钥路径}" app/release.jks
./gradlew :app:assembleRelease -Pantigem.arm64Only=true
./gradlew :app:testDebugUnitTest
```

发布 APK 位于 `app/build/outputs/apk/release/`。将 `APK` 设置为生成的文件路径，再用 Android Build Tools 检查签名和架构。

```sh
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs "$APK"
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging "$APK"
```

架构应仅显示 `arm64-v8a`，语言配置应包含 `zh-Hans`。在支持 ARM64 的 Android 24 或更新版本上，分别用简体中文及英语系统语言启动应用以验证界面。

汉化版使用自己的发布签名。Android 只允许用相同应用 ID 和相同签名的包原地升级，因此已安装上游官方版时，需要先备份数据并卸载官方版，再安装汉化版。
外置 Termux:API 和 Termux:X11 与主应用共享 Android UID，必须和主应用使用同一签名。上游提供的外置 APK 使用上游签名，与汉化版的签名不同。

## 同步上游和补充翻译

首次克隆后可用 `git remote add upstream https://github.com/Santoshkurmi/antigem.git` 关联上游。同步时运行 `git fetch upstream --tags`，核对官方 Release 实际构建的提交，再将该提交合并到 `zh-cn` 分支。

新增界面文案时，在 `app/src/main/res/values/` 保留英文默认值，在 `app/src/main/res/values-b+zh+Hans/` 添加同名的简体中文资源。保持格式占位符、命令、路径和模型名称一致。用上面的构建命令和两种系统语言验证改动。
