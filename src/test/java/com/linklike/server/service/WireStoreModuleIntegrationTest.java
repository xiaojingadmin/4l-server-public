package com.linklike.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.linklike.server.protocol.WireModels;
import com.linklike.server.resource.ClientDefaults;
import com.linklike.server.wire.RouteSnapshot;
import com.linklike.server.wire.WireHandler;
import com.linklike.server.wire.WireRequest;
import com.linklike.server.wire.WireRouter;
import com.linklike.server.wire.WireServices;
import com.linklike.server.wire.WireStore;
import com.linklike.server.wire.WireStoreModule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 商店 / 画廊 / 章节首页的端到端（不含 HTTP）行为。 */
class WireStoreModuleIntegrationTest {

    private ObjectMapper mapper;
    private WireRouter router;
    private PlayerStore store;
    private PlayerContext player;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        ServerProperties config = new ServerProperties();
        ClientDefaults defaults = new ClientDefaults(mapper, config);

        RefCatalogStore catalogs = mock(RefCatalogStore.class);
        when(catalogs.list(anyString())).thenReturn(new ArrayList<>());
        WireServices services = mock(WireServices.class);
        when(services.refs(anyString())).thenReturn(new ArrayList<>());
        when(services.served(anyString(), anyList())).thenReturn(new ArrayList<>());

        store = mock(PlayerStore.class);
        WireStoreModule module = new WireStoreModule(
                new WireStore(defaults, catalogs), services, config);
        router = new WireRouter(List.of(module), new RouteSnapshot(mapper));

        Player entity = new Player();
        entity.playerId = "P00000001";
        entity.playerName = "Tester";
        entity.stickerPointNum = 45;
        player = new PlayerContext(entity, new LinkedHashMap<>());
    }

    @Test
    void siscaListCarriesOwnAndBuyableCounts() {
        Map<String, Object> result = call("/v1/sisca_store/get_list", Map.of());

        List<Map<String, Object>> rows = J.rowsOr(result.get("item_list"));
        assertFalse(rows.isEmpty());
        Map<String, Object> first = find(rows, "sisca_store_datas_id", 1);
        // 商品 1 是剧情解锁型：需要 7 个解锁道具，账号一个都没有。
        assertEquals(7, first.get("buyable_num"));
        assertEquals(0, first.get("own_num"));

        // 商品 2 不限购，按 Python 的约定返回 -1。
        assertEquals(-1, find(rows, "sisca_store_datas_id", 2).get("buyable_num"));
    }

    @Test
    void itemExchangeListReportsRemainingAndLockState() {
        Map<String, Object> result = call("/v1/item_exchange/get_list_new", Map.of());

        List<Map<String, Object>> rows = J.rowsOr(result.get("item_exchange_list"));
        Map<String, Object> monthly = find(rows, "item_exchanges_id", 41);
        assertEquals(50, monthly.get("remaining_exchange_num"));
        assertEquals(false, monthly.get("is_lock"));

        Map<String, Object> gated = find(rows, "item_exchanges_id", 30604002);
        assertEquals(true, gated.get("is_lock"));
        assertEquals(false, result.get("is_convertible_limit_break_material"));
    }

    @Test
    void stickerListMarksOwnedStickersAsHaving() {
        player.put("Stickers", new ArrayList<>(List.of(J.map("StickersId", 90000002))));

        Map<String, Object> result = call("/v1/sticker_exchange/get_list", Map.of());

        assertEquals(45L, result.get("sticker_point_num"));
        List<Map<String, Object>> rows = J.rowsOr(result.get("sticker_exchange_list"));
        assertEquals(true, find(rows, "sticker_exchanges_id", 1).get("is_having"));
        assertEquals(false, find(rows, "sticker_exchanges_id", 2).get("is_having"));
    }

    @Test
    void chapterHomeServesTheAccountsCategories() {
        player.put("chapter_categories", new ArrayList<>(List.of(
                J.map("category_id", 3, "current_point", 12L, "max_point", 40L, "is_capped", false))));

        Map<String, Object> result = call("/v1/chapter/home", Map.of());

        assertEquals(0, result.get("chapter_id"));
        assertEquals(0L, J.nodeOrEmpty(result.get("rank_info")).get("current_point"));
        assertEquals(1, J.rowsOr(result.get("category_list")).size());
    }

    @Test
    void recommendListReportsTheConfiguredFollowLimit() {
        Map<String, Object> result = call("/v1/follow/recommend_list", Map.of());

        assertEquals(List.of(), result.get("RecommendInfoList"));
        assertEquals(1000L, result.get("MaxRecommendCount"));
    }

    @Test
    void galleryListIsEmptyWithoutAnImportedCatalog() {
        Map<String, Object> result = call("/v1/collection/get_gallary_list", Map.of());

        assertEquals(List.of(), result.get("gallary_info_list"));
    }

    @Test
    void petalListMergesOwnedCardLimitBreakTimes() {
        player.put("petal_exchanges", new ArrayList<>(List.of(J.map(
                "character_id", 1021,
                "limit_break_exchange_list", new ArrayList<>(List.of(J.map(
                        "card_series_id", 1031406,
                        "card_datas_id", 10314060,
                        "total_num", 3L,
                        "limit_break_times", 0L,
                        "item_num", 0L)))))));
        player.put("Cards", new ArrayList<>(List.of(J.map(
                "CardDatasId", 10314060, "LimitBreakTimes", 4))));

        Map<String, Object> result = call("/v1/petal_exchange/get_list", Map.of());

        // 目录未导入时不伪造分组，只下发账号自己的行。
        List<Map<String, Object>> groups = J.rowsOr(result.get("character_petal_exchange_list"));
        assertEquals(1, groups.size());
        assertEquals(0L, result.get("petal_coin_num"));
    }

    private Map<String, Object> call(String path, Map<String, Object> body) {
        WireHandler handler = router.resolve(path);
        assertNotNull(handler, path + " 未注册");
        Map<String, Object> result = asMap(handler.handle(new WireRequest(path, body, Map.of(), player,
                store, mapper, new ServerProperties())));
        assertNotNull(result, path + " 返回 null");
        return result;
    }

    private static Map<String, Object> find(List<Map<String, Object>> rows, String key, int ident) {
        for (Map<String, Object> row : rows) {
            if (J.intOr(row.get(key), -1) == ident) {
                return row;
            }
        }
        throw new AssertionError("缺少 " + key + "=" + ident);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
