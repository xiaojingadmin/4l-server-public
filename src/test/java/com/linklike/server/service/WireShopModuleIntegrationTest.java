package com.linklike.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.linklike.server.protocol.WireModels;
import com.linklike.server.resource.ClientDefaults;
import com.linklike.server.resource.ResourceCatalogLabels;
import com.linklike.server.wire.RouteSnapshot;
import com.linklike.server.wire.WireCardMint;
import com.linklike.server.wire.WireCatalog;
import com.linklike.server.wire.WireHandler;
import com.linklike.server.wire.WireRequest;
import com.linklike.server.wire.WireRouter;
import com.linklike.server.wire.WireServices;
import com.linklike.server.wire.WireShopModule;
import com.linklike.server.wire.WireStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 商店结算的行为，用随仓库分发的真实 5.1.0 目录（{@code shop_catalog.json}）。
 *
 * <p>覆盖：道具兑换的扣素材/加产物与限购、兑换回执的幂等与 409、突破素材转换、SIsCa 购买
 * 扣宝石、贴纸兑换扣点数、GP 奖品兑换（新卡与已有系列的重复素材分支）。
 */
class WireShopModuleIntegrationTest {

    private static final int UNLIMITED_EXCHANGE = 10601001;
    private static final int LIMITED_EXCHANGE = 10619002;
    private static final long PRODUCT_ITEM = 3012001L;
    private static final long MATERIAL_ITEM = 3006001L;
    private static final long CONVERT_MATERIAL = 4010400L;
    private static final int SISCA_PRODUCT = 2;
    private static final long SISCA_ITEM = 2004005L;
    private static final int STICKER_LISTING = 1;
    private static final int GP_PRIZE = 1;

    private ObjectMapper mapper;
    private WireRouter router;
    private PlayerStore store;
    private PlayerContext player;
    private ServerProperties config;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        config = new ServerProperties();
        ClientDefaults defaults = new ClientDefaults(mapper, config);
        WireModels models = new WireModels(mapper);
        RefCatalogStore catalogs = mock(RefCatalogStore.class);
        when(catalogs.list(anyString())).thenReturn(new ArrayList<>());

        WireServices services = new WireServices(catalogs,
                new ResourceCatalogLabels(config, mapper));
        WireStore wireStore = new WireStore(defaults, catalogs);
        WireCatalog catalog = new WireCatalog(defaults, catalogs, models);
        WireCardMint mint = new WireCardMint(defaults, catalogs, models);
        router = new WireRouter(
                List.of(new WireShopModule(wireStore, mint, catalog, services)),
                new RouteSnapshot(mapper));

        store = mock(PlayerStore.class);
        doAnswer(call -> null).when(store).save(any());

