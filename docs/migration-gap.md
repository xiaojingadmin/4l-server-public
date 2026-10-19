# 迁移对齐状态（5.1.0）

Python 版快照（`wire_api.SUPPORTED`）共 **315** 条路径，去掉 6 条大小写别名后 Java 版注册
**309** 条，**309 条全部有真实 handler**，没有一条还靠 `RouteSnapshot` 兜底成「未实现」
（`WireRouter.pending()` 为 0，日志为 `已注册 309 条（已实现 309，快照兜底 501 0）`）。

- **308 条**与 Python 版语义一致地实现（读接口照原样返回，写接口照原样结算）。
  其中 `mission/get_list` 有一处刻意增强：Python 只回目录原值（`achieved_num`
  恒为 0），Java 版按账号真实状态派生进度（见下「关卡任务进度」）。
- **1 条**原本在 Python 版 `UNIMPLEMENTED_WRITES` 里、现在已有真实结算：
  `step_up_beginner_mission/set_reward`（见下「任务领奖」）。
- **33 条**仍是「有 handler 的 501」：要真实的付费、定价或归属规则，本地没有可核实的
  依据，宁可不做，也不发明数值。清单见下。

## 任务领奖（已实现）

`client_defaults/mission_groups.json` 取自官方 `/v1/mission/get_list`，
`achieved_num` / `is_achieved` 已按 `capture_import.SPECS` 的 reset 规则归零，
所以它是**任务定义**而不是某个账号的进度快照。`WireCatalog.seedMissionGroups()` 在启动时
把它灌进 `ref_catalogs`（`catalog_key=mission_groups`，键为 `mission_type`），
`/v1/mission/get_list` 再按账号自己的 `service_missions` 覆盖进度。

`/v1/step_up_beginner_mission/set_reward` 据此按核实过的定义结算：只接受「已达成且未领取」
的任务，奖励逐条走 `grantReward`，任一奖励不可核实则整体失败、状态不变，返回
`ReceivedRewardList`。

任务奖励引用了一批不在 `shop_catalog.items`（653 条）里的道具（如 `3002210` 十连券、
`3001002` 体力券）。`WireShop.catalogItem()` 因此在商店目录查不到时回退到完整的
5.1.0 主数据 `master_items_5.1.0.json`（939 条），否则这些奖励会被误判成「定义缺失」。

### 关卡任务进度（`mission/get_list`）

`mission/get_list` 不再原样回目录里的 `achieved_num=0`，而是按账号真实状态派生：

- **计数器按 `(transition_contents_id, mission_id 前 6 位)` 分组**，每组只有一个计数器。
  这条规则用官方账号导出的 90 条任务逐条验证过（50 条可派生，全部与官方
  `achieved_num` 相等）：同组内要么全组共享同一个未达成值，要么已达成行等于自身
  `condition_achieve_num`。
- **进度 = `min(计数器, condition_achieve_num)`**。
- **同一个 `transition` 在不同前缀下语义不同**，由 `mission_id` 决定：

| transition | 前缀 | 语义 | 来源 |
|---|---|---|---|
| 101 | `301000` | クエストライブ的☆合计 | `SchoolStageStar` |
| 203 | `3060xx` | 该角色 Member Fan Lv | `MemberFanLevels[CharactersId].MemberFanlevel` |
| 304 | `3201xx` / `3202xx` | 关注数 / 被关注数 | `FollowCount` / `FollowerCount` |
| 309 | `3030xx` | 该角色所有卡 StyleLevel 合计 | `Cards[].StyleLevel` |
| 501 | `3020xx` | 该角色持有卡的去重 `CardSeriesId` 数 | `Cards` + `card_master` |
| 501 | `302000` | 全账号去重 `CardSeriesId` 数 | `Cards` + `card_master` |

- **角色优先取卡行自带的 `CharacterId`**，查不到才回退 `card_master`。实测
  `card_master_5.1.0` 缺 `19515010` 等卡，绕经 master 会把整张卡的 `StyleLevel`
  漏掉（官方 107，旧实现算 106）。
- **算不出来的不写**：100/103/106/800（通关次数）、`202000`（入手回数）、
  `203000`/`204000`/`204100`（特训、技能 Lv 次数）、`205000`（With×MEETS 视听数）
  在账号 schema 里没有对应计数器，这些行保持目录原值，不猜、不用别的字段顶替。
  官方导出里 `202000` 是 3/10/30 但 `gacha_get_history` 为空、卡行没有获得时间，
  确认本地无法派生。
- **列表长度不等于计数器**：`follow_get_list` 回 2 条与 `32010002` 的 2 一致，但
  `follow_get_follower_list` 只回 12 条而 `32020001` 官方是 13（疑分页），所以被关注
  数一律读 `FollowerCount`，不数列表。
