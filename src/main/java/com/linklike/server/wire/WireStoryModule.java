package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireError;
import com.linklike.server.service.PlayerContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 剧情（活动记录）—— 对应 Python {@code wire_services.py} 里 {@code /v1/activity_record/*} 的
 * 四个分支，以及 {@code /v1/webview/school_idol_connect_post/get_theme_list}。
 *
 * <p>月份与章节来自抓包导入的 {@code story_months} 目录，本账号只记录「看没看过」和
 * 「解锁到哪一步」（{@code activity_records}）。目录里缺少本机资源包能渲染的图片或脚本时，
 * 该月 / 该话不下发 —— 客户端的剧情首页会一直等这些 addressable，永远进不去场景。
 *
 * <p>解锁状态是由账号道具推出来的（{@code adv_unlocks} 里定义每话要花的解锁道具）：
 * 前置话可看才进入 {@code WATCHABLE_TARGET}，否则看道具是够（{@code WATCHABLE_ABLE}）
 * 还是不够（{@code WATCHABLE_LOCKED}）。
 */
@Component
public class WireStoryModule implements WireModule {

    static final int WATCHABLE_ABLE = 1;
    static final int WATCHABLE_FINISHED = 5;
    static final int WATCHABLE_LOCKED = 6;
    static final int WATCHABLE_TARGET = 7;

    /** Python {@code wire_shop.BEGINNER_CONDITION_WATCH_ADV}。 */
    private static final int BEGINNER_CONDITION_WATCH_ADV = 70;

    private static final Set<String> INNER_FIELDS = Set.of("is_not_watched", "watchable_status");
    private static final Set<String> GROUP_FIELDS =
            Set.of("is_monthly_not_watched", "is_current_season", "is_not_displayed_new_part");

    private final WireServices services;
    private final WireStore store;
    private final WireShop shop;

    public WireStoryModule(WireServices services, WireStore store, WireShop shop) {
        this.services = services;
        this.store = store;
        this.shop = shop;
    }

    @Override
    public void register(WireRouter router) {
        router.register("/v1/activity_record/get_top", this::top);
        router.register("/v1/activity_record/notify_month_displayed", this::notifyMonthDisplayed);
        router.register("/v1/activity_record/play_adv_data", this::playAdvData);
        router.register("/v1/activity_record/set_watchable_status", this::setWatchableStatus);
        router.register("/v1/webview/school_idol_connect_post/get_theme_list",
                this::themeList);
    }

    private Map<String, Object> top(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        List<Map<String, Object>> months = WireServices.overlayNested(
                services.refs("story_months"), player.rows("activity_records"),
                "adv_series_id", "adv_info_list", "adv_data_id", INNER_FIELDS, GROUP_FIELDS);
        applyStoryUnlocks(months, player);
        return J.map("activity_record_monthly_info_list",
                storyAvailable(months, services.resourceLabels()));
    }

    private Map<String, Object> notifyMonthDisplayed(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        int series = WireServices.integer(request.body(), "adv_series_id", (Integer) null,
                1, Integer.MAX_VALUE);
        storyMonth(player, series).put("is_not_displayed_new_part", false);
        request.store().save(player);
        return new LinkedHashMap<>();
    }

    private Map<String, Object> playAdvData(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        int advId = WireServices.integer(request.body(), "adv_data_id", (Integer) null,
                1, Integer.MAX_VALUE);
        Integer status = watchableStatus(player, advId, new LinkedHashMap<>());
        if (status != null && status != WATCHABLE_ABLE) {
            throw WireError.badRequest("Adventure is not watchable");
        }
        Map<String, Object>[] episode = storyEpisode(advId);
        List<Object> rewards = new ArrayList<>(
                J.listOrEmpty(J.nodeOrEmpty(episode[1]).get("watch_reward_list")));
        Object series = episode[0] == null ? null : episode[0].get("adv_series_id");
        if (series instanceof Integer) {
            storyOwn(player, (Integer) series, advId).put("is_not_watched", false);
            shop.advance(player, BEGINNER_CONDITION_WATCH_ADV, advId, 1);
            request.store().save(player);
        }
        return J.map("watch_reward_list", WireServices.deepCopyJson(rewards));
    }

    private Map<String, Object> setWatchableStatus(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        int advId = WireServices.integer(request.body(), "adv_data_id", (Integer) null,
                1, Integer.MAX_VALUE);
        Map<String, Object> spec = advSpec(advId);
        if (spec == null || J.intOr(spec.get("AdvUnlockItemId"), 0) <= 0
                || J.intOr(spec.get("AdvUnlockItemNum"), 0) <= 0) {
            throw WireError.badRequest("Adventure does not require an unlock item");
        }
        Map<String, Object>[] episode = storyEpisode(advId);
        if (episode[1] == null || !(episode[0].get("adv_series_id") instanceof Integer)) {
            throw WireError.notFound("Local adventure not found");
        }
        if (!Integer.valueOf(WATCHABLE_LOCKED).equals(
                watchableStatus(player, advId, new LinkedHashMap<>()))) {
            throw WireError.badRequest("Adventure is not locked");
        }
        long itemId = J.longOr(spec.get("AdvUnlockItemId"), 0L);
        int need = J.intOr(spec.get("AdvUnlockItemNum"), 0);
        Map<String, Object> item = J.nodeOr(J.nodeOrEmpty(store.catalog().get("items"))
                .get(String.valueOf(itemId)));
        if (item == null) {
            throw new WireError(501, "Missing verified item definition for this exchange");
        }
        debitItem(player, itemId, need);
        storyOwn(player, (Integer) episode[0].get("adv_series_id"), advId)
                .put("watchable_status", WATCHABLE_ABLE);
        request.store().save(player);
        return J.map("watchable_status", WATCHABLE_ABLE,
                "adv_unlock_item_num", WireShop.itemNum(player, itemId));
    }

    /**
     * Python 版直接返回目录行数组（不套 {@code theme_list} 对象），每行按
     * {@code GetThemeListResponseInner} 编码。
     */
    private Object themeList(WireRequest request) {
        request.requirePlayer();
        return services.refs("school_idol_post_themes");
    }

    // ------------------------------------------------------------------
    // 剧情状态
    // ------------------------------------------------------------------

    /** 本账号在某一月的剧情记录；没有时补一条空记录。 */
    private static Map<String, Object> storyMonth(PlayerContext player, int series) {
        List<Map<String, Object>> records = player.rows("activity_records");
        for (Map<String, Object> row : records) {
            if (J.intOr(row.get("adv_series_id"), Integer.MIN_VALUE) == series) {
                return row;
            }
        }
        Map<String, Object> row = J.map("adv_series_id", series,
                "adv_info_list", new ArrayList<>());
        records.add(row);
        player.put("activity_records", records);
        return row;
    }

    /** 某一话在本账号记录里的行；没有时补一条。 */
    private static Map<String, Object> storyOwn(PlayerContext player, int series, int advId) {
        Map<String, Object> month = storyMonth(player, series);
        List<Map<String, Object>> rows = J.rowsOr(month.get("adv_info_list"));
        for (Map<String, Object> row : rows) {
            if (J.intOr(row.get("adv_data_id"), Integer.MIN_VALUE) == advId) {
                return row;
            }
        }
        Map<String, Object> row = J.map("adv_data_id", advId);
        rows.add(row);
        month.put("adv_info_list", rows);
        return row;
    }

    /** 目录里这一话所属的月份与章节；找不到时返回 {@code {null, null}}。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object>[] storyEpisode(int advId) {
        for (Map<String, Object> month : services.refs("story_months")) {
            for (Map<String, Object> row : J.rowsOr(month.get("adv_info_list"))) {
                if (J.intOr(row.get("adv_data_id"), Integer.MIN_VALUE) == advId) {
                    return new Map[] {month, row};
                }
            }
        }
        return new Map[] {null, null};
    }

    /** 这一话的解锁定义（{@code shop_catalog.adv_unlocks}）。 */
    private Map<String, Object> advSpec(int advId) {
        return J.nodeOr(J.nodeOrEmpty(store.catalog().get("adv_unlocks"))
                .get(String.valueOf(advId)));
    }

    /** Python {@code _watchable_status}：由前置话与解锁道具推出这一话的状态。 */
    private Integer watchableStatus(PlayerContext player, int advId,
                                    Map<Integer, Integer> memo) {
        if (memo.containsKey(advId)) {
            return memo.get(advId);
        }
        Map<String, Object> spec = advSpec(advId);
        if (spec == null) {
            memo.put(advId, null);
            return null;
        }
        int previous = J.intOr(spec.get("AdvUnlockConditionValue"), 0);
        if (previous != 0) {
            Integer status = watchableStatus(player, previous, memo);
            if (status == null || status != WATCHABLE_ABLE) {
                memo.put(advId, WATCHABLE_TARGET);
                return WATCHABLE_TARGET;
            }
        }
        int status;
        if (J.intOr(spec.get("AdvUnlockItemId"), 0) > 0
                && J.intOr(spec.get("AdvUnlockItemNum"), 0) > 0) {
            status = store.advUnlocked(player, advId) ? WATCHABLE_ABLE : WATCHABLE_LOCKED;
        } else {
            status = WATCHABLE_ABLE;
        }
        memo.put(advId, status);
        return status;
    }

    private void applyStoryUnlocks(List<Map<String, Object>> months, PlayerContext player) {
        Map<Integer, Integer> memo = new LinkedHashMap<>();
        for (Map<String, Object> month : months) {
            for (Map<String, Object> episode : J.rowsOr(month.get("adv_info_list"))) {
                Object raw = episode.get("adv_data_id");
                if (!(raw instanceof Integer advId)) {
                    continue;
                }
                Integer status = watchableStatus(player, advId, memo);
                if (status != null) {
                    episode.put("watchable_status", status);
                }
                Map<String, Object> spec = advSpec(advId);
                if (spec != null && J.intOr(spec.get("AdvUnlockItemId"), 0) > 0) {
                    episode.put("adv_unlock_item_num",
                            WireShop.itemNum(player, J.longOr(spec.get("AdvUnlockItemId"), 0L)));
                }
            }
        }
    }

    /**
     * 只下发本机资源包能渲染的月份与章节 —— Python {@code story_available}。
     *
     * <p>{@code labels} 为 null（没有解码资源目录）时不过滤。
     */
    static List<Map<String, Object>> storyAvailable(List<Map<String, Object>> months,
                                                    Set<String> labels) {
        if (labels == null) {
            return months;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> month : months) {
            String image = "image_record_monthly_" + J.str(month.get("adv_series_id"));
            if (!labels.contains(image)) {
                continue;
            }
            List<Map<String, Object>> episodes = new ArrayList<>();
            for (Map<String, Object> episode : J.rowsOr(month.get("adv_info_list"))) {
                String script = "story_main_" + J.str(episode.get("adv_data_id")) + ".txt";
                if (labels.contains(script)) {
                    episodes.add(episode);
                }
            }
            if (episodes.isEmpty()) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(month);
            copy.put("adv_info_list", episodes);
            result.add(copy);
        }
        return result;
    }

    private static void debitItem(PlayerContext player, long itemId, int quantity) {
        for (Map<String, Object> group : player.rows("Items")) {
            for (Map<String, Object> row : J.rowsOr(group.get("UserItemList"))) {
                if (J.longOr(row.get("ItemId"), Long.MIN_VALUE) == itemId) {
                    int have = J.intOr(row.get("ItemNum"), 0);
                    if (have < quantity) {
                        throw WireError.badRequest("Not enough items");
                    }
                    row.put("ItemNum", have - quantity);
                    return;
                }
            }
        }
        throw WireError.badRequest("Not enough items");
    }
}
