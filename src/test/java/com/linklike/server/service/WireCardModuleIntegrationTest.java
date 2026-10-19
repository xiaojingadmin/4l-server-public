package com.linklike.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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
import com.linklike.server.wire.WireCardMaster;
import com.linklike.server.wire.WireCardModule;
import com.linklike.server.wire.WireFanCatalog;
import com.linklike.server.wire.WireFanLevel;
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
 * 卡片成长 / 展示接口的行为，用随仓库分发的真实 5.1.0 主数据。
 *
 * <p>用系列 {@code 1011501}（UR「ノヴァホワイト」）做样本：它带完整的进化、界限突破、
 * 技能与节奏游戏技能成本行，进化到二阶需要 24×3005042 + 48×3005041。
 */
class WireCardModuleIntegrationTest {

    private static final long SERIES = 1011501L;
    private static final long CARD = 10115010L;
    private static final long NEXT_CARD = 10115011L;
    private static final long EVOLUTION_MATERIAL_A = 3005042L;
    private static final long EVOLUTION_MATERIAL_B = 3005041L;
    private static final long SKILL_MATERIAL = 3006001L;
    private static final long LIMIT_BREAK_MATERIAL = 4011501L;
    private static final long RHYTHM_MATERIAL = 3006111L;
    private static final long SKILL_EXTRA_MATERIAL = 3006041L;
    private static final long RHYTHM_EXTRA_A = 3006121L;
    private static final long RHYTHM_EXTRA_B = 3006131L;

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
        WireCardMaster master = new WireCardMaster(defaults);
        new WireFanCatalog(master);
        WireShop shop = new WireShop(defaults);
        router = new WireRouter(
                List.of(new WireCardModule(master, shop, new WireCodec(models))),
                new RouteSnapshot(mapper));

        store = mock(PlayerStore.class);
        doAnswer(call -> null).when(store).save(any());

