# Link! Like! Love Live! 5.1.0 本地服务端

这是一个面向本地测试的 5.1.0 版服务端实现。仓库已经包含运行所需的数据文件，克隆后即可部署，不需要另外准备数据目录。

> ⚠️ 本项目只适合隔离的本地测试环境。登录校验和会话管理尚未达到公开部署的安全要求，请不要把服务直接暴露到公网。

## 快速开始

### 1. 准备环境

| 依赖 | 要求 | 何时需要 |
| --- | --- | --- |
| JDK | 17 或更高 | 运行服务端或编译项目 |
| Maven | 3.8 或更高 | 从源码编译或启动项目 |
| MariaDB | 10.6 或更高 | 使用 MariaDB 模式时需要 |
| 客户端 | 5.1.0 | 当前只针对 5.1.0 协议进行适配 |

```powershell
git clone <本仓库地址> 4l-server
cd 4l-server
```

可选：校验仓库内数据文件是否完整。

```powershell
sha256sum -c data/MANIFEST.sha256
```

正常情况下应输出 85 行 `OK`。

### 2. 选择数据库

服务端支持两种数据库模式，按需要选择一种即可。

| 模式 | 适合场景 | 额外依赖 | 启动命令 |
| --- | --- | --- | --- |
| MariaDB（默认） | 希望功能更完整，或需要长期使用 | 需要安装并运行 MariaDB | `mvn spring-boot:run` |
| H2 文件库 | 快速体验，不想安装数据库 | 无 | `mvn spring-boot:run -Dspring-boot.run.profiles=h2` |

两种模式都会自动创建表结构。首次启动时，如果 `master_items` 为空，程序会从仓库里的 `master_items_5.1.0.json` 导入基础物品数据。

> H2 模式目前只会自动导入 `master_items`。卡片成长、抽卡和商店结算等功能还依赖 `data/catalog/` 中的资源目录；缺少这些数据时，相关接口会明确返回 501。迁移进度和仍未实现的接口见 [`docs/migration-gap.md`](docs/migration-gap.md)。

## 启动服务

### MariaDB 模式

1. 启动 MariaDB。默认连接地址是 `127.0.0.1:3306`，用户名为 `root`，密码为空。
2. 启动项目。如果数据库不存在且当前账号有创建权限，程序会自动建库。

```powershell
mvn spring-boot:run
```

3. 检查服务是否正常运行。

```powershell
curl http://127.0.0.1:8081/health
```

### H2 模式

```powershell
mvn spring-boot:run -Dspring-boot.run.profiles=h2
curl http://127.0.0.1:8081/health
```

H2 数据库文件会生成在 `data/linklike.mv.db`。停止服务后删除这个文件，即可重置存档。

### 编译后运行

```powershell
mvn -DskipTests package
java -jar target/linklike-server-5.1.0.jar
```

使用 H2 模式时：

```powershell
java -jar target/linklike-server-5.1.0.jar --spring.profiles.active=h2
```

首次启动时会注册 309 条路由，其中 32 条会按设计返回 501。可以根据启动日志确认数据是否加载完成。

## 客户端接入

1. 服务端默认监听 `0.0.0.0:8081`，也可以通过 `LINKLIKE_PORT` 修改端口。
2. 在客户端中填写服务端地址和端口。使用本地测试时，地址通常是 `http://<服务器IP>:8081`。
3. 客户端首次登录会自动创建新账号。本仓库不包含任何真实账号数据。

如果启用了 API Key，客户端还需要在请求中携带匹配的 `X-Api-Key` 请求头。

### APK / IPA 地址修改工具

仓库提供了一个浏览器端工具，用来修改 5.1.0 APK 或 IPA 中的服务端地址。文件只在浏览器本地处理，不会上传到服务器。

在线使用：

- GitHub Pages：<https://xiaojingadmin.github.io/4l-server-public/>

本地启动：

```bash
python3 tools/client_patch_web/server.py --host 0.0.0.0 --port 8090
```

