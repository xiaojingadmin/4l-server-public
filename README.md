# Link! Like! Love Live! 5.1.0 本地服务端（Spring Boot 版）

这是 Python 版本地私服的 Spring Boot 移植。协议行为、错误码形状、响应头与字段
编码规则都以 Python 版为唯一依据；移植过程中不做「顺手改进」，以免客户端行为偏移。

- 原项目：`xiaojingadmin/4l-server`（Python 标准库实现）
- 本仓库：`xiaojingadmin/4l-server-public`
- 目标版本：**只支持 5.1.0 协议**

技术栈：Java 17 / Spring Boot 3.2.5 / Spring Data JPA / Flyway / MariaDB。

---

## 快速开始

```powershell
# 1. 准备 MariaDB（默认连接串 mysql://root@127.0.0.1:3306/linkura_5_1_0）
#    库不存在且有 CREATE 权限时会自动建库，表结构由 Flyway 管理。

# 2. 编译并启动
mvn spring-boot:run

# 3. 健康检查
curl http://127.0.0.1:8081/health
```

打包与运行：

```powershell
mvn -DskipTests package
java -jar target/linklike-server-5.1.0.jar
```

---

## 配置

配置在 `src/main/resources/application.yml`，全部键都可用环境变量覆盖。

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `LINKLIKE_PORT` | `8081` | 监听端口 |
| `LINKLIKE_DATABASE_URL` | `jdbc:mariadb://127.0.0.1:3306/linkura_5_1_0` | 数据库连接串，也接受 Python 版格式 `mysql://user:pass@host:port/db` |
| `LINKLIKE_DATABASE_USER` / `LINKLIKE_DATABASE_PASSWORD` | `root` / 空 | 数据库账号 |
| `LINKLIKE_API_KEY` | `off` | 设为非 `off` 后请求必须匹配 `X-Api-Key`，否则 401 |
| `LINKLIKE_REQUIRE_BEARER` | `false` | `true` 时强制所有 `/v1` 接口带有效 Bearer |
| `LINKLIKE_LOG_REQUESTS` | `true` | 每个请求写一份脱敏 JSON 日志 |
| `LINKLIKE_LOG_DIR` | `build/server-profiles/5.1.0/logs` | 请求日志目录 |
| `LINKLIKE_OFFICIAL_API` | `false` | 是否允许向官方 API 回源补齐本地缺失数据 |

`LINKLIKE_DATABASE_URL` 写成 Python 版格式时，`mysql://user:pass@host:3306/db` 会被
转换成对应的 JDBC 连接串，方便直接复用原项目的配置。

---

## 目录结构

```
src/main/java/com/linklike/server/
├─ LinkLikeServerApplication.java   # 启动类
├─ config/        ServerProperties          # 对应 Python 版 config.json
├─ protocol/      J, WireCodec, WireModels, WireError, ErrorCodes
│                 # 内部 PascalCase 数据 <-> 线上 snake_case 字段的编解码
├─ resource/      ResourceManifest, ResourceChecksum
│                 # 资源版本解析（CRC-64 + VLQ + Base32）与 XXH64 下载校验和
├─ domain/        Player, PlayerState, RefCatalogEntry, SessionRecord, IdempotencyKey
├─ repository/    对应的 Spring Data JPA 仓库
├─ service/       PlayerStore, PlayerContext     # 玩家/会话/状态的持久化门面
├─ web/           GameHeaders, ...               # /v1 响应头与 HTTP 层
└─ wire/          WireRouter, WireModule, WireHandler, WireRequest, 各 wire_* 模块
src/main/resources/
├─ application.yml
├─ wire_models.json            # 949 个协议模型定义（运行时读取）
├─ endpoints.json              # 353 个 5.1.0 API 方法清单
├─ login_bonus_periods.json    # 登录奖励周期种子数据
├─ client_defaults/*.json      # 客户端提取的 master 静态主表
└─ db/migration/V1__init.sql   # Flyway 初始 schema
```

---

## 与 Python 版的结构对应

| Python | Java | 说明 |
| --- | --- | --- |
| `server.py` 的 `_users` / `_sessions` 字典 | `PlayerStore` + `PlayerContext` | 不再维护内存缓存，每次请求按需装载、显式保存 |
| `server.py` 的 `build_common_game_headers` | `web/GameHeaders` | `/v1` 成功响应的游戏响应头 |
| `wire_api.encode` | `protocol/WireCodec` | 只输出模型声明过的属性，嵌套模型递归编码 |
| 各 `wire_*.py` 的 `ROUTES` + `dispatch` | `wire/*Module` 实现 `WireModule` | 一个模块注册自己的全部路由 |
| `entity_schema.py` 动态建表 | `db/migration/V1__init.sql` | 改为显式 schema：核心标量独立成列，其余状态按 JSON 行存储 |
| `resource_manifest.py` | `resource/ResourceManifest` | 已与 Python 版逐值比对通过 |
| `resource_checksum.py` | `resource/ResourceChecksum` | 已与 Python 版逐值比对通过 |

### 玩家状态为什么改成 JSON 行

Python 版按 949 个协议模型动态生成「每个标量一列」的表，嵌套列表再拆子表。这在 JPA
里无法映射，所以改成：

1. `players` —— 需要被查询、索引的核心标量字段，一列一个；
2. `player_state` —— 其余标量与全部集合型状态，按 `state_key` 存 JSON 行；
3. `ref_catalogs` —— 官方 master / 参考目录，按 `catalog_key + entity_id` 存 JSON。

键名沿用 Python 版的 PascalCase 拼写（`Cards`、`Decks`、`RhythmGameDecks`……），
这样移植过来的业务代码能保持一对一的可读性，也不必随协议版本改表结构。

---

## 开发

编译校验（不写 `target/`，适合并行开发）：

```powershell
mvn -B -q dependency:build-classpath -Dmdep.outputFile=cp.txt   # 首次
powershell -NoProfile -File tools\verify-compile.ps1
```

---

## 已知限制

与 Python 版一致：本服务面向隔离的本地测试，**不要暴露到公网**。登录校验、第三方身份
验证与会话过期都不满足公开部署要求。约 40 个写接口（付费、领奖、体力恢复、直播礼物等）
在认证后显式返回 501，因为本地没有可核实的定价或结算规则；未实现的路径同样返回 501，
不会用空对象伪装成功。
