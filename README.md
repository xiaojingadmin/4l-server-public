# Link! Like! Love Live! 5.1.0 本地服务端（Spring Boot 版）

这是 Python 版本地私服的 Spring Boot 移植。协议行为、错误码形状、响应头与字段
编码规则都以 Python 版为唯一依据；移植过程中不做「顺手改进」，以免客户端行为偏移。

- 原项目：`xiaojingadmin/4l-server`（Python 标准库实现）
- 本仓库：`xiaojingadmin/4l-server-public`
- 目标版本：**只支持 5.1.0 协议**

技术栈：Java 17 / Spring Boot 3.2.5 / Spring Data JPA / Flyway / MariaDB。

---

## 快速开始

运行所需数据已随仓库分发，不需要外部数据目录。

### 方式 A：MariaDB（默认）

```powershell
# 1. 准备 MariaDB（默认连接串 mysql://root@127.0.0.1:3306/linkura_5_1_0）
#    库不存在且有 CREATE 权限时会自动建库，表结构由 Flyway 管理。
#    启动时会把 client_defaults/master_items_<version>.json 写入空的 master_items。

# 2. 编译并启动
mvn spring-boot:run

# 3. 健康检查
curl http://127.0.0.1:8081/health
```

### 方式 B：H2 文件库（不需要 MariaDB）

```powershell
mvn spring-boot:run -Dspring-boot.run.profiles=h2
# 数据库文件：data/linklike.mv.db
curl http://127.0.0.1:8081/health
```

H2 模式只自动导入 `master_items`；其它 master 表尚未迁移的领域接口仍按 501/空目录处理。

打包与运行：

```powershell
mvn -DskipTests package
java -jar target/linklike-server-5.1.0.jar
java -jar target/linklike-server-5.1.0.jar --spring.profiles.active=h2
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
| `LINKLIKE_RESOURCE_CATALOG_DIR` | `build/catalog` | 已解码资源目录（仓库已带 5.1.0/6.0.0），目录型接口据此过滤 |
| `LINKLIKE_CLIENT_DEFAULTS_DIR` | 空 | 可选外部抽取目录；为空时读 classpath 的 `client_defaults/`。运行不需要设置 |
| `LINKLIKE_OFFICIAL_API` | `false` | 保留键；当前 Java 版尚未实现官方 API 回源，设为 `true` 也不会改变空列表行为 |

`LINKLIKE_RESOURCE_CATALOG_DIR` 指向 `verify_resource_catalog.py --output-dir` 的产物，
里面按 `<client_version>-<Rversion>/catalog_entries.json` 分版本存放。目录型接口（表情、
歌曲……）会据此丢掉资源包里没有对应 label 的条目 —— 客户端加载界面遇到不存在的资源会一直
等下去，所以这一步不能省。**目录不存在时不过滤**，与 Python 版「本机没有解码目录」的行为
一致；启动日志会明确写明过滤是否生效。

`LINKLIKE_DATABASE_URL` 写成 Python 版格式时，`mysql://user:pass@host:3306/db` 会被
转换成对应的 JDBC 连接串，方便直接复用原项目的配置。

---

## 目录结构

```
src/main/java/com/linklike/server/
├─ LinkLikeServerApplication.java   # 启动类
├─ config/        ServerProperties, MasterItemsSeeder # 配置与物品主表初始化
├─ protocol/      J, WireCodec, WireModels, WireError, ErrorCodes
│                 # 内部 PascalCase 数据 <-> 线上 snake_case 字段的编解码
├─ resource/      ResourceManifest, ResourceChecksum, ResourceCatalogLabels
│                 # 资源版本解析（CRC-64 + VLQ + Base32）、XXH64 下载校验和、
│                 # 已解码资源目录的 label 集合
├─ domain/        Player, PlayerState, RefCatalogEntry, SessionRecord, IdempotencyKey
├─ repository/    对应的 Spring Data JPA 仓库
├─ service/       PlayerStore, PlayerContext     # 玩家/会话/状态的持久化门面
├─ web/           GameHeaders, ...               # /v1 响应头与 HTTP 层
└─ wire/          WireRouter, WireModule, WireHandler, WireRequest, WireServices,
│                 WireShop, WireDeckModule, WireAccountModule, 各 wire_* 模块
src/main/resources/
├─ application.yml / application-h2.yml
├─ wire_models.json            # 949 个协议模型定义（运行时读取）
├─ endpoints.json              # 353 个 5.1.0 API 方法清单
├─ login_bonus_periods.json    # 登录奖励周期种子数据
├─ client_defaults/            # 68 个客户端抽取/静态主表文件（含 5.1.0/6.0.0 物品表）
└─ db/migration/V1__init.sql   # Flyway 初始 schema
build/catalog/                  # 已解码资源目录（5.1.0 与 6.0.0）
data/                           # master_5.1.0.db、cards.db、物品 JSON、校验清单与来源说明
```