- 角色编码取 `mission_id` 的 `[3:6]`（如 `30202101` → `021` → `1021`），
  且必须存在于 `card_master.characters`。
- 回归测试 `WireMissionRewardTest.officialAccountExportIsReproducedExactly` 以官方
  账号导出（`src/test/resources/mission_progress_official.json`）为基准，逐条比对
  50 条派生值，并断言其余 40 条不得被写入。

### 为什么 `mission/receive_common_mission_reward` 仍是 501

官方目录拿得到，但两条都不满足「可核实结算」：

- **阶段奖励（`achieve_reward_list`）的 `achieved_num` 无法派生**。用官方导出核对：
  它是独立计数器，和任务进度无关 —— type 2 档 0 是 `1/2` 但对应任务 `20010001` 是 0，
  档 8 是 `3/3` 而对应任务 `20020003` 是 0；type 1 三档全 0 而账号已达成 2 条任务。
  按 transition 分组（11 档 vs 6 组）、按 `mission_id` 前缀分组（11 档 vs 7 组）、
  按下标复用任务 `achieved_num` 三种假设都试过，全部对不上。
  唯一能确认的是自洽关系 `is_achieved == (achieved_num >= target_num)`（官方 16 档
  全成立）。缺的是它的推进规则，只能整块原样透传，不能自己算。
- `mission_type` 1 与 2 的 37 条任务，奖励是占位值 `item_type=0 / item_id=0`
  （`reward_type=1`），真实奖励挂在 `achieve_reward_list` 的阶段奖励上，所以这两类的
  单任务奖励本身就不可结算。
- `mission_type` 3 的 53 条奖励有真实 `item_id`，进度也能派生了，但领奖还要写回
  `achieve_reward_list` 的 `achieved_num`，而那个计数器不可派生，无法保持一致。

缺的是阶段奖励的推进规则。补上之前保持 501，避免出现「接口成功但发了零奖励」。


`H2ProfileSmokeTest.everySnapshotRouteHasAHandler` 断言 `WireRouter.pending()` 为空，
所以以后新增未移植路径会直接让构建失败，而不是静默退回 501。

## 显式 501 的写接口（33 条）

这些接口要真实的定价、归属、资格校验和原子结算；本地没有可核实的规则，宁可不做。
其中 32 条与 Python `UNIMPLEMENTED_WRITES` 一一对应，`mission/receive_common_mission_reward` 是清单里唯一一条「定义已拿到、缺推进规则」的。
注意它们是「有 handler 的 501」：路径存在、认证先过，然后拒绝，和 Python 的返回码一致。

### 商店 / 交换 / 抽卡点数

- `/v1/jewel_shop/set_purchase`
- `/v1/jewel_shop/set_membership_purchase`
- `/v1/gacha/set_prize_receive`
- `/v1/profile/use_fanlevel_point_stocks`

### 章节点数

- `/v1/chapter/claim_point_reward`
- `/v1/chapter/claim_point_rewards`
- `/v1/chapter/receive`
- `/v1/chapter/receive_mission_point`

### 任务 / 新手任务 / 每日关卡

- `/v1/mission/receive_common_mission_reward`（原因见上）
- `/v1/out_quest_live/daily/set_release`
- `/v1/out_quest_live/daily/recovery_challenge_count`

### 档案（Archive）

- `/v1/archive/set_purchase_ticket`
- `/v1/archive/withlive_gift`
- `/v1/archive/withlive_prize`
- `/v1/archive/withlive_stars`

### With×Live / With×Station / Fes×Live

- `/v1/withlive/gift`
- `/v1/withlive/message_card`
- `/v1/withlive/prize`
- `/v1/withlive/set_star`
- `/v1/withlive/enquetes/answer`
- `/v1/withstation/gift`
- `/v1/withstation/message_card`
- `/v1/withstation/prize`
- `/v1/withstation/set_star`
- `/v1/feslive/gift`
- `/v1/feslive/lobby_gift`
- `/v1/feslive/message_card`
- `/v1/feslive/lobby_message_card`
- `/v1/feslive/prize`
- `/v1/feslive/set_prize`
- `/v1/feslive/set_flower_stand`
- `/v1/feslive/enquete`

### Webview

- `/v1/webview/live/login`

## 与 Python 版的刻意差异

1. `/v1/common/get_header_announs`：Python 版返回的是 JSON 数组 `[]`。Java 版
   `WireHandler` 的返回类型是 `Object`，可以直接返回列表，所以同样下发数组而非 `{}`。
2. 缺失主数据时回 501：Python 在若干分支里会因为缺 catalog 行而抛 501
   （例如 `Missing verified item definition`）。Java 版沿用同一策略，不做零成本结算。
