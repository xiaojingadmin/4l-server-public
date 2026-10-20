package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireError;
import com.linklike.server.resource.ClientDefaults;
import com.linklike.server.service.PlayerContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code wire_shop.py} 里被其它模块共用的部分（进行中）。
 *
 * <p>目前只有两块：
 * <ul>
 *   <li>背包里某种道具的持有数 {@code _item_num}；
 *   <li>新手任务（beginner mission）进度推进 —— {@code advance_beginner_missions} /
 *       {@code raise_beginner_missions} 以及对应的条件常量。
 * </ul>
 *
 * <p>任务定义来自随仓库分发的 {@code client_defaults/shop_catalog.json}
 * （{@code beginner_missions} 是 {@code Id -> 条件定义} 的对象）。文件缺失或没有这个目录时
 * 任务推进是空操作，与 Python 版「本机没有导入目录」的行为一致；这里不会伪造任务。
 *
 * <p>第三块是新手任务的读取与领奖（{@code /v1/beginner_mission/*}）：目录行由
 * {@code beginner_missions} 生成并重置进度，账号自己的行覆盖达成数与状态；领奖走
 * {@code grant_reward}，只接受目录里核实过的 {@code ItemType/ItemId/Num}。
 *
 * <p>推进逻辑与 Python 版逐条对齐：
 * <ul>
 *   <li>只匹配 {@code MissionConditionType + MissionConditionDetail} 都相同的任务；
 *   <li>已完成（status=2）的任务不再推进；
 *   <li>进度封顶在任务目标，达到目标后状态从 0（未达成）变成 1（未领取）；
 *   <li>{@code raise} 记录绝对值（例如最高等级），{@code advance} 累加。
 * </ul>
 */
@Component
public class WireShop {

    // GameDefine.eMissionConditionType（与 Python wire_shop.py 同值）。
    public static final int BEGINNER_CONDITION_STANDARD_LIVE_CLEAR = 2;
    public static final int BEGINNER_CONDITION_DAILY_LIVE_CLEAR = 3;
    public static final int BEGINNER_CONDITION_MUSIC_RELEASE = 14;
    public static final int BEGINNER_CONDITION_MAX_STYLE_LEVEL = 31;
    public static final int BEGINNER_CONDITION_TOTAL_EVOLUTION = 40;
    public static final int BEGINNER_CONDITION_WITH_ARCHIVE_WATCH = 51;
    public static final int BEGINNER_CONDITION_FES_ARCHIVE_WATCH = 52;
    public static final int BEGINNER_CONDITION_WATCH_ADV = 70;
    public static final int BEGINNER_CONDITION_TOTAL_GACHA = 100;
    public static final int BEGINNER_CONDITION_SELECT_TICKET = 101;
    public static final int BEGINNER_CONDITION_DREAM_LIVE_TOP = 110;
    public static final int BEGINNER_CONDITION_MEMBER_RELEASE_CONFIRM = 111;
    public static final int BEGINNER_CONDITION_ACE_CARD = 112;
    public static final int BEGINNER_CONDITION_AUTO_ORGANIZATION = 113;
    public static final int BEGINNER_CONDITION_WALLPAPER_CHANGE = 120;
    public static final int BEGINNER_CONDITION_DISPLAY_CLOCK = 121;
    public static final int BEGINNER_CONDITION_CLOCK_DESIGN = 122;
    public static final int BEGINNER_CONDITION_ANIMATION_CARD_WALLPAPER = 123;
    public static final int BEGINNER_CONDITION_PROFILE_CARD = 124;

    public static final int BEGINNER_MISSION_NOT_ACHIEVED = 0;
    public static final int BEGINNER_MISSION_NOT_RECEIVED = 1;
    public static final int BEGINNER_MISSION_COMPLETED = 2;

    private final ClientDefaults defaults;

    /** {@code mission_groups.json} 展平后的关卡任务定义，懒加载。 */
    private volatile List<Map<String, Object>> serviceMissionSpecs;

    /** 与 {@link #serviceMissionSpecs} 平行的 {@code mission_type}，用于把进度落回正确的分组。 */
    private volatile List<Integer> serviceMissionTypes;

    /** {@code shop_catalog.beginner_missions}，按 JSON 里的顺序缓存。 */
    private volatile Map<String, Map<String, Object>> beginnerMissions;

    /** {@code shop_catalog.beginner_banner_rewards}（按 MissionType 的横幅奖励）。 */
    private volatile Map<String, List<Map<String, Object>>> beginnerBannerRewards;

    /** {@code step_up_missions.json} 的 {@code missions}（进阶新手任务的静态定义）。 */
    private volatile Map<String, Map<String, Object>> stepUp;

    /** {@code master_items_5.1.0.json} 按 Id 的索引，作为商店目录之外的道具兜底。 */
    private volatile Map<Integer, Map<String, Object>> masterItems;

    /**
     * {@code card_master_5.1.0.json} 的两张索引：卡 ID → {@code CardSeriesId}、
     * {@code CharactersId} → 角色定义。关卡任务的按角色计数器要用它们分组。
     */
    private volatile Map<Integer, Integer> cardSeriesOf;
    private volatile Map<Integer, Integer> cardCharacterOf;
    private volatile Set<Integer> knownCharacters;

    /**
     * 关卡任务的进度计数器来源，按官方 {@code transition_contents_id} 分组。
     *
     * <p>取值 <b>不是</b> {@code distinct stars} 之和：账号的 {@code school_stage_star}
     * 是 2526，而「クエストライブの☆を30個獲得」的进度只有 30。两者只在任务上限以内重合，
     * 所以这里按 {@code achieved_num = min(计数器, condition)} 还原，计数器本身从本地
     * 真实状态现算（见 {@link #missionCounters}）。
     */
    private static final int TRANSITION_STAGE_STAR = 101;
    private static final int TRANSITION_DAILY_LIVE = 800;
    private static final int TRANSITION_MEMBER_FAN_LEVEL = 203;
    private static final int TRANSITION_FOLLOW = 304;
    private static final int TRANSITION_STYLE_LEVEL = 309;
    private static final int TRANSITION_CARD_SPECIES = 501;

    public WireShop(ClientDefaults defaults) {
        this.defaults = defaults;
    }

    // ------------------------------------------------------------------
    // 背包
    // ------------------------------------------------------------------

    /** Python {@code wire_shop._item_num}：道具 ID 在任意分组里的持有数。 */
    public static int itemNum(PlayerContext player, long itemId) {
        for (Map<String, Object> group : player.rowsSnapshot("Items")) {
            for (Map<String, Object> row : J.rowsOr(group.get("UserItemList"))) {
                if (sameNumber(row.get("ItemId"), itemId)) {
                    return J.intOr(row.get("ItemNum"), 0);
                }
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // 新手任务
    // ------------------------------------------------------------------

    /** 存档里的新手任务列表（深拷贝，失败时可整体丢弃）。 */
    public static List<Map<String, Object>> copyMissions(PlayerContext player) {
        return WireServices.rows(player, "beginner_missions");
    }

    /** 累加条件进度；只会改传入的副本。 */
    public void advance(PlayerContext player, int conditionType) {
        advance(player, conditionType, 0, 1);
    }

    public void advance(PlayerContext player, int conditionType, int detail, int count) {
        List<Map<String, Object>> draft = copyMissions(player);
        boolean matched = advance(draft, conditionType, detail, count);
        if (matched || player.has("beginner_missions")) {
            player.put("beginner_missions", draft);
        }
    }

    /** 记录绝对进度（例如「最高风格等级」）。 */
    public void raise(PlayerContext player, int conditionType, int value) {
        raise(player, conditionType, value, 0);
    }

    public void raise(PlayerContext player, int conditionType, int value, int detail) {
        List<Map<String, Object>> draft = copyMissions(player);
        boolean matched = raise(draft, conditionType, value, detail);
        if (matched || player.has("beginner_missions")) {
            player.put("beginner_missions", draft);
        }
    }

    /**
     * 累加进度（副本版）。
     *
     * @return 是否有任务定义命中该条件；命中即代表 Python 版也会触碰
     *         {@code player['beginner_missions']} 这个键
     */
    public boolean advance(List<Map<String, Object>> missions, int conditionType,
                           int detail, int count) {
        return settle(missions, conditionType, detail, have -> have + count);
    }

    /** 累加进度（副本版，detail 固定为 0）。 */
    public boolean advance(List<Map<String, Object>> missions, int conditionType) {
        return advance(missions, conditionType, 0, 1);
    }

    /** 记录绝对进度（副本版）。 */
    public boolean raise(List<Map<String, Object>> missions, int conditionType,
                         int value, int detail) {
        return settle(missions, conditionType, detail, have -> Math.max(have, value));
    }

    /** 记录绝对进度（副本版，detail 固定为 0）。 */
    public boolean raise(List<Map<String, Object>> missions, int conditionType, int value) {
        return raise(missions, conditionType, value, 0);
    }

    private boolean settle(List<Map<String, Object>> missions, int conditionType, int detail,
                           java.util.function.IntUnaryOperator progress) {
        boolean matched = false;
        for (Map<String, Object> spec : missions().values()) {
            if (missionInt(spec, "MissionConditionType") != conditionType
                    || missionInt(spec, "MissionConditionDetail") != detail) {
                continue;
            }
            matched = true;
            Map<String, Object> row = missionRow(missions, spec);
            if (missionInt(row, "status") == BEGINNER_MISSION_COMPLETED) {
                continue;
            }
            int target = Math.max(1, missionInt(spec, "MissionConditionNum"));
            int achieved = Math.min(target, progress.applyAsInt(missionInt(row, "achieved_num")));
            row.put("achieved_num", achieved);
            row.put("status", achieved >= target
                    ? BEGINNER_MISSION_NOT_RECEIVED : BEGINNER_MISSION_NOT_ACHIEVED);
        }
        return matched;
    }

    /** Python {@code beginner_mission_row}：按 mission_type 分组、按 mission_id 找行。 */
    public static Map<String, Object> missionRow(List<Map<String, Object>> groups,
                                                 Map<String, Object> spec) {
        int missionType = missionInt(spec, "MissionType");
        Map<String, Object> group = null;
        for (Map<String, Object> candidate : groups) {
            if (missionInt(candidate, "mission_type") == missionType) {
                group = candidate;
                break;
            }
        }
        if (group == null) {
            group = new LinkedHashMap<>();
            group.put("mission_type", missionType);
            group.put("is_received", false);
            group.put("mission_list", new ArrayList<Map<String, Object>>());
            groups.add(group);
        }

        int missionId = missionInt(spec, "Id");
        // 必须操作分组里真正的那份列表，不能拿 J.rowsOr 过滤出来的副本。
        List<Map<String, Object>> rows = groupMissionList(group);
        Map<String, Object> row = null;
        for (Map<String, Object> candidate : rows) {
            if (missionInt(candidate, "mission_id") == missionId) {
                row = candidate;
                break;
            }
        }
        if (row == null) {
            row = new LinkedHashMap<>();
            row.put("mission_id", missionId);
            row.put("achieved_num", 0);
            row.put("status", BEGINNER_MISSION_NOT_ACHIEVED);
            rows.add(row);
        }
        return row;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> groupMissionList(Map<String, Object> group) {
        Object value = group.get("mission_list");
        if (value instanceof List<?>) {
            return (List<Map<String, Object>>) value;
        }
        List<Map<String, Object>> created = new ArrayList<>();
        group.put("mission_list", created);
        return created;
    }

    private Map<String, Map<String, Object>> missions() {
        Map<String, Map<String, Object>> cached = beginnerMissions;
        if (cached != null) {
            return cached;
        }
        Map<String, Map<String, Object>> parsed = new LinkedHashMap<>();
        Map<String, Object> catalog = defaults.object("shop_catalog").orElse(null);
        if (catalog != null) {
            Object table = catalog.get("beginner_missions");
            if (table instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getValue() instanceof Map<?, ?> spec) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> typed = (Map<String, Object>) spec;
                        parsed.put(String.valueOf(entry.getKey()), typed);
                    }
                }
            }
        }
        cached = parsed;
        beginnerMissions = cached;
        return cached;
    }

    /**
     * 新手任务分类目录（进度已重置）—— 抓包导入的 {@code ref_beginner_mission_catalog} 的
     * 等价物，改由随仓库分发的 {@code shop_catalog.beginner_missions} 生成。
     *
     * <p>生成结果与 Python {@code capture_import} 写入的形状一致（{@code mission_type} /
     * {@code is_received} / {@code mission_list[mission_id, achieved_num, status]}），
     * 因此读取分支可以用同一套 {@code _overlay_nested} 语义叠账号进度。
     */
    public List<Map<String, Object>> beginnerMissionCatalog() {
        List<Map<String, Object>> groups = new ArrayList<>();
        Map<Integer, Map<String, Object>> byType = new LinkedHashMap<>();
        for (Map<String, Object> spec : missions().values()) {
            int missionType = missionInt(spec, "MissionType");
            Map<String, Object> group = byType.get(missionType);
            if (group == null) {
                group = J.map("mission_type", missionType, "is_received", false,
                        "mission_list", new ArrayList<Map<String, Object>>());
                byType.put(missionType, group);
                groups.add(group);
            }
            J.rowsOr(group.get("mission_list")).add(J.map(
                    "mission_id", missionInt(spec, "Id"),
                    "achieved_num", 0L,
                    "status", BEGINNER_MISSION_NOT_ACHIEVED));
        }
        return groups;
    }

    /** {@code step_up_missions.json} 的任务定义，按 {@code Id} 排序。 */
    public List<Map<String, Object>> stepUpMissionSpecs() {
        List<Map<String, Object>> specs = new ArrayList<>();
        for (Map<String, Object> spec : stepUpMissions().values()) {
            specs.add(spec);
        }
        specs.sort(Comparator.comparingInt(spec -> missionInt(spec, "Id")));
        return specs;
    }

    /**
     * 进阶新手任务目录（进度已重置）—— 对应抓包导入的
     * {@code ref_step_up_beginner_mission_catalog}，由随仓库分发的
     * {@code step_up_missions.json} 生成。
     *
     * <p>客户端的 {@code CreateUserDataIfNeededAsync} 会等待这个列表，空表会让新建账号卡在
     * 初始化阶段，所以这里在目录缺失时不返回空表而是不注册该路径的 501，避免静默卡死。
     */
    public List<Map<String, Object>> stepUpMissionCatalog() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> spec : stepUpMissionSpecs()) {
            rows.add(J.map(
                    "mission_id", missionInt(spec, "Id"),
                    "achieved_num", 0L,
                    "is_achieved", false,
                    "is_received", false));
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // 进阶新手任务：领奖
    // ------------------------------------------------------------------

    /** 进阶新手任务的一条目录行；目录里没有这个 Id 时返回 null。 */
    public Map<String, Object> stepUpMissionSpec(int missionId) {
        return stepUpMissions().get(String.valueOf(missionId));
    }

    /**
     * 领取一条进阶新手任务奖励 —— 对应 Python {@code wire_services} 的
     * {@code _claim_step_up_mission}。
     *
     * <p>先在玩家数据的副本上判定「已达成且未领取」，再逐条 {@link #grantReward}；
     * 奖励只要有一条发不出去，整单失败、账号状态原样不动 —— 不出现「领到了但少发」。
     *
     * @return 实际发放的奖励清单，对应 {@code ReceivedRewardList}
     */
    public List<Map<String, Object>> claimStepUpMission(PlayerContext player, int missionId) {
        Map<String, Object> spec = stepUpMissionSpec(missionId);
        if (spec == null) {
            throw new WireError(404, "Unknown step up mission");
        }
        List<Map<String, Object>> draft = WireServices.rows(player, "step_up_beginner_missions");
        Map<String, Object> row = null;
        for (Map<String, Object> candidate : draft) {
            if (missionInt(candidate, "mission_id") == missionId) {
                row = candidate;
                break;
            }
        }
        if (row == null) {
            row = J.map("mission_id", missionId, "achieved_num", 0L,
                    "is_achieved", false, "is_received", false);
            draft.add(row);
        }
        if (!J.boolOr(row.get("is_achieved"), false)) {
            throw WireError.badRequest("Step up mission " + missionId + " is not achieved");
        }
        if (J.boolOr(row.get("is_received"), false)) {
            throw WireError.badRequest("Step up mission " + missionId + " is already received");
        }
        List<Map<String, Object>> granted = new ArrayList<>();
        for (Map<String, Object> reward : J.rowsOr(spec.get("Rewards"))) {
            grantReward(player, reward);
            granted.add(J.map(
                    "RewardType", missionInt(reward, "ItemType"),
                    "RewardItemId", missionInt(reward, "ItemId"),
                    "RewardNum", missionInt(reward, "Num")));
        }
        row.put("is_received", true);
        player.put("step_up_beginner_missions", draft);
        return granted;
    }

    // ------------------------------------------------------------------
    // 关卡任务（/v1/mission/*）
    // ------------------------------------------------------------------

    /**
     * 按账号当前状态刷新关卡任务的进度。
     *
     * <p>官方目录只下发任务定义和 {@code transition_contents_id}（计数器来源），进度由
     * 客户端侧状态派生：{@code achieved_num = min(计数器, condition_achieve_num)}。
     * 同一 {@code (transition, mission_id 前 6 位)} 的任务共用一个计数器，这个分组已用
     * 官方账号的 91 条任务实测验证（39 组无一例外）。
     *
     * <p>只处理本地能算出真实值的计数器；{@code transition} 无法派生时整组保持原值，
     * 由调用方决定是否回 501。
     *
     * @return 是否有任何一行的进度发生变化
     */
    public boolean refreshServiceMissions(PlayerContext player) {
        DerivedServiceMissions derived = deriveServiceMissions(player);
        if (derived.changed()) {
            player.put("service_missions", derived.groups());
        }
        return derived.changed();
    }

    /**
     * 关卡任务进度的只读派生结果。
     *
     * <p>{@code /v1/mission/get_list} 是 GET 语义：这里只基于账号快照生成一份草稿，
     * 不把派生结果写回 {@link PlayerContext}，也不触发 {@code PlayerStore.save}。这样既保证
     * 新账号能立即看到真实进度，又不会让并发读取争抢玩家行或互相覆盖状态。
     */
    public List<Map<String, Object>> derivedServiceMissions(PlayerContext player) {
        return deriveServiceMissions(player).groups();
    }

    private DerivedServiceMissions deriveServiceMissions(PlayerContext player) {
        Object raw = player.raw("service_missions");
        List<Map<String, Object>> groups = copyGroups(raw);
        Map<String, Integer> counters = missionCounters(player);
        List<Map<String, Object>> specs = serviceMissions();
        List<Integer> types = serviceMissionTypes();
        for (int i = 0; i < specs.size(); i++) {
            Map<String, Object> spec = specs.get(i);
            int transition = missionInt(spec, "transition_contents_id");
            Integer counter = counters.get(counterKey(transition,
                    missionInt(spec, "mission_id")));
            if (counter == null) {
                continue;
            }
            int target = Math.max(0, missionInt(spec, "condition_achieve_num"));
            int achieved = Math.min(counter, target);
            Map<String, Object> row = serviceMissionRow(groups, types.get(i), spec);
            int before = missionInt(row, "achieved_num");
            boolean isAchieved = target > 0 && achieved >= target;
            if (achieved != before || !row.containsKey("is_achieved")) {
                row.put("achieved_num", achieved);
                row.put("is_achieved", isAchieved);
            }
        }
        return new DerivedServiceMissions(groups, !Objects.equals(raw, groups));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> copyGroups(Object raw) {
        if (!(raw instanceof List<?>)) {
            return new ArrayList<>();
        }
        return (List<Map<String, Object>>) WireServices.deepCopyJson(raw);
    }

    private record DerivedServiceMissions(List<Map<String, Object>> groups, boolean changed) {
    }

    /** 单条任务的派生进度；{@code null} 表示本地无可信来源（不写该行）。 */
    public Integer serviceMissionProgressRow(PlayerContext player, int transition, int missionId) {
        return missionCounter(player, transition, missionId);
    }

    /** 计数器分组键：{@code transition} + {@code mission_id} 的前 6 位（去掉档位后缀）。 */
    static String counterKey(int transition, int missionId) {
        String text = Integer.toString(missionId);
        String prefix = text.length() > 6 ? text.substring(0, 6) : text;
        return transition + ":" + prefix;
    }

    /** 目录里的关卡任务定义（{@code mission_groups} 的 {@code mission_list} 展平）。 */
    public List<Map<String, Object>> serviceMissions() {
        loadServiceMissions();
        return serviceMissionSpecs;
    }

    /** 与 {@link #serviceMissions()} 平行的分组序号，按下标取。 */
    public List<Integer> serviceMissionTypes() {
        loadServiceMissions();
        return serviceMissionTypes;
    }

    private void loadServiceMissions() {
        if (serviceMissionSpecs != null) {
            return;
        }
        List<Map<String, Object>> specs = new ArrayList<>();
        List<Integer> types = new ArrayList<>();
        Map<String, Object> catalog = defaults.object("mission_groups").orElse(null);
        Object groups = catalog == null ? null : catalog.get("groups");
        for (Map<String, Object> group : J.rowsOr(groups)) {
            int missionType = missionInt(group, "mission_type");
            for (Map<String, Object> spec : J.rowsOr(group.get("mission_list"))) {
                specs.add(spec);
                types.add(missionType);
            }
        }
        serviceMissionTypes = types;
        serviceMissionSpecs = specs;
    }

    /**
     * 从账号真实状态算出的任务计数器。
     *
     * <p>每个 {@code transition} 对应一类账号数据：关卡☆、日常关卡、梦活通关、风格等级
     * 合计、持有卡种类、关注数、Member Fan Lv。算不出来的不放进结果，调用方据此跳过。
     */
    public Map<String, Integer> missionCounters(PlayerContext player) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map<String, Object> spec : serviceMissions()) {
            int transition = missionInt(spec, "transition_contents_id");
            String key = counterKey(transition, missionInt(spec, "mission_id"));
            if (out.containsKey(key)) {
                continue;
            }
            Integer value = missionCounter(player, transition, missionInt(spec, "mission_id"));
            if (value != null) {
                out.put(key, value);
            }
        }
        return out;
    }

    private Integer missionCounter(PlayerContext player, int transition, int missionId) {
        switch (transition) {
            case TRANSITION_STAGE_STAR:
                return player.intOf("SchoolStageStar", 0);
            case TRANSITION_FOLLOW:
                // 3201 是「我关注了谁」，3202 是「谁关注了我」，两者是不同计数器。
                return player.intOf(Integer.toString(missionId).startsWith("3202")
                        ? "FollowerCount" : "FollowCount", 0);
            case TRANSITION_MEMBER_FAN_LEVEL:
            case TRANSITION_STYLE_LEVEL:
            case TRANSITION_CARD_SPECIES:
                break;
            default:
                // 100/103/106/800 等通关次数类：账号 schema 里没有对应计数器
                // （SchoolStageStar 是☆总数，不是通关次数），不猜。
                return null;
        }
        // 同一个 transition 在不同 mission_id 前缀下语义不同：带角色编码的是「该角色」，
        // 000 结尾的是账号全局值（如 20200001「カードを3回入手」= 全局入手回数）。
        String text = Integer.toString(missionId);
        if (text.length() < 6 || !text.substring(3, 6).equals("000")) {
            Integer character = missionCharacter(missionId);
            if (character == null) {
                return null;
            }
            return characterCounter(player, transition, character);
        }
        // 全局计数器：本地没有任何可信来源，绝不猜。
        if (transition == TRANSITION_CARD_SPECIES && text.startsWith("302000")) {
            // 「カードを計20種類入手」= 全账号去重后的 CardSeriesId 数。
            Set<Integer> series = new HashSet<>();
            for (Map<String, Object> card : player.rowsSnapshot("Cards")) {
                int seriesId = cardSeries(missionInt(card, "CardDatasId"));
                if (seriesId > 0) {
                    series.add(seriesId);
                }
            }
            return series.size();
        }
        return null;
    }

    /**
     * {@code mission_id} 的编码是 {@code [mission_type][角色尾号 3 位][档位 2 位]}，
     * 例如 {@code 30202101} → type 3、角色 {@code 1021}、档位 01。
     */
    private Integer missionCharacter(int missionId) {
        String text = Integer.toString(missionId);
        if (text.length() < 6) {
            return null;
        }
        try {
            int character = 1000 + Integer.parseInt(text.substring(3, 6));
            return characters().contains(character) ? character : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int characterCounter(PlayerContext player, int transition, int character) {
        if (transition == TRANSITION_CARD_SPECIES) {
            Set<Integer> series = new HashSet<>();
            for (Map<String, Object> card : player.rowsSnapshot("Cards")) {
                int cardId = missionInt(card, "CardDatasId");
                if (cardCharacterOf(card) == character) {
                    int seriesId = cardSeries(cardId);
                    if (seriesId > 0) {
                        series.add(seriesId);
                    }
                }
            }
            return series.size();
        }
        if (transition == TRANSITION_MEMBER_FAN_LEVEL) {
            // 203 的文案是「このメンバーのファンレベルを10にしよう」，是单个角色的等级，
            // 不是所有卡等级求和。
            for (Map<String, Object> row : player.rowsSnapshot("MemberFanLevels")) {
                if (missionInt(row, "CharactersId") == character) {
                    return missionInt(row, "MemberFanlevel");
                }
            }
            return 1;
        }
        int total = 0;
        for (Map<String, Object> card : player.rowsSnapshot("Cards")) {
            if (cardCharacterOf(card) != character) {
                continue;
            }
            total += transition == TRANSITION_STYLE_LEVEL
                    ? missionInt(card, "StyleLevel")
                    : 0;
        }
        return total;
    }

    /**
     * 账号卡行自带 {@code CharacterId}，优先用它：{@code card_master} 不完整
     * （实测 5.1.0 缺 {@code 19515010} 等卡）时绕经 master 会漏算整张卡的 StyleLevel。
     */
    private int cardCharacterOf(Map<String, Object> card) {
        Integer known = card.get("CharacterId") instanceof Number
                ? ((Number) card.get("CharacterId")).intValue() : null;
        return known != null ? known : cardCharacter(missionInt(card, "CardDatasId"));
    }

    private int cardCharacter(int cardId) {
        return cardCharacterOf().getOrDefault(cardId, Integer.valueOf(-1));
    }

    private int cardSeries(int cardId) {
        return cardSeriesOf().getOrDefault(cardId, Integer.valueOf(-1));
    }

    private Set<Integer> characters() {
        return knownCharacters();
    }

    private Map<Integer, Integer> cardCharacterOf() {
        if (cardCharacterOf == null) {
            loadCardMaster();
        }
        return cardCharacterOf;
    }

    private Map<Integer, Integer> cardSeriesOf() {
        if (cardSeriesOf == null) {
            loadCardMaster();
        }
        return cardSeriesOf;
    }

    private Set<Integer> knownCharacters() {
        if (knownCharacters == null) {
            loadCardMaster();
        }
        return knownCharacters;
    }

    private synchronized void loadCardMaster() {
        if (knownCharacters != null) {
            return;
        }
        Map<Integer, Integer> byCard = new HashMap<>();
        Map<Integer, Integer> series = new HashMap<>();
        Set<Integer> chars = new HashSet<>();
        Map<String, Object> master = defaults.object("card_master_5.1.0").orElse(null);
        if (master != null) {
            Object rawCharacters = master.get("characters");
            if (rawCharacters instanceof Map<?, ?> map) {
                for (Object key : map.keySet()) {
                    try {
                        chars.add(Integer.parseInt(String.valueOf(key)));
                    } catch (NumberFormatException ignored) {
                        // 非数字键忽略。
                    }
                }
            }
            Object rawCards = master.get("cards");
            Iterable<?> cardRows = rawCards instanceof Map<?, ?> map
                    ? map.values() : J.rowsOr(rawCards);
            for (Object rawCard : cardRows) {
                if (!(rawCard instanceof Map<?, ?>)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> card = (Map<String, Object>) rawCard;
                int id = missionInt(card, "Id");
                byCard.put(id, missionInt(card, "CharactersId"));
                series.put(id, missionInt(card, "CardSeriesId"));
            }
        }
        cardSeriesOf = series;
        cardCharacterOf = byCard;
        knownCharacters = chars;
    }

    /** 账号里该任务的行；没有就按「未达成」补一行进分组。 */
    private static Map<String, Object> serviceMissionRow(List<Map<String, Object>> groups,
                                                         int missionType,
                                                         Map<String, Object> spec) {
        Map<String, Object> group = null;
        for (Map<String, Object> candidate : groups) {
            if (missionInt(candidate, "mission_type") == missionType) {
                group = candidate;
                break;
            }
        }
        if (group == null) {
            group = J.map("mission_type", missionType, "mission_list", new ArrayList<>());
            groups.add(group);
        }
        List<Map<String, Object>> rows = mutableRows(group.get("mission_list"));
        int missionId = missionInt(spec, "mission_id");
        for (Map<String, Object> row : rows) {
            if (missionInt(row, "mission_id") == missionId) {
                return row;
            }
        }
        Map<String, Object> row = J.map("mission_id", missionId, "achieved_num", 0,
                "is_achieved", false);
        rows.add(row);
        group.put("mission_list", rows);
        return row;
    }

    /**
     * 取分组里 {@code mission_list} 的<b>原列表</b>，没有就补一个。
     *
     * <p>{@link J#rowsOr} 总是新建列表，往它上面 {@code add} 不会影响账号数据，所以这里
     * 必须拿到原引用。
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mutableRows(Object value) {
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return new ArrayList<>();
    }

    private Map<String, Map<String, Object>> stepUpMissions() {
        Map<String, Map<String, Object>> cached = stepUp;
        if (cached != null) {
            return cached;
        }
        Map<String, Map<String, Object>> parsed = new LinkedHashMap<>();
        Map<String, Object> doc = defaults.object("step_up_missions").orElse(null);
        Object table = doc == null ? null : doc.get("missions");
        if (table instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> spec) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) spec;
                    parsed.put(String.valueOf(entry.getKey()), typed);
                }
            }
        }
        cached = parsed;
        stepUp = cached;
        return cached;
    }

    /** {@code int(spec.get(key) or 0)}，但只接受 JSON 整数。 */
    private static int missionInt(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Integer number) {
            return number;
        }
        if (value instanceof Long number) {
            return number.intValue();
        }
        return 0;
    }

    private static boolean sameNumber(Object value, long expected) {
        if (value instanceof Number number) {
            return number.longValue() == expected;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 新手任务：目录、进度行、领奖
    // ------------------------------------------------------------------

    /** 某一类（{@code MissionType}）的任务定义，按 (SortOrder, Id) 排序 —— Python
     *  {@code _beginner_mission_type}。 */
    public List<Map<String, Object>> missionSpecs(int missionType) {
        List<Map<String, Object>> specs = new ArrayList<>();
        for (Map<String, Object> spec : missions().values()) {
            if (missionInt(spec, "MissionType") == missionType) {
                specs.add(spec);
            }
        }
        specs.sort(Comparator.comparingInt((Map<String, Object> spec) -> missionInt(spec, "SortOrder"))
                .thenComparingInt(spec -> missionInt(spec, "Id")));
        return specs;
    }

    public Map<String, Object> missionSpec(int missionId) {
        return missions().get(String.valueOf(missionId));
    }

    /** 某一类任务的横幅奖励；目录里没有定义时返回空表（调用方据此返回 501，不伪造奖励）。 */
    public List<Map<String, Object>> bannerRewards(int missionType) {
        Map<String, List<Map<String, Object>>> cached = beginnerBannerRewards;
        if (cached == null) {
            Map<String, List<Map<String, Object>>> parsed = new LinkedHashMap<>();
            Map<String, Object> catalog = defaults.object("shop_catalog").orElse(null);
            Object table = catalog == null ? null : catalog.get("beginner_banner_rewards");
            if (table instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    parsed.put(String.valueOf(entry.getKey()), J.rowsOr(entry.getValue()));
                }
            }
            cached = parsed;
            beginnerBannerRewards = cached;
        }
        return cached.getOrDefault(String.valueOf(missionType), List.of());
    }

    /**
     * 新手任务分类列表（目录进度已重置）+ 账号自己的达成数与状态 —— 对应 Python
     * {@code /v1/beginner_mission/get_list} 的 {@code _overlay_nested(...)}。
     *
     * <p>目录来自随仓库分发的 {@code shop_catalog.beginner_missions}（与
     * {@code missions.tsv} 一致），因此不需要抓包目录也能下发完整分类与任务 id。
     */
    public List<Map<String, Object>> missionCategories(PlayerContext player) {
        return WireServices.overlayNested(beginnerMissionCatalog(), copyMissions(player),
                "mission_type", "mission_list", "mission_id",
                Set.of("achieved_num", "status"), Set.of("is_received"));
    }

    /**
     * 领取一个已达成的任务 —— 对应 Python {@code _claim_beginner_mission}。
     *
     * <p>只有 {@code status == 1}（已达成未领取）可领；奖励逐条走
     * {@link #grantReward}，任一奖励不可核实就整体失败，状态不变。
     *
     * @return 该任务在账号状态里的行
     */
    public Map<String, Object> claim(PlayerContext player, Map<String, Object> spec) {
        List<Map<String, Object>> draft = copyMissions(player);
        Map<String, Object> row = missionRow(draft, spec);
        int missionId = missionInt(spec, "Id");
        if (missionInt(row, "status") != BEGINNER_MISSION_NOT_RECEIVED) {
            throw WireError.badRequest("Beginner mission " + missionId + " is not claimable");
        }
        for (Map<String, Object> reward : J.rowsOr(spec.get("Rewards"))) {
            grantReward(player, reward);
        }
        row.put("status", BEGINNER_MISSION_COMPLETED);
        player.put("beginner_missions", draft);
        return row;
    }

    /**
     * 发放一条核实过的任务奖励 —— 对应 Python {@code wire_shop.grant_reward}。
     *
     * <p>SIsCa 进免费宝石钱包（不限上限）；其余钱包类道具按主数据 {@code LimitNum} 截断；
     * 背包道具走与抽卡共用的 credit 路径，发不满就整条失败（返回 400），不静默少发。
     */
    public void grantReward(PlayerContext player, Map<String, Object> reward) {
        Map<String, Object> catalog = catalog();
        int itemType = missionInt(reward, "ItemType");
        int ident = missionInt(reward, "ItemId");
        int num = missionInt(reward, "Num");
        if ((itemType != WireGrants.ITEM_TYPE_ITEM && itemType != WireGrants.ITEM_TYPE_RESOURCE)
                || num <= 0) {
            throw new WireError(501, "Reward type " + itemType + " has not been verified");
        }
        if (ident == catalogInt(catalog, "sisca_item_id")) {
            WireGrants.addWallet(player, "JewelFree", num);
            return;
        }
        Map<String, Object> item = requireItem(catalog, ident);
        String wallet = WireGrants.WALLET_ITEMS.get(ident);
        if (wallet != null) {
            long total = WireGrants.addWallet(player, wallet, num);
            long limit = J.longOr(item.get("LimitNum"), 0);
            WireGrants.setWallet(player, wallet, limit > 0 ? Math.min(total, limit) : total);
            return;
        }
        if (itemType == WireGrants.ITEM_TYPE_RESOURCE && missionInt(item, "ItemCategory") == 0) {
            throw new WireError(501, "Missing verified wallet for resource " + ident);
        }
        if (credit(player, item, num) != num) {
            throw WireError.badRequest("Cannot grant the full product quantity");
        }
    }

    /** Python {@code wire_gacha._credit_item}：按 ItemCategory 归组、按 ItemId 累加并按
     *  {@code LimitNum} 截断，返回实际记入的数量。 */
    private static int credit(PlayerContext player, Map<String, Object> item, int quantity) {
        if (quantity <= 0) {
            return 0;
        }
        int category = missionInt(item, "ItemCategory");
        int itemId = missionInt(item, "Id");
        List<Map<String, Object>> groups = player.rows("Items");
        Map<String, Object> group = null;
        for (Map<String, Object> row : groups) {
            if (missionInt(row, "ItemCategory") == category) {
                group = row;
                break;
            }
        }
        if (group == null) {
            group = J.map("ItemCategory", category, "UserItemList", new ArrayList<>());
            groups.add(group);
        }
        List<Map<String, Object>> owned = mutableRows(group, "UserItemList");
        Map<String, Object> row = null;
        for (Map<String, Object> candidate : owned) {
            if (missionInt(candidate, "ItemId") == itemId) {
                row = candidate;
                break;
            }
        }
        int before = row == null ? 0 : missionInt(row, "ItemNum");
        int limit = missionInt(item, "LimitNum");
        int credited = Math.max(0, Math.min(quantity, limit - before));
        if (credited > 0) {
            if (row == null) {
                row = J.map("UserItemId", UUID.randomUUID().toString(), "ItemId", itemId,
                        "ItemType", item.get("ItemType"), "Rarity", item.get("Rarity"),
                        "ItemNum", 0, "ResourceFileName", "");
                owned.add(row);
            }
            row.put("ItemNum", before + credited);
        }
        return credited;
    }

    private Map<String, Object> requireItem(Map<String, Object> catalog, int ident) {
        Map<String, Object> item = catalogItem(catalog, ident);
        if (item == null) {
            throw new WireError(501, "Missing verified item definition for this exchange");
        }
        return item;
    }

    /**
     * 按道具 ID 查核实过的定义：先查 {@code shop_catalog.items}（结算相关的那批），
     * 再退回完整的 5.1.0 主数据 {@code master_items_5.1.0.json}。
     *
     * <p>后者的用途是奖励发放：任务奖励会引用不在商店目录里的道具（例如
     * {@code 3002210} 十连券、{@code 3001002} 体力券）。只查商店子集会把它们误判成
     * 「定义缺失」而拒绝整单奖励。
     */
    private Map<String, Object> catalogItem(Map<String, Object> catalog, int ident) {
        Object items = catalog.get("items");
        Map<String, Object> item = items instanceof Map<?, ?> map
                ? J.nodeOr(map.get(String.valueOf(ident))) : null;
        return item != null ? item : masterItem(ident);
    }

    /** 5.1.0 主数据里的道具行；文件缺失时返回 null（调用方据此拒绝结算）。 */
    private Map<String, Object> masterItem(int ident) {
        Map<Integer, Map<String, Object>> index = masterItems;
        if (index == null) {
            index = new LinkedHashMap<>();
            for (Map<String, Object> row : defaults.rows("master_items_5.1.0")) {
                int id = J.intOr(row.get("Id"), 0);
                if (id != 0) {
                    index.put(id, row);
                }
            }
            masterItems = index;
        }
        return index.get(ident);
    }

    private Map<String, Object> catalog() {
        return defaults.object("shop_catalog").orElseGet(LinkedHashMap::new);
    }

    private static int catalogInt(Map<String, Object> catalog, String key) {
        Object value = catalog.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mutableRows(Map<String, Object> node, String key) {
        Object value = node.get(key);
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) (List<?>) list;
        }
        List<Map<String, Object>> created = new ArrayList<>();
        node.put(key, created);
        return created;
    }
}