---

## 迁移进度

`wire_routes_5_1_0.json` 记录 Python 版支持的 315 条路径。默认 MariaDB 配置下实际实现 95 条，
其余 214 条由 `RouteSnapshot` 注册为显式 501（连同别名共注册 309 条路由）—— 客户端看到的
都是「未实现」，排查日志里能区分「还没移植」和「路径不存在」。

已完整移植的模块：

| Python | Java | 范围 |
| --- | --- | --- |
| `wire_api.py` 内联分支 | `WireApiModule` | 登录 / 注册 / 连携 / 教程 / 资料 / 首页 |
| `wire_catalog.py` | `WireCatalog` | 客户端静态实体与参考目录 |
| `wire_state.py` | `WireState` | 资料与首页读取模型 |
| `wire_login_bonus.py` | `WireLoginBonus` | 登录奖励板与盖章 |
| `wire_services.py`（部分） | `WireServices` | 资源过滤、目录叠加、日界、礼物盒行 |
| `wire_shop.py`（部分） | `WireShop` | 道具持有数与新手任务进度 |
| `wire_decks.py` | `WireDeckModule` | `/v1/user/deck/*` 全部 10 条 |
| `wire_account.py` | `WireAccountModule` | 首页设置、公告、礼物盒、资料、收藏浏览、商店目录读取 |
| `wire_rhythm.py` | `WireRhythmModule` | 节奏游戏与 SHOW live |

两个接口暂时保留 501，原因写在这里避免被当成遗漏：

- `/v1/common/get_header_announs`：Python 版返回 JSON 数组，而当前 `WireHandler` 契约只承载
  对象；用 `{}` 冒充会让客户端反序列化失败。
- `/v1/item_exchange/get_list`：依赖 `wire_shop` 的限购/解锁结算层（重置周期、Adv/Music
  解锁、兑换状态），尚未移植，不能用未叠加的目录行冒充已结算结果。

### 数据已随仓库分发

运行数据已经复制进本目录，运行时不再访问外部参考服务端。

| 路径 | 内容 |
| --- | --- |
| `src/main/resources/client_defaults/` | 68 个文件：20 个抽取结果 + 20 个 `.source.json`（合计 40 个 `base_res_content_*.json`）、静态 master JSON、`master_tsv/standardqueststages.tsv`、5.1.0/6.0.0 `master_items_*.json` |
| `build/catalog/5.1.0-R2604100/` | 5.1.0 资源目录（`catalog_entries.json`、`verification.json`、samples、编码 blob） |
| `build/catalog/6.0.0-R2606100/` | 6.0.0 资源目录，结构相同 |
| `data/master_5.1.0.db` | 5.1.0 主表 SQLite（items 939 行） |
| `data/cards.db` | 卡片主表 SQLite |
| `data/master_items_5.1.0.json` | 939 行物品主表（12 列） |
| `data/master_items_6.0.0.json` | 958 行物品主表（12 列） |
| `data/MANIFEST.sha256` | 85 个随仓库数据文件的 SHA-256（client_defaults 68、catalog 12、data 5） |
| `data/PROVENANCE.md` | 来源路径、导出命令与版本说明（来源信息集中在这里） |

