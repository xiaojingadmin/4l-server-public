package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireCodec;
import com.linklike.server.protocol.WireError;
import com.linklike.server.service.PlayerContext;
import com.linklike.server.service.PlayerStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 节奏游戏（School Idol Show）的本地实现 —— 对应 Python 版 {@code wire_rhythm.py}。
 *
 * <p>{@code /v1/rhythm_game/home} 是客户端登录链路上的接口（{@code
 * UserDataRepository.CreateUserDataIfNeededAsync} 会与 {@code /v1/user/items/get_list}、
 * {@code /v1/user/card/check_style_level_up} 一起等它），九个响应成员客户端都做了
 * 判空保护，但字段必须齐全；只有 {@code friend_card_list} 是可选的。
 *
 * <p>歌曲与等级任务的定义来自抓包导入（{@code rhythm_music_catalog} /
 * {@code rhythm_class_mission_catalog} 两个参考目录），再叠上本账号自己的行。卡组、卡片、
 * 粉丝等级与好友卡片则纯粹是账号状态。
 *
 * <h2>本模块已实现的路由</h2>
 * {@code rhythm_game/home}、{@code rhythm_game_live/set_start}、
 * {@code rhythm_game_live/set_retire}、{@code rhythm_game_live/set_finish} 与三个卡组接口
 * （{@code rhythm_game_deck/modify_deck_list}、{@code set_deck_name}、
 * {@code set_reset_deck}）。
 *
 * <h2>结算（{@code set_finish}）</h2>
 * 结算要把「分数 / 连击 / 任务进度 / 熟练度」和奖励一起落库，主表驱动的算法在
 * {@link WireRhythmMaster}，发奖在 {@link WireGrants}（要 {@code master_items} 才能核实
 * 每条奖励的道具类型与背包归属）。Python 版为此先把整份账号状态 {@code deepcopy} 成
 * {@code draft}，全部算完再一次性 {@code save_player}；Java 的
 * {@link PlayerStore#save} 是单个事务，写失败整体回滚，所以这里就地改账号状态、最后统一落库 ——
 * 出错时同样什么都留不下。
 *
 * <p>{@code master_items} 不可用（这台机器没跑过 Python 侧的 {@code admin/catalog.py}）
 * 时按 Python 版同一句文案返回 500，而不是发一份拿不到的奖励清单。
 */
@Component
public class WireRhythmModule implements WireModule {

    /** {@code dump.cs MusicScoreDifficulty}：Normal=1 Hard=2 Expert=3 Master=4。 */
    private static final int MIN_DIFFICULTY = 1;
    private static final int MAX_DIFFICULTY = 4;

    /** {@code RhythmGameConditionType}：TotalClear=1 TotalScore=2 TotalHighScore=3。 */
    private static final List<Integer> MISSIONS = List.of(1, 2, 3);

    /**
     * 一次 SHOW live 每倍率的体力消耗（官方抓包：x1 时 220 -> 210）。
     * x0 是客户端的「不消耗游玩」。
     */
    private static final int STAMINA_COST = 10;

    /**
     * {@code NotesJudgementType}（Python {@code _COMBO} / {@code _PERFECT}）：连击与
     * FullCombo 记 Good 以上，AllPerfect 只认 Perfect 以上。
     */
    private static final int COMBO_JUDGE = 2;
    private static final int PERFECT_JUDGE = 4;

    /**
     * {@code RhythmGameLiveClearStatus}：NotClear=0 Clear=1 FullCombo=2 AllPerfect=3。
     */
    private static final int CLEAR_NOT_CLEAR = 0;
    private static final int CLEAR_CLEAR = 1;
    private static final int CLEAR_FULL_COMBO = 2;
    private static final int CLEAR_ALL_PERFECT = 3;

    /** 没有进行中的 live 时 {@code set_finish} 的 409 文案（Python {@code _active}）。 */
    private static final String NO_ACTIVE_LIVE = "No local SHOW live is in progress";

    /** 主数据目录不可用时结算的整体失败文案（Python {@code _grant_rewards}）。 */
    private static final String NO_MASTER_CATALOG =
            "Master catalog is unavailable; the SHOW live was not settled";

    /** {@code consume_stamina_magnification} 的可用取值区间（Python {@code _MAGNIFICATIONS}）。 */
    private static final int MIN_MAGNIFICATION = 0;
    private static final int MAX_MAGNIFICATION = 10;

    /** SHOW live 卡组最多六张卡（Python {@code _DECK_SLOTS}，与 {@code wire_initial.RHYTHM_DECK_SLOTS} 一致）。 */
    private static final int DECK_SLOTS = 6;

    /** 卡组编号区间（Python {@code _deck_no}）。 */
    private static final int MIN_DECK_NO = 1;
    private static final int MAX_DECK_NO = 100;

    /** 一次 {@code modify_deck_list} 允许提交的卡组条数。 */
    private static final int MAX_DECK_CHANGES = 100;

    private final WireCodec codec;
    private final WireServices services;
    private final WireRhythmMaster master;
    private final WireGrants grants;

    public WireRhythmModule(WireCodec codec, WireServices services, WireRhythmMaster master,
                            WireGrants grants) {
        this.codec = codec;
        this.services = services;
        this.master = master;
        this.grants = grants;
    }

    @Override
    public void register(WireRouter router) {
        router.register("/v1/rhythm_game/home", this::home);
        router.register("/v1/rhythm_game_live/set_start", this::setStart);
        router.register("/v1/rhythm_game_live/set_retire", this::setRetire);
        router.register("/v1/rhythm_game_live/set_finish", this::setFinish);
        router.register("/v1/rhythm_game_deck/modify_deck_list", this::deckWrites);
        router.register("/v1/rhythm_game_deck/set_deck_name", this::deckWrites);
        router.register("/v1/rhythm_game_deck/set_reset_deck", this::deckWrites);
    }

    // ------------------------------------------------------------------
    // 首页
    // ------------------------------------------------------------------

    /**
     * {@code /v1/rhythm_game/home}：歌曲与等级任务目录 + 账号自己的卡组 / 卡片 / 粉丝等级。
     *
     * <p>SHOW 首页与卡组编辑界面在 live 进行中都进不去，所以这里看到残留的
     * {@code ActiveRhythmGame} 只可能是「App 被杀掉留下的」，直接丢弃 —— 体力与奖励都只在
     * 结算时落账，丢掉不亏任何东西。
     */
    private Map<String, Object> home(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        discardAbandonedLive(player, request.store());

        List<Map<String, Object>> music = catalog(request, player, "rhythm_music_catalog",
                "RhythmMusic", "RhythmGameMusic", "music_id", WireServices.RHYTHM_MUSIC_ASSETS);
        List<Map<String, Object>> missions = catalog(request, player,
                "rhythm_class_mission_catalog", "RhythmClassMissions",
                "RhythmGameClassMissionInfo", "condition_type", null);

        Map<String, Object> response = codec.encode("RhythmGameHomeGetResponse", J.map(
                "RhythmGameDeckList", player.rowsSnapshot("RhythmGameDecks"),
                "CardDataList", player.rowsSnapshot("Cards"),
                "ReceivedTotalMissionOrder", player.intOf("RhythmReceivedTotalMissionOrder", 0),
                "RhythmGameStarTotalCount", player.intOf("RhythmGameStarTotalCount", 0),
                "MemberFanlevelList", player.rowsSnapshot("MemberFanLevels"),
                "FriendCardList", player.rowsSnapshot("RhythmFriendCards"),
                "AutoPlayTicketInfo", ticket(player)));
        // 这两项本身已经是线上形态，放在 encode() 之后插入，不走属性名映射。
        response.put("music_list", music);
        response.put("class_mission_list", missions);
        return response;
    }

    /**
     * 自动游玩券信息 —— 对应 Python {@code _ticket}。
     *
     * <p>{@code NextResetTime} 是下一个 04:00 JST，与日常券共用同一条上游重置边界；
     * 不落库（每次按当前时刻算），所以刷新页面时它只会往后走。
     */
    private Map<String, Object> ticket(PlayerContext player) {
        return J.map(
                "Num", player.intOf("AutoPlayTicketNum", 10),
                "Max", player.intOf("AutoPlayTicketMax", 10),
                "NextResetTime", WireServices.nextDailyReset());
    }

    /**
     * 导入的目录行按资源过滤后叠上账号自己的行 —— 对应 Python {@code _catalog}。
     *
     * @param storage 账号里存这类记录的状态键（{@code RhythmMusic} / {@code RhythmClassMissions}）
     * @param model   元素模型；账号行先编码成线上拼写再与目录行合并
     * @param assets  每条记录需要的资源 label；null 表示这一层不过滤资源
     */
    private List<Map<String, Object>> catalog(WireRequest request, PlayerContext player,
                                              String catalogKey, String storage, String model,
                                              String identity, List<String> assets) {
        List<Map<String, Object>> rows = assets == null
                ? services.refs(catalogKey)
                : services.served(catalogKey, assets);
        List<Map<String, Object>> owned = new ArrayList<>();
        for (Map<String, Object> row : player.rowsSnapshot(storage)) {
            owned.add(codec.encode(model, row));
        }
        return WireServices.overlay(rows, owned, identity);
    }

    // ------------------------------------------------------------------
    // SHOW live 开始 / 中止
    // ------------------------------------------------------------------

    /**
     * {@code /v1/rhythm_game_live/set_start}：确认开始，只把谱面与倍率记在账号上，
     * 体力留到结算时再扣（官方抓包里的开始响应头仍然是游玩前的体力值）。
     */
    private Map<String, Object> setStart(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> body = request.body();

        int musicId = requiredInteger(body, "music_id", 1, Integer.MAX_VALUE);
        int difficulty = requiredInteger(body, "music_score_difficulty",
                MIN_DIFFICULTY, MAX_DIFFICULTY);
        int slot = requiredInteger(body, "use_slot_no", 1, Integer.MAX_VALUE);
        int magnification = 1;
        if (body.get("consume_stamina_magnification") != null) {
            magnification = requiredInteger(body, "consume_stamina_magnification",
                    MIN_MAGNIFICATION, MAX_MAGNIFICATION);
        }
        for (String key : List.of("friend_card_player_id", "friend_card_id")) {
            Object value = body.get(key);
            if (value != null && !(value instanceof String)) {
                throw WireError.badRequest(key + " must be a string");
            }
        }
        int cost = STAMINA_COST * magnification;
        if (Stamina.tracked(player) && Stamina.current(player) < cost) {
            throw WireError.badRequest("スタミナが不足しています。("
                    + Stamina.current(player) + " / " + cost + ")");
        }
        player.put("ActiveRhythmGame", activeKey(musicId, difficulty, slot, magnification));
        request.store().save(player);
        return new LinkedHashMap<>();
    }

    /** {@code /v1/rhythm_game_live/set_retire}：丢掉进行中的 live，不结算体力与奖励。 */
    private Map<String, Object> setRetire(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        setRetire(player, request.store());
        return new LinkedHashMap<>();
    }

    private void setRetire(PlayerContext player, PlayerStore store) {
        if (player.has("ActiveRhythmGame")) {
            player.remove("ActiveRhythmGame");
            store.save(player);
        }
    }

    /**
     * {@code ActiveRhythmGame} 的存档形态 —— 对应 Python 的
     * {@code '%d:%d:%d:%d'}；第四个字段是 {@code set_start} 记下的体力倍率。
     *
     * <p>注意这里的「倍率」是直接相乘的倍数，而不是客户端请求里的百分比取值。
     */
    private static String activeKey(int musicId, int difficulty, int slot, int magnification) {
        return musicId + ":" + difficulty + ":" + slot + ":" + magnification;
    }

    // ------------------------------------------------------------------
    // SHOW live 结算
    // ------------------------------------------------------------------

    /**
     * {@code /v1/rhythm_game_live/set_finish}：把一局 SHOW live 的结果写进账号并回一份结算详情。
     *
     * <p>落库顺序与 Python 版一致：先算分数 / 连击 / 熟练度，再按倍率掷掉落、算得分档位奖励，
     * 然后扣体力、推进三个等级任务、发奖，最后一次性写回。中途任何一步抛错（体力不足、
     * 主数据目录不可用）都不会留下半截状态。
     */
    private Map<String, Object> setFinish(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        PlayerStore store = request.store();
        Map<String, Object> body = request.body();

        int[] active = active(player);
        int musicId = active[0];
        int difficulty = active[1];
        int magnification = active[2];

        if (body.get("play_data") != null && !(body.get("play_data") instanceof Map<?, ?>)) {
            throw WireError.badRequest("play_data must be an object");
        }
        // Python 的 `type(...) is not bool`：有键就必须是真正的布尔，数字与字符串都不收。
        if (body.containsKey("enable_auto") && !(body.get("enable_auto") instanceof Boolean)) {
            throw WireError.badRequest("enable_auto must be a boolean");
        }
        Long score = optionalInteger(body, "score");
        Long technical = optionalInteger(body, "technical_score");
        Long remain = optionalInteger(body, "remain_mental");
        List<Integer> judgements = judgements(body);
        int clear = clearStatus(remain, judgements, score);
        int combo = maxCombo(judgements);
        long gained = score == null ? 0L : score;
        long tech = technical == null ? 0L : technical;

        Map<String, Object> before = musicRow(player, musicId);
        Map<String, Object> slot = scoreSlot(before, difficulty);
        long highScoreBefore = J.longOr(before.get("HighScore"), 0);
        int comboBefore = J.intOr(slot.get("BestCombo"), 0);
        long technicalBefore = J.longOr(slot.get("TechnicalScore"), 0);
        int lampBefore = J.intOr(slot.get("ClearLamp"), 0);
        int comboRankBefore = J.intOr(slot.get("ComboAchievementStatus"), 0);
        int scoreRankBefore = J.intOr(before.get("HighScoreAchievementStatus"), 0);

        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("HighScore", Math.max(highScoreBefore, gained));
        slot.put("TechnicalScore", Math.max(technicalBefore, tech));
        slot.put("BestCombo", Math.max(comboBefore, combo));
        if (clear != CLEAR_NOT_CLEAR) {
            slot.put("ClearLamp", Math.max(lampBefore, clear));
        }

        Map<String, Object> chart = master.chart(musicId);
        long expBefore = J.longOr(before.get("MusicMasteryExp"), 0);
        long gainedExp = WireRhythmMaster.lookup(chartValue(chart, "gain_exp"), difficulty, 0)
                * magnification;
        // 官方抓包里的失败局（103119）同样拿到经验。
        long expAfter = expBefore + gainedExp;
        after.put("MusicMasteryExp", expAfter);
        after.put("MusicMasteryLevel", master.masteryLevel(
                chart == null ? 0 : J.longOr(chart.get("experience_type"), 0), expAfter));

        long maxCombo = WireRhythmMaster.lookup(chartValue(chart, "max_combo"), difficulty, 0);
        long comboPct = maxCombo != 0 ? J.longOr(slot.get("BestCombo"), 0) * 100 / maxCombo : 0;
        Object scoreSeries = chartValue(chart, "score_reward_series");
        Map<String, Object> scoreRow = rewardRow(scoreSeries, 0);
        Map<String, Object> comboRow = rewardRow(scoreSeries, difficulty);
        int scoreRankAfter = Math.max(scoreRankBefore, scoreRow == null ? 0
                : WireRhythmMaster.rank(J.longOr(after.get("HighScore"), 0),
                        J.listOrEmpty(scoreRow.get("thresholds"))));
        int comboRankAfter = Math.max(comboRankBefore, comboRow == null ? 0
                : WireRhythmMaster.rank(comboPct, J.listOrEmpty(comboRow.get("thresholds"))));
        after.put("HighScoreAchievementStatus", scoreRankAfter);
        slot.put("ComboAchievementStatus", comboRankAfter);
        List<Object> scores = new ArrayList<>();
        for (Object item : J.listOrEmpty(after.get("MusicScores"))) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> row = J.nodeOr(raw);
            if (!sameNumber(field(row, "Difficulty", "difficulty"), difficulty)) {
                scores.add(item);
            }
        }
        scores.add(slot);
        after.put("MusicScores", scores);

        boolean enableAuto = Boolean.TRUE.equals(body.get("enable_auto"));
        Instant now = Instant.now();
        Random rng = new Random();
        long dropSeries = WireRhythmMaster.lookup(chartValue(chart, "drop_series"), difficulty, 0);
        List<Map<String, Object>> drops = new ArrayList<>();
        if (dropSeries != 0) {
            // 每个倍率各掷一轮掉落，与官方「倍率越高、掉落越多」一致。
            for (int round = 0; round < magnification; round++) {
                drops.addAll(master.dropRewards(dropSeries, enableAuto, now, rng));
            }
            drops = mergeRewards(drops);
        }
        List<Map<String, Object>> missionRewards = new ArrayList<>();
        long scoreSeriesId = J.longOr(scoreSeries, 0);
        if (scoreSeriesId != 0) {
            missionRewards.addAll(
                    master.scoreRewards(scoreSeriesId, 0, scoreRankBefore, scoreRankAfter));
            missionRewards.addAll(master.scoreRewards(
                    scoreSeriesId, difficulty, comboRankBefore, comboRankAfter));
        }

        if (!Stamina.consume(player, STAMINA_COST * magnification, now)) {
            throw WireError.badRequest("スタミナが不足しています。");
        }
        Map<Integer, Map<String, Object>> missions = missionRows(player);
        List<Map<String, Object>> progress = new ArrayList<>();
        long clearBefore = J.longOr(missions.get(1).get("ProgressNum"), 0);
        long clearAfter = clearBefore + (clear != CLEAR_NOT_CLEAR ? 1 : 0);
        progress.add(progressRow(1, clearBefore, clearAfter));
        missions.get(1).put("ProgressNum", clearAfter);
        long playBefore = J.longOr(missions.get(2).get("ProgressNum"), 0);
        long playAfter = playBefore + gained;
        progress.add(progressRow(2, playBefore, playAfter));
        missions.get(2).put("ProgressNum", playAfter);
        putMusic(player, after);
        // 官方首页抓包（20260909-122755）：type 3 记的是各曲最高分之和
        // （约 183M），而不是 type 2 那种累计得分（约 283M）。
        long highScoreAfter = highScoreTotal(player);
        progress.add(progressRow(3, J.longOr(missions.get(3).get("ProgressNum"), 0), highScoreAfter));
        missions.get(3).put("ProgressNum", highScoreAfter);
        player.put("RhythmClassMissions",
                new ArrayList<>(List.of(missions.get(1), missions.get(2), missions.get(3))));

        List<Map<String, Object>> rewards = new ArrayList<>(drops);
        rewards.addAll(missionRewards);
        grantRewards(player, rewards);
        player.remove("ActiveRhythmGame");
        store.save(player);

        return codec.encode("RhythmGameLiveSetFinishResponse", J.map(
                "MusicMasteryLevelResult", J.map(
                        "MusicId", musicId,
                        "MusicMasteryExpBefore", expBefore,
                        "MusicMasteryExpAfter", expAfter,
                        "OwnStatus", knownMusic(request, musicId) ? 2 : 0),
                "DropRewardList", drops,
                "MusicScoreMissionResult", J.map(
                        "HighScoreBefore", highScoreBefore,
                        "HighScoreAfter", after.get("HighScore"),
                        "HighScoreAchievementStatusBefore", scoreRankBefore,
                        "HighScoreAchievementStatusAfter", scoreRankAfter,
                        "MaxComboBefore", comboBefore,
                        "MaxComboAfter", slot.get("BestCombo"),
                        "ComboAchievementStatusBefore", comboRankBefore,
                        "ComboAchievementStatusAfter", comboRankAfter,
                        "TechnicalScoreBefore", technicalBefore,
                        "TechnicalScoreAfter", slot.get("TechnicalScore"),
                        "RewardList", missionRewards),
                "ClearStatus", clear,
                "ClassMissionProgressList", progress,
                "AutoPlayTicketInfo", ticket(player),
                "RhythmGameStarTotalCount", player.intOf("RhythmGameStarTotalCount", 0),
                "AppliedCampaignTypes", new ArrayList<>()));
    }

    /**
     * 进行中的 live —— 对应 Python {@code _active}。
     *
     * <p>存档形态是 {@code music:difficulty:slot[:magnification]}，第四个字段是
     * {@code set_start} 记下的体力倍率；三段（旧存档）时按 x1 处理。任何一段解析不出来
     * 都当作「没有进行中的 live」，返回 409 而不是猜。
     *
     * @return {@code [music_id, difficulty, magnification]}
     */
    private static int[] active(PlayerContext player) {
        String raw = player.str("ActiveRhythmGame");
        String[] parts = (raw == null ? "" : raw).split(":", -1);
        if (parts.length != 3 && parts.length != 4) {
            throw WireError.conflict(NO_ACTIVE_LIVE);
        }
        int musicId;
        int difficulty;
        int slot;
        int magnification;
        try {
            musicId = Integer.parseInt(parts[0]);
            difficulty = Integer.parseInt(parts[1]);
            slot = Integer.parseInt(parts[2]);
            magnification = parts.length == 4 ? Integer.parseInt(parts[3]) : 1;
        } catch (NumberFormatException e) {
            throw WireError.conflict(NO_ACTIVE_LIVE);
        }
        if (musicId < 1 || difficulty < MIN_DIFFICULTY || difficulty > MAX_DIFFICULTY || slot < 1) {
            throw WireError.conflict(NO_ACTIVE_LIVE);
        }
        if (magnification < MIN_MAGNIFICATION || magnification > MAX_MAGNIFICATION) {
            throw WireError.conflict(NO_ACTIVE_LIVE);
        }
        return new int[] {musicId, difficulty, magnification};
    }

    /**
     * 判定结果列表 —— 对应 Python {@code _judgements}。
     *
     * <p>省略 {@code notes_result_list} 表示客户端没上报（当作空表，连击算 0）；给了就必须是
     * 对象数组，每条都要有 0..5 的 {@code judgement_result}。
     */
    private static List<Integer> judgements(Map<String, Object> body) {
        Object raw = body.get("notes_result_list");
        if (raw == null) {
            return new ArrayList<>();
        }
        if (!(raw instanceof List<?> list)) {
            throw WireError.badRequest("notes_result_list must be a list");
        }
        List<Integer> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?>)) {
                throw WireError.badRequest("each note result must be an object");
            }
            result.add((int) WireServices.integer(J.nodeOr(item), "judgement_result",
                    (Long) null, 0, 5));
        }
        return result;
    }

    /**
     * 通关状态 —— 对应 Python {@code _clear_status}。
     *
     * <p>{@code remain_mental} 为 0 表示中途失败，直接是 NotClear（即使判定全 Perfect）；
     * 有判定结果时按最差的一条定档；没上报判定结果且分数与剩余精神也都没给，只能判 NotClear。
     */
    private static int clearStatus(Long remain, List<Integer> judgements, Long score) {
        if (remain != null && remain == 0L) {
            return CLEAR_NOT_CLEAR;
        }
        if (!judgements.isEmpty()) {
            boolean perfect = true;
            boolean combo = true;
            for (int judge : judgements) {
                perfect &= judge >= PERFECT_JUDGE;
                combo &= judge >= COMBO_JUDGE;
            }
            if (perfect) {
                return CLEAR_ALL_PERFECT;
            }
            return combo ? CLEAR_FULL_COMBO : CLEAR_CLEAR;
        }
        if (remain == null && score == null) {
            return CLEAR_NOT_CLEAR;
        }
        return CLEAR_CLEAR;
    }

    /** 最长连击 —— 对应 Python {@code _max_combo}（Good 以上才续上连击）。 */
    private static int maxCombo(List<Integer> judgements) {
        int best = 0;
        int current = 0;
        for (int judge : judgements) {
            if (judge >= COMBO_JUDGE) {
                current++;
                best = Math.max(best, current);
            } else {
                current = 0;
            }
        }
        return best;
    }

    /**
     * 账号里这首歌的熟练度行，归一成结算用的六个字段 —— 对应 Python {@code _music_row}。
     *
     * <p>没有这首歌时返回「全 0 的空行」，而不是少写几个键：结算结果要把这一行整体写回去。
     */
    private static Map<String, Object> musicRow(PlayerContext player, int musicId) {
        for (Map<String, Object> row : player.rowsSnapshot("RhythmMusic")) {
            if (!sameNumber(field(row, "MusicId", "music_id"), musicId)) {
                continue;
            }
            return J.map(
                    "MusicId", musicId,
                    "MusicScores", new ArrayList<>(J.listOrEmpty(
                            field(row, "MusicScores", "music_scores"))),
                    "HighScore", J.longOr(field(row, "HighScore", "high_score"), 0),
                    "HighScoreAchievementStatus", J.longOr(
                            field(row, "HighScoreAchievementStatus",
                                    "high_score_achievement_status"), 0),
                    "MusicMasteryLevel", J.longOr(
                            field(row, "MusicMasteryLevel", "music_mastery_level"), 0),
                    "MusicMasteryExp", J.longOr(
                            field(row, "MusicMasteryExp", "music_mastery_exp"), 0));
        }
        return J.map(
                "MusicId", musicId,
                "MusicScores", new ArrayList<>(),
                "HighScore", 0,
                "HighScoreAchievementStatus", 0,
                "MusicMasteryLevel", 0,
                "MusicMasteryExp", 0);
    }

    /** 某个难度的成绩行，归一成五个字段 —— 对应 Python {@code _score_slot}。 */
    private static Map<String, Object> scoreSlot(Map<String, Object> musicRow, int difficulty) {
        for (Map<String, Object> slot : J.rowsOr(musicRow.get("MusicScores"))) {
            if (!sameNumber(field(slot, "Difficulty", "difficulty"), difficulty)) {
                continue;
            }
            return J.map(
                    "Difficulty", difficulty,
                    "TechnicalScore", J.longOr(field(slot, "TechnicalScore",
                            "technical_score"), 0),
                    "BestCombo", J.longOr(field(slot, "BestCombo", "best_combo"), 0),
                    "ComboAchievementStatus", J.longOr(field(slot,
                            "ComboAchievementStatus", "combo_achievement_status"), 0),
                    "ClearLamp", J.longOr(field(slot, "ClearLamp", "clear_lamp"), 0));
        }
        return J.map("Difficulty", difficulty, "TechnicalScore", 0, "BestCombo", 0,
                "ComboAchievementStatus", 0, "ClearLamp", 0);
    }

    /** 把结算后的熟练度行整体写回 {@code RhythmMusic} —— 对应 Python {@code _put_music}。 */
    private static void putMusic(PlayerContext player, Map<String, Object> row) {
        long musicId = J.longOr(row.get("MusicId"), 0);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> item : player.rowsSnapshot("RhythmMusic")) {
            if (!sameNumber(field(item, "MusicId", "music_id"), musicId)) {
                rows.add(item);
            }
        }
        rows.add(row);
        player.put("RhythmMusic", rows);
    }

    /**
     * 三个等级任务（通关数 / 累计得分 / 最高分之和）的进度行 —— 对应 Python
     * {@code _mission_rows}。
     *
     * <p>只保留这三个 {@code ConditionType}，账号里其它类型的行会被丢掉；每个任务的字段也只剩
     * 三个（进度与已领档位），与 Python 写回的形态一致。
     */
    private static Map<Integer, Map<String, Object>> missionRows(PlayerContext player) {
        Map<Integer, Map<String, Object>> byType = new LinkedHashMap<>();
        for (Map<String, Object> row : player.rowsSnapshot("RhythmClassMissions")) {
            Object raw = field(row, "ConditionType", "condition_type");
            if (!(raw instanceof Number number) || !MISSIONS.contains(number.intValue())) {
                continue;
            }
            int type = number.intValue();
            byType.put(type, J.map(
                    "ConditionType", type,
                    "ProgressNum", J.longOr(field(row, "ProgressNum", "progress_num"), 0),
                    "ReceivedOrder", J.longOr(
                            field(row, "ReceivedOrder", "received_order"), 0)));
        }
        for (int type : MISSIONS) {
            byType.putIfAbsent(type, J.map("ConditionType", type, "ProgressNum", 0,
                    "ReceivedOrder", 0));
        }
        return byType;
    }

    /** 各曲最高分之和 —— 对应 Python {@code _high_score_total}。 */
    private static long highScoreTotal(PlayerContext player) {
        long total = 0;
        for (Map<String, Object> row : player.rowsSnapshot("RhythmMusic")) {
            total += J.longOr(field(row, "HighScore", "high_score"), 0);
        }
        return total;
    }

    /** 这首歌在本机已下发资源的节奏游戏目录里是否存在 —— 对应 Python {@code _known_music}。 */
    private boolean knownMusic(WireRequest request, int musicId) {
        for (Map<String, Object> row : services.served(
                "rhythm_music_catalog", WireServices.RHYTHM_MUSIC_ASSETS)) {
            if (sameNumber(row.get("music_id"), musicId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 同一条奖励合并数量，保留首次出现的顺序 —— 对应 Python {@code _merge_rewards}。
     *
     * <p>掉落按倍率掷了多轮，同一件道具会重复出现；合并后客户端只看到一行。
     */
    private static List<Map<String, Object>> mergeRewards(List<Map<String, Object>> rewards) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> row : rewards) {
            String key = row.get("RewardType") + "\u0000" + row.get("RewardItemId");
            Map<String, Object> existing = merged.get(key);
            if (existing == null) {
                merged.put(key, new LinkedHashMap<>(row));
            } else {
                existing.put("RewardNum",
                        J.longOr(existing.get("RewardNum"), 0) + J.longOr(row.get("RewardNum"), 0));
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * 把奖励真正发到账号上 —— 对应 Python {@code _grant_rewards}。
     *
     * <p>只有背包类奖励（{@code ItemType} 在 {@link WireGrants#INVENTORY_TYPES} 里）走这一层；
     * 主数据里查不到、或本地没有可核实发放路径的单条奖励跳过（Python 的
     * {@code except ValueError: continue}），不让整单失败。但主数据目录整体不可用时必须整单拒绝：
     * 否则客户端会显示一份拿不到的奖励清单。
     */
    private void grantRewards(PlayerContext player, List<Map<String, Object>> rewards) {
        if (rewards.isEmpty()) {
            return;
        }
        if (!grants.available()) {
            throw new WireError(500, NO_MASTER_CATALOG);
        }
        for (Map<String, Object> reward : rewards) {
            Object rawType = reward.get("RewardType");
            if (!(rawType instanceof Number number)
                    || !WireGrants.INVENTORY_TYPES.contains(number.intValue())) {
                continue;
            }
            try {
                grants.grantItem(player, J.longOr(reward.get("RewardItemId"), 0),
                        J.intOr(reward.get("RewardNum"), 0));
            } catch (IllegalArgumentException ignored) {
                // 这一条没有本地发放路径，跳过它。
            }
        }
    }

    /** 得分 / 连击档位的奖励行 —— 对应 Python 里对 {@code score_rewards} 的两次 {@code next(...)}。 */
    private Map<String, Object> rewardRow(Object seriesId, int rewardType) {
        for (Map<String, Object> row : master.scoreRewardsOf(seriesId)) {
            if (J.intOr(row.get("type"), -1) == rewardType) {
                return row;
            }
        }
        return null;
    }

    private static Map<String, Object> progressRow(int conditionType, long before, long after) {
        return J.map("ConditionType", conditionType, "ProgressBefore", before,
                "ProgressAfter", after);
    }

    /** 主表行里可能缺席的字段；{@code chart} 为 null（本地没有这首歌）时一律缺席。 */
    private static Object chartValue(Map<String, Object> chart, String key) {
        return chart == null ? null : chart.get(key);
    }

    /**
     * Python 的 {@code row.get('PascalKey', row.get('snake_key'))}：PascalCase 键存在就用它
     * （哪怕是 null），否则退回 snake_case。不能用 {@code getOrDefault} —— 那在值为 null 时
     * 会去取备用键，与 Python 不同。
     */
    private static Object field(Map<String, Object> row, String pascal, String snake) {
        return row.containsKey(pascal) ? row.get(pascal) : row.get(snake);
    }

    /**
     * Python {@code value == expected} 的严格数值比较：字符串 {@code "5"} 不等于 {@code 5}。
     *
     * <p>账号状态是我们自己写的整数，这里只需要排除「类型不对却数值相等」的情况。
     */
    private static boolean sameNumber(Object value, long expected) {
        return value instanceof Number number && number.longValue() == expected;
    }

    /** 显式整数，缺字段或为 null 时返回 null —— 对应 Python {@code _optional_int}。 */
    private static Long optionalInteger(Map<String, Object> body, String key) {
        if (!body.containsKey(key) || body.get(key) == null) {
            return null;
        }
        return WireServices.integer(body, key, (Long) null, 0, Long.MAX_VALUE);
    }

    // ------------------------------------------------------------------
    // SHOW live 卡组
    // ------------------------------------------------------------------

    /**
     * 三个卡组接口共用的写入路径 —— 对应 Python {@code _deck_writes}。
     *
     * <p>卡组存在账号本地（{@code RhythmGameDecks}），按 {@code deck_no} 索引；卡片必须是
     * 本账号拥有的。改动连同「丢掉残留的 {@code ActiveRhythmGame}」一起落库：客户端在 live
     * 进行中进不了卡组编辑界面，能带着进行中的 live 改卡组只可能是状态不一致。
     *
     * <p>Python 版在每个写入点手写了「失败就把旧值放回去」的恢复分支；Java 的
     * {@link com.linklike.server.service.PlayerStore#save} 是事务性的，写失败时整个事务回滚，
     * 不存在写一半的中间态，所以这里不做手工恢复。
     */
    private Map<String, Object> deckWrites(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> body = request.body();
        List<Map<String, Object>> decks = WireServices.rows(player, "RhythmGameDecks");
        Map<Integer, Map<String, Object>> byNo = new LinkedHashMap<>();
        for (Map<String, Object> deck : decks) {
            byNo.put(J.intOr(deck.get("DeckNo"), 0), deck);
        }

        if (request.path().endsWith("/modify_deck_list")) {
            // 逐条校验：非对象的条目要报「每条改动必须是对象」，不能当成空表糊过去。
            List<Object> changes = J.listOr(request.arg("modify_deck_list"));
            if (changes == null || changes.isEmpty() || changes.size() > MAX_DECK_CHANGES) {
                throw WireError.badRequest(
                        "modify_deck_list must contain 1 to " + MAX_DECK_CHANGES + " decks");
            }
            Set<String> owned = ownedCardIds(player);
            Set<Integer> seen = new HashSet<>();
            for (Object item : changes) {
                if (!(item instanceof Map<?, ?> rawChange)) {
                    throw WireError.badRequest("Each deck change must be an object");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> change = (Map<String, Object>) rawChange;
                int number = deckNo(change);
                if (!seen.add(number)) {
                    throw WireError.badRequest("Duplicate deck_no in modify_deck_list");
                }
                // Python 的 `change.get('deck_card_list', [])`：键缺失才是空表，
                // 键存在但值不是列表要走 _deck_cards 的校验失败。
                Object rawCards = change.containsKey("deck_card_list")
                        ? change.get("deck_card_list") : List.of();
                List<Map<String, Object>> cards = deckCards(rawCards, owned);
                Map<String, Object> deck = byNo.get(number);
                if (deck == null) {
                    deck = J.map("RhythmGameDeckId", UUID.randomUUID().toString(),
                            "Name", "", "DeckNo", number);
                    decks.add(deck);
                    byNo.put(number, deck);
                }
                deck.put("DeckCardList", cards);
            }
            commitDecks(player, request.store(), decks);
            return new LinkedHashMap<>();
        }

        int number = deckNo(body);
        Map<String, Object> deck = byNo.get(number);
        if (deck == null) {
            throw WireError.notFound("Local rhythm game deck not found");
        }
        if (request.path().endsWith("/set_deck_name")) {
            Object rawName = body.get("name");
            if (!(rawName instanceof String name) || name.length() > 128) {
                throw WireError.badRequest("Invalid name");
            }
            deck.put("Name", name);
        } else {
            deck.put("DeckCardList", new ArrayList<>());
        }
        commitDecks(player, request.store(), decks);
        return new LinkedHashMap<>();
    }

    /** 卡组编号 —— 对应 Python {@code _deck_no}。 */
    private static int deckNo(Map<String, Object> body) {
        return requiredInteger(body, "deck_no", MIN_DECK_NO, MAX_DECK_NO);
    }

    /**
     * 必填整数 + 闭区间校验 —— 对应 Python 的
     * {@code wire_services._integer(body, key, None, error, minimum, maximum)}。
     *
     * <p>{@code fallback} 传 {@code (Integer) null}：缺字段与类型不对都走同一句 400 文案。
     */
    private static int requiredInteger(Map<String, Object> body, String key,
                                       int minimum, int maximum) {
        return WireServices.integer(body, key, (Integer) null, minimum, maximum);
    }

    /**
     * 客户端提交的卡组卡片列表 -> 账号自己生成的卡组卡行 —— 对应 Python {@code _deck_cards}。
     *
     * <p>卡片必须本账号拥有，槽位 1..6 且不可重复；卡片 ID 与槽位都不允许在一次请求里重复。
     * 生成的 {@code RhythmGameDeckCardsId} 是服务端新造的实例 ID，客户端下次就按它引用。
     */
    private static List<Map<String, Object>> deckCards(Object raw, Set<String> owned) {
        if (!(raw instanceof List<?> list) || list.size() > DECK_SLOTS) {
            throw WireError.badRequest("deck_card_list must hold at most " + DECK_SLOTS + " cards");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seenCards = new HashSet<>();
        Set<Integer> seenSlots = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> rawCard)) {
                throw WireError.badRequest("Each deck card must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> card = (Map<String, Object>) rawCard;
            Object rawId = card.get("d_card_datas_id");
            if (!(rawId instanceof String cardId) || cardId.isEmpty() || !owned.contains(cardId)) {
                throw WireError.badRequest("Card is not owned");
            }
            Long slot = WireServices.strictLong(card.get("slot_no"));
            if (slot == null || slot < 1 || slot > DECK_SLOTS) {
                throw WireError.badRequest("Invalid slot_no");
            }
            if (seenCards.contains(cardId) || seenSlots.contains(slot.intValue())) {
                throw WireError.badRequest("Duplicate card or slot_no in deck");
            }
            seenCards.add(cardId);
            seenSlots.add(slot.intValue());
            result.add(J.map("RhythmGameDeckCardsId", UUID.randomUUID().toString(),
                    "DCardDatasId", cardId, "SlotNo", slot));
        }
        return result;
    }

    /** 本账号拥有的卡片实例 ID（Python 的 {@code {card.get('DCardDatasId') for ...}}）。 */
    private static Set<String> ownedCardIds(PlayerContext player) {
        Set<String> owned = new HashSet<>();
        for (Map<String, Object> card : player.rowsSnapshot("Cards")) {
            Object ident = card.get("DCardDatasId");
            if (ident != null) {
                owned.add(String.valueOf(ident));
            }
        }
        return owned;
    }

    private void commitDecks(PlayerContext player, PlayerStore store,
                             List<Map<String, Object>> decks) {
        player.put("RhythmGameDecks", decks);
        player.remove("ActiveRhythmGame");
        store.save(player);
    }

    /** 首页看到残留的进行中 live 就丢掉 —— 对应 Python {@code _discard_abandoned_live}。 */
    private void discardAbandonedLive(PlayerContext player, PlayerStore store) {
        if (player.has("ActiveRhythmGame")) {
            setRetire(player, store);
        }
    }
}
