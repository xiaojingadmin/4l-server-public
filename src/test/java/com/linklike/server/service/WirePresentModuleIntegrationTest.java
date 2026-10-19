package com.linklike.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import com.linklike.server.wire.WireCardMint;
import com.linklike.server.wire.WireGrants;
import com.linklike.server.wire.WireHandler;
import com.linklike.server.wire.WirePresentModule;
import com.linklike.server.wire.WireRequest;
import com.linklike.server.wire.WireRouter;
import com.linklike.server.wire.WireServices;
import com.linklike.server.wire.WireShop;
import com.linklike.server.wire.WireStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * {@code /v1/present_box/set_item} 与 {@code set_item_all}。
 *
 * <p>背包 / 道具类走真实的 {@link WireGrants} 发放路径（物品定义来自 {@code shop_catalog}），
 * 卡与歌曲走 {@link WireCardMint} 与主表；没有本地发放路径的类型要把该条放进
 * {@code receipt_failed_items} 且不改账号。
 */
class WirePresentModuleIntegrationTest {

    private static final long UNLOCK_ITEM = 1006001L;
    private static final long CARD_ID = 10314060L;

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
        WireCodec codec = new WireCodec(models);
        WireStore wireStore = new WireStore(defaults, mock(com.linklike.server.service.RefCatalogStore.class));
        WireCardMint mint = new WireCardMint(cardCatalogs(), models,
                new com.linklike.server.wire.WireCardMaster(defaults),
                new com.linklike.server.wire.WireFanCatalog(
                        new com.linklike.server.wire.WireCardMaster(defaults)));
        WireGrants grants = new WireGrants(masterItems());

        WirePresentModule module = new WirePresentModule(codec, grants, mint, wireStore, defaults);
        router = new WireRouter(List.of(module), new RouteSnapshot(mapper));

        store = mock(PlayerStore.class);
        doAnswer(call -> null).when(store).save(any());

