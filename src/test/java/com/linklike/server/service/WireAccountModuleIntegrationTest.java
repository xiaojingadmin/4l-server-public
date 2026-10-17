package com.linklike.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklike.server.config.ServerProperties;
import com.linklike.server.domain.Player;
import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireCodec;
import com.linklike.server.protocol.WireError;
import com.linklike.server.protocol.WireModels;
import com.linklike.server.resource.ClientDefaults;
import com.linklike.server.resource.ResourceCatalogLabels;
import com.linklike.server.wire.RouteSnapshot;
import com.linklike.server.wire.WireAccountModule;
import com.linklike.server.wire.WireCatalog;
import com.linklike.server.wire.WireGrants;
import com.linklike.server.wire.WireHandler;
import com.linklike.server.wire.WireRequest;
import com.linklike.server.wire.WireRouter;
import com.linklike.server.wire.WireServices;
import com.linklike.server.wire.WireShop;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 账号类路由的端到端（不含 HTTP）行为。 */
class WireAccountModuleIntegrationTest {

    private ObjectMapper mapper;
    private WireRouter router;
    private PlayerStore store;
    private PlayerContext player;
    private WireGrants grants;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        WireCodec codec = new WireCodec(new WireModels(mapper));
        ServerProperties config = new ServerProperties();
        ClientDefaults defaults = new ClientDefaults(mapper, config);
        WireShop shop = new WireShop(defaults);

        WireCatalog catalog = mock(WireCatalog.class);
        WireServices services = mock(WireServices.class);
        when(services.refs(anyString())).thenReturn(new ArrayList<>());
        when(services.served(anyString(), anyList())).thenReturn(new ArrayList<>());
        RefCatalogStore catalogs = mock(RefCatalogStore.class);
        store = mock(PlayerStore.class);
        ResourceCatalogLabels labels = mock(ResourceCatalogLabels.class);
        grants = mock(WireGrants.class);

        WireAccountModule module = new WireAccountModule(codec, shop, catalog, services,
                catalogs, store, labels, grants, defaults, config, mapper);
        router = new WireRouter(List.of(module), new RouteSnapshot(mapper));