`MasterItemsSeeder` 在 MariaDB 或 H2 启动时检查 `master_items`：表为空时从
`master_items_<clientVersion>.json` 建表并写入；已有数据则跳过。物品详情优先读数据库，
数据库没有该行时才用静态 JSON 回退。没有 MariaDB 时使用 `h2` profile，不要依赖静态
回退去启动默认 MariaDB 配置。

外部目录的取用规则：外部文件存在时优先；外部文件不存在时回退 classpath；外部文件存在但
解析失败时返回空并告警，**不会**静默改用 classpath；目录不存在时告警并回退 classpath。
5.1.0/6.0.0 物品表都随仓库提供；服务端协议目标仍是 5.1.0，切换到 6.0.0 需要另行验证。

---

## 与 Python 版的结构对应

| Python | Java | 说明 |
| --- | --- | --- |
| `server.py` 的 `_users` / `_sessions` 字典 | `PlayerStore` + `PlayerContext` | 不再维护内存缓存，每次请求按需装载、显式保存 |
| `server.py` 的 `build_common_game_headers` | `web/GameHeaders` | `/v1` 成功响应的游戏响应头 |
| `wire_api.encode` | `protocol/WireCodec` | 只输出模型声明过的属性，嵌套模型递归编码 |
| 各 `wire_*.py` 的 `ROUTES` + `dispatch` | `wire/*Module` 实现 `WireModule` | 一个模块注册自己的全部路由 |
| `wire_services.py` 的目录/资源标签辅助函数 | `wire/WireServices` | 目前只有 `catalog_labels`/`with_resources`/`served`/`overlay` 这一层；该文件其余部分移植时继续往这里加 |
| `build/catalog/<client>-<Rversion>/catalog_entries.json` | `resource/ResourceCatalogLabels` | 已解码资源目录的 label 集合，目录类接口据此过滤 |
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

### 变更行覆盖率

`tools/changed_line_coverage.py` 只统计本 lane 改动过的 Java 文件里、JaCoCo 认定为可执行的行。
`pom.xml` 不绑定 JaCoCo 插件；按下面三条命令手动生成报告（JaCoCo 0.8.12 的 agent 与 CLI
jar 已在本机 Maven 仓库；缺失时可用 `mvn dependency:get` 拉取）：

```powershell
mvn -B clean test "-DargLine=-javaagent:$env:USERPROFILE\.m2\repository\org\jacoco\org.jacoco.agent\0.8.12\org.jacoco.agent-0.8.12-runtime.jar=destfile=target/jacoco.exec"

java -jar "$env:USERPROFILE\.m2\repository\org\jacoco\org.jacoco.cli\0.8.12\org.jacoco.cli-0.8.12-nodeps.jar" report target/jacoco.exec --classfiles target/classes --sourcefiles src/main/java --xml target/jacoco.xml

python tools/changed_line_coverage.py
```

当前 lane（`MasterItemsSeeder.java` 42 行、`WireGrants.java` 23 行）的预期结果是：
`"covered": 65, "executable": 65, "percent": 100.0`，`uncoveredLines` 为空。

---

## 已知限制

与 Python 版一致：本服务面向隔离的本地测试，**不要暴露到公网**。登录校验、第三方身份
验证与会话过期都不满足公开部署要求。约 40 个写接口（付费、领奖、体力恢复、直播礼物等）
在认证后显式返回 501，因为本地没有可核实的定价或结算规则；未实现的路径同样返回 501，
不会用空对象伪装成功。

数据与运行限制：

- `users.db`/`users.json` 未迁入；public 不含真实账号、会话或进度，首次运行创建新账号。
- 默认 MariaDB 路径仍需要本机 MariaDB；`h2` profile 用目录内文件库，适合没有 MariaDB 的机器。
- H2 只自动初始化 `master_items`；卡片成长、抽卡、商店结算等尚未迁移领域仍需要完整 master_* 数据。
- 5.1.0 与 6.0.0 数据都已随仓库提供，但协议模型与业务代码仍以 5.1.0 为目标。
- `data/` 约 3.9 MB、`build/catalog/` 约 60 MB；若托管平台有限制，使用 Git LFS 或 release asset，`data/MANIFEST.sha256` 用于校验。
