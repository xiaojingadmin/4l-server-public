# Link! Like! Love Live! 5.1.0 本地服务端 — 部署教程

这是《Link! Like! Love Live!》5.1.0 版本的本地私服（Python 版的 Spring Boot 移植），
运行所需的全部数据已随仓库分发，克隆下来就能部署，不需要外部数据目录。

> ⚠️ 本服务面向**隔离的本地测试**，登录校验与会话过期都不满足公开部署要求，**不要暴露到公网**。

---

## 一、前置条件

| 依赖 | 要求 | 说明 |
| --- | --- | --- |
| JDK | 17 或更高 | 仅运行（用别人打好的 jar）也需要 |
| Maven | 3.8+ | 只有自己编译时需要 |
| MariaDB | 10.6+ | 方式 A 需要；没有的话用方式 B（H2），零额外依赖 |
| 客户端 | 5.1.0 版 | 服务端协议只针对 5.1.0，更高版本客户端未验证 |

克隆仓库：

```powershell
git clone <本仓库地址> 4l-server
cd 4l-server
```

（可选）校验随仓库分发的数据文件完整性：

```powershell
# 在仓库根目录执行，应输出 85 行 OK
sha256sum -c data/MANIFEST.sha256
```

---

## 二、选择数据库方式

服务端支持两种数据库，二选一：

| | 方式 A：MariaDB（默认） | 方式 B：H2 文件库 |
| --- | --- | --- |
| 适用 | 想要完整功能、长期使用 | 快速体验、没有 MariaDB 的机器 |
| 额外依赖 | 需安装并运行 MariaDB | 无（数据库文件在 `data/` 下自动生成） |
| 启动命令 | `mvn spring-boot:run` | `mvn spring-boot:run -Dspring-boot.run.profiles=h2` |

两种方式启动时都会自动初始化表结构（Flyway），并在 `master_items` 表为空时从随仓库的
`master_items_5.1.0.json`（939 行物品主表）写入种子数据。

> H2 模式的限制：只自动导入 `master_items`；卡片成长、抽卡、商店结算等领域还依赖运行时
> 导入的 catalog（`data/catalog/`），缺这些数据时接口按 501 拒绝结算，与 Python 版
> 「缺 master 表」的行为一致。
>
> 迁移进度与仍是显式 501 的写接口清单见 [`docs/migration-gap.md`](docs/migration-gap.md)。

---

## 三、启动服务

### 方式 A：MariaDB（默认）

1. 安装并启动 MariaDB（默认连接 `127.0.0.1:3306`，账号 `root`，无密码）。
2. 直接启动，库不存在且有 CREATE 权限时会自动建库：

   ```powershell
   mvn spring-boot:run
   ```

3. 看到启动日志后做健康检查：

   ```powershell
   curl http://127.0.0.1:8081/health
   ```

### 方式 B：H2 文件库

```powershell
mvn spring-boot:run -Dspring-boot.run.profiles=h2
curl http://127.0.0.1:8081/health
```

数据库文件会生成在 `data/linklike.mv.db`，删除它即可重置存档。

### 打包成 jar 运行（生产推荐）

```powershell
mvn -DskipTests package
java -jar target/linklike-server-5.1.0.jar
# H2 方式：
java -jar target/linklike-server-5.1.0.jar --spring.profiles.active=h2
```

首次启动会注册 309 条路由（309 条都有 handler，其中 32 条按设计返回 501），日志里可据此确认数据加载正常。

---

## 四、客户端接入

1. 服务端监听 `0.0.0.0:8081`（可用 `LINKLIKE_PORT` 改），客户端需要指向这台机器的 8081 端口。
2. 按你使用的客户端改法，把 API 地址指向 `http://<服务器IP>:8081`。
3. 客户端首次登录会自动创建新账号（本仓库不含任何真实账号数据）。

若启用了 API Key（见下节），客户端还需要带上匹配的 `X-Api-Key` 请求头。

### APK / IPA 地址补丁 Web

仓库附带独立的上传式补丁页面，可让用户直接提交原始 5.1.0 APK 或 IPA，自动修改 API 与资源地址后下载：

```bash
python3 tools/client_patch_web/server.py --host 0.0.0.0 --port 8090
```

