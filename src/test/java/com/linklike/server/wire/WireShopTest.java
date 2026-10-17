package com.linklike.server.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklike.server.config.ServerProperties;
import com.linklike.server.resource.ClientDefaults;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 新手任务进度：只改副本、封顶、已完成不再推进、绝对值记录。 */
class WireShopTest {

    private WireShop shop;

    @BeforeEach
    void setUp() {
        shop = new WireShop(new ClientDefaults(new ObjectMapper(), new ServerProperties()));
    }

    @Test
    void advanceCreatesMissionRowAndMarksItReceivable() {
        List<Map<String, Object>> missions = new ArrayList<>();

        boolean matched = shop.advance(missions, WireShop.BEGINNER_CONDITION_ACE_CARD, 0, 1);

        assertTrue(matched);
        Map<String, Object> row = findMission(missions, 900213);
        assertNotNull(row);
        assertEquals(1, row.get("achieved_num"));
        assertEquals(WireShop.BEGINNER_MISSION_NOT_RECEIVED, row.get("status"));
        Map<String, Object> group = missions.get(0);
        assertEquals(92, group.get("mission_type"));
        assertFalse((Boolean) group.get("is_received"));
    }

    @Test
    void advanceCapsAtTheMissionTargetAndDoesNotTouchCompletedRows() {
        List<Map<String, Object>> missions = new ArrayList<>();
        shop.advance(missions, WireShop.BEGINNER_CONDITION_MAX_STYLE_LEVEL, 0, 8);
        Map<String, Object> row = findMission(missions, 900210);
        assertEquals(8, row.get("achieved_num"));
        assertEquals(WireShop.BEGINNER_MISSION_NOT_ACHIEVED, row.get("status"));

        shop.advance(missions, WireShop.BEGINNER_CONDITION_MAX_STYLE_LEVEL, 0, 99);
        assertEquals(10, row.get("achieved_num"));
        assertEquals(WireShop.BEGINNER_MISSION_NOT_RECEIVED, row.get("status"));

        row.put("status", WireShop.BEGINNER_MISSION_COMPLETED);
        shop.advance(missions, WireShop.BEGINNER_CONDITION_MAX_STYLE_LEVEL, 0, 5);
        assertEquals(10, row.get("achieved_num"));
        assertEquals(WireShop.BEGINNER_MISSION_COMPLETED, row.get("status"));
    }

    @Test
    void raiseRecordsAnAbsoluteValue() {
        List<Map<String, Object>> missions = new ArrayList<>();
        shop.raise(missions, WireShop.BEGINNER_CONDITION_MAX_STYLE_LEVEL, 4);
        shop.raise(missions, WireShop.BEGINNER_CONDITION_MAX_STYLE_LEVEL, 3);
        Map<String, Object> row = findMission(missions, 900210);
        assertEquals(4, row.get("achieved_num"));
    }

    @Test
    void unmatchedConditionLeavesTheDraftAlone() {
        List<Map<String, Object>> missions = new ArrayList<>();
        boolean matched = shop.advance(missions, 999_999, 0, 1);
        assertFalse(matched);
        assertTrue(missions.isEmpty());
    }

    private static Map<String, Object> findMission(List<Map<String, Object>> groups, int id) {
        for (Map<String, Object> group : groups) {
            for (Map<String, Object> row : com.linklike.server.protocol.J
                    .rowsOr(group.get("mission_list"))) {
                if (id == com.linklike.server.protocol.J.intOr(row.get("mission_id"), 0)) {
                    return row;
                }
            }
        }
        return null;
    }
}
