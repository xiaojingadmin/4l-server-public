# APK / IPA 地址补丁 Web（浏览器本地）

文件选择只是本地入口：选择 APK 或 IPA 后，地址替换和重新打包全部在当前浏览器内完成，
文件字节不会发送到服务器，也不需要服务端安装 `apksigner` / `zsign`。

需要支持 `DecompressionStream` 的现代浏览器：Chrome/Edge 80+、Firefox 113+、Safari 16.4+。

## 启动静态页面

仓库附带一个只提供静态文件的轻量服务器：

```bash
python3 tools/client_patch_web/server.py --host 0.0.0.0 --port 8090
```

浏览器打开 `http://服务器地址:8090/`。也可以使用任意静态文件服务器：

```bash
python3 -m http.server 8090 --directory tools/client_patch_web
```

`server.py` 不提供上传接口，`POST` 会直接返回 405；数据目录、任务 token、服务端签名工具都不再需要。

## 工作方式

1. 浏览器读取本地 APK/IPA 的 ZIP 中央目录，只解压 `global-metadata.dat`（IPA 的 HTTP 目标还会读取 `Info.plist`）。
2. 在浏览器内校验 IL2CPP v31 布局，替换 API 与资源地址，并调整 metadata 段偏移、字面量索引和默认值引用。
3. 重新写出 ZIP：未修改条目直接复制原压缩数据，修改条目写入新内容；APK 会移除旧 `META-INF` v1 签名条目，并按 16 KiB 对齐未压缩的 `.so`。
4. 通过浏览器 `Blob` 下载 `<原名>-patched.apk` 或 `<原名>-patched.ipa`。

页面默认地址：

- API：`https://linklike.lovelive-series.cn`
- 资源：`https://assets-link-like.lovelive-series.cn`

两个输入框都只接受 `http(s)://host[:port]` 形式的 origin，不能带路径、参数或凭据。HTTP 地址写入 IPA 时会自动补 App Transport Security 例外；APK 若指向明文 HTTP，还需要原始 Manifest 允许 cleartext。

## 签名

浏览器不持有任何私钥，也不实现 `apksigner` / Apple 重签。输出的 APK/IPA **未签名**：

- APK：旧签名已移除，必须重新签名后才能安装。
- IPA：修改 bundle 后原签名失效，必须用自己的 Apple 证书重签。

APK 示例：

```bash
apksigner sign --ks /secure/client.keystore --ks-key-alias client-patch \
  --out game-signed.apk game-patched.apk
```

IPA 示例（`zsign`）：

```bash
zsign -k cert.p12 -p 'password' -m profile.mobileprovision \
  -o game-signed.ipa game-patched.ipa
```

## 限制与安全

- 文件只在页面内存中处理，服务端只返回 HTML/JS/CSS；页面 CSP 为 `connect-src 'none'`，不会发起上传请求。
- 默认上限：输入 4 GiB、压缩包 200000 个条目、解压总量 8 GiB、metadata 512 MiB。
- 拒绝加密、重复条目、分卷 ZIP 和超出浏览器安全整数范围的 ZIP64 数据。
- 输出使用 ZIP32；补丁后超过 4 GiB 会直接拒绝。
- 只支持 5.1.0 IL2CPP v31 客户端；其他版本/布局会 fail closed。
- 浏览器端不签名，因此不能替代发布流程中的 `apksigner` / `zsign`。

## 测试

```bash
python3 -m unittest -v test_assets_patch test_assets_cdn test_browser_patch
```

`test_browser_patch.py` 会用 Node.js 运行与浏览器相同的 `il2cpp.js` / `zip.js` 模块，并与 Python
实现逐字节比对 APK/IPA 的 patched metadata。没有 Node.js 时相关用例自动跳过。
