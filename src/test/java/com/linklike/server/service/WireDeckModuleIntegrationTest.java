package com.linklike.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklike.server.config.ServerProperties;
import com.linklike.server.domain.Player;
import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireCodec;
import com.linklike.server.protocol.WireError;
import com.linklike.server.protocol.WireModels;
import com.linklike.server.resource.ClientDefaults;
import com.linklike.server.wire.RouteSnapshot;
import com.linklike.server.wire.WireDeckModule;
import com.linklike.server.wire.WireHandler;
import com.linklike.server.wire.WireRequest;
import com.linklike.server.wire.WireRouter;
import com.linklike.server.wire.WireShop;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 卡组模块的端到端（不含 HTTP）行为：请求 -> 路由 -> 状态写入。
 *
 * <p>{@link PlayerStore} 用 mock 代替数据库，只验证业务逻辑与落库调用；状态读写用的是
 * 真实的 {@link PlayerContext}。
 */
class WireDeckModuleIntegrationTest {

    private ObjectMapper mapper;
    private WireRouter router;
    private PlayerStore store;
    private PlayerContext player;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        WireCodec codec = new WireCodec(new WireModels(mapper));
        WireShop shop = new WireShop(new ClientDefaults(mapper, new ServerProperties()));
        WireDeckModule module = new WireDeckModule(codec, shop);
        router = new WireRouter(List.of(module), new RouteSnapshot(mapper));
        store = mock(PlayerStore.class);

