package com.linklike.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklike.server.config.ServerProperties;
import com.linklike.server.domain.Player;
import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireError;
import com.linklike.server.resource.ClientDefaults;
import com.linklike.server.resource.ResourceCatalogLabels;
import com.linklike.server.wire.RouteSnapshot;
import com.linklike.server.wire.WireHandler;
import com.linklike.server.wire.WireRequest;
import com.linklike.server.wire.WireRouter;
import com.linklike.server.wire.WireServices;
import com.linklike.server.wire.WireShop;
import com.linklike.server.wire.WireStore;
import com.linklike.server.wire.WireStoryModule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code /v1/activity_record/*} 的状态推导。
 *
 * <p>解锁链取自随仓库分发的 {@code shop_catalog.adv_unlocks}（10200101 → 10200102），
 * 月份 / 章节来自 mocked {@code story_months}，账号侧只看 {@code activity_records} 与道具。
 */
class WireStoryModuleIntegrationTest {

    private static final int SERIES = 102001;
    private static final int FIRST_ADV = 10200101;
    private static final int SECOND_ADV = 10200102;
    private static final long UNLOCK_ITEM = 1006001L;

    private ObjectMapper mapper;
    private WireRouter router;
    private PlayerStore store;
    private PlayerContext player;
    private RefCatalogStore refs;
    private ResourceCatalogLabels labels;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        ServerProperties config = new ServerProperties();
        ClientDefaults defaults = new ClientDefaults(mapper, config);

        List<Map<String, Object>> episodes = new ArrayList<>();
        episodes.add(J.map("adv_data_id", FIRST_ADV,
                "watch_reward_list", new ArrayList<>(List.of(
                        J.map("ItemId", UNLOCK_ITEM, "Num", 2)))));
        episodes.add(J.map("adv_data_id", SECOND_ADV));
        List<Map<String, Object>> months = new ArrayList<>();
        months.add(J.map("adv_series_id", SERIES, "is_monthly_not_watched", true,
                "is_current_season", true, "is_not_displayed_new_part", true,
                "adv_info_list", episodes));

        refs = mock(RefCatalogStore.class);
        when(refs.list(anyString())).thenReturn(new ArrayList<>(months));
        when(refs.get(anyString(), any())).thenReturn(null);

        labels = mock(ResourceCatalogLabels.class);
        when(labels.current()).thenReturn(null);

        WireServices services = new WireServices(refs, labels);
        WireStore wireStore = new WireStore(defaults, refs);
        WireShop shop = new WireShop(defaults);
        WireStoryModule module = new WireStoryModule(services, wireStore, shop);
        router = new WireRouter(List.of(module), new RouteSnapshot(mapper));

        store = mock(PlayerStore.class);
        doAnswer(call -> null).when(store).save(any());