        Player entity = new Player();
        entity.playerId = "P00000001";
        entity.playerName = "Tester";
        entity.jewelFree = 3000L;
        entity.jewelPaid = 500L;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("Items", new ArrayList<>(List.of(J.map("ItemCategory", 1,
                "UserItemList", new ArrayList<>(List.of(
                        item("i-a", MATERIAL_ITEM, 100),
                        item("i-b", CONVERT_MATERIAL, 60),
                        item("i-c", 3011001L, 400)))))));
        state.put("Cards", new ArrayList<>());
        state.put("Stickers", new ArrayList<>());
        state.put("StickerPointNum", 50L);
        player = new PlayerContext(entity, state);
    }

    // ------------------------------------------------------------------
    // 道具兑换
    // ------------------------------------------------------------------

    @Test
    void unlimitedItemExchangeDebitsMaterialAndCreditsProduct() {
        Map<String, Object> result = call("/v1/item_exchange/set_purchase",
                Map.of("item_exchanges_id", UNLIMITED_EXCHANGE, "num", 2), "key-1");

        assertEquals(2L, J.longOr(result.get("user_product_item_num"), -1));
        assertEquals(80L, J.longOr(result.get("user_material_item_num"), -1));
        assertEquals(80L, itemNum(MATERIAL_ITEM));
        assertEquals(2L, itemNum(PRODUCT_ITEM));
    }

    @Test
    void itemExchangeRequiresAnIdempotencyKey() {
        WireError error = assertThrows(WireError.class, () -> call(
                "/v1/item_exchange/set_purchase",
                Map.of("item_exchanges_id", UNLIMITED_EXCHANGE, "num", 1), null));

        assertEquals(400, error.status());
    }

    @Test
    void sameIdempotencyKeyReplaysTheOriginalReceipt() {
        Map<String, Object> body = Map.of("item_exchanges_id", UNLIMITED_EXCHANGE, "num", 1);
        Map<String, Object> first = call("/v1/item_exchange/set_purchase", body, "key-1");
        Map<String, Object> second = call("/v1/item_exchange/set_purchase", body, "key-1");

        assertEquals(first, second);
        // 重放不再扣一次素材。
        assertEquals(90L, itemNum(MATERIAL_ITEM));
    }

    @Test
    void sameIdempotencyKeyWithDifferentArgumentsConflicts() {
        call("/v1/item_exchange/set_purchase",
                Map.of("item_exchanges_id", UNLIMITED_EXCHANGE, "num", 1), "key-1");

        WireError error = assertThrows(WireError.class, () -> call(
                "/v1/item_exchange/set_purchase",
                Map.of("item_exchanges_id", UNLIMITED_EXCHANGE, "num", 2), "key-1"));

        assertEquals(409, error.status());
        assertEquals(90L, itemNum(MATERIAL_ITEM));
    }

    @Test
    void limitedExchangeDebitsTheMonthlyCapAndRefusesToOverspendIt() {
        // 条目 10619002 每月 75 次，素材是 3012001。
        player.put("Items", new ArrayList<>(List.of(J.map("ItemCategory", 3,
                "UserItemList", new ArrayList<>(List.of(item("i-a", 3012001L, 100)))))));
        call("/v1/item_exchange/set_purchase",
                Map.of("item_exchanges_id", LIMITED_EXCHANGE, "num", 70), "key-a");
        WireError error = assertThrows(WireError.class, () -> call(
                "/v1/item_exchange/set_purchase",
                Map.of("item_exchanges_id", LIMITED_EXCHANGE, "num", 10), "key-b"));

        assertEquals(400, error.status());
        assertEquals("Exchange limit reached", error.getMessage());

        List<Map<String, Object>> states = J.rowsOr(player.raw("shop_period_states"));
        assertEquals(1, states.size());
        assertEquals(5L, states.get(0).get("remaining"));
    }

    @Test
    void failedSettlementLeavesTheAccountUntouched() {
        player.put("Items", new ArrayList<>(List.of(J.map("ItemCategory", 1,
                "UserItemList", new ArrayList<>(List.of(item("i-a", MATERIAL_ITEM, 1)))))));

        assertThrows(WireError.class, () -> call("/v1/item_exchange/set_purchase",
                Map.of("item_exchanges_id", UNLIMITED_EXCHANGE, "num", 5), "key-1"));

        assertEquals(1L, itemNum(MATERIAL_ITEM));
        assertEquals(0L, itemNum(PRODUCT_ITEM));
        assertTrue(J.rowsOr(player.raw("shop_receipts")).isEmpty());
    }

    @Test
    void missingListingIsNotFound() {
        WireError error = assertThrows(WireError.class, () -> call(
                "/v1/item_exchange/set_purchase",
                Map.of("item_exchanges_id", 99999999, "num", 1), "key-1"));

        assertEquals(404, error.status());
    }

    // ------------------------------------------------------------------
    // 突破素材转换
    // ------------------------------------------------------------------

    @Test
    void limitBreakConversionTradesFullBatchesOnly() {
        // 稀有度 4 的 4010400 有 60 个，25 个换 1 个 3012003；50 个成两批，余 10 个留下。
        Map<String, Object> result = call(
                "/v1/item_exchange/set_limit_break_material_convert", Map.of(), "conv-1");

        List<Map<String, Object>> granted = J.rowsOr(result.get("get_item_list"));
        assertEquals(1, granted.size());
        assertEquals(3012003L, granted.get(0).get("item_id"));
        assertEquals(2L, granted.get(0).get("get_num"));
        assertEquals(10L, itemNum(CONVERT_MATERIAL));
        assertEquals(2L, itemNum(3012003L));
    }

    @Test
    void limitBreakConversionReplaysFinishedReceipt() {
        Map<String, Object> first = call(
                "/v1/item_exchange/set_limit_break_material_convert", Map.of(), "conv-1");
        Map<String, Object> second = call(
                "/v1/item_exchange/set_limit_break_material_convert", Map.of(), "conv-1");

        assertEquals(first, second);
        assertEquals(10L, itemNum(CONVERT_MATERIAL));
    }

    // ------------------------------------------------------------------
    // SIsCa 商店
    // ------------------------------------------------------------------

    @Test
    void siscaPurchaseDebitsJewelsAndCreditsTheItem() {
        Map<String, Object> result = call("/v1/sisca_store/set_purchase",
                Map.of("sisca_store_datas_id", SISCA_PRODUCT, "purchase_num", 1), "sisca-1");

        // 商品 2 要 2000 宝石：先用 3000 免费宝石，付费余额不动。
        Map<String, Object> info = J.nodeOrEmpty(result.get("sisca_info"));
        assertEquals(1000L, info.get("free"));
        assertEquals(500L, info.get("paid"));
        assertEquals(1L, itemNum(SISCA_ITEM));
        assertEquals(1000L, player.entity().jewelFree);
    }

    @Test
    void siscaPurchaseFallsBackToPaidBalance() {
        player.entity().jewelFree = 1000L;
        player.entity().jewelPaid = 1500L;

        Map<String, Object> result = call("/v1/sisca_store/set_purchase",
                Map.of("sisca_store_datas_id", SISCA_PRODUCT, "purchase_num", 1), "sisca-1");

        Map<String, Object> info = J.nodeOrEmpty(result.get("sisca_info"));
        assertEquals(0L, info.get("free"));
        assertEquals(500L, info.get("paid"));
        assertEquals(0L, player.entity().jewelFree);
        assertEquals(500L, player.entity().jewelPaid);
    }

    @Test
    void siscaPurchaseRollsBackWhenJewelsAreShort() {
        player.entity().jewelFree = 10L;
        player.entity().jewelPaid = 10L;

        WireError error = assertThrows(WireError.class, () -> call(
                "/v1/sisca_store/set_purchase",
                Map.of("sisca_store_datas_id", SISCA_PRODUCT, "purchase_num", 1), "sisca-1"));

        assertEquals(400, error.status());
        assertEquals(10L, player.entity().jewelFree);
        assertEquals(0L, itemNum(SISCA_ITEM));
    }

    // ------------------------------------------------------------------
    // 贴纸兑换 / GP 奖品
    // ------------------------------------------------------------------

    @Test
    void stickerExchangeSpendsPointsAndMarksTheStickerHaving() {
        Map<String, Object> result = call("/v1/sticker_exchange/set_purchase",
                Map.of("sticker_exchanges_id", STICKER_LISTING), "sticker-1");

        assertEquals(30L, result.get("sticker_point_num"));
        List<Map<String, Object>> stickers = J.rowsOr(player.raw("Stickers"));
        assertEquals(1, stickers.size());
        assertEquals(90000002, stickers.get(0).get("StickersId"));
        assertEquals(true, stickers.get(0).get("IsOwned"));
        List<Map<String, Object>> states = J.rowsOr(player.raw("sticker_exchange_states"));
        assertEquals(true, states.get(0).get("is_having"));
    }

    @Test
    void stickerExchangeRefusesWhenPointsAreShort() {
        player.put("StickerPointNum", 5L);

        WireError error = assertThrows(WireError.class, () -> call(
                "/v1/sticker_exchange/set_purchase",
                Map.of("sticker_exchanges_id", STICKER_LISTING), "sticker-1"));

        assertEquals("Not enough sticker points", error.getMessage());
        assertEquals(5L, player.raw("StickerPointNum"));
    }

    @Test
    void gpPrizeExchangeMintsTheCardWhenTheSeriesIsNew() {
        Map<String, Object> result = call("/v1/gp_prize_exchange/set_purchase",
                Map.of("gp_prize_exchanges_id", GP_PRIZE), "gp-1");

        assertEquals(1, result.get("gp_prize_ticket_id"));
        assertEquals(200L, result.get("user_ticket_num"));
        assertEquals(1, J.rowsOr(player.raw("Cards")).size());
        assertEquals(10314060L,
                J.longOr(J.rowsOr(player.raw("Cards")).get(0).get("CardDatasId"), -1));
    }

    @Test
    void gpPrizeExchangeGrantsDuplicatesWhenTheSeriesIsOwned() {
        player.put("Cards", new ArrayList<>(List.of(J.map(
                "DCardDatasId", "owned", "CardDatasId", 10314060, "CharacterId", 1031))));

        Map<String, Object> result = call("/v1/gp_prize_exchange/set_purchase",
                Map.of("gp_prize_exchanges_id", GP_PRIZE), "gp-1");

        assertEquals(200L, result.get("user_ticket_num"));
        // 已有同系列：不发第二张，改发 10 个 4031406 与 3 枚花瓣币。
        assertEquals(1, J.rowsOr(player.raw("Cards")).size());
        assertEquals(10L, itemNum(4031406L));
        assertEquals(3L, player.raw("PetalCoinNum"));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private Map<String, Object> call(String path, Map<String, Object> body, String idempotency) {
        WireHandler handler = router.resolve(path);
        assertNotNull(handler, path + " 未注册");
        Map<String, String> headers = idempotency == null
                ? Map.of() : Map.of("x-idempotency-key", idempotency);
        Map<String, Object> result = asMap(handler.handle(new WireRequest(
                path, body, headers, player, store, mapper, config)));
        assertNotNull(result, path + " 返回 null");
        return result;
    }

    private long itemNum(long ident) {
        for (Map<String, Object> group : J.rowsOr(player.raw("Items"))) {
            for (Map<String, Object> row : J.rowsOr(group.get("UserItemList"))) {
                if (J.longOr(row.get("ItemId"), Long.MIN_VALUE) == ident) {
                    return J.longOr(row.get("ItemNum"), 0L);
                }
            }
        }
        return 0L;
    }

    private static Map<String, Object> item(String userId, long ident, int num) {
        return J.map("UserItemId", userId, "ItemId", ident, "ItemNum", num);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