这个工具只提供静态页面，不接收上传，也不需要在服务器上安装 Android 或 iOS 签名工具。生成的文件未签名：APK 需要使用 `apksigner` 重签，IPA 需要使用自己的 Apple 证书重签。完整说明见 [`tools/client_patch_web/README.md`](tools/client_patch_web/README.md)。

## 配置

配置文件是 `src/main/resources/application.yml`，所有配置都可以通过环境变量覆盖。

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `LINKLIKE_PORT` | `8081` | 服务端监听端口 |
| `LINKLIKE_DATABASE_URL` | `jdbc:mariadb://127.0.0.1:3306/linkura_5_1_0` | 数据库连接串，也支持 `mysql://user:pass@host:port/db` 格式 |
| `LINKLIKE_DATABASE_USER` / `LINKLIKE_DATABASE_PASSWORD` | `root` / 空 | 数据库账号和密码 |
| `LINKLIKE_API_KEY` | `off` | 设置为其他值后，请求必须携带匹配的 `X-Api-Key`，否则返回 401 |
| `LINKLIKE_REQUIRE_BEARER` | `false` | 设置为 `true` 后，所有 `/v1` 接口都必须携带有效 Bearer |
| `LINKLIKE_LOG_REQUESTS` | `true` | 是否记录脱敏后的请求日志 |
| `LINKLIKE_LOG_DIR` | `build/server-profiles/5.1.0/logs` | 请求日志目录 |
| `LINKLIKE_RESOURCE_CATALOG_DIR` | `build/catalog` | 已解码的资源目录，保持默认值即可 |
| `LINKLIKE_CLIENT_DEFAULTS_DIR` | 空 | 可选的外部客户端数据目录；为空时使用仓库内置数据 |
| `LINKLIKE_OFFICIAL_API` | `false` | 预留配置，当前 Java 版尚未实现官方 API 回源 |

示例：修改端口并连接远程 MariaDB。

```powershell
$env:LINKLIKE_PORT = "9090"
$env:LINKLIKE_DATABASE_URL = "mysql://game:secret@192.168.1.10:3306/linkura_5_1_0"
mvn spring-boot:run
```

## 常见问题

**启动时报数据库连接失败怎么办？**

先确认 MariaDB 已启动，并检查 `LINKLIKE_DATABASE_*` 配置。没有 MariaDB 时，可以改用 H2 模式，不需要额外安装数据库。

**`/health` 正常，但客户端一直转圈怎么办？**

先确认客户端填写的 IP 和端口正确，再检查启动日志中的资源目录过滤信息。目录型接口会根据 `build/catalog` 中的资源进行过滤；仓库已经附带 5.1.0 和 6.0.0 两套目录，通常不需要修改 `LINKLIKE_RESOURCE_CATALOG_DIR`。

**为什么有些接口返回 501？**

这是有意保留的行为。309 条已注册路径中，有 32 条因为本地无法确认定价、归属或结算规则，会明确返回 501，而不是返回一个看似成功的空对象。登录、首页、卡组和商店目录等核心链路已经实现。

**如何重置存档？**

H2 模式：停止服务后删除 `data/linklike.mv.db`。MariaDB 模式：删除数据库，或清空 `players` 表后重新启动。

**数据文件校验失败怎么办？**

在仓库根目录重新运行 `sha256sum -c data/MANIFEST.sha256`。如果某个文件失败，可以用 `git checkout -- <文件>` 恢复。来源和版本说明见 `data/PROVENANCE.md`。

## 仓库结构

```text
src/main/resources/client_defaults/   # 客户端抽取数据和静态主表
build/catalog/                        # 已解码资源目录
data/                                 # 数据库、物品数据、校验清单和来源说明
src/main/resources/application.yml    # 配置文件
src/main/java/com/linklike/server/    # 服务端源码
```

如果需要了解协议移植、玩家状态存储和路由实现范围，请阅读源码注释和 `docs/` 目录中的说明。