APK 需要 `apksigner`（可选 `zipalign`）才能自动生成可安装包；IPA 默认输出未签名包，需使用自己的 Apple 证书重签，也可配置 `zsign` 自动重签。地址、证书和大小限制等配置见
[`tools/client_patch_web/README.md`](tools/client_patch_web/README.md)。

---

## 五、配置

全部配置都在 `src/main/resources/application.yml`，所有键都可用环境变量覆盖：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `LINKLIKE_PORT` | `8081` | 监听端口 |
| `LINKLIKE_DATABASE_URL` | `jdbc:mariadb://127.0.0.1:3306/linkura_5_1_0` | 数据库连接串；也接受 Python 版格式 `mysql://user:pass@host:port/db`，会自动转换 |
| `LINKLIKE_DATABASE_USER` / `LINKLIKE_DATABASE_PASSWORD` | `root` / 空 | 数据库账号 |
| `LINKLIKE_API_KEY` | `off` | 设为非 `off` 后请求必须匹配 `X-Api-Key`，否则 401 |
| `LINKLIKE_REQUIRE_BEARER` | `false` | `true` 时强制所有 `/v1` 接口带有效 Bearer |
| `LINKLIKE_LOG_REQUESTS` | `true` | 每个请求写一份脱敏 JSON 日志 |
| `LINKLIKE_LOG_DIR` | `build/server-profiles/5.1.0/logs` | 请求日志目录 |
| `LINKLIKE_RESOURCE_CATALOG_DIR` | `build/catalog` | 已解码资源目录（仓库已带 5.1.0/6.0.0），目录型接口据此过滤，保持默认即可 |
| `LINKLIKE_CLIENT_DEFAULTS_DIR` | 空 | 可选外部抽取目录；为空时读仓库内置的 `client_defaults/`，**部署时不需要设置** |
| `LINKLIKE_OFFICIAL_API` | `false` | 保留键；当前 Java 版尚未实现官方 API 回源 |

示例：改端口 + 指定远程 MariaDB：

```powershell
$env:LINKLIKE_PORT = "9090"
$env:LINKLIKE_DATABASE_URL = "mysql://game:secret@192.168.1.10:3306/linkura_5_1_0"
mvn spring-boot:run
```

---

## 六、常见问题（FAQ）

**Q：启动时报数据库连接失败？**
方式 A 先确认 MariaDB 已启动、账号密码与 `LINKLIKE_DATABASE_*` 一致；本机没有 MariaDB
就直接用方式 B（`--spring.profiles.active=h2`），不需要任何数据库安装。

**Q：`/health` 正常但客户端一直转圈？**
确认客户端指向的 IP:端口正确；再查启动日志里「资源目录过滤」是否生效——目录型接口会按
`build/catalog` 里解码好的资源 label 过滤，客户端加载界面遇到不存在的资源会一直等待。
仓库已自带 5.1.0/6.0.0 两套目录，保持 `LINKLIKE_RESOURCE_CATALOG_DIR` 默认值即可。

**Q：某个接口返回 501？**
这是有意的：已注册的 309 条路径中，32 条因本地无法核实定价、归属或结算规则而显式返回
501（完整清单见 `docs/migration-gap.md`），不会用空对象伪装成功。登录/首页/卡组/商店目录
等核心链路已完整实现。

**Q：想重置存档？**
H2 方式：停服后删除 `data/linklike.mv.db`。MariaDB 方式：删库或清空 `players` 表后重启。

**Q：数据文件校验失败？**
在仓库根目录重新执行 `sha256sum -c data/MANIFEST.sha256`，失败的文件用 `git checkout -- <文件>`
恢复；来源与版本说明见 `data/PROVENANCE.md`。

---

## 附：仓库结构速查

```
src/main/resources/client_defaults/   # 68 个客户端抽取/静态主表文件（含 5.1.0/6.0.0 物品表）
build/catalog/                        # 已解码资源目录（5.1.0-R2604100 与 6.0.0-R2606100）
data/                                 # master_5.1.0.db、cards.db、物品 JSON、校验清单与来源说明
src/main/resources/application.yml    # 全部配置（环境变量可覆盖）
src/main/java/com/linklike/server/    # 服务端源码（config/protocol/resource/service/web/wire）
```

完整的设计说明（协议移植对照、玩家状态存储设计、路由实现范围）见原版 README 历史，
或直接阅读源码注释。