        Player entity = new Player();
        entity.playerId = "P00000001";
        entity.playerName = "Tester";
        entity.jewelFree = 12;
        entity.jewelPaid = 3;
        player = new PlayerContext(entity, new LinkedHashMap<>());
    }

    @Test
    void clockSettingIsStoredAndAdvancesBothMissions() {
        Map<String, Object> result = call("/v1/home/set_clock_setting", J.map(
                "position_x", 10, "position_y", -20, "is_disp_on", true,
                "type_id", 1, "collor_id", 2));

        assertEquals(true, result.get("result"));
        Map<String, Object> clock = player.node("ClockSetting");
        assertNotNull(clock);
        assertEquals(10, clock.get("PositionX"));
        assertEquals(-20, clock.get("PositionY"));
        assertEquals(true, clock.get("IsDispOn"));
        assertNotNull(findMission(player.rowsSnapshot("beginner_missions"), 900302));
        assertNotNull(findMission(player.rowsSnapshot("beginner_missions"), 900303));
    }

    @Test
    void wallpaperSettingCreatesTheFirstCurrentWallpaper() {
        Map<String, Object> result = call("/v1/home/set_wallpaper_setting",
                J.map("wallpaper_setting_info", "{\"B\":[]}"));

        assertEquals(true, result.get("result"));
        List<Map<String, Object>> rows = player.rowsSnapshot("wallpaper_settings");
        assertEquals(1, rows.size());
        assertEquals(rows.get(0).get("d_home_wall_paper_settings_id"),
                player.raw("CurrentWallpaperSettingsId"));
        assertNotNull(findMission(player.rowsSnapshot("beginner_missions"), 900301));
    }

    @Test
    void myDesignCanBeRenamedAndDeleted() {
        Map<String, Object> design = new LinkedHashMap<>();
        design.put("DProfileMyDesignsId", "design-1");
        design.put("MyDesignName", "old");
        design.put("IsSetting", false);
        player.put("IconDesigns", new ArrayList<>(List.of(design)));

        call("/v1/profile/set_my_design_name",
                J.map("d_profile_my_designs_id", "design-1", "my_design_name", "new name"));
        assertEquals("new name", design.get("MyDesignName"));

        Map<String, Object> result = call("/v1/profile/delete_my_design",
                J.map("d_profile_my_designs_id", "design-1"));
        assertEquals(true, result.get("result"));
        assertTrue(player.rowsSnapshot("IconDesigns").isEmpty());
    }

    @Test
    void presentHistoryIsNewestFirstAndPaginated() {
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(J.map("DPresentBoxesId", "p1", "Num", 1,
                "GetTime", "2026-01-01T00:00:00Z"));
        history.add(J.map("DPresentBoxesId", "p2", "Num", 2,
                "GetTime", "2026-01-02T00:00:00Z"));
        player.put("present_history", history);

        Map<String, Object> first = call("/v1/present_box/get_history", J.map("page", 1));
        assertEquals(2, first.get("total_count"));
        List<Map<String, Object>> items = J.rowsOr(first.get("items"));
        assertEquals("p2", items.get(0).get("d_present_boxeses_id") != null
                ? items.get(0).get("d_present_boxeses_id")
                : items.get(0).get("d_present_boxes_id"));
        assertEquals("2026-01-02T00:00:00.000000Z", items.get(0).get("get_time"));

        Map<String, Object> second = call("/v1/present_box/get_history", J.map("page", 2));
        assertTrue(J.rowsOr(second.get("items")).isEmpty());
    }

    @Test
    void unimplementedPurchaseStillReturns501() {
        WireError error = assertThrows(WireError.class,
                () -> call("/v1/jewel_shop/set_purchase", J.map()));
        assertEquals(501, error.status());
    }

    @Test
    void chapterCategoryReturnsVerifiedEmptyShape() {
        Map<String, Object> result = call("/v1/chapter/category_info",
                J.map("category_id", 7));
        Map<String, Object> info = J.nodeOr(result.get("category_info"));
        assertNotNull(info);
        assertEquals(7, info.get("category_id"));
        assertEquals(0, info.get("current_point"));
        assertTrue(J.rowsOr(result.get("mission_list")).isEmpty());
    }

    @Test
    void muteAddsTheTargetPlayer() {
        Player other = new Player();
        other.playerId = "P00000002";
        other.playerName = "Other";
        PlayerContext target = new PlayerContext(other, new LinkedHashMap<>());
        when(store.loadById("P00000002")).thenReturn(target);

        Map<String, Object> result = call("/v1/profile/set_mute",
                J.map("player_id", "P00000002"));

        assertEquals(true, result.get("result"));
        assertTrue(player.scalarsOrEmpty("muted_players").contains("P00000002"));
    }

    @Test
    void birthdayCanOnlyBeSetOnce() {
        call("/v1/profile/set_birthday", J.map("birthday", "2000-01-02T03:04:05Z"));
        assertEquals("2000-01-02T03:04:05Z", player.raw("Birthday"));
        assertEquals(false, player.raw("IsBirthdayChangeable"));

        WireError error = assertThrows(WireError.class, () -> call(
                "/v1/profile/set_birthday", J.map("birthday", "2000-01-02T03:04:05Z")));
        assertEquals(400, error.status());
    }

    @Test
    void itemDetailFallsBackToTheStaticMasterItems() {
        assertFalse(grants.available());
        player.put("Items", new ArrayList<>(List.of(J.map(
                "UserItemList", List.of(J.map(
                        "UserItemId", "u-1", "ItemId", 3007001, "ItemNum", 5))))));

        Map<String, Object> result = call("/v1/user/item/get_detail",
                J.map("d_item_datas_id", "u-1"));

        assertEquals("ペタルコイン", result.get("name"));
        assertTrue(String.valueOf(result.get("description")).contains("コイン"));
        assertEquals(5, result.get("item_num"));
    }

    @Test
    void pointRewardsValidatesCurrentPoint() {
        call("/v1/chapter/get_point_rewards",
                J.map("chapter_id", 1, "current_point", 0));

        WireError error = assertThrows(WireError.class, () -> call(
                "/v1/chapter/get_point_rewards",
                J.map("chapter_id", 1, "current_point", "nope")));
        assertEquals(400, error.status());
    }

    @Test
    void presentHistoryHandlesLargePageWithoutOverflow() {
        player.put("present_history", new ArrayList<>(List.of(
                J.map("DPresentBoxesId", "p1", "Num", 1,
                        "GetTime", "2026-01-01T00:00:00Z"))));

        Map<String, Object> result = call("/v1/present_box/get_history",
                J.map("page", 2_000_000_000));

        assertTrue(J.rowsOr(result.get("items")).isEmpty());
    }

    private Map<String, Object> call(String path, Map<String, Object> body) {
        WireHandler handler = router.resolve(path);
        assertNotNull(handler, path + " 未注册");
        return handler.handle(new WireRequest(path, body, Map.of(), player, store, mapper,
                new ServerProperties()));
    }

    private static Map<String, Object> findMission(List<Map<String, Object>> groups, int id) {
        for (Map<String, Object> group : groups) {
            for (Map<String, Object> row : J.rowsOr(group.get("mission_list"))) {
                if (id == J.intOr(row.get("mission_id"), 0)) {
                    return row;
                }
            }
        }
        return null;
    }
}