        Player entity = new Player();
        entity.playerId = "P00000001";
        entity.playerName = "Tester";
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("Cards", new ArrayList<>(List.of(card(1))));
        state.put("Items", new ArrayList<>(List.of(J.map("ItemCategory", 1,
                "UserItemList", new ArrayList<>(List.of(
                        item("i-a", EVOLUTION_MATERIAL_A, 100),
                        item("i-b", EVOLUTION_MATERIAL_B, 100),
                        item("i-c", SKILL_MATERIAL, 100),
                        item("i-d", LIMIT_BREAK_MATERIAL, 100),
                        item("i-e", RHYTHM_MATERIAL, 100),
                        item("i-f", SKILL_EXTRA_MATERIAL, 100),
                        item("i-g", RHYTHM_EXTRA_A, 100),
                        item("i-h", RHYTHM_EXTRA_B, 100)))))));
        state.put("StylePoint", 100_000L);
        state.put("SeasonId", 0L);
        player = new PlayerContext(entity, state);
    }

    private static Map<String, Object> card(int styleLevel) {
        return J.map(
                "DCardDatasId", "card-a",
                "CardDatasId", CARD,
                "CardName", "ノヴァホワイト",
                "StyleLevel", styleLevel,
                "MaxStyleLevel", 60,
                "LimitBreakTimes", 0,
                "MaxLimitBreakTimes", 4,
                "SkillList", new ArrayList<>(List.of(
                        J.map("SkillType", 1, "CardSkillSeriesId", 1, "SkillLevel", 1,
                                "MaxSkillLevel", 14),
                        J.map("SkillType", 2, "CardSkillSeriesId", 2, "SkillLevel", 1,
                                "MaxSkillLevel", 14))),
                "RhythmGameSkillList", new ArrayList<>(List.of(
                        J.map("RhythmGameSkillType", 1, "SkillLevel", 1),
                        J.map("RhythmGameSkillType", 2, "SkillLevel", 1))),
                "IsEvolvePossible", false,
                "IsEvolveMax", false,
                "IsLimitBreak", true,
                "IsStyleLevelUp", true,
                "CharacterId", 1021,
                "MemberFanLevel", 1);
    }

    private static Map<String, Object> item(String id, long itemId, long num) {
        return J.map("UserItemId", id, "ItemId", itemId, "ItemType", 1, "Rarity", 1,
                "ItemNum", num, "ResourceFileName", "");
    }

    // ------------------------------------------------------------------
    // 风格等级
    // ------------------------------------------------------------------

    @Test
    void checkStyleLevelUpQuotesTheWalletAndTheCap() {
        Map<String, Object> result = call("/v1/user/card/check_style_level_up",
                J.map("d_card_datas_id", "card-a"));

        assertEquals(true, result.get("is_possible"));
        assertEquals(1L, ((Number) result.get("current_style_level")).longValue());
        assertEquals(60L, ((Number) result.get("selectable_max_level")).longValue());
        assertEquals(100_000L, ((Number) result.get("style_point")).longValue());
    }

    @Test
    void styleLevelUpSpendsTheCumulativeDifference() {
        Map<String, Object> result = call("/v1/user/card/style_level_up",
                J.map("d_card_datas_id", "card-a", "style_level", 3));

        assertEquals(true, result.get("result"));
        // UR（ExperienceType 5）: 1 -> 3 是 550 点。
        assertEquals(99_450L, player.longOf("StylePoint", 0L));
        assertEquals(3L, J.longOr(player.rowsSnapshot("Cards").get(0).get("StyleLevel"), 0L));
    }

    @Test
    void styleLevelUpRejectsALevelBeyondTheCap() {
        WireError error = assertThrows(WireError.class, () ->
                call("/v1/user/card/style_level_up",
                        J.map("d_card_datas_id", "card-a", "style_level", 61)));
        assertEquals(400, error.status());
        assertEquals(100_000L, player.longOf("StylePoint", 0L));
    }

    @Test
    void styleLevelUpWithoutStylePointsKeepsTheCard() {
        player.put("StylePoint", 1L);

        WireError error = assertThrows(WireError.class, () ->
                call("/v1/user/card/style_level_up",
                        J.map("d_card_datas_id", "card-a", "style_level", 2)));
        assertEquals(400, error.status());
        assertEquals(1L, J.longOr(player.rowsSnapshot("Cards").get(0).get("StyleLevel"), 0L));
        assertEquals(1L, player.longOf("StylePoint", 0L));
    }

    // ------------------------------------------------------------------
    // 技能
    // ------------------------------------------------------------------

    @Test
    void checkSkillLevelUpListsTheNextCost() {
        Map<String, Object> result = call("/v1/user/card/check_skill_level_up",
                J.map("d_card_datas_id", "card-a", "skill_type", 1));

        assertEquals(true, result.get("is_possible"));
        assertEquals(1L, ((Number) result.get("current_skill_level")).longValue());
        List<Map<String, Object>> required = J.rowsOr(result.get("required_item_list"));
        assertEquals(5, ((Number) required.get(0).get("cost_num1")).intValue());
        assertEquals(SKILL_MATERIAL,
                ((Number) required.get(0).get("cost_item_id1")).longValue());
    }

    @Test
    void skillLevelUpDebitsEveryIntermediateStep() {
        Map<String, Object> result = call("/v1/user/card/skill_level_up",
                J.map("d_card_datas_id", "card-a", "skill_type", 1, "skill_level", 3));

        assertEquals(true, result.get("result"));
        // 1->2 花 5×3006001，2->3 花 7×3006001 + 5×3006041。
        assertEquals(88L, itemNum(SKILL_MATERIAL));
        assertEquals(95L, itemNum(SKILL_EXTRA_MATERIAL));
        Map<String, Object> skill = J.rowsOr(player.rowsSnapshot("Cards").get(0).get("SkillList"))
                .get(0);
        assertEquals(3L, J.longOr(skill.get("SkillLevel"), 0L));
    }

    @Test
    void skillLevelUpWithAMissingCostRowIsRefused() {
        // 系列 1010400 没有打包技能升级成本行 → 501，不动账号。
        player.put("Cards", new ArrayList<>(List.of(card(1), J.map(
                "DCardDatasId", "card-b", "CardDatasId", 10104000L, "CardName", "？？？",
                "StyleLevel", 1, "MaxStyleLevel", 40, "LimitBreakTimes", 0,
                "MaxLimitBreakTimes", 4,
                "SkillList", new ArrayList<>(List.of(
                        J.map("SkillType", 1, "CardSkillSeriesId", 1, "SkillLevel", 1,
                                "MaxSkillLevel", 14))),
                "RhythmGameSkillList", new ArrayList<>(), "CharacterId", 1021,
                "MemberFanLevel", 1))));

        WireError error = assertThrows(WireError.class, () ->
                call("/v1/user/card/skill_level_up",
                        J.map("d_card_datas_id", "card-b", "skill_type", 1, "skill_level", 2)));
        assertEquals(501, error.status());
        assertEquals(100L, itemNum(SKILL_MATERIAL));
    }

    @Test
    void skillLevelUpRejectsAnUnknownSkillType() {
        WireError error = assertThrows(WireError.class, () ->
                call("/v1/user/card/skill_level_up",
                        J.map("d_card_datas_id", "card-a", "skill_type", 3, "skill_level", 2)));
        assertEquals(400, error.status());
    }

    // ------------------------------------------------------------------
    // 界限突破
    // ------------------------------------------------------------------

    @Test
    void checkLimitBreakReportsTheRemainingSteps() {
        Map<String, Object> result = call("/v1/user/card/check_limit_break",
                J.map("d_card_datas_id", "card-a"));

        assertEquals(true, result.get("is_possible"));
        assertEquals(0L, ((Number) result.get("current_limit_break_times")).longValue());
        assertEquals(4L, ((Number) result.get("selectable_limit_break_times")).longValue());
        assertEquals(LIMIT_BREAK_MATERIAL, ((Number) J.rowsOr(result.get("required_item_list"))
                .get(0).get("cost_item_id1")).longValue());
    }

    @Test
    void limitBreakTakesTheReachedCountAndDebitsEachStep() {
        Map<String, Object> result = call("/v1/user/card/limit_break",
                J.map("d_card_datas_id", "card-a", "limit_break_step", 2));

        assertEquals(true, result.get("result"));
        assertEquals(0L, itemNum(LIMIT_BREAK_MATERIAL));
        Map<String, Object> updated = player.rowsSnapshot("Cards").get(0);
        assertEquals(2L, J.longOr(updated.get("LimitBreakTimes"), 0L));
        assertEquals(true, updated.get("IsLimitBreak"));
    }

    @Test
    void limitBreakBeyondTheCapIsRejected() {
        WireError error = assertThrows(WireError.class, () ->
                call("/v1/user/card/limit_break",
                        J.map("d_card_datas_id", "card-a", "limit_break_step", 5)));
        assertEquals(400, error.status());
    }

    // ------------------------------------------------------------------
    // 进化
    // ------------------------------------------------------------------

    @Test
    void checkEvolutionNeedsMaxStyleLevel() {
        Map<String, Object> blocked = call("/v1/user/card/check_evolution",
                J.map("d_card_datas_id", "card-a"));
        assertEquals(false, blocked.get("is_possible"));
        assertEquals(80L, ((Number) blocked.get("next_max_style_level")).longValue());

        player.put("Cards", new ArrayList<>(List.of(card(60))));
        Map<String, Object> allowed = call("/v1/user/card/check_evolution",
                J.map("d_card_datas_id", "card-a"));
        assertEquals(true, allowed.get("is_possible"));
    }

    @Test
    void evolutionMovesToTheNextStageAndDebitsMaterials() {
        player.put("Cards", new ArrayList<>(List.of(card(60))));

        Map<String, Object> result = call("/v1/user/card/evolution",
                J.map("d_card_datas_id", "card-a"));

        assertEquals(true, result.get("result"));
        Map<String, Object> evolved = player.rowsSnapshot("Cards").get(0);
        assertEquals(NEXT_CARD, J.longOr(evolved.get("CardDatasId"), 0L));
        assertEquals(80L, J.longOr(evolved.get("MaxStyleLevel"), 0L));
        assertEquals(76L, itemNum(EVOLUTION_MATERIAL_A));
        assertEquals(52L, itemNum(EVOLUTION_MATERIAL_B));
    }

    @Test
    void evolutionBeforeMaxStyleLevelKeepsEverything() {
        WireError error = assertThrows(WireError.class, () ->
                call("/v1/user/card/evolution", J.map("d_card_datas_id", "card-a")));
        assertEquals(400, error.status());
        assertEquals(CARD, J.longOr(player.rowsSnapshot("Cards").get(0).get("CardDatasId"), 0L));
        assertEquals(100L, itemNum(EVOLUTION_MATERIAL_A));
    }

    // ------------------------------------------------------------------
    // 节奏游戏技能
    // ------------------------------------------------------------------

    @Test
    void rhythmGameSkillLevelUpDebitsTheItemSeriesCost() {
        Map<String, Object> result = call("/v1/user/card/rhythm_game_skill_level_up",
                J.map("d_card_datas_id", "card-a", "rhythm_game_skill_type", 2,
                        "skill_level", 2));

        assertEquals(true, result.get("result"));
        // 1->2 是 5×3006001 + 15×3006111 + 15×3006121 + 15×3006131。
        assertEquals(85L, itemNum(RHYTHM_MATERIAL));
        assertEquals(85L, itemNum(RHYTHM_EXTRA_A));
        assertEquals(85L, itemNum(RHYTHM_EXTRA_B));
        assertEquals(95L, itemNum(SKILL_MATERIAL));
        Map<String, Object> skill = J.rowsOr(
                player.rowsSnapshot("Cards").get(0).get("RhythmGameSkillList")).get(1);
        assertEquals(2L, J.longOr(skill.get("SkillLevel"), 0L));
    }

    @Test
    void rhythmGameSkillLevelUpBeyondTheCapIsRejected() {
        WireError error = assertThrows(WireError.class, () ->
                call("/v1/user/card/rhythm_game_skill_level_up",
                        J.map("d_card_datas_id", "card-a", "rhythm_game_skill_type", 2,
                                "skill_level", 15)));
        assertEquals(400, error.status());
    }

    // ------------------------------------------------------------------
    // 展示卡与租借卡
    // ------------------------------------------------------------------

    @Test
    void displayDataFallsBackToTheOwnedCard() {
        Map<String, Object> result = call("/v1/card/get_display_data",
                J.map("base_card_series_id", SERIES));

        assertEquals(SERIES, ((Number) result.get("base_card_series_id")).longValue());
        assertEquals(CARD, ((Number) result.get("display_card_id")).longValue());
    }

    @Test
    void setDisplayDataRecordsTheChoiceForTheWholeSeries() {
        Map<String, Object> result = call("/v1/card/set_display_data",
                J.map("base_card_series_id", SERIES, "display_card_id", NEXT_CARD));

        assertEquals(CARD, ((Number) result.get("before_display_card_id")).longValue());
        assertEquals(NEXT_CARD, ((Number) result.get("after_display_card_id")).longValue());
        assertEquals(NEXT_CARD, J.longOr(
                player.rowsSnapshot("Cards").get(0).get("DisplayCardId"), 0L));
        assertEquals(1, player.rowsSnapshot("card_display_settings").size());
    }

    @Test
    void setDisplayDataRejectsACardOfAnotherSeries() {
        WireError error = assertThrows(WireError.class, () ->
                call("/v1/card/set_display_data",
                        J.map("base_card_series_id", SERIES, "display_card_id", 10104000)));
        assertEquals(400, error.status());
    }

    @Test
    void detailRentalServesTheStoredRow() {
        player.put("RentalCards", new ArrayList<>(List.of(
                J.map("RentalCardDatasId", 77,
                        "CardParameters", J.map("Smile", 1, "Pure", 2, "Cool", 3,
                                "Mental", 4, "BeatPoint", 5),
                        "CharacterBonus", J.map("CharacterId", 1021, "MusicMasteryBonus", 0,
                                "LoveCorrectionValue", 0, "MusicMasteryBonusList",
                                new ArrayList<>(), "SeasonFanLevel", 0)))));

        Map<String, Object> result = call("/v1/user/card/get_detail_rental",
                J.map("rental_card_datas_id", 77));

        Map<String, Object> rental = J.nodeOr(result.get("rental_card_data"));
        assertNotNull(rental);
        assertEquals(77L, ((Number) rental.get("rental_card_datas_id")).longValue());
        assertEquals(1L, ((Number) J.nodeOr(rental.get("card_parameters")).get("smile"))
                .longValue());
    }

    @Test
    void unknownCardIsNotFound() {
        WireError error = assertThrows(WireError.class, () ->
                call("/v1/user/card/check_style_level_up", J.map("d_card_datas_id", "nope")));
        assertEquals(404, error.status());
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private long itemNum(long itemId) {
        for (Map<String, Object> group : player.rowsSnapshot("Items")) {
            for (Map<String, Object> row : J.rowsOr(group.get("UserItemList"))) {
                if (J.longOr(row.get("ItemId"), -1L) == itemId) {
                    return J.longOr(row.get("ItemNum"), 0L);
                }
            }
        }
        return 0L;
    }

    private Map<String, Object> call(String path, Map<String, Object> body) {
        WireHandler handler = router.resolve(path);
        assertNotNull(handler, path + " 未注册");
        return asMap(handler.handle(new WireRequest(path, body, Map.of(), player, store, mapper,
                new ServerProperties())));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
