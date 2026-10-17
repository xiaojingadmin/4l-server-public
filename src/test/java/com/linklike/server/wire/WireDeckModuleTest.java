package com.linklike.server.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireError;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 卡组槽位规范化：ID 生成、拥有校验、重复槽位与数量上限。 */
class WireDeckModuleTest {

    private static final Set<String> OWNED = Set.of("card-1", "card-2");

    @Test
    void slotsGenerateMissingIdsAndKeepWireFieldNames() {
        List<Map<String, Object>> rows = WireDeckModule.slots(List.of(
                J.map("d_card_datas_id", "card-1", "slot_no", 1),
                J.map("d_card_datas_id", "card-2", "slot_no", 2,
                        "side_style1_d_card_datas_id", "card-1")), OWNED);

        assertEquals(2, rows.size());
        String generated = String.valueOf(rows.get(0).get("DDeckCardsId"));
        assertTrue(generated.matches("[0-9a-f-]{36}"));
        assertNotEquals(generated, rows.get(1).get("DDeckCardsId"));
        assertEquals("card-1", rows.get(0).get("DCardDatasId"));
        assertEquals(1L, rows.get(0).get("SlotNo"));
        assertEquals("card-1", rows.get(1).get("SideStyle1DCardDatasId"));
        assertEquals(0, rows.get(0).get("GradeCardBonusValue"));
    }

    @Test
    void slotsRejectDuplicateSlotNumbers() {
        WireError error = assertThrows(WireError.class, () -> WireDeckModule.slots(List.of(
                J.map("d_card_datas_id", "card-1", "slot_no", 1),
                J.map("d_card_datas_id", "card-2", "slot_no", 1)), OWNED));
        assertEquals(400, error.status());
    }

    @Test
    void slotsRejectUnownedCards() {
        WireError error = assertThrows(WireError.class, () -> WireDeckModule.slots(List.of(
                J.map("d_card_datas_id", "not-owned", "slot_no", 1)), OWNED));
        assertEquals(400, error.status());
    }

    @Test
    void slotsRejectUnownedSideStyleCards() {
        WireError error = assertThrows(WireError.class, () -> WireDeckModule.slots(List.of(
                J.map("d_card_datas_id", "card-1", "slot_no", 1,
                        "side_style2_d_card_datas_id", "not-owned")), OWNED));
        assertEquals(400, error.status());
    }

    @Test
    void slotsRejectMoreThanThirtyTwoEntries() {
        List<Object> cards = new ArrayList<>();
        for (int index = 0; index < 33; index++) {
            cards.add(J.map("d_card_datas_id", "card-1", "slot_no", index + 1));
        }
        WireError error = assertThrows(WireError.class,
                () -> WireDeckModule.slots(cards, OWNED));
        assertEquals(400, error.status());
    }

    @Test
    void slotsRejectNonObjectEntries() {
        WireError error = assertThrows(WireError.class,
                () -> WireDeckModule.slots(List.of("nope"), OWNED));
        assertEquals(400, error.status());
    }

    @Test
    void strictIntMatchesPythonIntOnlySemantics() {
        assertEquals(1L, WireDeckModule.strictInt(1));
        assertEquals(1L, WireDeckModule.strictInt(1L));
        assertEquals(null, WireDeckModule.strictInt(true));
        assertEquals(null, WireDeckModule.strictInt(1.0));
        assertEquals(null, WireDeckModule.strictInt("1"));
    }
}
