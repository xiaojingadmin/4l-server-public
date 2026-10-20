# APK / IPA 地址补丁 Web

上传原始 5.1.0 APK 或 IPA，服务会自动修改 IL2CPP metadata 中的 API 地址与资源 CDN 地址，并生成下载文件。

需要 Python 3.10+。

## 启动

```bash
python3 tools/client_patch_web/server.py --host 0.0.0.0 --port 8090
```

浏览器打开 `http://服务器地址:8090/`。服务只接受 `.apk` / `.ipa`，默认最大 2 GiB，默认同时处理 2 个任务，结果保留 1 小时。

处理结果的文件名会沿用上传时的文件名，例如 `game.apk` 输出为
`game-patched.apk`；自动签名成功时改为 `game-signed.apk`。

## 地址配置

```bash
export PATCH_API_ORIGIN='https://linklike.lovelive-series.cn'
export PATCH_ASSET_ORIGIN='https://assets-link-like.lovelive-series.cn'
```

两个值都必须是 `http(s)://host[:port]` 形式的 origin，不能带路径、参数或凭据。HTTP 地址写入 IPA 时会自动补 App Transport Security 例外。

APK 若指向明文 HTTP，还需要原始 Manifest 允许 cleartext；生产使用建议优先配置 HTTPS。

## APK 签名

APK 修改后必须重新签名。服务会优先使用：

```bash
export PATCH_APKSIGNER=/path/to/apksigner
export PATCH_ZIPALIGN=/path/to/zipalign
```

未配置时会在 `PATH` 查找 `apksigner` / `zipalign`。若没有 keystore，会用 `keytool` 自动生成一个本地证书并保存在数据目录。也可以显式配置：

```bash
export PATCH_APK_KEYSTORE=/secure/client.keystore
export PATCH_APK_KEYSTORE_PASSWORD='...'
export PATCH_APK_KEY_PASSWORD='...'   # 可选，默认与 keystore 密码相同
export PATCH_APK_KEY_ALIAS='client-patch'
```

找不到 `apksigner` 时仍会输出补丁 APK，但页面会提示需要自行签名；设置 `PATCH_REQUIRE_APK_SIGNATURE=1` 可让这种情况直接失败。

没有现成 keystore 时，服务会调用 JDK 自带的 `keytool` 生成并复用本地证书；因此自动签名需要 JDK。

## IPA 签名

iOS 修改后必须用你的 Apple 证书重签。默认返回未签名 IPA 并在页面提示。若已安装 `zsign`，可配置自动重签：

```bash
export PATCH_ZSIGN=/path/to/zsign
export PATCH_IPA_CERT=/secure/cert.p12
export PATCH_IPA_CERT_PASSWORD='...'
export PATCH_IPA_PROVISION=/secure/profile.mobileprovision
```

## 数据与安全

- 上传文件先落到 `PATCH_DATA_DIR`（默认 `data/client-patch-web/`）下的随机任务目录，处理完成立即删除输入文件。
- 输出只通过随机 token 下载，不会列出磁盘目录；默认 1 小时后清理。
- zip 结构、IL2CPP 版本、metadata 地址布局都会严格校验；不是 5.1.0 v31 客户端会直接拒绝。
- 会拒绝加密、含重复条目、条目数过多或解压后体积过大的压缩包，并按解压上限流式重新打包，避免 zip bomb 和整包读入内存。
- 上传文件名会被清洗为单层路径，不能借助 `../` 写到任务目录之外。
- 该服务会处理用户上传的压缩包并调用本地签名工具，建议放在内网或反向代理认证之后，不要直接暴露公网。
- 如果前面还有 Nginx/网关，需要同步调大请求体上限（例如 `client_max_body_size 2048m;`），否则浏览器会先收到代理返回的 413。

## 可配置项

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PATCH_HOST` / `PATCH_PORT` | `127.0.0.1` / `8090` | 监听地址与端口 |
| `PATCH_DATA_DIR` | `data/client-patch-web` | 任务目录与自动生成的 keystore 所在目录 |
| `PATCH_MAX_UPLOAD_MB` | `2048` | 单次上传大小上限 |
| `PATCH_CONCURRENCY` | `2` | 同时执行的补丁/签名任务数 |
| `PATCH_JOB_TTL_SECONDS` | `3600` | 下载结果保留时间 |
| `PATCH_MAX_METADATA_MB` | `512` | 单个 `global-metadata.dat` 解压后上限 |
| `PATCH_MAX_UNCOMPRESSED_MB` | `8192` | 整个 APK/IPA 解压后总大小上限 |
| `PATCH_MAX_ARCHIVE_ENTRIES` | `200000` | 压缩包条目数上限 |

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/` | 上传页面 |
| `GET` | `/health` | 健康检查与当前地址配置 |
| `POST` | `/api/patch?name=client.apk` | 请求体为文件原始字节，`Content-Type: application/octet-stream` |
| `GET` | `/download/<token>` | 下载补丁产物 |
| `GET` | `/report/<token>` | 下载 JSON 补丁报告 |

命令行上传示例：

```bash
curl --data-binary @game.apk \
  -H 'Content-Type: application/octet-stream' \
  'http://127.0.0.1:8090/api/patch?name=game.apk'
```

响应中的 `download_url` / `report_url` 是相对路径，直接拼上服务地址即可。

## 测试

```bash
python3 -m unittest -v test_assets_patch test_assets_cdn tools.client_patch_web.test_patcher
```
