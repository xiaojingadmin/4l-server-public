package com.linklike.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklike.server.config.ServerProperties;
import com.linklike.server.domain.Player;
import com.linklike.server.protocol.WireCodec;
import com.linklike.server.protocol.WireModels;
import com.linklike.server.wire.RouteSnapshot;
import com.linklike.server.wire.WireCatalog;
import com.linklike.server.wire.WireHandler;
import com.linklike.server.wire.WireRequest;
import com.linklike.server.wire.WireRouter;
import com.linklike.server.wire.WireSocialModule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 关注列表与任务计数器共用的 FollowCount / FollowerCount 必须同步更新。 */
class WireSocialModuleIntegrationTest {

    private static final String TARGET_ID = "P00000002";

    private ObjectMapper mapper;
    private WireRouter router;
    private PlayerStore store;
    private PlayerContext player;
    private PlayerContext target;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        ServerProperties config = new ServerProperties();
        WireCodec codec = new WireCodec(new WireModels(mapper));
        WireSocialModule module = new WireSocialModule(codec, mock(WireCatalog.class), config);
        router = new WireRouter(List.of(module), new RouteSnapshot(mapper));

        store = mock(PlayerStore.class);
        Player entity = new Player();
        entity.playerId = "P00000001";
        entity.playerName = "Follower";
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("FollowedPlayers", new ArrayList<>());
        player = new PlayerContext(entity, state);

        Player targetEntity = new Player();
        targetEntity.playerId = TARGET_ID;
        targetEntity.playerName = "Target";
        target = new PlayerContext(targetEntity, new LinkedHashMap<>());
        when(store.loadById(TARGET_ID)).thenReturn(target);
    }

    @Test
    void followAndUnfollowKeepBothMissionCountersInSync() {
        call(Map.of("player_id", TARGET_ID, "follow_type", 1));

        assertEquals(List.of(TARGET_ID), player.raw("FollowedPlayers"));
        assertEquals(1, player.intOf("FollowCount", -1));
        assertEquals(1, target.intOf("FollowerCount", -1));

        call(Map.of("player_id", TARGET_ID, "follow_type", 2));

        assertEquals(List.of(), player.raw("FollowedPlayers"));
        assertEquals(0, player.intOf("FollowCount", -1));
        assertEquals(0, target.intOf("FollowerCount", -1));
    }

    private Map<String, Object> call(Map<String, Object> body) {
        WireHandler handler = router.resolve("/v1/follow/set_follow");
        assertNotNull(handler);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.handle(new WireRequest(
                "/v1/follow/set_follow", body, Map.of(), player, store, mapper,
                new ServerProperties()));
        return result;
    }
}
