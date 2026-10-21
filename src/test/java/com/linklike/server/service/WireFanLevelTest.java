package com.linklike.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.linklike.server.domain.Player;
import com.linklike.server.protocol.J;
import com.linklike.server.wire.WireFanLevel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WireFanLevelTest {

    /**
     * 新账号初始发卡会先写入所有角色的卡，再逐张建 MemberFanLevels 行；回扫时其它角色的卡
     * 还没有对应行。这里 known == null，不能拆箱比较等级（原实现会 NPE）。
     */
    @Test
    void applyToCardsToleratesCharactersWithoutMemberLevelRows() {
        Player entity = new Player();
        entity.playerId = "P00000001";
        entity.playerName = "Fan Level";
        PlayerContext player = new PlayerContext(entity, new LinkedHashMap<>());

        List<Map<String, Object>> cards = new ArrayList<>();
        cards.add(J.map("CharacterId", 1032, "MemberFanLevel", 1));
        cards.add(J.map("CharacterId", 1022, "MemberFanLevel", 1));
        player.put("Cards", cards);
        player.put("MemberFanLevels",
                new ArrayList<>(List.of(J.map("CharactersId", 1032, "MemberFanlevel", 3))));

        List<Map<String, Object>> out = WireFanLevel.applyToCards(player, player.rows("Cards"));

        assertEquals(2, out.size());
        assertEquals(3, J.intOr(out.get(0).get("MemberFanLevel"), 0));
        assertEquals(1, J.intOr(out.get(1).get("MemberFanLevel"), 0));
    }
}
