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
import com.linklike.server.protocol.WireModels;
import com.linklike.server.resource.ClientDefaults;
import com.linklike.server.resource.ResourceCatalogLabels;
import com.linklike.server.wire.RouteSnapshot;
import com.linklike.server.wire.WireCardMint;
import com.linklike.server.wire.WireGachaModule;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ガチャ的端到端（不含 HTTP）行为。
 *
 * <p>卡池、概率、价格与交换率都用随仓库分发的真实抽取目录，因此这里的抽取结果、扣费
 * 和回执形状都是可核对的；只把数据库落库换成 mock。
 */
class WireGachaModuleIntegrationTest {

    /** 目录里一直有效的标准池（含券、含有偿单抽）。 */
    private static final long POOL = 1050316L;
    private static final long TICKET_ITEM = 3002001L;

    private ObjectMapper mapper;
    private WireRouter router;
    private PlayerStore store;
    private PlayerContext player;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        ServerProperties config = new ServerProperties();
        ClientDefaults defaults = new ClientDefaults(mapper, config);
        WireModels models = new WireModels(mapper);

        RefCatalogStore refs = mock(RefCatalogStore.class);
        when(refs.list(anyString())).thenReturn(new ArrayList<>());
        when(refs.get(anyString(), any())).thenReturn(null);

        WireServices services = new WireServices(refs,
                new ResourceCatalogLabels(config, mapper));
        WireCardMint mint = new WireCardMint(defaults, refs, models);
        WireShop shop = new WireShop(defaults);
        router = new WireRouter(
                List.of(new WireGachaModule(services, mint, shop, defaults)),
                new RouteSnapshot(mapper));

        store = mock(PlayerStore.class);
        doAnswer(call -> null).when(store).save(any());