        Player entity = new Player();
        entity.playerId = "P00000001";
        entity.playerName = "Tester";
        player = new PlayerContext(entity, new LinkedHashMap<>());
    }

    @Test
    void topDerivesLockAndTargetFromTheUnlockChain() {
        Map<String, Object> result = call("/v1/activity_record/get_top", Map.of());

        Map<String, Object> month = month(result);
        assertEquals(true, month.get("is_monthly_not_watched"));
        assertEquals(6, episode(month, FIRST_ADV).get("watchable_status"));
        // 前置话还不能看，后一话只能是「目标」。
        assertEquals(7, episode(month, SECOND_ADV).get("watchable_status"));
        assertEquals(0, J.intOr(episode(month, FIRST_ADV).get("adv_unlock_item_num"), -1));
    }

    @Test
    void topCountsOwnedUnlockItems() {
        giveItem(3);

        Map<String, Object> month = month(call("/v1/activity_record/get_top", Map.of()));

        assertEquals(6, episode(month, FIRST_ADV).get("watchable_status"));
        assertEquals(3, J.intOr(episode(month, FIRST_ADV).get("adv_unlock_item_num"), -1));
    }

    @Test
    void unlockingSpendsOneItemAndOpensTheNextEpisode() {
        giveItem(2);

        Map<String, Object> result = call("/v1/activity_record/set_watchable_status",
                Map.of("adv_data_id", FIRST_ADV));

        assertEquals(1, result.get("watchable_status"));
        assertEquals(1, J.intOr(result.get("adv_unlock_item_num"), -1));
        assertEquals(1, itemNum());

        Map<String, Object> month = month(call("/v1/activity_record/get_top", Map.of()));
        assertEquals(1, episode(month, FIRST_ADV).get("watchable_status"));
        // 前置话已可看，于是后一话从「目标」变成「锁定」（道具还不够）。
        assertEquals(6, episode(month, SECOND_ADV).get("watchable_status"));
    }

    @Test
    void unlockingWithoutTheItemFails() {
        WireError error = assertThrows(WireError.class, () -> call(
                "/v1/activity_record/set_watchable_status", Map.of("adv_data_id", FIRST_ADV)));

        assertEquals(400, error.status());
    }

    @Test
    void unlockingTwiceIsRejected() {
        giveItem(3);
        call("/v1/activity_record/set_watchable_status", Map.of("adv_data_id", FIRST_ADV));

        WireError error = assertThrows(WireError.class, () -> call(
                "/v1/activity_record/set_watchable_status", Map.of("adv_data_id", FIRST_ADV)));

        assertEquals(400, error.status());
    }

    @Test
    void watchingAnUnlockedEpisodeReturnsItsRewardsAndClearsTheBadge() {
        giveItem(1);
        call("/v1/activity_record/set_watchable_status", Map.of("adv_data_id", FIRST_ADV));

        Map<String, Object> result = call("/v1/activity_record/play_adv_data",
                Map.of("adv_data_id", FIRST_ADV));

        assertEquals(1, J.rowsOr(result.get("watch_reward_list")).size());
        assertEquals(false, ownEpisode(FIRST_ADV).get("is_not_watched"));
    }

    @Test
    void watchingALockedEpisodeIsRejected() {
        WireError error = assertThrows(WireError.class, () -> call(
                "/v1/activity_record/play_adv_data", Map.of("adv_data_id", FIRST_ADV)));

        assertEquals(400, error.status());
    }

    @Test
    void watchingAnEpisodeWithoutAnUnlockSpecReturnsNoRewards() {
        // 主数据里没有这条 adv_unlocks（Python 的 adv_spec 返回 None）时，
        // _watchable_status 返回 None，play_adv_data 不算锁定、也不抛错，
        // 只回一个空的 watch_reward_list。
        Map<String, Object> result = call("/v1/activity_record/play_adv_data",
                Map.of("adv_data_id", 99999999));

        assertEquals(List.of(), J.rowsOr(result.get("watch_reward_list")));
        assertTrue(player.rows("activity_records").isEmpty());
    }

    @Test
    void notifyMonthDisplayedCreatesTheAccountRow() {
        call("/v1/activity_record/notify_month_displayed", Map.of("adv_series_id", SERIES));

        Map<String, Object> row = player.rows("activity_records").get(0);
        assertEquals(SERIES, row.get("adv_series_id"));
        assertEquals(false, row.get("is_not_displayed_new_part"));
    }

    @Test
    void monthsAndEpisodesWithoutResourcesAreWithheld() {
        when(labels.current()).thenReturn(Set.of(
                "image_record_monthly_" + SERIES, "story_main_" + FIRST_ADV + ".txt"));

        Map<String, Object> month = month(call("/v1/activity_record/get_top", Map.of()));

        List<Map<String, Object>> episodes = J.rowsOr(month.get("adv_info_list"));
        assertEquals(1, episodes.size());
        assertEquals(FIRST_ADV, episodes.get(0).get("adv_data_id"));
    }

    @Test
    void aMonthWithoutItsImageIsWithheldEntirely() {
        when(labels.current()).thenReturn(Set.of("story_main_" + FIRST_ADV + ".txt"));

        Map<String, Object> result = call("/v1/activity_record/get_top", Map.of());

        assertTrue(J.rowsOr(result.get("activity_record_monthly_info_list")).isEmpty());
    }

    @Test
    void themeListServesTheCatalog() {
        when(refs.list("school_idol_post_themes")).thenReturn(new ArrayList<>(List.of(
                J.map("school_idol_post_themes_id", 1, "name", "Theme"))));

        Object result = router.resolve("/v1/webview/school_idol_connect_post/get_theme_list")
                .handle(new WireRequest("/v1/webview/school_idol_connect_post/get_theme_list",
                        Map.of(), Map.of(), player, store, mapper, new ServerProperties()));

        // Python 版返回的就是数组本身，不套 theme_list 键。
        assertEquals(1, J.rowsOr(result).size());
    }

    @Test
    void setWatchableStatusOnAnUnknownAdventureIsRejected() {
        WireError error = assertThrows(WireError.class, () -> call(
                "/v1/activity_record/set_watchable_status", Map.of("adv_data_id", 99999999)));

        assertEquals(400, error.status());
    }

    // ------------------------------------------------------------------

    private void giveItem(int num) {
        player.put("Items", new ArrayList<>(List.of(J.map("ItemCategory", 3,
                "UserItemList", new ArrayList<>(List.of(
                        J.map("ItemId", UNLOCK_ITEM, "ItemNum", num)))))));
    }

    private long itemNum() {
        return WireShop.itemNum(player, UNLOCK_ITEM);
    }

    private Map<String, Object> month(Map<String, Object> result) {
        List<Map<String, Object>> months = J.rowsOr(result.get("activity_record_monthly_info_list"));
        assertEquals(1, months.size());
        return months.get(0);
    }

    private static Map<String, Object> episode(Map<String, Object> month, int advId) {
        for (Map<String, Object> row : J.rowsOr(month.get("adv_info_list"))) {
            if (J.intOr(row.get("adv_data_id"), -1) == advId) {
                return row;
            }
        }
        throw new AssertionError("缺少 adv_data_id=" + advId);
    }

    private Map<String, Object> ownEpisode(int advId) {
        for (Map<String, Object> month : player.rows("activity_records")) {
            for (Map<String, Object> row : J.rowsOr(month.get("adv_info_list"))) {
                if (J.intOr(row.get("adv_data_id"), -1) == advId) {
                    return row;
                }
            }
        }
        return null;
    }

    private Map<String, Object> call(String path, Map<String, Object> body) {
        WireHandler handler = router.resolve(path);
        assertNotNull(handler, path + " 未注册");
        Object result = handler.handle(new WireRequest(path, body, Map.of(), player, store,
                mapper, new ServerProperties()));
        assertNotNull(result, path + " 返回 null");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        return map;
    }
}
