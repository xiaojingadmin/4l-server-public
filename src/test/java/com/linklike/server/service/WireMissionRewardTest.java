package com.linklike.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
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
import com.linklike.server.wire.WireHandler;
import com.linklike.server.wire.WireMissionModule;
import com.linklike.server.wire.WireRequest;
import com.linklike.server.wire.WireRouter;
import com.linklike.server.wire.WireServices;
import com.linklike.server.wire.WireShop;
import com.linklike.server.wire.WireStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 任务目录与领奖：{@code mission_groups.json} 种子、{@code mission/get_list} 的目录下发、
 * 进阶新手任务领奖的真实结算，以及「奖励不可核实时不发零奖励」。
 */
class WireMissionRewardTest {

    private WireShop shop;
    private WireRouter router;
    private PlayerStore store;
    private PlayerContext player;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        ServerProperties config = new ServerProperties();
        ClientDefaults defaults = new ClientDefaults(mapper, config);
        WireModels models = new WireModels(mapper);
        RefCatalogStore catalogs = mock(RefCatalogStore.class);
        when(catalogs.list(anyString())).thenReturn(new ArrayList<>());

        WireServices services = new WireServices(catalogs, new ResourceCatalogLabels(config, mapper));
        WireStore wireStore = new WireStore(defaults, catalogs);
        shop = new WireShop(defaults);
        router = new WireRouter(
                List.of(new WireMissionModule(shop, services)), new RouteSnapshot(mapper));

        store = mock(PlayerStore.class);
        doAnswer(call -> null).when(store).save(any());