        Player entity = new Player();
        entity.playerId = "P00000001";
        entity.playerName = "Tester";
        player = new PlayerContext(entity, new LinkedHashMap<>());
    }

    @Test
    void receivingAnItemMovesItIntoTheInventoryAndKeepsHistory() {
        player.put("presents", rows(present("present-1", 1, UNLOCK_ITEM, 3)));
        player.entity().presentBoxCount = 1;

        Map<String, Object> result = call("/v1/present_box/set_item",
                Map.of("d_present_boxes_id", "present-1"));

        assertEquals(1, result.get("result"));
        assertNotNull(result.get("receipt_success_item"));
        assertNull(result.get("receipt_failed_item"));
        assertEquals(3L, itemNum(UNLOCK_ITEM));
        assertTrue(player.rows("presents").isEmpty());
        assertEquals(0, player.entity().presentBoxCount);
        assertEquals(1, player.rows("present_history").size());
        verify(store).save(player);
    }

    @Test
    void aCardPresentIsMintedIntoTheCardList() {
        player.put("presents", rows(present("present-2", 2, CARD_ID, 1)));

        Map<String, Object> result = call("/v1/present_box/set_item",
                Map.of("d_present_boxes_id", "present-2"));

        assertEquals(1, result.get("result"));
        assertEquals(1, player.rows("Cards").size());
        // 卡必须来自 5.1.0 主数据（初始 fixture 不存在），而不是空 fixture 拼出来的空壳
        Map<String, Object> card = player.rows("Cards").get(0);
        assertEquals(CARD_ID, J.longOr(card.get("CardDatasId"), 0L));
        assertNotNull(card.get("CardName"));
        assertTrue(Long.parseLong(String.valueOf(card.get("MaxStyleLevel"))) > 0);
    }

    @Test
    void unsupportedItemTypeIsReportedAsFailureAndLeavesThePresentInPlace() {
        player.put("presents", rows(present("present-3", 7, 90000002L, 1)));

        Map<String, Object> result = call("/v1/present_box/set_item",
                Map.of("d_present_boxes_id", "present-3"));

        assertEquals(0, result.get("result"));
        assertNull(result.get("receipt_success_item"));
        assertNotNull(result.get("receipt_failed_item"));
        assertEquals(1, player.rows("presents").size());
        // Python 版的 set_item 无论成功与否都会落库（失败时状态未变）。
        verify(store).save(player);
    }

    @Test
    void unknownOrAlreadyReceivedPresentIsNotFound() {
        player.put("presents", rows(J.map("DPresentBoxesId", "present-4", "ItemType", 1,
                "ItemId", UNLOCK_ITEM, "Num", 1,
                "ReceiptedTime", "2026-01-01T00:00:00.000000Z")));

        WireError error = assertThrows(WireError.class, () -> call("/v1/present_box/set_item",
                Map.of("d_present_boxes_id", "present-4")));

        assertEquals(404, error.status());
    }

    @Test
    void receiveAllSkipsExpiredItemsUnlessAskedToIncludeThem() {
        player.put("presents", rows(
                present("fresh", 1, UNLOCK_ITEM, 2),
                J.map("DPresentBoxesId", "expired", "ItemType", 1, "ItemId", UNLOCK_ITEM,
                        "Num", 1,
                        "ReceiptLimitTime", Instant.now().minus(1, ChronoUnit.DAYS).toString())));

        Map<String, Object> result = call("/v1/present_box/set_item_all", Map.of("page", 1));

        assertEquals(1, result.get("result"));
        assertEquals(1, J.rowsOr(result.get("receipt_success_items")).size());
        assertEquals(1, J.rowsOr(result.get("receipt_exclude_items")).size());
        assertEquals(2L, itemNum(UNLOCK_ITEM));
        assertEquals(1, player.rows("presents").size());
    }

    @Test
    void receiveAllContinuesPastAnUngrantableRowInsteadOfFailing() {
        // 队列中间混一条没有发放路径的记录：它进 receipt_failed_items，前后两条照常入账，
        // 请求整体仍是 200。发放路径抛 IllegalArgumentException（Python 的 ValueError），
        // 不能被当成请求级错误冒到 set_item_all 外面。
        player.put("presents", rows(
                present("ok-1", 1, UNLOCK_ITEM, 1),
                present("bad", 7, 90000002L, 1),
                present("ok-2", 1, UNLOCK_ITEM, 2)));

        Map<String, Object> result = call("/v1/present_box/set_item_all", Map.of("page", 1));

        assertEquals(1, result.get("result"));
        assertEquals(2, J.rowsOr(result.get("receipt_success_items")).size());
        assertEquals(1, J.rowsOr(result.get("receipt_failed_items")).size());
        assertEquals(3L, itemNum(UNLOCK_ITEM));
        assertEquals(List.of("bad"), player.rows("presents").stream()
                .map(row -> row.get("DPresentBoxesId")).toList());
    }

    /**
     * 四种发放分支（卡 / 背包 / 歌曲 / 贴纸）在缺主数据时的失败都必须是「单条降级」。
     *
     * <p>{@code receive} 只兜 {@code IllegalArgumentException} / {@code IllegalStateException}；
     * 若将来某条分支改成抛 {@link com.linklike.server.protocol.WireError}（501），
     * 这里会直接失败，而不是悄悄把整批 {@code set_item_all} 变成请求级 501。
     */
    @Test
    void everyGrantBranchDegradesPerRowInsteadOfFailingTheRequest() {
        player.put("presents", rows(
                present("card", 2, 99999999L, 1),
                present("item", 1, 99999999L, 1),
                present("music", 5, 99999999L, 1),
                present("sticker", 6, 99999999L, 1),
                present("ok", 1, UNLOCK_ITEM, 1)));

        Map<String, Object> result = call("/v1/present_box/set_item_all", Map.of("page", 1));

        assertEquals(1, result.get("result"));
        assertEquals(1, J.rowsOr(result.get("receipt_success_items")).size());
        assertEquals(4, J.rowsOr(result.get("receipt_failed_items")).size());
        assertEquals(1L, itemNum(UNLOCK_ITEM));
        // 成功的 "ok" 出箱，四条发不出去的留在箱子里
        assertEquals(List.of("card", "item", "music", "sticker"),
                player.rows("presents").stream()
                        .map(row -> String.valueOf(row.get("DPresentBoxesId")))
                        .sorted().toList());
    }

    @Test
    void receiveAllCanIncludeExpiredItems() {
        player.put("presents", rows(
                J.map("DPresentBoxesId", "expired", "ItemType", 1, "ItemId", UNLOCK_ITEM,
                        "Num", 4,
                        "ReceiptLimitTime", Instant.now().minus(1, ChronoUnit.DAYS).toString())));

        Map<String, Object> result = call("/v1/present_box/set_item_all", J.map(
                "page", 1, "is_receive_with_expire", true));

        assertEquals(1, result.get("result"));
        assertTrue(J.rowsOr(result.get("receipt_exclude_items")).isEmpty());
        assertEquals(4L, itemNum(UNLOCK_ITEM));
    }

    @Test
    void receiveAllRejectsANonBooleanExpireFlag() {
        WireError error = assertThrows(WireError.class, () -> call(
                "/v1/present_box/set_item_all", J.map("page", 1, "is_receive_with_expire", "yes")));

        assertEquals(400, error.status());
    }

    @Test
    void paginationUsesFiftyRowsPerPage() {
        List<Map<String, Object>> presents = new ArrayList<>();
        for (int index = 0; index < 60; index++) {
            presents.add(present("p" + index, 1, UNLOCK_ITEM, 1));
        }
        player.put("presents", presents);

        Map<String, Object> first = call("/v1/present_box/set_item_all", Map.of("page", 1));
        assertEquals(50, J.rowsOr(first.get("receipt_success_items")).size());
        assertEquals(10, player.rows("presents").size());

        Map<String, Object> second = call("/v1/present_box/set_item_all", Map.of("page", 1));
        assertEquals(10, J.rowsOr(second.get("receipt_success_items")).size());
        assertTrue(player.rows("presents").isEmpty());
    }

    // ------------------------------------------------------------------

    /**
     * 没有初始卡 fixture 的目录：{@code initial_cards} 一律查不到。
     *
     * <p>不能直接用 {@code mock(RefCatalogStore.class)}——Mockito 对 {@code Map} 返回类型
     * 默认给空 Map 而非 null，{@code mint} 会把空 fixture 当成命中、静默发出空卡。
     */
    private static RefCatalogStore cardCatalogs() {
        RefCatalogStore catalogs = mock(RefCatalogStore.class);
        when(catalogs.get(anyString(), any())).thenReturn(null);
        return catalogs;
    }

    /** 最小可用主数据表：解锁道具（ItemType 3）与贴纸 ID 各一行。 */
    private static JdbcTemplate masterItems() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:present_" + System.nanoTime()
                + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE master_items (
                    Id BIGINT PRIMARY KEY,
                    Name VARCHAR(512),
                    ItemType INT,
                    ItemCategory INT,
                    Rarity INT,
                    LimitNum BIGINT
                )
                """);
        jdbc.update("INSERT INTO master_items (Id, Name, ItemType, ItemCategory, Rarity, LimitNum)"
                + " VALUES (?, ?, ?, ?, ?, ?)", UNLOCK_ITEM, "Unlock Item", 3, 3, 4, 9999L);
        return jdbc;
    }

    private static Map<String, Object> present(String ident, int type, long itemId, int num) {
        return J.map("DPresentBoxesId", ident, "ItemType", type, "ItemId", itemId, "Num", num);
    }

    @SafeVarargs
    private static List<Map<String, Object>> rows(Map<String, Object>... items) {
        return new ArrayList<>(List.of(items));
    }

    private long itemNum(long itemId) {
        return WireShop.itemNum(player, itemId);
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
