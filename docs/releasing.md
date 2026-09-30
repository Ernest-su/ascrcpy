# 发布流程

推送 `vMAJOR.MINOR.PATCH` 形式的 tag，GitHub Actions 会构建已签名的 release APK 并发布为
GitHub Release。普通 push 和 pull request 只会跑 [CI workflow](../.github/workflows/ci.yml)
里的构建和单元测试。

## 1. 一次性配置仓库 secrets

发布需要四个 repository secrets，在
`Settings -> Secrets and variables -> Actions -> New repository secret` 中创建：

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | keystore 文件的 base64 编码 |
| `STORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

生成 keystore 并编码：

```shell
keytool -genkeypair -v \
  -keystore ascrcpy-release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias ascrcpy \
  -dname "CN=AScrcpy, OU=AScrcpy, O=AScrcpy, L=, ST=, C="

base64 -w0 ascrcpy-release.jks
```

密钥库文件和 `keystore.properties` 已在 `.gitignore` 中，**绝不能提交**。备份好 keystore：一旦
丢失就无法为已发布的应用发布更新。

## 2. 本地验证签名构建

在仓库根目录写入 `keystore.properties`（已被 gitignore）：

```properties
storeFile=/absolute/path/to/ascrcpy-release.jks
storePassword=***
keyAlias=ascrcpy
keyPassword=***
```

然后：

```shell
./gradlew :app:assembleRelease
```

产物为已签名的 `app/build/outputs/apk/release/app-release.apk`；没有 `keystore.properties` 时产物是
`app-release-unsigned.apk`，这是本地开发的正常结果。

## 3. 发版

```shell
git tag -a v1.0.0 -m "AScrcpy 1.0.0"
git push origin v1.0.0
```

[release workflow](../.github/workflows/release.yml) 会：

1. 校验四个 secrets 都已配置；
2. 从 tag 推导 `versionName` 和 `versionCode`（`MAJOR * 10000 + MINOR * 100 + PATCH`）；
3. 还原 keystore，跑完整单元测试；
4. 构建 release APK，**并校验产物确实已签名**，否则直接失败，不会发布未签名的包；
5. 创建 GitHub Release，附上 `AScrcpy-<version>.apk`、`LICENSE` 和 `THIRD_PARTY_NOTICES.md`。

带预发布后缀的 tag（如 `v1.1.0-rc1`）会被标记为 prerelease，`versionName` 保留后缀，
`versionCode` 仍由三段数字推导。

本地也可以手动触发：`Actions -> Release -> Run workflow`，输入已有 tag 的版本号（不带 `v`）。

## 4. 版本号规则

- `versionCode` 从 tag 推导，不要手工指定。同一 `versionCode` 无法覆盖安装。
- `MINOR` 和 `PATCH` 必须小于 100，否则编码会进位撞车（例如 `v1.100.0` 会得到 `20000`，和
  `v2.0.0` 一样）。workflow 会直接拒绝这类 tag。
- `MAJOR` 必须小于 210000，超过后 `versionCode` 会超出 Android 上限。
- 不接受两段或四段形式的 tag（如 `v1.2`、`v1.2.3.4`），避免发布出无法升级的包。

## 与 scrcpy 版本的关系

`app/src/main/assets/scrcpy-server-v4.0` 与 `ScrcpyClient.SERVER_VERSION` 必须锁定同一个 scrcpy
版本。升级 scrcpy 时要在同一个改动里更新 asset、协议实现、版本常量、
[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) 的 SHA-256、测试和架构文档，然后才打 tag。
