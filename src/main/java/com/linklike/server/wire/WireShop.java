package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import com.linklike.server.resource.ClientDefaults;
import com.linklike.server.service.PlayerContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** {@code shop_catalog.beginner_missions}，按 JSON 里的顺序缓存。 */
    private volatile Map<String, Map<String, Object>> beginnerMissions;

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
    private static Map<String, Object> missionRow(List<Map<String, Object>> groups,
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

    /** Python 的 {@code int(spec.get(key) or 0)}，但只接受 JSON 整数。 */
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
}