        Player entity = new Player();
        entity.playerId = "P00000001";
        entity.playerName = "Tester";
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("Items", new ArrayList<>());
        state.put("step_up_beginner_missions", new ArrayList<>());
        player = new PlayerContext(entity, state);
    }

    @Test
    void stepUpCatalogComesFromBundledDefinitions() {
        List<Map<String, Object>> catalog = shop.stepUpMissionCatalog();

        assertEquals(15, catalog.size());
        Map<String, Object> spec = shop.stepUpMissionSpec(94001);
        assertNotNull(spec);
        assertFalse(((List<?>) spec.get("Rewards")).isEmpty());
    }

    @Test
    void claimingAnUnachievedStepUpMissionIsRejectedWithoutStateChange() {
        WireError error = assertThrows(WireError.class,
                () -> shop.claimStepUpMission(player, 94001));

        assertEquals(400, error.status());
        assertTrue(player.rowsSnapshot("step_up_beginner_missions").isEmpty());
    }

    @Test
    void claimingAnUnknownStepUpMissionIs404() {
        WireError error = assertThrows(WireError.class,
                () -> shop.claimStepUpMission(player, 99999));

        assertEquals(404, error.status());
    }

    @Test
    void claimedStepUpMissionGrantsRewardsOnceAndMarksReceived() {
        Map<String, Object> row = J.map("mission_id", 94001, "achieved_num", 1L,
                "is_achieved", true, "is_received", false);
        player.put("step_up_beginner_missions", new ArrayList<>(List.of(row)));

        List<Map<String, Object>> granted = shop.claimStepUpMission(player, 94001);

        assertEquals(6, granted.size());
        // 领奖改的是玩家数据的副本，账号里存下来的那行才应被标记。
        Map<String, Object> saved = player.rowsSnapshot("step_up_beginner_missions").get(0);
        assertTrue(J.boolOr(saved.get("is_received"), false));
        // 94001 的奖励里有 3006001 技能书 50 本，按 ItemCategory 归组进背包。
        assertTrue(WireShop.itemNum(player, 3006001) >= 50);
        // 第二册奖励不能再领。
        assertEquals(400, assertThrows(WireError.class,
                () -> shop.claimStepUpMission(player, 94001)).status());
    }

    @Test
    void rewardItemOutsideShopCatalogStillResolvesFromMasterItems() {
        // 3002210（十连券）不在 shop_catalog.items 的 653 条里，只在 5.1.0 主数据里。
        shop.grantReward(player, J.map("ItemType", 3, "ItemId", 3002210, "Num", 1));

        assertEquals(1, WireShop.itemNum(player, 3002210));
    }

    @Test
    void unverifiedRewardTypeIsStillRefused() {
        assertEquals(501, assertThrows(WireError.class,
                () -> shop.grantReward(player, J.map("ItemType", 2, "ItemId", 2003071, "Num", 1)))
                .status());
    }

    @Test
    void walletOverflowIsRejectedWithoutWrappingTheBalance() {
        player.entity().musicPoint = Long.MAX_VALUE;

        WireError error = assertThrows(WireError.class, () -> shop.grantReward(
                player, J.map("ItemType", 3, "ItemId", 3009000, "Num", 1)));

        assertEquals(400, error.status());
        assertEquals(Long.MAX_VALUE, player.entity().musicPoint);
    }

    // ------------------------------------------------------------------
    // 关卡任务进度（transition_contents_id -> 账号状态）
    // ------------------------------------------------------------------

    @Test
    void stageStarMissionIsCappedByItsOwnCondition() {
        // 101 的计数器是账号的 SchoolStageStar（2526），进度按 min(计数器, condition) 收口。
        player.put("SchoolStageStar", 2526);

        shop.refreshServiceMissions(player);

        assertEquals(30, missionRow(3, 30100001).get("achieved_num"));
        assertTrue(J.boolOr(missionRow(3, 30100001).get("is_achieved"), false));
        assertEquals(60, missionRow(3, 30100002).get("achieved_num"));
        assertTrue(J.boolOr(missionRow(3, 30100002).get("is_achieved"), false));
    }

    @Test
    void cardSpeciesMissionCountsDistinctSeriesInBag() {
        // 501 的计数器是「该角色持有卡的去重 CardSeriesId 数」，不是卡池里的种类数：
        // 1021 在卡池里有 61 种，任务门槛只有 5。
        player.put("Cards", new ArrayList<>(List.of(
                J.map("CardDatasId", 10104000, "StyleLevel", 1),
                J.map("CardDatasId", 10104001, "StyleLevel", 1))));

        shop.refreshServiceMissions(player);

        // 两张卡同属一个 series，只算 1 种。
        assertEquals(1, missionRow(3, 30202101).get("achieved_num"));
        assertFalse(J.boolOr(missionRow(3, 30202101).get("is_achieved"), false));
    }

    @Test
    void styleLevelMissionSumsPerCharacterAndStopsAtCondition() {
        player.put("Cards", new ArrayList<>(List.of(
                J.map("CardDatasId", 10104000, "StyleLevel", 200),
                J.map("CardDatasId", 10104003, "StyleLevel", 200))));

        shop.refreshServiceMissions(player);

        Map<String, Object> row = missionRow(3, 30302101);
        // 2 张 1021 的卡共 400 级，但任务门槛是 300。
        assertEquals(300, row.get("achieved_num"));
        assertTrue(J.boolOr(row.get("is_achieved"), false));
    }

    @Test
    void unDerivableTransitionKeepsProgressUntouched() {
        // 100（スクールアイドルステージ通关）本地没有可核实的计数器，不能凭空造进度。
        player.put("SchoolStageStar", 2526);

        shop.refreshServiceMissions(player);

        assertNull(missionRowOrNull(3, 10010001));
    }

    @Test
    void globalCardSpeciesMissionCountsAllSeriesAndIgnoresCharacter() {
        // 30200001「カードを計20種類入手」不带角色编码，是全局去重种类数。
        player.put("Cards", new ArrayList<>(List.of(
                J.map("CardDatasId", 10104000, "StyleLevel", 1),
                J.map("CardDatasId", 10104001, "StyleLevel", 1))));

        shop.refreshServiceMissions(player);

        // 两张卡同 series，全局也只算 1 种。
        assertEquals(1, missionRow(3, 30200001).get("achieved_num"));
    }

    @Test
    void globalPrefixWithUnknownCounterIsNotDerived() {
        // 20200001「カードを3回入手」的计数器是入手回数，账号里没有，不能拿持有种类顶替。
        player.put("Cards", new ArrayList<>(List.of(
                J.map("CardDatasId", 10104000, "StyleLevel", 1))));

        shop.refreshServiceMissions(player);

        assertNull(missionRowOrNull(2, 20200001));
    }

    @Test
    void followerMissionUsesFollowerCountWhileFollowMissionUsesFollowCount() {
        player.put("FollowCount", 3);
        player.put("FollowerCount", 13);

        shop.refreshServiceMissions(player);

        assertEquals(3, missionRow(3, 32010002).get("achieved_num"));
        assertEquals(13, missionRow(3, 32020001).get("achieved_num"));
    }

    @Test
    void memberFanLevelMissionReadsPerCharacterLevelNotEnrolledCards() {
        player.put("MemberFanLevels", new ArrayList<>(List.of(
                J.map("CharactersId", 1021, "MemberFanlevel", 10),
                J.map("CharactersId", 1022, "MemberFanlevel", 20))));

        shop.refreshServiceMissions(player);

        assertEquals(10, missionRow(3, 30602101).get("achieved_num"));
        assertTrue(J.boolOr(missionRow(3, 30602101).get("is_achieved"), false));
        assertEquals(20, missionRow(3, 30602202).get("achieved_num"));
    }

    @Test
    void officialAccountExportIsReproducedExactly() throws Exception {
        // 基准：官方 /v1/mission/get_list 的 90 条任务 + 同账号的卡片/粉丝等级明细。
        // 该账号 50 条可派生，全部要与官方 achieved_num 逐条一致。
        Map<String, Object> fixture = new ObjectMapper().readValue(
                getClass().getResourceAsStream("/mission_progress_official.json"),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });

        player.put("Cards", new ArrayList<>(J.rowsOr(fixture.get("cards"))));
        player.put("MemberFanLevels", new ArrayList<>(J.rowsOr(fixture.get("fanLevels"))));
        player.put("SchoolStageStar", ((Number) fixture.get("schoolStageStar")).intValue());
        // 关注数取 follow_info_list 的长度（2），被关注数取官方进度反推出的 13：
        // follow_get_follower_list 只回 12 条，比官方计数器少 1，说明列表长度不等于计数器。
        player.put("FollowCount", ((Number) fixture.get("followCount")).intValue());
        player.put("FollowerCount", ((Number) fixture.get("followerCount")).intValue());

        shop.refreshServiceMissions(player);

        int checked = 0;
        int skipped = 0;
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) fixture.get("expected")).entrySet()) {
            int missionId = Integer.parseInt(entry.getKey());
            List<?> spec = (List<?>) entry.getValue();
            int transition = ((Number) spec.get(0)).intValue();
            int condition = ((Number) spec.get(1)).intValue();
            int official = ((Number) spec.get(2)).intValue();

            Integer derived = shop.serviceMissionProgressRow(player, transition, missionId);
            if (derived == null) {
                // 本地无可信来源的分组（100/103/106/800 通关次数、202000 入手回数等）
                // 必须保持不写，而不是补一个 0 或拿别的字段顶替。
                assertNull(findServiceMission(missionId), "任务 " + missionId + " 不应被派生");
                skipped++;
                continue;
            }
            Map<String, Object> row = findServiceMission(missionId);
            assertNotNull(row, "缺少任务行 " + missionId);
            int expected = Math.min(derived, condition);
            assertEquals(official, expected,
                    "任务 " + missionId + " 派生值与官方不一致");
            assertEquals(expected, J.intOr(row.get("achieved_num"), -2));
            checked++;
        }
        assertEquals(50, checked, "可派生任务数应与官方账号一致");
        assertEquals(40, skipped, "不可派生任务数应与官方账号一致");
    }

    @Test
    void serviceMissionCatalogMatchesOfficialDefinitions() {
        // 目录（id、档位、目标值、奖励）必须与官方 mission/get_list 逐条相同，
        // 只有进度字段归零；多一条或少一条都会让客户端对不上号。
        List<Map<String, Object>> specs = shop.serviceMissions();
        Map<Integer, List<Map<String, Object>>> byType = new LinkedHashMap<>();
        List<Integer> types = shop.serviceMissionTypes();
        for (int i = 0; i < specs.size(); i++) {
            byType.computeIfAbsent(types.get(i), key -> new ArrayList<>()).add(specs.get(i));
        }
        assertEquals(List.of(1, 2, 3), new ArrayList<>(byType.keySet()));
        assertEquals(12, byType.get(1).size());
        assertEquals(25, byType.get(2).size());
        assertEquals(53, byType.get(3).size());

        assertEquals(90, specs.stream().map(spec -> J.intOr(spec.get("mission_id"), 0))
                .distinct().count(), "任务 id 不可重复");

        // 304 只有两档，官方没有「关注 5 人」这一行。
        assertNull(byType.get(3).stream()
                .filter(spec -> J.intOr(spec.get("mission_id"), 0) == 32010003)
                .findFirst().orElse(null));
        assertTrue(specs.stream().allMatch(spec -> J.intOr(spec.get("achieved_num"), -1) == 0),
                "种子目录的进度必须归零");
    }

    private Map<String, Object> findServiceMission(int missionId) {
        for (Map<String, Object> group : player.rowsSnapshot("service_missions")) {
            for (Map<String, Object> row : J.rowsOr(group.get("mission_list"))) {
                if (J.intOr(row.get("mission_id"), -1) == missionId) {
                    return row;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 横幅奖励
    // ------------------------------------------------------------------

    @Test
    void bannerRewardFailureLeavesNothingPersisted() {
        // 奖励列表里只要有一条不可核实，grantReward 就抛 501，handler 不会走到 save。
        // 前面几条已写进本请求的 PlayerContext（和 Python 一样是就地改），靠 dispatch
        // 的 @Transactional 整体回滚丢弃，所以这里只锁「没有落库」这条不变量。
        WireShop spied = spy(shop);
        doReturn(List.of(
                J.map("ItemType", 3, "ItemId", 1002001, "Num", 1000),
                J.map("ItemType", 2, "ItemId", 2003071, "Num", 1)))
                .when(spied).bannerRewards(anyInt());
        WireRouter bannerRouter = new WireRouter(
                List.of(new WireMissionModule(spied,
                        new WireServices(mock(RefCatalogStore.class),
                                new ResourceCatalogLabels(new ServerProperties(),
                                        new ObjectMapper())))),
                new RouteSnapshot(new ObjectMapper()));

        player.put("beginner_missions", beginnerGroupsAllClaimed(91));

        WireError error = assertThrows(WireError.class, () -> {
            WireHandler handler = bannerRouter.resolve("/v1/beginner_mission/set_banner_reward");
            assertNotNull(handler);
            handler.handle(new WireRequest("/v1/beginner_mission/set_banner_reward",
                    J.map("mission_type", 91), Map.of(), player, store, new ObjectMapper(),
                    new ServerProperties()));
        });

        assertEquals(501, error.status());
        verify(store, never()).save(any());
        // is_received 只写在 draft 副本上，失败时不会传播到账号状态。
        assertFalse(J.boolOr(firstGroup(player, 91).get("is_received"), true));
    }

    @Test
    void bannerRewardSucceedsOnceEveryMissionOfThatTypeIsClaimed() {
        player.put("beginner_missions", beginnerGroupsAllClaimed(91));
        long jewelBefore = player.entity().jewelFree;

        Map<String, Object> result = call("/v1/beginner_mission/set_banner_reward",
                J.map("mission_type", 91));

        assertTrue(result.isEmpty());
        assertEquals(jewelBefore + 1000, player.entity().jewelFree);
        assertTrue(J.boolOr(firstGroup(player, 91).get("is_received"), false));
        verify(store).save(player);
    }

    @Test
    void bannerRewardIsRejectedOnSecondClaim() {
        player.put("beginner_missions", beginnerGroupsAllClaimed(91));
        call("/v1/beginner_mission/set_banner_reward", J.map("mission_type", 91));

        WireError error = assertThrows(WireError.class,
                () -> call("/v1/beginner_mission/set_banner_reward", J.map("mission_type", 91)));

        assertEquals(400, error.status());
    }

    /** 把某一类新手任务全部标成已达成且未领取，只留横幅奖励可领。 */
    private List<Map<String, Object>> beginnerGroupsAllClaimed(int missionType) {
        List<Map<String, Object>> missions = new ArrayList<>();
        for (Map<String, Object> spec : shop.missionSpecs(missionType)) {
            missions.add(J.map("mission_id", J.intOr(spec.get("Id"), 0),
                    "achieved_num", J.intOr(spec.get("Condition"), 0),
                    "status", WireShop.BEGINNER_MISSION_COMPLETED));
        }
        return new ArrayList<>(List.of(
                J.map("mission_type", missionType, "is_received", false,
                        "mission_list", missions)));
    }

    private static Map<String, Object> firstGroup(PlayerContext player, int missionType) {
        for (Map<String, Object> group : player.rowsSnapshot("beginner_missions")) {
            if (J.intOr(group.get("mission_type"), -1) == missionType) {
                return group;
            }
        }
        throw new AssertionError("缺少新手任务分组 " + missionType);
    }

    private Map<String, Object> call(String path, Map<String, Object> body) {
        WireHandler handler = router.resolve(path);
        assertNotNull(handler, path + " 未注册");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.handle(
                new WireRequest(path, body, Map.of(), player, store, new ObjectMapper(),
                        new ServerProperties()));
        return result;
    }

    private Map<String, Object> missionRow(int missionType, int missionId) {
        Map<String, Object> row = missionRowOrNull(missionType, missionId);
        assertNotNull(row, "缺少任务行 " + missionId);
        return row;
    }

    private Map<String, Object> missionRowOrNull(int missionType, int missionId) {
        for (Map<String, Object> group : player.rowsSnapshot("service_missions")) {
            if (J.intOr(group.get("mission_type"), -1) != missionType) {
                continue;
            }
            for (Map<String, Object> row : J.rowsOr(group.get("mission_list"))) {
                if (J.intOr(row.get("mission_id"), -1) == missionId) {
                    return row;
                }
            }
        }
        return null;
    }
}
