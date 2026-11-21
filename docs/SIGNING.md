# 发布签名（唯一密钥）

## 为什么必须统一

Android 只允许在 **applicationId 相同且签名证书相同** 时原地升级。所以：

- 包名必须是 `ai.antigem`（debug 变体是 `ai.antidev`，与正式包互不覆盖）
- 所有 APK 必须用**同一把 keystore** 签名

任何一次"本地随手 `keytool` 生成一把"，都会导致已安装用户必须卸载重装。

## 密钥存放约定

| 位置 | 用途 |
| --- | --- |
| 仓库外私有目录（如 `~/.keys/antigem.jks`） | 唯一副本，人工备份 |
| GitHub Secret `RELEASE_KEYSTORE_BASE64` | CI 构建用（`base64 -w0 antigem.jks`） |
| 环境变量 `ANTIGEM_KEYSTORE` | 本地与合作方构建时指向该文件 |

**禁止**：把 `.jks`、其 base64、或口令提交进 Git、贴到 issue、上传为 Release 附件。

## CI 配置

仓库 Settings → Secrets and variables → Actions：

| 名称 | 类型 | 内容 |
| --- | --- | --- |
| `RELEASE_KEYSTORE_BASE64` | Secret | `base64 -w0 antigem.jks` 的输出 |
| `RELEASE_KEYSTORE_PASSWORD` | Secret | keystore 口令 |
| `RELEASE_KEY_PASSWORD` | Secret | 私钥口令（与上面相同则可不设） |
| `ANTIGEM_RELEASE_CERT_SHA256` | **Variable** | 证书 SHA-256（小写十六进制，64 位） |

`ANTIGEM_RELEASE_CERT_SHA256` 用于 workflow 校验产物证书，取法：

```sh
keytool -list -v -keystore antigem.jks -alias antigem | grep -i "SHA256:"
```

缺任一 Secret，workflow 会退化成 `ai.antidev` 的 CI-only 调试包（明确标注、不可用于升级）；
release 路径下证书不匹配会直接失败。

## 本地 / 合作方构建

```sh
export ANTIGEM_KEYSTORE=/绝对路径/antigem.jks
export RELEASE_KEYSTORE_PASSWORD='口令'
# 可选：RELEASE_KEY_ALIAS（默认 antigem）、RELEASE_KEY_PASSWORD（默认同 store 口令）
./gradlew :app:assembleRelease -Pantigem.arm64Only=true
```

`ANTIGEM_KEYSTORE` 未设置时，回退读取 `app/release.jks`（该文件被 gitignore）。
两者都不存在时，`packageRelease` 会**直接失败并提示**，不会产出一个"看着能装、实际装不上"的包。

构建后核对证书指纹，必须与 `ANTIGEM_RELEASE_CERT_SHA256` 一致：

```sh
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs app/build/outputs/apk/release/*.apk \
  | grep "SHA-256 digest"
```

## 包名规则

| 变体 | applicationId | 可否升级正式包 |
| --- | --- | --- |
| release | `ai.antigem` | 是（证书一致时） |
| debug | `ai.antidev` | 否，需卸载 |
| x86_64 debug（临时） | `ai.antigem` | 否，证书不同会冲突 |
