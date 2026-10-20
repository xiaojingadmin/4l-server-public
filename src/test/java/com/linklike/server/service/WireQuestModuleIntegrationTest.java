package com.linklike.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
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
import com.linklike.server.wire.WireQuestMaster;
import com.linklike.server.wire.WireQuestModule;
import com.linklike.server.wire.WireRequest;
import com.linklike.server.wire.WireRouter;
import com.linklike.server.wire.WireServices;
import com.linklike.server.wire.WireShop;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Quest Live 的端到端（不含 HTTP）行为：请求 -> 路由 -> 账号状态。
 *
 * <p>参考目录来自 {@link RefCatalogStore} 的 mock（只提供关卡 / 学习 / 段位几类）；静态表
 * （奖励、星数门槛、样式点、section skill、歌曲熟练度）来自随仓库分发的
 * {@code client_defaults/*.json}，所以这里的奖励、星级、经验都是真实数据。
 */
class WireQuestModuleIntegrationTest {

    private static final long STANDARD_STAGE = 103010101L;
    private static final long STANDARD_AREA = 1030101L;
    private static final long DAILY_STAGE = 303010101L;
    private static final long DAILY_QUEST = 3030101L;
    private static final long LEARNING_STAGE = 401031011L;
    private static final long LEARNING_MUSIC = 103101L;
    private static final long LEARNING_SERIES = 40103101L;

    private ObjectMapper mapper;
    private WireRouter router;
    private PlayerStore store;
    private PlayerContext player;
    private WireGrants grants;
    private WireQuestModule module;
    private Map<String, List<Map<String, Object>>> catalogs;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        ServerProperties config = new ServerProperties();
        ClientDefaults defaults = new ClientDefaults(mapper, config);
        WireCodec codec = new WireCodec(new WireModels(mapper));

        catalogs = new LinkedHashMap<>();
        catalogs.put("quest_stages", new ArrayList<>(List.of(
                J.map("stage_id", STANDARD_STAGE, "area_id", STANDARD_AREA,
                        "m_musics_id", 103102L, "clear_status", 0, "is_lock", false))));
        catalogs.put("daily_quest_stages", new ArrayList<>(List.of(
                J.map("stage_id", DAILY_STAGE, "quest_id", DAILY_QUEST,
                        "m_musics_id", 0, "clear_status", 0, "is_lock", false))));
        catalogs.put("learning_live_catalog", new ArrayList<>(List.of(
                J.map("learning_live_series_id", LEARNING_SERIES, "music_id", LEARNING_MUSIC,
                        "earned_music_exp", 0, "music_exp_level", 0, "is_mastery", false,
                        "stage_list", new ArrayList<>(List.of(
                                J.map("learning_live_stages_id", LEARNING_STAGE,
                                        "quest_rank", 1, "quest_level", 10, "is_lock", false,
                                        "is_clear", false, "gain_music_exp", 30, "page", 0)))),
                J.map("learning_live_series_id", 40103201L, "music_id", 103201L,
                        "earned_music_exp", 0, "music_exp_level", 0, "is_mastery", false,
                        "stage_list", new ArrayList<>()))));
        catalogs.put("grade_live_catalog", new ArrayList<>(List.of(
                J.map("quest_id", "10311", "current_rank", 0, "is_lock", false,
                        "is_try", false, "is_complete", false))));
        catalogs.put("grade_quest_stages", new ArrayList<>(List.of(
                J.map("stage_id", 11031101L, "series_id", 10311, "is_boss", false,
                        "clear_status", 0, "is_lock", false),
                J.map("stage_id", 11031199L, "series_id", 10311, "is_boss", true,
                        "clear_status", 0, "is_lock", true))));
        catalogs.put("grade_season_catalog", new ArrayList<>());
        catalogs.put("grade_quest_squares", new ArrayList<>(List.of(
                J.map("grade_quest_square_id", 11031101L, "grade_quest_series_id", 10311,
                        "target_id", 11031101L, "live_point", 0, "status", 0))));

        RefCatalogStore refs = mock(RefCatalogStore.class);
        when(refs.list(anyString())).thenAnswer(call ->
                new ArrayList<>(catalogs.getOrDefault(call.getArgument(0), List.of())));
        when(refs.get(anyString(), any())).thenReturn(null);

        ResourceCatalogLabels labels = new ResourceCatalogLabels(config, mapper);
        WireServices services = new WireServices(refs, labels);
        WireQuestMaster master = new WireQuestMaster(defaults);
        WireShop shop = new WireShop(defaults);
        com.linklike.server.wire.WireCardMaster cardMaster =
                new com.linklike.server.wire.WireCardMaster(defaults);
        WireCardMint mint = new WireCardMint(refs, new WireModels(mapper), cardMaster,
                new com.linklike.server.wire.WireFanCatalog(cardMaster));
        grants = spy(new WireGrants(mock(JdbcTemplate.class)));
        module = new WireQuestModule(codec, services, master, shop, mint,
                grants, defaults);
        router = new WireRouter(List.of(module), new RouteSnapshot(mapper));

        store = mock(PlayerStore.class);
        // 落库是空操作：状态本来就写在 PlayerContext 里，这里只验证调用了 save。
        doAnswer(call -> null).when(store).save(any());

        Player entity = new Player();
        entity.playerId = "P00000001";
        entity.playerName = "Tester";
        entity.staminaMax = 100;
        entity.staminaNow = 40;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("Decks", new ArrayList<>(List.of(J.map(
                "DDeckDatasId", "deck-1", "DeckName", "Deck", "DeckNo", 1,
                "GenerationsId", 1, "AceCard", "",
                "DeckCardsList", new ArrayList<>(), "IsChangeDeckCards", false,
                "GradeBonusValue", 0))));
        state.put("Cards", new ArrayList<>());
        player = new PlayerContext(entity, state);
    }

    // ------------------------------------------------------------------
    // 出题界面
    // ------------------------------------------------------------------

    @Test
    void stageSelectListsOnlyTheRequestedArea() {
        Map<String, Object> result = call("/v1/out_quest_live/get_stage_select",
                J.map("area_id", STANDARD_AREA));

        List<Map<String, Object>> stages = J.rowsOr(result.get("stage_list"));
        assertEquals(1, stages.size());
        assertEquals(STANDARD_STAGE, stages.get(0).get("stage_id"));
        assertEquals(40, ((Map<?, ?>) result.get("user_stamina")).get("stamina_now"));
        assertEquals(false, result.get("is_update_grade_live"));
    }

    @Test
    void stageSelectRejectsAnUnknownArea() {
        WireError error = assertThrows(WireError.class, () ->
                call("/v1/out_quest_live/get_stage_select", J.map("area_id", 999L)));
        assertEquals(404, error.status());
    }

    @Test
    void stageDataCarriesRewardsAndTheCatalogSong() {
        Map<String, Object> result = call("/v1/out_quest_live/get_stage_data",
                J.map("area_id", STANDARD_AREA, "stage_id", STANDARD_STAGE));

        assertEquals(STANDARD_STAGE, result.get("view_stage_id"));
        List<Map<String, Object>> details = J.rowsOr(result.get("stage_detail_list"));
        assertEquals(1, details.size());
        assertFalse(J.rowsOr(details.get(0).get("stage_reward_list")).isEmpty());
        assertFalse(J.rowsOr(result.get("music_list")).isEmpty());
        assertEquals(103102L, ((Number) J.rowsOr(result.get("music_list")).get(0)
                .get("m_musics_id")).longValue());
    }

    @Test
    void questTopServesStatusScalarsAndTheGradeDetail() {
        player.put("StandardLiveStatus", 1);
        player.put("GradeNum", 3);

        Map<String, Object> result = call("/v1/out_quest_live/get_quest_top", J.map());

        assertEquals(1, result.get("standard_live_status"));
        assertEquals(false, result.get("grand_prix_open"));
        assertEquals(3, ((Map<?, ?>) result.get("grade_detail")).get("grade_num"));
        assertNotNull(result.get("standard_area_select"));
    }

    @Test
    void liveSettingReturnsTheSelectedDeck() {
        Map<String, Object> result = call("/v1/out_quest_live/get_live_setting",
                J.map("quest_live_type", 1, "stage_id", STANDARD_STAGE));

        List<Map<String, Object>> decks = J.rowsOr(result.get("deck_data"));
        assertEquals(1, decks.size());
        assertEquals("deck-1", decks.get(0).get("d_deck_datas_id"));
        assertEquals("deck-1", ((Map<?, ?>) result.get("best_love_deck")).get("d_deck_datas_id"));
    }

    @Test
    void setLiveSettingRejectsASongTheStageDoesNotPlay() {
        WireError error = assertThrows(WireError.class, () ->
                call("/v1/out_quest_live/set_live_setting", J.map(
                        "quest_live_type", 1, "stage_id", STANDARD_STAGE,
                        "music_id", 999999)));
        assertEquals(400, error.status());
        assertNull(player.raw("ActiveQuest"));
    }

    @Test
    void setLiveSettingStoresAPlayableSongAndAnswersWithLiveInfo() {
        Map<String, Object> result = call("/v1/out_quest_live/set_live_setting",
                J.map("quest_live_type", 1, "stage_id", STANDARD_STAGE,
                        "music_id", 103102));

        assertEquals(true, result.get("result"));
        assertEquals(103102L, ((Number) result.get("music_id")).longValue());
        assertEquals(STANDARD_STAGE, result.get("quest_id"));
        assertEquals("play:1:103010101:103102:deck-1:", player.raw("ActiveQuest"));
    }

    // ------------------------------------------------------------------
    // 结算
    // ------------------------------------------------------------------

    @Test
    void finishWithAScoreRecordsStarsDropsAndStylePoint() {
        player.put("ActiveQuest", "play:1:" + STANDARD_STAGE + ":103102:deck-1:live-1");

        Map<String, Object> result = call("/v1/in_quest_live/set_finish",
                J.map("quest_live_id", "live-1", "score", 250000, "play_report", "report"));

        assertEquals(true, result.get("quest_result"));
        List<Map<String, Object>> states = player.rowsSnapshot("quest_stage_states");
        assertEquals(1, states.size());
        assertEquals(2L, ((Number) states.get(0).get("clear_status")).longValue());
        assertTrue(player.longOf("StylePoint", 0) > 0);

        String stored = String.valueOf(player.raw("ActiveQuest"));
        assertTrue(stored.startsWith("done:1:" + STANDARD_STAGE + ":103102:deck-1:live-1:"));
        assertTrue(stored.endsWith(":report"));
    }

    @Test
    void finishWithoutAScoreCountsAsAFullClear() {
        player.put("ActiveQuest", "play:1:" + STANDARD_STAGE + ":103102:deck-1:live-1");

        call("/v1/in_quest_live/set_finish", J.map("quest_live_id", "live-1"));

        assertEquals(3L, ((Number) player.rowsSnapshot("quest_stage_states").get(0)
                .get("clear_status")).longValue());
    }

    @Test
    void replayingADoneFinishKeepsTheOriginalResultAndDoesNotGrantAgain() {
        player.put("ActiveQuest", "play:1:" + STANDARD_STAGE + ":103102:deck-1:live-1");
        call("/v1/in_quest_live/set_finish",
                J.map("quest_live_id", "live-1", "score", 250000, "play_report", "first"));
        long stylePoint = player.longOf("StylePoint", 0);
        Object beginnerBefore = WireServices.deepCopyJson(player.raw("beginner_missions"));

        Map<String, Object> replay = call("/v1/in_quest_live/set_finish",
                J.map("quest_live_id", "live-1", "score", 1, "play_report", "retry"));

        assertEquals(true, replay.get("quest_result"));
        assertEquals(stylePoint, player.longOf("StylePoint", 0));
        assertEquals(beginnerBefore, player.raw("beginner_missions"));
        String stored = String.valueOf(player.raw("ActiveQuest"));
        assertTrue(stored.endsWith(":retry"));

        Map<String, Object> result = call("/v1/out_quest_live/get_result",
                J.map("quest_live_id", "live-1"));
        assertEquals(true, result.get("quest_result"));
        assertEquals(true, result.get("first_clear_flag"));
    }

    @Test
    void finishKeepsTheBestStarCountWhenTheNextRunIsWorse() {
        player.put("ActiveQuest", "play:1:" + STANDARD_STAGE + ":103102:deck-1:live-1");
        call("/v1/in_quest_live/set_finish",
                J.map("quest_live_id", "live-1", "score", 250000));
        player.put("ActiveQuest", "play:1:" + STANDARD_STAGE + ":103102:deck-1:live-2");
        call("/v1/in_quest_live/set_finish",
                J.map("quest_live_id", "live-2", "score", 1));

        assertEquals(2L, ((Number) player.rowsSnapshot("quest_stage_states").get(0)
                .get("clear_status")).longValue());
    }

    @Test
    void finishWithAForeignLiveIdIsRejected() {
        player.put("ActiveQuest", "play:1:" + STANDARD_STAGE + ":103102:deck-1:live-1");

        WireError error = assertThrows(WireError.class, () ->
                call("/v1/in_quest_live/set_finish",
                        J.map("quest_live_id", "other", "score", 1)));
        assertEquals(409, error.status());
    }

    @Test
    void finishWithoutAPlayStateIsRejected() {
        WireError error = assertThrows(WireError.class, () ->
                call("/v1/in_quest_live/set_finish", J.map("quest_live_id", "live-1")));
        assertEquals(409, error.status());
    }

    @Test
    void resultServesTheFinishedRunAndThenTheReport() {
        player.put("ActiveQuest", "play:1:" + STANDARD_STAGE + ":103102:deck-1:live-1");
        call("/v1/in_quest_live/set_finish",
                J.map("quest_live_id", "live-1", "score", 250000, "play_report", "abc"));

        Map<String, Object> result = call("/v1/out_quest_live/get_result",
                J.map("quest_live_id", "live-1"));
        assertEquals(true, result.get("quest_result"));
        assertEquals(250000L, result.get("result_love"));
        assertEquals("abc", result.get("play_report"));
        assertEquals(true, result.get("first_clear_flag"));

        Map<String, Object> report = call("/v1/out_quest_live/get_play_report",
                J.map("quest_live_id", "live-1"));
        assertEquals("abc", report.get("play_report"));
    }

    @Test
    void retireClearsTheLiveWithoutTouchingProgress() {
        player.put("ActiveQuest", "play:1:" + STANDARD_STAGE + ":103102:deck-1:live-1");

        Map<String, Object> result = call("/v1/in_quest_live/set_retire", J.map());

        assertEquals(1, result.get("quest_live_type"));
        assertEquals(0, result.get("return_resource"));
        assertNull(player.raw("ActiveQuest"));
        assertTrue(player.rowsSnapshot("quest_stage_states").isEmpty());
    }

    @Test
    void startWritesTheLiveIdAndReturnsAStartTime() {
        player.put("ActiveQuest", "play:1:" + STANDARD_STAGE + ":103102:deck-1:");

        Map<String, Object> result = call("/v1/in_quest_live/set_start",
                J.map("quest_live_id", "live-1", "init_hand_data", "hand"));

        assertTrue(String.valueOf(result.get("quest_start_time"))
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"));
        assertEquals("play:1:" + STANDARD_STAGE + ":103102:deck-1:live-1",
                player.raw("ActiveQuest"));
    }

    @Test
    void skipRequiresAClearedStage() {
        WireError error = assertThrows(WireError.class, () ->
                call("/v1/in_quest_live/skip_quest", J.map(
                        "quest_live_type", 1, "stage_id", STANDARD_STAGE, "skip_count", 1)));
        assertEquals(400, error.status());
    }

    @Test
    void skipSettlesEveryRunOfAClearedStage() {
        player.put("quest_stage_states", new ArrayList<>(List.of(
                J.map("stage_id", STANDARD_STAGE, "clear_status", 3, "is_lock", false))));

        Map<String, Object> result = call("/v1/in_quest_live/skip_quest", J.map(
                "quest_live_type", 1, "stage_id", STANDARD_STAGE, "skip_count", 3));

        assertEquals(3, J.rowsOr(result.get("skip_reward_list")).size());
        assertFalse(J.rowsOr(result.get("total_skip_reward_list")).isEmpty());
        assertTrue(player.longOf("StylePoint", 0) > 0);
        assertTrue(((Number) result.get("add_style_point")).longValue() > 0);
    }

    @Test
    void dataAccessFailuresWhileGrantingDropsAreNotSwallowed() {
        player.put("quest_stage_states", new ArrayList<>(List.of(
                J.map("stage_id", STANDARD_STAGE, "clear_status", 3, "is_lock", false))));
        doThrow(new IllegalStateException("database offline"))
                .when(grants).grantItem(any(), anyLong(), anyInt());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> call(
                "/v1/in_quest_live/skip_quest", J.map(
                        "quest_live_type", 1, "stage_id", STANDARD_STAGE, "skip_count", 1)));
        assertEquals("database offline", error.getMessage());
    }

    @Test
    void unverifiedDropTypeIsSkippedInsteadOfFailingTheSettlement() throws Exception {
        when(grants.item(987654L)).thenReturn(J.map("Id", 987654L, "Name", "unverified",
                "ItemType", 2, "ItemCategory", 0));
        Method grant = WireQuestModule.class.getDeclaredMethod(
                "grantQuestDrops", PlayerContext.class, List.class);
        grant.setAccessible(true);

        grant.invoke(module, player, List.of(
                J.map("item_id", 987654L, "item_num", 1)));

        assertTrue(player.rowsSnapshot("Items").isEmpty());
    }

    // ------------------------------------------------------------------
    // 学习关
    // ------------------------------------------------------------------

    @Test
    void learningSelectServesTheCatalogSeries() {
        Map<String, Object> result = call(
                "/v1/out_quest_live/music_learning/get_music_select", J.map());

        List<Map<String, Object>> music = J.rowsOr(result.get("music_list"));
        assertEquals(2, music.size());
        assertTrue(music.stream().anyMatch(row ->
                J.longOr(row.get("music_id"), -1) == LEARNING_MUSIC));
        assertEquals(0L, ((Number) result.get("music_point")).longValue());
    }

    @Test
    void learningFinishAddsTheStageExperienceToTheSong() {
        player.put("ActiveQuest", "play:3:" + LEARNING_STAGE + ":" + LEARNING_MUSIC
                + ":deck-1:live-1");

        call("/v1/in_quest_live/set_finish", J.map("quest_live_id", "live-1"));

        List<Map<String, Object>> progress = player.rowsSnapshot("learning_live_progress");
        assertEquals(1, progress.size());
        assertEquals(LEARNING_SERIES, ((Number) progress.get(0)
                .get("learning_live_series_id")).longValue());
        assertEquals(30L, ((Number) progress.get(0).get("earned_music_exp")).longValue());
        assertEquals(1, ((Number) progress.get(0).get("music_exp_level")).intValue());

        List<Map<String, Object>> stageList = J.rowsOr(progress.get(0).get("stage_list"));
        assertEquals(1, stageList.size());
        assertEquals(true, stageList.get(0).get("is_clear"));

        assertEquals(1, player.rowsSnapshot("RhythmMusic").size());
        assertEquals(30L, ((Number) player.rowsSnapshot("RhythmMusic").get(0)
                .get("MusicMasteryExp")).longValue());
    }

    @Test
    void learningResultReportsTheGainedExperience() {
        player.put("ActiveQuest", "play:3:" + LEARNING_STAGE + ":" + LEARNING_MUSIC
                + ":deck-1:live-1");
        call("/v1/in_quest_live/set_finish", J.map("quest_live_id", "live-1"));

        Map<String, Object> result = call(
                "/v1/out_quest_live/music_learning/get_result",
                J.map("quest_live_id", "live-1"));

        assertEquals(3, result.get("quest_live_type"));
        assertEquals(true, result.get("is_clear"));
        assertEquals(30L, result.get("earned_music_exp"));
        assertEquals(30L, result.get("mastary_level_experience"));
        assertEquals(0L, result.get("before_earned_music_exp"));
    }

    @Test
    void learningResultRejectsAStandardRun() {
        player.put("ActiveQuest", "play:1:" + STANDARD_STAGE + ":103102:deck-1:live-1");
        call("/v1/in_quest_live/set_finish", J.map("quest_live_id", "live-1"));

        WireError error = assertThrows(WireError.class, () ->
                call("/v1/out_quest_live/music_learning/get_result",
                        J.map("quest_live_id", "live-1")));
        assertEquals(409, error.status());
    }

    @Test
    void musicReleaseReadsAndDebitsThePlayerWalletColumn() {
        player.entity().musicPoint = 5L;

        Map<String, Object> before = call(
                "/v1/out_quest_live/music_learning/get_music_select", J.map());
        assertEquals(5L, before.get("music_point"));

        Map<String, Object> after = call(
                "/v1/out_quest_live/music_learning/set_music",
                J.map("music_id", 103201L));

        assertEquals(4L, after.get("music_point"));
        assertEquals(4L, player.entity().musicPoint);
        assertNull(player.raw("MusicPoint"));
    }

    // ------------------------------------------------------------------
    // Grade
    // ------------------------------------------------------------------

    @Test
    void gradeStageListSplitsNormalStagesFromTheBoss() {
        Map<String, Object> result = call("/v1/out_quest_live/grade/get_stage_list",
                J.map("quest_id", "10311", "rank", 1));

        List<Map<String, Object>> normal = J.rowsOr(result.get("normal_stage_list"));
        assertEquals(1, normal.size());
        assertEquals("11031101", normal.get(0).get("stage_id"));
        assertEquals("11031199", ((Map<?, ?>) result.get("boss_stage")).get("stage_id"));
    }

    @Test
    void gradeStageListRejectsAnUnknownSeries() {
        WireError error = assertThrows(WireError.class, () ->
                call("/v1/out_quest_live/grade/get_stage_list",
                        J.map("quest_id", "99999", "rank", 1)));
        assertEquals(404, error.status());
    }

    @Test
    void gradeRankListServesOneSelectableRank() {
        Map<String, Object> result = call("/v1/out_quest_live/grade/get_rank_list",
                J.map("quest_id", "10311"));

        assertEquals(1, J.rowsOr(result.get("rank_list")).size());
        assertEquals(0, result.get("current_rank"));
    }

    @Test
    void gradeRankingListStaysEmpty() {
        Map<String, Object> result = call("/v1/out_quest_live/grade/get_ranking_list",
                J.map("season_grade_id", 0, "get_rank_type", 0, "target_rank", 1));

        assertEquals(0, result.get("status"));
        assertTrue(J.rowsOr(result.get("point_rankings")).isEmpty());
    }

    @Test
    void gradeRankingListRejectsAnOutOfRangeRankType() {
        WireError error = assertThrows(WireError.class, () ->
                call("/v1/out_quest_live/grade/get_ranking_list",
                        J.map("get_rank_type", 3)));
        assertEquals(400, error.status());
    }

    // ------------------------------------------------------------------
    // 日常
    // ------------------------------------------------------------------

    @Test
    void dailyStageSelectServesTheTicketInfo() {
        Map<String, Object> result = call(
                "/v1/out_quest_live/daily/get_stage_select", J.map());

        Map<?, ?> tickets = (Map<?, ?>) result.get("user_daily_ticket_info");
        assertEquals(0, tickets.get("num"));
        assertNotNull(tickets.get("next_reset_time"));
    }

    @Test
    void dailyStageListFiltersByQuestId() {
        Map<String, Object> result = call("/v1/out_quest_live/daily/get_stage_list",
                J.map("quest_id", DAILY_QUEST));

        assertEquals(1, J.rowsOr(result.get("stage_list")).size());
    }

    @Test
    void dailyStageDataServesTheRewards() {
        Map<String, Object> result = call("/v1/out_quest_live/daily/get_stage_data",
                J.map("stage_id", DAILY_STAGE));

        assertEquals(DAILY_STAGE, result.get("stage_id"));
        assertNotNull(result.get("user_daily_ticket_info"));
        assertNotNull(result.get("music_list"));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

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