        Player entity = new Player();
        entity.playerId = "P00000001";
        entity.playerName = "Tester";
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("Cards", new ArrayList<>(List.of(
                J.map("DCardDatasId", "card-1"),
                J.map("DCardDatasId", "card-2"))));
        state.put("Decks", new ArrayList<>());
        player = new PlayerContext(entity, state);
    }

    @Test
    void modifyCreatesADeckAndAdvancesTheAceMission() {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("d_deck_datas_id", "");
        change.put("generations_id", 1);
        change.put("deck_no", 1);
        change.put("deck_name", "Test");
        change.put("deck_cards_list", List.of(
                J.map("d_card_datas_id", "card-1", "slot_no", 1),
                J.map("d_card_datas_id", "card-2", "slot_no", 2,
                        "side_style1_d_card_datas_id", "card-1")));
        change.put("ace_card", "card-1");
        change.put("is_change_deck_cards", true);

        Map<String, Object> result = call("/v1/user/deck/modify_deck_list",
                J.map("modify_deck_list", List.of(change)));

        List<Map<String, Object>> encoded = J.rowsOr(result.get("deck_list"));
        assertEquals(1, encoded.size());
        Map<String, Object> deck = encoded.get(0);
        assertTrue(String.valueOf(deck.get("d_deck_datas_id")).length() > 10);
        assertEquals(1L, deck.get("deck_no"));
        assertEquals("card-1", deck.get("ace_card"));
        assertEquals(2, J.rowsOr(deck.get("deck_cards_list")).size());

        List<Map<String, Object>> stored = player.rowsSnapshot("Decks");
        assertEquals(1, stored.size());
        assertEquals("card-1", stored.get(0).get("AceCard"));
        assertNotNull(findMission(player.rowsSnapshot("beginner_missions"), 900213));
    }

    @Test
    void modifyRejectsDuplicateSlotNumbers() {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("d_deck_datas_id", "");
        change.put("generations_id", 1);
        change.put("deck_no", 1);
        change.put("deck_cards_list", List.of(
                J.map("d_card_datas_id", "card-1", "slot_no", 1),
                J.map("d_card_datas_id", "card-2", "slot_no", 1)));

        WireError error = assertThrows(WireError.class, () ->
                call("/v1/user/deck/modify_deck_list", J.map("modify_deck_list", List.of(change))));
        assertEquals(400, error.status());
        assertTrue(player.rowsSnapshot("Decks").isEmpty());
    }

    @Test
    void setPositionSwapsTheTwoSlotsAndSortsThem() {
        player.put("Decks", new ArrayList<>(List.of(deckWithTwoSlots())));

        Map<String, Object> result = call("/v1/user/deck/set_position", J.map(
                "d_deck_datas_id", "deck-1", "position_id", 1, "slot_no", 2));

        assertEquals(true, result.get("result"));
        List<Map<String, Object>> slots = J.rowsOr(
                player.rowsSnapshot("Decks").get(0).get("DeckCardsList"));
        assertEquals(1L, slots.get(0).get("SlotNo"));
        assertEquals("card-2", slots.get(0).get("DCardDatasId"));
        assertEquals(2L, slots.get(1).get("SlotNo"));
        assertEquals("card-1", slots.get(1).get("DCardDatasId"));
    }

    @Test
    void deckWritesAreBlockedDuringALive() {
        player.put("Decks", new ArrayList<>(List.of(deckWithTwoSlots())));
        player.put("ActiveQuest", "live:1:2:3:4:5");

        WireError error = assertThrows(WireError.class,
                () -> call("/v1/user/deck/set_delete_deck", J.map("d_deck_datas_id", "deck-1")));
        assertEquals(409, error.status());
    }

    @Test
    void deleteRemovesOnlyTheRequestedDeck() {
        player.put("Decks", new ArrayList<>(List.of(deckWithTwoSlots())));

        Map<String, Object> result = call("/v1/user/deck/set_delete_deck",
                J.map("d_deck_datas_id", "deck-1"));

        assertEquals(true, result.get("result"));
        assertTrue(player.rowsSnapshot("Decks").isEmpty());
    }

    @Test
    void setDeckCreatesThroughTheModifyPath() {
        Map<String, Object> deckData = new LinkedHashMap<>();
        deckData.put("deck_name", "FromSetDeck");
        deckData.put("generations_id", 2);
        deckData.put("deck_no", 1);
        deckData.put("deck_cards_list", List.of(
                J.map("d_card_datas_id", "card-2", "slot_no", 1)));

        Map<String, Object> result = call("/v1/user/deck/set_deck",
                J.map("deck_data", deckData));

        assertEquals(true, result.get("result"));
        assertEquals("FromSetDeck",
                player.rowsSnapshot("Decks").get(0).get("DeckName"));
    }

    private Map<String, Object> call(String path, Map<String, Object> body) {
        WireHandler handler = router.resolve(path);
        assertNotNull(handler, path + " 未注册");
        return handler.handle(new WireRequest(path, body, Map.of(), player, store, mapper,
                new ServerProperties()));
    }

    private static Map<String, Object> deckWithTwoSlots() {
        Map<String, Object> deck = new LinkedHashMap<>();
        deck.put("DDeckDatasId", "deck-1");
        deck.put("DeckName", "Deck");
        deck.put("DeckNo", 1);
        deck.put("GenerationsId", 1);
        deck.put("AceCard", "card-1");
        deck.put("DeckCardsList", new ArrayList<>(List.of(
                slot("slot-1", "card-1", 1),
                slot("slot-2", "card-2", 2))));
        deck.put("IsChangeDeckCards", false);
        deck.put("GradeBonusValue", 0);
        return deck;
    }

    private static Map<String, Object> slot(String id, String card, int number) {
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("DDeckCardsId", id);
        slot.put("DCardDatasId", card);
        slot.put("SlotNo", number);
        slot.put("GradeCardBonusValue", 0);
        slot.put("GradeCardBonusLimitUp", 0);
        slot.put("SideStyle1DCardDatasId", "");
        slot.put("SideStyle1GradeCardBonusValue", 0);
        slot.put("SideStyle1GradeCardBonusLimitUp", 0);
        slot.put("SideStyle2DCardDatasId", "");
        slot.put("SideStyle2GradeCardBonusValue", 0);
        slot.put("SideStyle2GradeCardBonusLimitUp", 0);
        return slot;
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
