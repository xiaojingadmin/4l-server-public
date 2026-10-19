package com.linklike.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklike.server.config.ServerProperties;
import com.linklike.server.domain.Player;
import com.linklike.server.protocol.J;
import com.linklike.server.resource.ClientDefaults;
import com.linklike.server.wire.WireStore;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 商店限购口径：周期键、总量上限、解锁条件与素材汇总。 */
class WireStoreTest {

    private WireStore store;
    private PlayerContext player;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        RefCatalogStore catalogs = mock(RefCatalogStore.class);
        when(catalogs.list(anyString())).thenReturn(new ArrayList<>());
        store = new WireStore(new ClientDefaults(mapper, new ServerProperties()), catalogs);

        Player entity = new Player();
        entity.playerId = "P00000001";
        player = new PlayerContext(entity, new LinkedHashMap<>());
    }

    @Test
    void unlimitedListingReportsMinusOne() {
        Map<String, Object> rate = rate(2);

        assertEquals(-1, store.siscaBuyable(rate, player, 2, Instant.now()));
    }

    @Test
    void monthlyListingResetsWhenTheStoredPeriodDiffers() {
        Map<String, Object> rate = exchange(41);
        Instant now = Instant.parse("2026-10-19T05:44:00Z");

        assertEquals(50, store.exchangeRemaining(rate, player, 41, WireStore.Kind.ITEM, now));

        player.put("shop_period_states", new ArrayList<>(List.of(J.map(
                "listing_kind", "item_exchange", "listing_id", 41,
                "period_key", "m:2026-09", "remaining", 10))));
        assertEquals(50, store.exchangeRemaining(rate, player, 41, WireStore.Kind.ITEM, now));

        player.put("shop_period_states", new ArrayList<>(List.of(J.map(
                "listing_kind", "item_exchange", "listing_id", 41,
                "period_key", "m:2026-10", "remaining", 10))));
        assertEquals(10, store.exchangeRemaining(rate, player, 41, WireStore.Kind.ITEM, now));
    }

    @Test
    void periodKeyUsesTheJstFourAmBoundary() {
        Map<String, Object> rate = exchange(41);
        // 2026-10-31T19:30Z 在 JST 是 11 月 1 日 04:30，减 4 小时仍是 11 月 1 日 → 新月份。
        assertEquals(50, store.exchangeRemaining(rate, player, 41, WireStore.Kind.ITEM,
                Instant.parse("2026-10-31T19:30:00Z")));
        player.put("shop_period_states", new ArrayList<>(List.of(J.map(
                "listing_kind", "item_exchange", "listing_id", 41,
                "period_key", "m:2026-11", "remaining", 7))));
        assertEquals(7, store.exchangeRemaining(rate, player, 41, WireStore.Kind.ITEM,
                Instant.parse("2026-10-31T19:30:00Z")));
    }

    @Test
    void unlockConditionFollowsThePrerequisiteRemainingCount() {
        Map<String, Object> rate = exchange(30604002);
        Instant now = Instant.parse("2026-10-19T05:44:00Z");

        assertTrue(store.itemExchangeList(player, now).stream()
                .filter(row -> J.intOr(row.get("item_exchanges_id"), 0) == 30604002)
                .findFirst().map(row -> J.boolOr(row.get("is_lock"), false)).orElse(false));

        // 前置 30604001 已买满（总量 1），后置条目因此解锁。
        player.put("item_exchange_states", new ArrayList<>(List.of(J.map(
                "item_exchanges_id", 30604001, "remaining_exchange_num", 0))));
        assertFalse(store.itemExchangeList(player, now).stream()
                .filter(row -> J.intOr(row.get("item_exchanges_id"), 0) == 30604002)
                .findFirst().map(row -> J.boolOr(row.get("is_lock"), true)).orElse(true));
    }

    @Test
    void advUnlockBuyableCountsOwnedItemsAndUnlockedEpisodes() {
        // SIsCa 商品 1 需要 7 个剧情解锁道具 1006001。
        Map<String, Object> rate = rate(1);
        player.put("Items", new ArrayList<>(List.of(J.map(
                "ItemCategory", 3,
                "UserItemList", new ArrayList<>(List.of(J.map(
                        "ItemId", 1006001, "ItemNum", 2)))))));

        assertEquals(5, store.siscaBuyable(rate, player, 1, Instant.now()));
    }

    @Test
    void convertListAggregatesLimitBreakMaterialsByRarity() {
        player.put("Items", new ArrayList<>(List.of(J.map(
                "ItemCategory", 4,
                "UserItemList", new ArrayList<>(List.of(
                        J.map("ItemId", 4010400, "ItemNum", 3),
                        J.map("ItemId", 4010500, "ItemNum", 2)))))));

        List<Map<String, Object>> rows = store.convertList(player);

        assertEquals(1, rows.size());
        assertEquals(4, rows.get(0).get("rarity"));
        assertEquals(5L, rows.get(0).get("limit_break_material_num"));
    }

    private Map<String, Object> exchange(int ident) {
        return J.nodeOrEmpty(J.nodeOrEmpty(store.catalog().get("item_exchanges"))
                .get(String.valueOf(ident)));
    }

    private Map<String, Object> rate(int ident) {
        return J.nodeOrEmpty(J.nodeOrEmpty(store.catalog().get("sisca_products"))
                .get(String.valueOf(ident)));
    }
}