        Player entity = new Player();
        entity.playerId = "P00000001";
        entity.playerName = "Tester";
        entity.jewelFree = 5000;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("Cards", new ArrayList<>());
        state.put("Items", new ArrayList<>(List.of(J.map(
                "ItemCategory", 0,
                "UserItemList", new ArrayList<>(List.of(J.map(
                        "UserItemId", "ticket-1", "ItemId", TICKET_ITEM,
                        "ItemType", 1, "Rarity", 1, "ItemNum", 3,
                        "ResourceFileName", "")))))));
        player = new PlayerContext(entity, state);
    }

    // ------------------------------------------------------------------
    // 读接口
    // ------------------------------------------------------------------

    @Test
    void seriesListServesTheCatalogPoolsWithAccountLedger() {
        Map<String, Object> result = call("/v1/gacha/get_series_list", J.map());

        List<Map<String, Object>> pools = J.rowsOr(result.get("gacha_series_list"));
        assertFalse(pools.isEmpty());
        Map<String, Object> first = pools.stream()
                .filter(row -> Long.valueOf(POOL).equals(
                        ((Number) row.get("gacha_series_id")).longValue()))
                .findFirst().orElseThrow();
        assertEquals(3, ((Number) first.get("user_single_ticket_num")).intValue());
        assertEquals(true, first.get("is_single_enable"));
        assertTrue(((Number) first.get("remaining_limited_paid_gacha_count")).intValue() > 0);
        assertNotNull(result.get("select_ticket_series_list"));
    }

    @Test
    void lotteryChanceMirrorsTheDrawGroups() {
        Map<String, Object> result = call("/v1/gacha/get_lottery_chance",
                J.map("gacha_series_id", POOL));

        List<Map<String, Object>> probabilities = J.rowsOr(result.get("gacha_prob_list"));
        assertFalse(probabilities.isEmpty());
        assertEquals("1-9", probabilities.get(0).get("title"));
        assertFalse(J.rowsOr(probabilities.get(0).get("gacha_prob_card_list")).isEmpty());
        assertFalse(J.rowsOr(result.get("gacha_bonus_prob_list")).isEmpty());
    }

    @Test
    void lotteryChanceRejectsAnUnknownPool() {
        WireError error = assertThrows(WireError.class, () ->
                call("/v1/gacha/get_lottery_chance", J.map("gacha_series_id", 9999999)));
        assertEquals(404, error.status());
    }

    @Test
    void detailServesThePoolNotice() {
        Map<String, Object> result = call("/v1/webview/gacha/get_detail",
                J.map("gacha_series_id", POOL));

        assertEquals("2025/H2 STANDARD COLLECTION", result.get("title"));
        assertFalse(J.rowsOr(result.get("rates")).isEmpty());
    }

    @Test
    void guaranteePointListFlagsOwnedSeries() {
        Map<String, Object> result = call("/v1/gacha/get_guarantee_point_list",
                J.map("exchange_point_id", POOL, "gacha_series_id", POOL));

        List<Map<String, Object>> items = J.rowsOr(result.get("exchange_item_list"));
        assertFalse(items.isEmpty());
        assertEquals(-1, ((Number) items.get(0).get("remaining_exchange_num")).intValue());
        assertEquals(false, items.get(0).get("is_having"));
    }

    @Test
    void guaranteePointListRejectsAMismatchedExchangePoint() {
        WireError error = assertThrows(WireError.class, () ->
                call("/v1/gacha/get_guarantee_point_list",
                        J.map("exchange_point_id", 1, "gacha_series_id", POOL)));
        assertEquals(404, error.status());
    }

    @Test
    void historyIsEmptyBeforeAnyDraw() {
        Map<String, Object> result = call("/v1/gacha/get_history", J.map());

        assertTrue(J.rowsOr(result.get("gacha_result_history_list")).isEmpty());
    }

    // ------------------------------------------------------------------
    // 抽卡
    // ------------------------------------------------------------------

    @Test
    void singlePurchaseSpendsJewelsAndMintsACard() {
        Map<String, Object> result = call("/v1/gacha/set_purchase",
                J.map("gacha_series_id", POOL, "gacha_set_purchase_type", 1),
                Map.of("x-idempotency-key", "key-1"));

        List<Map<String, Object>> draws = J.rowsOr(result.get("gacha_result_list"));
        assertEquals(1, draws.size());
        assertEquals(true, draws.get(0).get("is_new"));
        assertEquals(4800L, player.entity().jewelFree);
        assertEquals(1, player.rowsSnapshot("Cards").size());

        Map<?, ?> points = (Map<?, ?>) result.get("gacha_guarantee_point");
        assertEquals(0L, ((Number) points.get("user_guarantee_point_num_before")).longValue());
        assertEquals(1L, ((Number) points.get("user_guarantee_point_num_after")).longValue());
        assertEquals(1, player.rowsSnapshot("gacha_points").size());
        assertEquals(1, player.rowsSnapshot("gacha_receipts").size());
    }

    @Test
    void consecutivePurchaseDrawsTenAndDeducesTheBundlePrice() {
        Map<String, Object> result = call("/v1/gacha/set_purchase",
                J.map("gacha_series_id", POOL, "gacha_set_purchase_type", 2),
                Map.of("x-idempotency-key", "key-ten"));

        assertEquals(10, J.rowsOr(result.get("gacha_result_list")).size());
        assertEquals(3000L, player.entity().jewelFree);
        List<Map<String, Object>> points = player.rowsSnapshot("gacha_points");
        assertEquals(1, points.size());
        assertEquals(10L, ((Number) points.get(0).get("num")).longValue());
        assertEquals(10, ((Number) player.rowsSnapshot("gacha_states").get(0)
                .get("exec_count")).intValue());
    }

    @Test
    void duplicateCardsCoinInsteadOfMintingTwice() {
        call("/v1/gacha/set_purchase",
                J.map("gacha_series_id", POOL, "gacha_set_purchase_type", 2),
                Map.of("x-idempotency-key", "key-a"));
        int cardsAfterFirst = player.rowsSnapshot("Cards").size();

        Map<String, Object> result = call("/v1/gacha/set_purchase",
                J.map("gacha_series_id", POOL, "gacha_set_purchase_type", 2),
                Map.of("x-idempotency-key", "key-b"));

        List<Map<String, Object>> draws = J.rowsOr(result.get("gacha_result_list"));
        assertEquals(10, draws.size());
        long newCards = draws.stream().filter(row -> Boolean.TRUE.equals(row.get("is_new")))
                .count();
        assertEquals(cardsAfterFirst + newCards, player.rowsSnapshot("Cards").size());
        assertTrue(player.longOf("PetalCoinNum", 0) >= 0);
    }

    @Test
    void purchaseWithoutIdempotencyKeyIsRejected() {
        WireError error = assertThrows(WireError.class, () ->
                call("/v1/gacha/set_purchase",
                        J.map("gacha_series_id", POOL, "gacha_set_purchase_type", 1)));
        assertEquals(400, error.status());
        assertEquals(5000L, player.entity().jewelFree);
    }

    @Test
    void replayingTheSameKeyReplaysTheReceipt() {
        Map<String, Object> first = call("/v1/gacha/set_purchase",
                J.map("gacha_series_id", POOL, "gacha_set_purchase_type", 1),
                Map.of("x-idempotency-key", "same"));

        Map<String, Object> second = call("/v1/gacha/set_purchase",
                J.map("gacha_series_id", POOL, "gacha_set_purchase_type", 1),
                Map.of("x-idempotency-key", "same"));

        assertEquals(J.rowsOr(first.get("gacha_result_list")).size(),
                J.rowsOr(second.get("gacha_result_list")).size());
        assertEquals(4800L, player.entity().jewelFree);
        assertEquals(1, player.rowsSnapshot("gacha_receipts").size());
    }

    @Test
    void reusingAKeyWithDifferentParametersConflicts() {
        call("/v1/gacha/set_purchase",
                J.map("gacha_series_id", POOL, "gacha_set_purchase_type", 1),
                Map.of("x-idempotency-key", "conflict"));

        WireError error = assertThrows(WireError.class, () ->
                call("/v1/gacha/set_purchase",
                        J.map("gacha_series_id", POOL, "gacha_set_purchase_type", 2),
                        Map.of("x-idempotency-key", "conflict")));
        assertEquals(409, error.status());
        assertEquals(4800L, player.entity().jewelFree);
    }

    @Test
    void purchaseFailsWithoutFundsAndLeavesNoTrace() {
        player.entity().jewelFree = 10;

        WireError error = assertThrows(WireError.class, () ->
                call("/v1/gacha/set_purchase",
                        J.map("gacha_series_id", POOL, "gacha_set_purchase_type", 1),
                        Map.of("x-idempotency-key", "poor")));
        assertEquals(400, error.status());
        assertEquals(10L, player.entity().jewelFree);
        assertTrue(player.rowsSnapshot("Cards").isEmpty());
        assertTrue(player.rowsSnapshot("gacha_receipts").isEmpty());
    }

    @Test
    void ticketPurchaseConsumesTicketsInsteadOfJewels() {
        Map<String, Object> result = call("/v1/gacha/set_purchase",
                J.map("gacha_series_id", POOL, "gacha_set_purchase_type", 4),
                Map.of("x-idempotency-key", "ticket"));

        assertEquals(1, J.rowsOr(result.get("gacha_result_list")).size());
        assertEquals(2, ((Number) result.get("user_single_ticket_num")).intValue());
        assertEquals(5000L, player.entity().jewelFree);
        assertEquals(2, WireShop.itemNum(player, TICKET_ITEM));
    }

    @Test
    void ticketPurchaseWithoutTicketsIsRejected() {
        player.put("Items", new ArrayList<>());

        WireError error = assertThrows(WireError.class, () ->
                call("/v1/gacha/set_purchase",
                        J.map("gacha_series_id", POOL, "gacha_set_purchase_type", 4),
                        Map.of("x-idempotency-key", "no-ticket")));
        assertEquals(400, error.status());
        assertEquals(5000L, player.entity().jewelFree);
    }

    @Test
    void campaignPurchaseStaysUnimplemented() {
        WireError error = assertThrows(WireError.class, () ->
                call("/v1/gacha/set_purchase",
                        J.map("gacha_series_id", POOL, "gacha_set_purchase_type", 1,
                                "campaign_id", 42),
                        Map.of("x-idempotency-key", "campaign")));
        assertEquals(501, error.status());
        assertEquals(5000L, player.entity().jewelFree);
    }

    @Test
    void purchaseRejectsAnUnknownPool() {
        WireError error = assertThrows(WireError.class, () ->
                call("/v1/gacha/set_purchase",
                        J.map("gacha_series_id", 9999999, "gacha_set_purchase_type", 1),
                        Map.of("x-idempotency-key", "unknown")));
        assertEquals(404, error.status());
    }

    @Test
    void historyListsFinishedDrawsNewestFirst() {
        call("/v1/gacha/set_purchase",
                J.map("gacha_series_id", POOL, "gacha_set_purchase_type", 1),
                Map.of("x-idempotency-key", "h1"));
        call("/v1/gacha/set_purchase",
                J.map("gacha_series_id", POOL, "gacha_set_purchase_type", 1),
                Map.of("x-idempotency-key", "h2"));

        Map<String, Object> result = call("/v1/gacha/get_history", J.map());

        List<Map<String, Object>> history = J.rowsOr(result.get("gacha_result_history_list"));
        assertEquals(2, history.size());
        assertEquals(1, J.rowsOr(history.get(0).get("gacha_result_history_info_list")).size());
        assertNotNull(history.get(0).get("gacha_date_time"));
    }

    // ------------------------------------------------------------------
    // 兑换
    // ------------------------------------------------------------------

    @Test
    void selectTicketExchangeSpendsATicketAndGrantsTheCard() {
        player.put("select_ticket_balances", new ArrayList<>(List.of(
                J.map("select_ticket_series_id", 1020002, "user_select_ticket_num", 1))));

        Map<String, Object> result = call("/v1/gacha/set_select_ticket_exchange",
                J.map("select_ticket_exchange_rate_id", 1020002001L),
                Map.of("x-idempotency-key", "exchange"));

        List<Map<String, Object>> items = J.rowsOr(result.get("receipt_items"));
        assertFalse(items.isEmpty());
        assertEquals(2, ((Number) items.get(0).get("get_item_type")).intValue());
        assertEquals(true, items.get(0).get("is_new"));
        assertEquals(0L, ((Number) player.rowsSnapshot("select_ticket_balances").get(0)
                .get("user_select_ticket_num")).longValue());
        assertEquals(1, player.rowsSnapshot("Cards").size());
        assertEquals(1, player.rowsSnapshot("select_ticket_exchange_receipts").size());
    }

    @Test
    void selectTicketExchangeRejectsADuplicateCardAsCoinsAndMaterial() {
        player.put("select_ticket_balances", new ArrayList<>(List.of(
                J.map("select_ticket_series_id", 1020002, "user_select_ticket_num", 2))));
        call("/v1/gacha/set_select_ticket_exchange",
                J.map("select_ticket_exchange_rate_id", 1020002001L),
                Map.of("x-idempotency-key", "first"));

        Map<String, Object> result = call("/v1/gacha/set_select_ticket_exchange",
                J.map("select_ticket_exchange_rate_id", 1020002001L),
                Map.of("x-idempotency-key", "second"));

        List<Map<String, Object>> items = J.rowsOr(result.get("receipt_items"));
        assertEquals(4, ((Number) items.get(0).get("get_item_type")).intValue());
        assertTrue(items.stream().anyMatch(row ->
                ((Number) row.get("get_item_type")).intValue() == 3));
        assertEquals(1, player.rowsSnapshot("Cards").size());
    }

    @Test
    void selectTicketExchangeWithoutTicketsIsRejected() {
        WireError error = assertThrows(WireError.class, () ->
                call("/v1/gacha/set_select_ticket_exchange",
                        J.map("select_ticket_exchange_rate_id", 1020002001L),
                        Map.of("x-idempotency-key", "none")));
        assertEquals(400, error.status());
        assertTrue(player.rowsSnapshot("Cards").isEmpty());
    }

    @Test
    void selectTicketExchangeRejectsAnUnknownRate() {
        WireError error = assertThrows(WireError.class, () ->
                call("/v1/gacha/set_select_ticket_exchange",
                        J.map("select_ticket_exchange_rate_id", 1),
                        Map.of("x-idempotency-key", "bad-rate")));
        assertEquals(404, error.status());
    }

    @Test
    void guaranteePointExchangeSpendsPointsForTheCard() {
        player.put("gacha_points", new ArrayList<>(List.of(
                J.map("exchange_point_id", POOL, "num", 1200))));

        Map<String, Object> result = call("/v1/gacha/set_guarantee_point_exchange",
                J.map("exchange_point_rate_id", 2000111L),
                Map.of("x-idempotency-key", "point-exchange"));

        assertFalse(J.rowsOr(result.get("receipt_items")).isEmpty());
        assertEquals(600L, ((Number) player.rowsSnapshot("gacha_points").get(0).get("num"))
                .longValue());
        assertEquals(1, player.rowsSnapshot("Cards").size());
    }

    @Test
    void guaranteePointExchangeWithoutPointsKeepsTheCardBack() {
        WireError error = assertThrows(WireError.class, () ->
                call("/v1/gacha/set_guarantee_point_exchange",
                        J.map("exchange_point_rate_id", 2000111L),
                        Map.of("x-idempotency-key", "no-points")));
        assertEquals(400, error.status());
        assertTrue(player.rowsSnapshot("Cards").isEmpty());
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private Map<String, Object> call(String path, Map<String, Object> body) {
        return call(path, body, Map.of());
    }

    private Map<String, Object> call(String path, Map<String, Object> body,
                                     Map<String, String> headers) {
        WireHandler handler = router.resolve(path);
        assertNotNull(handler, path + " 未注册");
        return asMap(handler.handle(new WireRequest(path, body, headers, player, store, mapper,
                new ServerProperties())));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    // ------------------------------------------------------------------
    // 天井奖励
    // ------------------------------------------------------------------

    /** 奖池档位来自 {@code GachaPrizes.GachaPlayCount}，不是猜的。 */
    @Test
    void prizeReceiveGrantsTheTierOnceWhenPlayCountIsReached() {
        long seriesId = 1050318L;
        player.put("gacha_states", new ArrayList<>(List.of(
                J.map("gacha_series_id", seriesId, "exec_count", 25, "paid_day", "",
                        "paid_count", 0))));

        // 第 20 抽档位（1050318002）已达成，第 30 抽档位尚未达成。
        Map<String, Object> result = call("/v1/gacha/set_prize_receive",
                J.map("gacha_series_id", seriesId, "gacha_prize_id", 1050318002L));
        assertEquals(true, result.get("result"));

        List<Map<String, Object>> receipts = player.rowsSnapshot("gacha_prize_receipts");
        assertEquals(1, receipts.size());
        assertEquals(List.of(1050318002L),
                J.listOrEmpty(receipts.get(0).get("received_gacha_prize_list")).stream()
                        .map(value -> J.longOr(value, 0L)).toList());
    }

    @Test
    void prizeReceiveIsRefusedBeforeTheTierIsReached() {
        long seriesId = 1050318L;
        player.put("gacha_states", new ArrayList<>(List.of(
                J.map("gacha_series_id", seriesId, "exec_count", 25, "paid_day", "",
                        "paid_count", 0))));

        WireError error = assertThrows(WireError.class, () ->
                call("/v1/gacha/set_prize_receive",
                        J.map("gacha_series_id", seriesId, "gacha_prize_id", 1050318003L)));
        assertEquals(400, error.status());
        assertTrue(player.rowsSnapshot("gacha_prize_receipts").isEmpty());
    }

    @Test
    void prizeReceiveIsRefusedTwiceForTheSameTier() {
        long seriesId = 1050318L;
        player.put("gacha_states", new ArrayList<>(List.of(
                J.map("gacha_series_id", seriesId, "exec_count", 25, "paid_day", "",
                        "paid_count", 0))));
        call("/v1/gacha/set_prize_receive",
                J.map("gacha_series_id", seriesId, "gacha_prize_id", 1050318002L));

        WireError error = assertThrows(WireError.class, () ->
                call("/v1/gacha/set_prize_receive",
                        J.map("gacha_series_id", seriesId, "gacha_prize_id", 1050318002L)));
        assertEquals(400, error.status());
        assertEquals(1, J.listOrEmpty(player.rowsSnapshot("gacha_prize_receipts").get(0)
                .get("received_gacha_prize_list")).size());
    }

    /** 读接口的 {@code received_gacha_prize_list} 必须反映真实账本，不能恒为空。 */
    @Test
    void seriesListReportsTheReceivedPrizeLedger() {
        long seriesId = 1050316L;
        player.put("gacha_prize_receipts", new ArrayList<>(List.of(
                J.map("gacha_series_id", seriesId,
                        "received_gacha_prize_list", new ArrayList<>(List.of(1050318001L))))));

        Map<String, Object> result = call("/v1/gacha/get_series_list", J.map());

        Map<String, Object> row = J.rowsOr(result.get("gacha_series_list")).stream()
                .filter(item -> ((Number) item.get("gacha_series_id")).longValue() == seriesId)
                .findFirst().orElseThrow();
        assertEquals(List.of(1050318001L), J.listOrEmpty(row.get("received_gacha_prize_list"))
                .stream().map(value -> J.longOr(value, 0L)).toList());
    }
}
