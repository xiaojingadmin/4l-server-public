package com.linklike.server.wire;

import com.linklike.server.domain.Player;
import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireCodec;
import com.linklike.server.protocol.WireError;
import com.linklike.server.resource.ClientDefaults;
import com.linklike.server.service.PlayerContext;
import com.linklike.server.service.PlayerStore;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * Quest Live（关卡 / 学习 / Dream / Grade）—— 对应 Python {@code wire_services.py} 里
 * {@code /v1/out_quest_live/*} 与 {@code /v1/in_quest_live/*} 的分支。
 *
 * <p>关卡定义来自导入的参考目录（{@code quest_stages} / {@code daily_quest_stages} /
 * {@code learning_live_catalog} / {@code grade_*} / {@code dream_live_catalog}），再叠上本账号
 * 自己的 {@code quest_stage_states} / {@code learning_live_progress} / {@code dream_live_members}
 * 等行。没有导入目录的部署不下发任何关卡，也不编造进度。
 *
 * <h2>进行中的 live</h2>
 * 与 Python 版一样，进行中的 Quest Live 压成一个字符串存在 {@code ActiveQuest} 里：
 * <pre>
 *   prep:&lt;类型&gt;:&lt;关卡&gt;:&lt;歌曲&gt;:&lt;卡组&gt;:&lt;live_id&gt;
 *   done:&lt;类型&gt;:&lt;关卡&gt;:&lt;歌曲&gt;:&lt;卡组&gt;:&lt;live_id&gt;:&lt;标志&gt;:&lt;分数&gt;:&lt;掉落&gt;:&lt;play_report&gt;
 * </pre>
 * 按 9 个冒号切分（{@code play_report} 是最后一个字段、可以自带冒号）；{@code 标志} 是
 * {@code result / first_clear / first_complete} 三个位，{@code 掉落} 是
 * {@code item_id.item_num.reward_type} 用逗号连起来的列表。
 *
 * <h2>结算口径</h2>
 * 分数只用来算通关星级（客户端只在打过时才带分数回来），不换算成 love / 经验；通关按
 * {@code quest_stage_rewards} 发奖并加 {@code GainStylePoint}。两张表都来自客户端包内的抽取
 * 结果，缺表时按 0 / 空表处理，不臆造数值。
 */
@Component
public class WireQuestModule implements WireModule {

    /** {@code dump.cs QuestType}：Standard=1 Daily=2 Learning=3 Grade=4 Dream=5 GrandPrix=6。 */
    private static final int QUEST_STANDARD = 1;
    private static final int QUEST_DAILY = 2;
    private static final int QUEST_LEARNING = 3;
    private static final int QUEST_DREAM = 5;

    /** {@code ActiveQuest} 的 result 标志位。 */
    private static final int RESULT_WON = 1;
    private static final int RESULT_FIRST_CLEAR = 2;
    private static final int RESULT_FIRST_COMPLETE = 4;

    /** {@code stage_reward_list.reward_type}：1 = 每次通关固定给，2 = 从随机池抽一个。 */
    private static final int REWARD_FIXED = 1;
    private static final int REWARD_RANDOM = 2;

    /** Dream Live 解放条件类型（{@code dreamlivereleaseconditions.tsv}）。 */
    private static final Set<Integer> DREAM_CONDITION_ITEM = Set.of(3, 5);
    private static final int DREAM_CONDITION_GRADE = 6;
    private static final Map<Integer, String> DREAM_GRADE_NAMES =
            Map.of(41, "シルバー1", 91, "ゴールド1");

    /** 5.1.0 里区域选择界面的标题。 */
    private static final String STANDARD_AREA_SELECT_NAME = "エリアセレクト";

    private static final String QUEST_AREA_ASSET = "quest_live_area_bg_{area_id}";

    private static final List<String> DREAM_LIVE_ASSETS = List.of(
            "dream_series_banner_{dream_live_series_id}",
            "dream_member_{dream_live_series_id}",
            "image_card_get_{dream_quest_series_id}_0");

    private static final DateTimeFormatter QUEST_START_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    /** 结算会改动的账号字段；落库失败时按 Python 的做法整体还原。 */
    private static final List<String> SETTLED_KEYS =
            List.of("StylePoint", "Items", "Cards", "quest_stage_states",
                    "learning_live_progress", "RhythmMusic", "beginner_missions");

    private final WireCodec codec;
    private final WireServices services;
    private final WireQuestMaster master;
    private final WireShop shop;
    private final WireCardMint mint;
    private final WireGrants grants;
    private final ClientDefaults defaults;

    public WireQuestModule(WireCodec codec, WireServices services, WireQuestMaster master,
                           WireShop shop, WireCardMint mint, WireGrants grants,
                           ClientDefaults defaults) {
        this.codec = codec;
        this.services = services;
        this.master = master;
        this.shop = shop;
        this.mint = mint;
        this.grants = grants;
        this.defaults = defaults;
    }

    @Override
    public void register(WireRouter router) {
        router.register("/v1/out_quest_live/daily/get_stage_select", this::dailyStageSelect);
        router.register("/v1/out_quest_live/daily/get_stage_list", this::dailyStageList);
        router.register("/v1/out_quest_live/daily/get_stage_data", this::dailyStageData);

        router.register("/v1/out_quest_live/get_quest_top", this::questTop);
        router.register("/v1/out_quest_live/get_area_select", this::areaSelect);
        router.register("/v1/out_quest_live/standard/get_area_select", this::areaSelect);
        router.register("/v1/out_quest_live/get_quest_list", this::questList);
        router.register("/v1/out_quest_live/get_quest_clear_status_list",
                this::questClearStatusList);
        router.register("/v1/out_quest_live/get_stamina_recovery_info", this::staminaRecoveryInfo);
        router.register("/v1/out_quest_live/recovery_stamina", this::recoveryStamina);
        router.register("/v1/out_quest_live/get_stage_select", this::stageSelect);
        router.register("/v1/out_quest_live/standard/get_stage_select", this::stageSelect);
        router.register("/v1/out_quest_live/get_stage_data", this::stageData);
        router.register("/v1/out_quest_live/standard/get_stage_data", this::stageData);

        router.register("/v1/out_quest_live/get_live_setting", this::getLiveSetting);
        router.register("/v1/out_quest_live/set_live_setting", this::setLiveSetting);
        router.register("/v1/out_quest_live/get_result", this::getResult);
        router.register("/v1/out_quest_live/get_play_report", this::getPlayReport);

        router.register("/v1/in_quest_live/set_start", this::inSetStart);
        router.register("/v1/in_quest_live/set_retire", this::inSetRetire);
        router.register("/v1/in_quest_live/set_finish", this::inSetFinish);
        router.register("/v1/in_quest_live/skip_quest", this::skipQuest);
        router.register("/v1/in_quest_live/get_live_info", this::inGetLiveInfo);

        // 客户端只用来回报「区域滚动条动过了」，本地不保存浏览历史。
        router.register("/v1/out_quest_live/set_area_select_view_hist", request -> J.emptyMap());
        router.register("/v1/out_quest_live/standard/set_area_select_view_hist",
                request -> J.emptyMap());

        router.register("/v1/out_quest_live/music_learning/get_music_select", this::musicSelect);
        router.register("/v1/out_quest_live/music_learning/set_music", this::setMusic);
        router.register("/v1/out_quest_live/music_learning/get_result", this::learningResult);

        router.register("/v1/out_quest_live/dream/get_result", this::dreamResult);
        router.register("/v1/out_quest_live/dream/get_member_select", this::dreamMemberSelect);
        router.register("/v1/out_quest_live/dream/set_card", this::dreamSetCard);
        router.register("/v1/out_quest_live/dream/notify_member_release_confirm",
                this::dreamNotifyRelease);

        router.register("/v1/out_quest_live/grade/get_live_list", this::gradeLiveList);
        router.register("/v1/out_quest_live/grade/get_quest_list", this::gradeQuestList);
        router.register("/v1/out_quest_live/grade/get_rank_list", this::gradeRankList);
        router.register("/v1/out_quest_live/grade/get_stage_list", this::gradeStageList);
        router.register("/v1/out_quest_live/grade/get_stage_data", this::gradeStageData);
        router.register("/v1/out_quest_live/grade/get_ranking_list", this::gradeRankingList);

        router.register("/v1/out_quest_live/daily/set_release", request -> {
            request.requirePlayer();
            throw new WireError(501, UNIMPLEMENTED_DAILY_MESSAGE);
        });
        router.register("/v1/out_quest_live/daily/recovery_challenge_count", request -> {
            request.requirePlayer();
            throw new WireError(501, UNIMPLEMENTED_DAILY_MESSAGE);
        });
    }

    // ------------------------------------------------------------------
    // ActiveQuest
    // ------------------------------------------------------------------

    /** 每日关卡的「临时开放」与「挑战次数回复」需要真实的道具定价与日界结算。 */
    private static final String UNIMPLEMENTED_DAILY_MESSAGE =
            "Daily quest release and challenge-count recovery require verified pricing and "
                    + "atomic settlement rules; no state was changed";

    /** {@code _quest_state} 解出来的状态；字段可变（{@code quest_live_id} 会被回填）。 */
    private static final class QuestState {
        String kind;
        int liveType;
        long stageId;
        long musicId;
        String deckId = "";
        String questLiveId = "";
        boolean questResult;
        boolean firstClear;
        boolean firstComplete;
        long score;
        List<Map<String, Object>> drops = new ArrayList<>();
        String playReport = "";
    }

    private static QuestState questState(PlayerContext player) {
        Object raw = player.raw("ActiveQuest");
        String value = raw == null ? "" : String.valueOf(raw);
        String[] parts = value.split(":", 10);
        if (parts.length < 6 || !Set.of("prep", "play", "done").contains(parts[0])) {
            return null;
        }
        try {
            QuestState state = new QuestState();
            state.kind = parts[0];
            state.liveType = Integer.parseInt(parts[1]);
            state.stageId = Long.parseLong(parts[2]);
            state.musicId = Long.parseLong(parts[3]);
            state.deckId = parts[4];
            state.questLiveId = parts[5];
            int flags = parts.length > 6 && !parts[6].isEmpty() ? Integer.parseInt(parts[6]) : 0;
            state.questResult = (flags & RESULT_WON) != 0;
            state.firstClear = (flags & RESULT_FIRST_CLEAR) != 0;
            state.firstComplete = (flags & RESULT_FIRST_COMPLETE) != 0;
            state.score = parts.length > 7 && !parts[7].isEmpty() ? Long.parseLong(parts[7]) : 0L;
            state.drops = parts.length > 8 ? decodeDrops(parts[8]) : new ArrayList<>();
            state.playReport = parts.length > 9 ? parts[9] : "";
            return state;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String encodeDrops(List<Map<String, Object>> drops) {
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> drop : drops) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(J.longOr(drop.get("item_id"), 0L))
                    .append('.').append(J.longOr(drop.get("item_num"), 0L))
                    .append('.').append(J.longOr(drop.get("reward_type"), 0L));
        }
        return out.toString();
    }

    private static List<Map<String, Object>> decodeDrops(String text) {
        List<Map<String, Object>> drops = new ArrayList<>();
        for (String part : text.split(",")) {
            if (part.isEmpty()) {
                continue;
            }
            String[] fields = part.split("\\.");
            if (fields.length != 3) {
                throw new NumberFormatException("bad drop " + part);
            }
            drops.add(J.map("item_id", Long.parseLong(fields[0]),
                    "item_num", Long.parseLong(fields[1]),
                    "reward_type", Long.parseLong(fields[2])));
        }
        return drops;
    }

    private static String questToken(String kind, int liveType, long stageId, long musicId,
                                     String deckId, String questLiveId, Integer flags,
                                     long score, List<Map<String, Object>> drops,
                                     String report) {
        StringBuilder out = new StringBuilder();
        out.append(kind).append(':').append(liveType).append(':').append(stageId)
                .append(':').append(musicId).append(':')
                .append(deckId == null ? "" : deckId).append(':')
                .append(questLiveId == null ? "" : questLiveId);
        if ("done".equals(kind)) {
            out.append(':').append(flags == null ? 0 : flags)
                    .append(':').append(score)
                    .append(':').append(encodeDrops(drops == null ? List.of() : drops))
                    .append(':').append(report == null ? "" : report);
        }
        return out.toString();
    }

    /**
     * 写入 {@code ActiveQuest} 并落库；失败时写回旧值 —— 对应 Python {@code _write_quest}。
     */
    private static void writeQuest(PlayerContext player, PlayerStore store, String value) {
        Object previous = player.raw("ActiveQuest");
        if (value == null) {
            player.remove("ActiveQuest");
        } else {
            player.put("ActiveQuest", value);
        }
        try {
            store.save(player);
        } catch (RuntimeException e) {
            if (previous == null) {
                player.remove("ActiveQuest");
            } else {
                player.put("ActiveQuest", previous);
            }
            throw e;
        }
    }

    /** {@code _require_quest}：kind 必须命中，{@code quest_live_id} 给出时要与本地一致。 */
    private static QuestState requireQuest(PlayerContext player, Object liveId,
                                           Set<String> kinds) {
        QuestState state = questState(player);
        if (state == null || !kinds.contains(state.kind)) {
            throw new WireError(409, "No local Quest Live is in progress");
        }
        if (liveId != null) {
            if (!(liveId instanceof String text) || text.isEmpty()) {
                throw WireError.badRequest("quest_live_id is required");
            }
            if (!state.questLiveId.isEmpty() && !state.questLiveId.equals(text)) {
                throw new WireError(409, "quest_live_id does not match the local Quest Live");
            }
            state.questLiveId = text;
        }
        return state;
    }

    // ------------------------------------------------------------------
    // 关卡目录
    // ------------------------------------------------------------------

    /** {@code _stage_row}：按 {@code stage_id}（可选再按父 ID）找一行。 */
    private static Map<String, Object> stageRow(List<Map<String, Object>> rows, long stageId,
                                                String parentKey, Long parentId) {
        for (Map<String, Object> row : rows) {
            if (J.longOr(row.get("stage_id"), Long.MIN_VALUE) != stageId) {
                continue;
            }
            if (parentKey != null && !Objects.equals(
                    J.longOr(row.get(parentKey), Long.MIN_VALUE), parentId)) {
                continue;
            }
            return row;
        }
        return null;
    }

    /** {@code _stage_state}：本账号在该关卡上的状态行；没有返回 null。 */
    private static Map<String, Object> stageState(PlayerContext player, long stageId) {
        for (Map<String, Object> row : player.rowsSnapshot("quest_stage_states")) {
            if (J.longOr(row.get("stage_id"), Long.MIN_VALUE) == stageId) {
                return row;
            }
        }
        return null;
    }

    /** 目录行叠上本账号的通关 / 解锁状态（{@code _with_stage_state}）。 */
    private static Map<String, Object> withStageState(PlayerContext player,
                                                      Map<String, Object> row) {
        Map<String, Object> state = stageState(player, J.longOr(row.get("stage_id"), 0L));
        if (state == null) {
            return row;
        }
        Map<String, Object> merged = new LinkedHashMap<>(row);
        merged.put("clear_status", J.longOr(state.get("clear_status"), 0L));
        merged.put("is_lock", J.truthy(state.containsKey("is_lock")
                ? state.get("is_lock") : row.get("is_lock")));
        return merged;
    }

    private static Map<String, Object> stageWire(Map<String, Object> row) {
        return J.map("stage_id", row.get("stage_id"),
                "clear_status", J.longOr(row.get("clear_status"), 0L),
                "is_lock", J.truthy(row.get("is_lock")));
    }

    /** {@code _stage_list}：父 ID 在目录里完全不存在时 404，否则返回其下的关卡。 */
    private List<Map<String, Object>> stageList(PlayerContext player, String catalog,
                                                String parentKey, long parentId) {
        List<Map<String, Object>> rows = services.refs(catalog);
        boolean known = false;
        for (Map<String, Object> row : rows) {
            if (J.longOr(row.get(parentKey), Long.MIN_VALUE) == parentId) {
                known = true;
                break;
            }
        }
        if (!rows.isEmpty() && !known) {
            throw WireError.notFound("Local quest area or series not found");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (J.longOr(row.get(parentKey), Long.MIN_VALUE) == parentId) {
                out.add(stageWire(withStageState(player, row)));
            }
        }
        return out;
    }

    /** {@code _catalog_music_ids}：服务端能渲染的歌曲 ID。 */
    private List<Long> catalogMusicIds() {
        List<Long> ids = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (Map<String, Object> row : services.served("rhythm_music_catalog",
                WireServices.RHYTHM_MUSIC_ASSETS)) {
            Object ident = row.containsKey("music_id") ? row.get("music_id") : row.get("MusicId");
            long value = J.longOr(ident, 0L);
            if (value > 0 && seen.add(value)) {
                ids.add(value);
            }
        }
        return ids;
    }

    /**
     * 关卡可玩的歌曲 —— 对应 Python {@code _music_list}。
     *
     * <p>关卡主数据只记一首歌（{@code m_musics_id}）；抽取目录把
     * {@code QuestMusicsDetail} 存在同一列，值可能只是前缀（{@code 105010115} 发过
     * {@code 105}），所以命中不了时先退回同前缀的目录歌曲，再退回整份目录。
     */
    private static List<Map<String, Object>> musicList(Map<String, Object> row,
                                                       List<Long> catalog) {
        long ident = J.longOr(row.get("m_musics_id"), 0L);
        if (ident != 0 && (catalog.isEmpty() || catalog.contains(ident))) {
            return new ArrayList<>(List.of(J.map("m_musics_id", ident, "is_enable", true)));
        }
        if (!catalog.isEmpty()) {
            List<Long> prefixed = new ArrayList<>();
            for (Long id : catalog) {
                if (ident != 0 && String.valueOf(id).startsWith(String.valueOf(ident))) {
                    prefixed.add(id);
                }
            }
            List<Long> chosen = new ArrayList<>(prefixed.isEmpty() ? catalog : prefixed);
            chosen.sort(Long::compare);
            List<Map<String, Object>> out = new ArrayList<>(chosen.size());
            for (Long id : chosen) {
                out.add(J.map("m_musics_id", id, "is_enable", true));
            }
            return out;
        }
        return ident == 0 ? new ArrayList<>()
                : new ArrayList<>(List.of(J.map("m_musics_id", ident, "is_enable", true)));
    }

    /** 已通关关卡的最佳成绩歌曲；没通关是 0。 */
    private static long bestLoveMusicId(PlayerContext player, Map<String, Object> row,
                                        List<Map<String, Object>> music) {
        if (J.longOr(row.get("clear_status"), 0L) == 0) {
            return 0L;
        }
        QuestState state = questState(player);
        if (state != null && "done".equals(state.kind) && state.questResult
                && state.stageId == J.longOr(row.get("stage_id"), 0L) && state.musicId != 0) {
            return state.musicId;
        }
        return music.isEmpty() ? 0L : J.longOr(music.get(0).get("m_musics_id"), 0L);
    }

    /** {@code _quest_stage}：按 QuestType 取关卡行；类型不支持 400、找不到 404。 */
    private Map<String, Object> questStage(int liveType, long stageId) {
        Map<String, Object> row;
        switch (liveType) {
            case QUEST_STANDARD -> row = stageRow(services.refs("quest_stages"), stageId,
                    null, null);
            case QUEST_DAILY -> row = stageRow(services.refs("daily_quest_stages"), stageId,
                    null, null);
            case QUEST_LEARNING -> row = learningStage(stageId);
            case QUEST_DREAM -> row = dreamStage(stageId);
            default -> throw WireError.badRequest(
                    "quest_live_type must be standard, daily, learning or dream");
        }
        if (row == null) {
            throw WireError.notFound("Local quest stage not found");
        }
        return row;
    }

    private Map<String, Object> learningStage(long stageId) {
        Object[] found = learningStageRow(stageId);
        if (found == null) {
            return null;
        }
        return J.map("stage_id", stageId,
                "m_musics_id", J.longOr(((Map<?, ?>) found[0]).get("music_id"), 0L));
    }

    private Map<String, Object> dreamStage(long stageId) {
        Object[] spec = dreamStageSpec(stageId);
        Object seriesId = spec == null ? null : ((Map<?, ?>) spec[0]).get("series_id");
        for (Map<String, Object> row : services.served("dream_live_catalog", DREAM_LIVE_ASSETS)) {
            boolean sameStage = J.longOr(row.get("dream_quest_stages_id"), Long.MIN_VALUE)
                    == stageId;
            boolean sameSeries = seriesId != null && Objects.equals(
                    J.str(row.get("dream_quest_series_id")), J.str(seriesId));
            if (!sameStage && !sameSeries) {
                continue;
            }
            long music = 0L;
            for (Map<String, Object> item : J.rowsOr(row.get("music_list"))) {
                long id = J.longOr(item.get("m_musics_id"), 0L);
                if (id != 0) {
                    music = id;
                    break;
                }
            }
            return J.map("stage_id", stageId, "m_musics_id", music);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 出题界面（out_quest_live）
    // ------------------------------------------------------------------

    private Map<String, Object> dailyStageSelect(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        return J.map("user_daily_ticket_info", tickets(player),
                "temporary_release_daily_quest_series_id_list",
                WireServices.rows(player, "daily_quest_releases"));
    }

    private Map<String, Object> dailyStageList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        long questId = WireServices.integer(request.body(), "quest_id", (Long) null,
                0, Integer.MAX_VALUE);
        return J.map("user_daily_ticket_info", tickets(player),
                "stage_list", stageList(player, "daily_quest_stages", "quest_id", questId));
    }

    private Map<String, Object> dailyStageData(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        long stageId = WireServices.integer(request.body(), "stage_id", (Long) null,
                0, Integer.MAX_VALUE);
        Map<String, Object> row = stageRow(services.refs("daily_quest_stages"), stageId,
                null, null);
        if (row == null) {
            throw WireError.notFound("Local daily stage not found");
        }
        row = withStageState(player, row);
        List<Map<String, Object>> music = musicList(row, catalogMusicIds());
        return J.map("stage_id", stageId,
                "clear_status", J.longOr(row.get("clear_status"), 0L),
                "best_love_music_id", bestLoveMusicId(player, row, music),
                "user_daily_ticket_info", tickets(player),
                "stage_reward_list", master.stageRewards(stageId),
                "music_list", music);
    }

    private Map<String, Object> questTop(WireRequest request) {
        return questTop(request.requirePlayer());
    }

    private Map<String, Object> questTop(PlayerContext player) {
        return J.map(
                "standard_live_status", player.intOf("StandardLiveStatus", 0),
                "daily_live_status", player.intOf("DailyLiveStatus", 0),
                "music_live_status", player.intOf("MusicLiveStatus", 0),
                "grade_live_status", player.intOf("GradeLiveStatus", 0),
                "grade_challenge_status", player.intOf("GradeChallengeStatus", 0),
                "dream_live_status", player.intOf("DreamLiveStatus", 0),
                "grand_prix_open", false,
                "grand_prix_status", 0,
                "grand_prix_id", 0,
                "grand_prix_start_datetime", null,
                "grand_prix_end_datetime", null,
                "raid_event_status", 0,
                "raid_event_id", 0,
                "raid_event_start_datetime", null,
                "raid_event_end_datetime", null,
                "standard_area_select", areaSelect(player),
                "dream_live_member_select", dreamMembers(player),
                "grade_detail", gradeDetail(player),
                "open_campaign_id_list", WireServices.rows(player, "open_campaigns"),
                "grade_challenge_season_id", player.intOf("GradeChallengeSeasonId", 0),
                "grade_challenge_season_start_datetime",
                player.raw("GradeChallengeSeasonStartTime"),
                "grade_challenge_season_end_datetime",
                player.raw("GradeChallengeSeasonEndTime"));
    }

    private static Map<String, Object> gradeDetail(PlayerContext player) {
        return J.map(
                "grade_num", player.intOf("GradeNum", 0),
                "season_grade_id", player.intOf("SeasonGradeId", 0),
                "season_grade_point", player.longOf("SeasonGradePoint", 0L),
                "total_grade_point", player.longOf("TotalGradePoint", 0L),
                "season_grade_point_rank", player.intOf("SeasonGradePointRank", 0),
                "is_not_received_grade_reward", player.boolOf("IsNotReceivedGradeReward", false),
                "grade_rewards", WireServices.rows(player, "grade_rewards"),
                "status", player.intOf("GradeStatus", 0));
    }

    private Map<String, Object> areaSelect(WireRequest request) {
        return areaSelect(request.requirePlayer());
    }

    private Map<String, Object> areaSelect(PlayerContext player) {
        List<Map<String, Object>> areas = WireServices.overlay(
                services.served("quest_areas", List.of(QUEST_AREA_ASSET)),
                WireServices.rows(player, "standard_areas"), "area_id");
        String name = player.str("StandardAreaName");
        if (name == null) {
            name = areas.isEmpty() ? "" : STANDARD_AREA_SELECT_NAME;
        }
        return J.map("name", name, "area_list", areas, "user_stamina", stamina(player));
    }

    private Map<String, Object> questList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> top = questTop(player);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("standard_live_status", "daily_live_status",
                "music_live_status", "grade_live_status", "dream_live_status",
                "grand_prix_status", "grand_prix_id", "grand_prix_start_datetime",
                "grand_prix_end_datetime", "raid_event_status", "raid_event_id",
                "raid_event_start_datetime", "raid_event_end_datetime",
                "open_campaign_id_list")) {
            out.put(key, top.get(key));
        }
        out.put("user_stamina", stamina(player));
        return out;
    }

    /** 某个道具作为通关奖励出现在哪些标准关卡（客户端用它标「可刷」）。 */
    private Map<String, Object> questClearStatusList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        long itemsId = WireServices.integer(request.body(), "items_id", (Long) null,
                1, Integer.MAX_VALUE);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : services.refs("quest_stages")) {
            long stageId = J.longOr(row.get("stage_id"), 0L);
            boolean rewarded = false;
            for (Map<String, Object> reward : master.stageRewards(stageId)) {
                if (J.longOr(reward.get("item_id"), Long.MIN_VALUE) == itemsId) {
                    rewarded = true;
                    break;
                }
            }
            if (!rewarded) {
                continue;
            }
            Map<String, Object> merged = withStageState(player, row);
            out.add(J.map("stages_id", merged.get("stage_id"),
                    "quest_live_type", QUEST_STANDARD,
                    "is_lock", J.truthy(merged.get("is_lock")),
                    "star_num", J.longOr(merged.get("clear_status"), 0L)));
        }
        return J.map("quest_clear_status_list", out);
    }

    private Map<String, Object> staminaRecoveryInfo(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        List<Map<String, Object>> settings = new ArrayList<>();
        for (Map<String, Object> row : Stamina.jewelSettings()) {
            settings.add(J.map(
                    "jewel_st_recovery_count_limit", row.get("JewelStRecoveryCountLimit"),
                    "price", row.get("Price"), "effect_value", row.get("EffectValue")));
        }
        return J.map("user_stamina", stamina(player),
                "item_list", new ArrayList<>(),
                "use_jewel_settings", settings,
                "jewel_st_recovery_count", Stamina.jewelRecoveryCount(player, null),
                "stamina_limit", player.entity().staminaMax,
                "max_st_recovery_count", Stamina.JEWEL_DAILY_LIMIT);
    }

    /** SIsCa 回复体力；道具回复没有打包，按 Python 一样返回 501 且不改状态。 */
    private Map<String, Object> recoveryStamina(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> body = request.body();
        long kind = WireServices.integer(body, "use_recovery_type", (Long) null, 1, 2);
        if (kind != 1) {
            throw new WireError(501,
                    "Stamina recovery items are not packaged; no state was changed");
        }
        if (!Stamina.tracked(player)) {
            throw WireError.badRequest("This account does not track stamina");
        }
        int count = WireServices.integer(body, "recovery_count", 1,
                1, Stamina.JEWEL_DAILY_LIMIT);
        Instant now = Instant.now();
        int used = Stamina.jewelRecoveryCount(player, now);
        int[] priced = Stamina.jewelPrice(used, count);
        if (priced == null) {
            throw WireError.badRequest("本日のSIsCaによる回復回数の上限に達しています。");
        }
        long freeBefore = player.entity().jewelFree;
        long paidBefore = player.entity().jewelPaid;
        long googleBefore = player.entity().jewelPaidGoogle;
        try {
            debitJewels(player, priced[0], false);
            Stamina.restore(player, priced[1], now);
            if (used == 0) {
                player.put("JewelStRecoveryResetTime", WireServices.nextDailyReset(now));
            }
            player.put("JewelStRecoveryCount", used + count);
            request.store().save(player);
        } catch (RuntimeException e) {
            player.entity().jewelFree = freeBefore;
            player.entity().jewelPaid = paidBefore;
            player.entity().jewelPaidGoogle = googleBefore;
            throw e;
        }
        return J.map("user_stamina", stamina(player), "stamina_present", priced[1]);
    }

    /** {@code _tickets}：日常券；没有下发过重置时刻就按下一个 04:00 JST 算。 */
    private static Map<String, Object> tickets(PlayerContext player) {
        String reset = player.str("DailyTicketNextResetTime");
        return J.map("num", player.intOf("DailyTicketNum", 0),
                "max", player.intOf("DailyTicketMax", 0),
                "next_reset_time", reset == null ? WireServices.nextDailyReset() : reset);
    }

    private Map<String, Object> stageSelect(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        long areaId = WireServices.integer(request.body(), "area_id", (Long) null,
                0, Integer.MAX_VALUE);
        return J.map("stage_list", stageList(player, "quest_stages", "area_id", areaId),
                "user_stamina", stamina(player),
                "is_update_grade_live", false);
    }

    private Map<String, Object> stageData(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> body = request.body();
        long areaId = WireServices.integer(body, "area_id", (Long) null, 0, Integer.MAX_VALUE);
        long stageId = WireServices.integer(body, "stage_id", (Long) null, 0, Integer.MAX_VALUE);
        Map<String, Object> row = stageRow(services.refs("quest_stages"), stageId,
                "area_id", areaId);
        if (row == null) {
            throw WireError.notFound("Local quest stage not found");
        }
        row = withStageState(player, row);
        List<Map<String, Object>> music = musicList(row, catalogMusicIds());
        Map<String, Object> detail = J.map(
                "stage_id", stageId,
                "is_challenge", false,
                "is_skip", J.longOr(row.get("clear_status"), 0L) != 0,
                "clear_status", J.longOr(row.get("clear_status"), 0L),
                "best_love_music_id", bestLoveMusicId(player, row, music),
                "stage_reward_list", master.stageRewards(stageId),
                "stage_challenge_reward_list", new ArrayList<>());
        return J.map("view_stage_id", stageId,
                "stage_detail_list", new ArrayList<>(List.of(detail)),
                "user_stamina", stamina(player),
                "music_list", music);
    }

    private Map<String, Object> getLiveSetting(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> body = request.body();
        long stageId = WireServices.integer(body, "stage_id", (Long) null, 0, Integer.MAX_VALUE);
        int liveType = WireServices.integer(body, "quest_live_type", QUEST_STANDARD, 1, 6);
        Map<String, Object> row = questStage(liveType, stageId);

        List<Map<String, Object>> decks = new ArrayList<>();
        for (Map<String, Object> deck : player.rowsSnapshot("Decks")) {
            decks.add(codec.encode("Deck", deck));
        }
        List<Map<String, Object>> cards = new ArrayList<>();
        for (Map<String, Object> card : player.rowsSnapshot("Cards")) {
            cards.add(codec.encode("UserCardData", card));
        }
        Map<String, Object> out = J.map(
                "quest_live_type", liveType,
                "stage_id", stageId,
                "deck_data", decks,
                "best_love_musics_id", 0,
                "user_stamina", stamina(player),
                "music_list", musicList(row, catalogMusicIds()),
                "user_card_data_list", cards,
                "rental_deck_data", new ArrayList<>(),
                "rental_card_data_list", new ArrayList<>(),
                "friend_card_list", new ArrayList<>());
        if (!decks.isEmpty()) {
            out.put("best_love_deck", decks.get(0));
        }
        return out;
    }

    /**
     * 选歌 / 选卡组：写 {@code play} 状态（{@code quest_live_id} 留空，等
     * {@code in_quest_live/set_start} 回填），返回 {@code GetLiveInfoResponse}。
     */
    private Map<String, Object> setLiveSetting(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> body = request.body();
        long stageId = WireServices.integer(body, "stage_id", (Long) null, 0, Integer.MAX_VALUE);
        int liveType = WireServices.integer(body, "quest_live_type", QUEST_STANDARD, 1, 6);
        Map<String, Object> row = questStage(liveType, stageId);

        List<Map<String, Object>> music = musicList(row, catalogMusicIds());
        Object rawMusic = body.get("music_id");
        long musicId;
        if (rawMusic == null) {
            musicId = music.isEmpty() ? J.longOr(row.get("m_musics_id"), 0L)
                    : J.longOr(music.get(0).get("m_musics_id"), 0L);
        } else {
            Long parsed = WireServices.strictLong(rawMusic);
            if (parsed == null || (!music.isEmpty() && !containsMusic(music, parsed))) {
                throw WireError.badRequest("music_id is not playable on this stage");
            }
            musicId = parsed;
        }
        Object rawDeck = body.get("deck_id");
        String deckId = "";
        if (rawDeck instanceof String text) {
            deckId = text;
        } else if (rawDeck != null && J.truthy(rawDeck)) {
            throw WireError.badRequest("deck_id must be a string");
        }
        if (!deckId.isEmpty() && findDeck(player, deckId) == null) {
            throw WireError.notFound("Local deck not found");
        }
        if (deckId.isEmpty() && !player.rowsSnapshot("Decks").isEmpty()) {
            String first = J.str(player.rowsSnapshot("Decks").get(0).get("DDeckDatasId"));
            deckId = first == null ? "" : first;
        }
        // 上游这里回 GetLiveInfoResponse（不是空对象），并返回空 live_id 等 set_start 回填。
        QuestState state = new QuestState();
        state.kind = "play";
        state.liveType = liveType;
        state.stageId = stageId;
        state.musicId = musicId;
        state.deckId = deckId;
        writeQuest(player, request.store(), questToken("play", liveType, stageId, musicId,
                deckId, "", null, 0, List.of(), ""));
        return liveInfo(player, state);
    }

    private static boolean containsMusic(List<Map<String, Object>> music, long musicId) {
        for (Map<String, Object> item : music) {
            if (J.longOr(item.get("m_musics_id"), Long.MIN_VALUE) == musicId) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> findDeck(PlayerContext player, String deckId) {
        for (Map<String, Object> deck : player.rowsSnapshot("Decks")) {
            if (deckId.equals(J.str(deck.get("DDeckDatasId")))) {
                return deck;
            }
        }
        return null;
    }

    private Map<String, Object> getResult(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        QuestState state = requireQuest(player, request.body().get("quest_live_id"),
                Set.of("done"));
        return J.map("quest_live_type", state.liveType,
                "stage_id", state.stageId,
                "quest_result", state.questResult,
                "result_love", state.score,
                "best_love", state.score,
                "before_best_love", 0,
                "add_style_point", state.questResult ? master.stageStylePoint(state.stageId) : 0,
                "is_challenge_mode", false,
                "music_id", state.musicId,
                "reward_list", state.questResult ? state.drops : new ArrayList<>(),
                "play_report", state.playReport,
                "mastary_level_before", 1,
                "mastary_level_after", 1,
                "mastary_level_experience", 0,
                "mastary_level_total_experience_before", 0,
                "first_clear_flag", state.firstClear,
                "first_complete_clear_flag", state.firstComplete,
                "is_limit_over_style_point", false);
    }

    private Map<String, Object> getPlayReport(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        QuestState state = requireQuest(player, request.body().get("quest_live_id"),
                Set.of("play", "done"));
        return J.map("quest_live_type", state.liveType, "play_report", state.playReport);
    }

    // ------------------------------------------------------------------
    // 进行中的 live（in_quest_live）
    // ------------------------------------------------------------------

    private Map<String, Object> inSetStart(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> body = request.body();
        QuestState state = requireQuest(player, body.get("quest_live_id"),
                Set.of("prep", "play"));
        Object hand = body.get("init_hand_data");
        if (hand != null && !(hand instanceof String)) {
            throw WireError.badRequest("init_hand_data must be a string");
        }
        writeQuest(player, request.store(), questToken("play", state.liveType, state.stageId,
                state.musicId, state.deckId, state.questLiveId, null, 0, List.of(), ""));
        return J.map("quest_start_time",
                QUEST_START_FORMAT.format(Instant.now().atOffset(ZoneOffset.UTC)));
    }

    private Map<String, Object> inSetRetire(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        QuestState state = requireQuest(player, null, Set.of("prep", "play"));
        writeQuest(player, request.store(), null);
        return J.map("quest_live_type", state.liveType, "return_resource", 0);
    }

    /**
     * 结算一次 live。
     *
     * <p>分数只决定星级（{@code quest_stage_scores} 的三档门槛），不换算成 love / 经验；
     * 星级落成 {@code quest_stage_states.clear_status} 的历史最好值，通关按
     * {@code quest_stage_rewards} 发奖并加 {@code GainStylePoint}。
     *
     * <p>重复调用（已经 {@code done}）沿用原来的结果与掉落，只刷新 report / score ——
     * 客户端断线重连后会重发一次 {@code set_finish}。
     */
    private Map<String, Object> inSetFinish(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> body = request.body();
        QuestState state = requireQuest(player, body.get("quest_live_id"),
                Set.of("play", "done"));
        boolean replay = "done".equals(state.kind);

        Object rawReport = body.get("play_report");
        String report = replay ? state.playReport : "";
        if (rawReport instanceof String text) {
            report = text;
        } else if (rawReport != null && J.truthy(rawReport)) {
            throw WireError.badRequest("play_report must be a string");
        }
        Object rawScore = body.get("score");
        Long score = null;
        if (rawScore != null) {
            score = WireServices.strictLong(rawScore);
            if (score == null) {
                throw WireError.badRequest("score must be an integer or null");
            }
        }

        // 已结算的重放以落库结果为准，不能拿重发时的分数重新判定通关；只允许刷新
        // report/score 这类展示字段，掉落、星级与发奖均保持首次结算的值。
        boolean won = replay ? state.questResult : questStars(state.stageId, score) > 0;
        int flags = won ? RESULT_WON : 0;
        List<Map<String, Object>> drops = replay
                ? new ArrayList<>(state.drops) : new ArrayList<>();
        long storedScore = score == null
                ? (replay ? state.score : 0L) : score;
        Map<String, Object> before = snapshot(player, SETTLED_KEYS);
        try {
            if (replay) {
                flags |= (state.firstClear ? RESULT_FIRST_CLEAR : 0)
                        | (state.firstComplete ? RESULT_FIRST_COMPLETE : 0);
            } else if (won) {
                flags |= recordStageClear(player, state.stageId,
                        questStars(state.stageId, score));
                drops = rollQuestDrops(state.stageId);
                grantQuestDrops(player, drops);
                addStylePoint(player, master.stageStylePoint(state.stageId));
                if (state.liveType == QUEST_LEARNING) {
                    flags |= settleLearning(player, state.stageId, state.musicId);
                }
            }
            writeQuest(player, request.store(), questToken("done", state.liveType,
                    state.stageId, state.musicId, state.deckId, state.questLiveId, flags,
                    storedScore, drops, report));
        } catch (RuntimeException e) {
            restore(player, before);
            throw e;
        }

        if (!replay && won) {
            int condition = 0;
            if (state.liveType == QUEST_STANDARD) {
                condition = WireShop.BEGINNER_CONDITION_STANDARD_LIVE_CLEAR;
            } else if (state.liveType == QUEST_DAILY) {
                condition = WireShop.BEGINNER_CONDITION_DAILY_LIVE_CLEAR;
            }
            if (condition != 0) {
                settleBeginnerProgress(player, request.store(), condition);
            }
        }
        return J.map("quest_live_type", state.liveType,
                "quest_result", won,
                "return_resource", 0,
                "applied_campaign_types", new ArrayList<>());
    }

    /**
     * スキップ：把已通关的关卡按次数直接结算 —— 每次发奖、加 {@code GainStylePoint}，
     * 学习关还按次加歌曲经验。
     */
    private Map<String, Object> skipQuest(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> body = request.body();
        int liveType = WireServices.integer(body, "quest_live_type", QUEST_STANDARD, 1, 6);
        long stageId = WireServices.integer(body, "stage_id", (Long) null, 0, Integer.MAX_VALUE);
        int count = WireServices.integer(body, "skip_count", 1, 1, 99);
        Map<String, Object> row = questStage(liveType, stageId);
        if (liveType == QUEST_DREAM) {
            throw WireError.badRequest("Dream Live cannot be skipped");
        }
        boolean cleared;
        if (liveType == QUEST_LEARNING) {
            cleared = learningStageCleared(player, stageId);
        } else {
            Map<String, Object> state = stageState(player, stageId);
            cleared = state != null && J.longOr(state.get("clear_status"), 0L) > 0;
        }
        if (!cleared) {
            throw WireError.badRequest("Stage must be cleared before it can be skipped");
        }
        long musicId = J.longOr(row.get("m_musics_id"), 0L);
        Map<String, Object> learning = liveType == QUEST_LEARNING
                ? learningRow(player, musicId) : null;
        long expBefore = learning == null ? 0L : J.longOr(learning.get("earned_music_exp"), 0L);
        long levelBefore = learning == null ? 0L : J.longOr(learning.get("music_exp_level"), 0L);

        Map<String, Object> before = snapshot(player, SETTLED_KEYS);
        long styleBefore = player.longOf("StylePoint", 0L);
        List<Map<String, Object>> runs = new ArrayList<>();
        Map<String, long[]> totals = new LinkedHashMap<>();
        try {
            for (int index = 0; index < count; index++) {
                List<Map<String, Object>> drops = rollQuestDrops(stageId);
                grantQuestDrops(player, drops);
                runs.add(J.map("reward_list", drops));
                for (Map<String, Object> drop : drops) {
                    long itemId = J.longOr(drop.get("item_id"), 0L);
                    long kind = J.longOr(drop.get("reward_type"), 0L);
                    totals.computeIfAbsent(itemId + ":" + kind,
                            key -> new long[] {itemId, kind, 0L})[2] += J.longOr(
                                    drop.get("item_num"), 0L);
                }
                addStylePoint(player, master.stageStylePoint(stageId));
                if (liveType == QUEST_LEARNING) {
                    settleLearning(player, stageId, musicId);
                }
            }
            request.store().save(player);
        } catch (RuntimeException e) {
            restore(player, before);
            throw e;
        }

        learning = liveType == QUEST_LEARNING ? learningRow(player, musicId) : null;
        long expAfter = learning == null ? 0L : J.longOr(learning.get("earned_music_exp"), 0L);
        long styleAfter = player.longOf("StylePoint", 0L);
        List<Map<String, Object>> totalList = new ArrayList<>();
        for (long[] value : totals.values()) {
            totalList.add(J.map("item_id", value[0], "item_num", value[2],
                    "reward_type", value[1]));
        }
        return J.map("quest_live_type", liveType,
                "quest_live_id", "",
                "user_stamina", stamina(player),
                "add_style_point", styleAfter - styleBefore,
                "skip_reward_list", runs,
                "total_skip_reward_list", totalList,
                "is_limit_over_style_point", styleAfter >= WireQuestMaster.STYLE_POINT_LIMIT,
                "mastery_level_before", levelBefore,
                "mastery_level_after", learning == null ? 0L
                        : J.longOr(learning.get("music_exp_level"), 0L),
                "mastery_level_experience", expAfter - expBefore,
                "mastery_level_total_experience_before", expBefore,
                "before_earned_music_exp", expBefore,
                "earned_music_exp", expAfter,
                "raid_stamina", J.map("stamina_now", 0,
                        "stamina_recovered_time", "0001-01-01T00:00:00Z"),
                "add_once_event_point", 0,
                "event_point_reward_list", new ArrayList<>(),
                "event_personal_total_point", 0,
                "applied_campaign_types", new ArrayList<>());
    }

    private Map<String, Object> inGetLiveInfo(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        QuestState state = requireQuest(player, request.body().get("quest_live_id"),
                Set.of("prep", "play", "done"));
        return liveInfo(player, state);
    }

    /** {@code GetLiveInfoResponse}；{@code set_live_setting} 也复用这份结构。 */
    private Map<String, Object> liveInfo(PlayerContext player, QuestState state) {
        Map<String, Object> deck = findDeck(player, state.deckId);
        if (deck == null && !player.rowsSnapshot("Decks").isEmpty()) {
            deck = player.rowsSnapshot("Decks").get(0);
        }
        Map<String, Object> rental = codec.encode("RentalDeck", J.map(
                "RentalDeckId", 0, "RentalDeckCardsList", null,
                "GradeBonusValue", 0, "IsReleased", false));
        Map<String, Object> bonus = codec.encode("CharacterBonus", J.map(
                "CharacterId", 0, "MusicMasteryBonus", 0, "LoveCorrectionValue", 0,
                "MusicMasteryBonusList", new ArrayList<>(), "SeasonFanLevel", 0));
        return J.map(
                "result", true,
                "quest_live_id", state.questLiveId,
                "quest_live_type", state.liveType,
                "quest_id", state.stageId,
                "is_challenge_mode", false,
                "music_id", state.musicId,
                "deck_data", deck == null ? J.emptyMap() : codec.encode("Deck", deck),
                "rental_deck_data", rental,
                "character_bonus", bonus,
                "section_skill_list", master.sections(state.stageId),
                "init_hand_data", "",
                "grand_prix_retry_count", 0,
                "grand_prix_is_rehearsal", false,
                "grand_prix_id", 0,
                "grade_retry_count", 0,
                "grade_add_skill_list", new ArrayList<>(),
                "playable_count", 0,
                "play_count", 0,
                "fan_level_info_list", new ArrayList<>());
    }

    // ------------------------------------------------------------------
    // 学习关（music_learning）
    // ------------------------------------------------------------------

    private Map<String, Object> musicSelect(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        return J.map("music_list", learningList(player),
                "music_point", WireGrants.walletValue(player, "MusicPoint"),
                "latest_music_id", player.intOf("LatestLearningMusicId", 0),
                "character_bonus_list", new ArrayList<>());
    }

    /**
     * 学习曲的 Music Pt. 解放：只有带解放代价（{@code ReleaseConditionType=2}）的曲子第一次
     * 会扣点并补一条 {@code learning_live_progress}，这次解放也计入新手任务 14；
     * 重复调用和不需要点数的曲子只更新 {@code LatestLearningMusicId}。
     */
    private Map<String, Object> setMusic(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        long musicId = WireServices.integer(request.body(), "music_id", (Long) null,
                0, Integer.MAX_VALUE);
        Map<String, Object> row = learningRow(player, musicId);
        if (row == null) {
            throw WireError.notFound("Local learning live music not found");
        }
        Map<String, Object> before = snapshot(player, List.of("LatestLearningMusicId",
                "learning_live_progress", "beginner_missions"));
        try {
            player.put("LatestLearningMusicId", musicId);

            Map<String, Object> release = musicRelease(musicId);
            Object seriesId = row.get("learning_live_series_id");
            List<Map<String, Object>> progress = WireServices.rows(player,
                    "learning_live_progress");
            boolean known = false;
            for (Map<String, Object> item : progress) {
                if (Objects.equals(J.str(item.get("learning_live_series_id")), J.str(seriesId))) {
                    known = true;
                    break;
                }
            }
            if (release != null && !known) {
                long cost = J.longOr(release.get("MusicPoint"), 0L);
                long have = WireGrants.walletValue(player, "MusicPoint");
                if (have < cost) {
                    throw WireError.badRequest("Not enough music point to release this music");
                }
                WireGrants.setWallet(player, "MusicPoint", have - cost);
                progress.add(J.map("learning_live_series_id", seriesId, "music_id", musicId,
                        "earned_music_exp", 0, "music_exp_level", 0, "is_mastery", false,
                        "stage_list", new ArrayList<>()));
                player.put("learning_live_progress", progress);
                shop.advance(player, WireShop.BEGINNER_CONDITION_MUSIC_RELEASE);
            }
            request.store().save(player);
        } catch (RuntimeException e) {
            restore(player, before);
            throw e;
        }
        return J.map("music_info", learningRow(player, musicId),
                "music_point", WireGrants.walletValue(player, "MusicPoint"));
    }

    private Map<String, Object> learningResult(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        QuestState state = typedResult(player, request.body(), QUEST_LEARNING);
        Map<String, Object> row = learningRow(player, state.musicId);
        Map<String, Object> safe = row == null ? J.emptyMap() : row;
        long level = J.longOr(safe.get("music_exp_level"), 0L);
        long exp = J.longOr(safe.get("earned_music_exp"), 0L);
        long gain = 0L;
        if (state.questResult) {
            Object[] found = learningStageRow(state.stageId);
            if (found != null) {
                gain = Math.min(J.longOr(((Map<?, ?>) found[1]).get("gain_music_exp"), 0L), exp);
            }
        }
        long expBefore = exp - gain;
        return J.map("stage_id", state.stageId,
                "quest_live_type", QUEST_LEARNING,
                "is_clear", state.questResult,
                "is_mastery", J.truthy(safe.get("is_mastery")),
                "result_love", 0,
                "best_love", 0,
                "before_best_love", 0,
                "music_id", state.musicId,
                "play_report", state.playReport,
                "mastary_level_before", master.musicLevel(state.musicId, expBefore),
                "mastary_level_after", level,
                "mastary_level_experience", gain,
                "mastary_level_total_experience_before", expBefore,
                "style_point", state.questResult ? master.stageStylePoint(state.stageId) : 0,
                "before_earned_music_exp", expBefore,
                "earned_music_exp", exp,
                "first_clear_flag", state.firstClear,
                "is_limit_over_style_point", false,
                "is_ex_stage_open", false);
    }

    /** 服务端能渲染的学习曲目录，叠上本账号的经验 / 熟练度 / 逐关通关行。 */
    private List<Map<String, Object>> learningList(PlayerContext player) {
        return WireServices.overlayNested(
                services.served("learning_live_catalog", WireServices.RHYTHM_MUSIC_ASSETS),
                WireServices.rows(player, "learning_live_progress"),
                "learning_live_series_id", "stage_list", "learning_live_stages_id",
                Set.of("is_lock", "is_clear"),
                Set.of("earned_music_exp", "music_exp_level", "is_mastery"));
    }

    private Map<String, Object> learningRow(PlayerContext player, long musicId) {
        for (Map<String, Object> row : learningList(player)) {
            if (J.longOr(row.get("music_id"), Long.MIN_VALUE) == musicId) {
                return row;
            }
        }
        return null;
    }

    /** {@code (music row, stage row)}；没打包这一关返回 null。 */
    private Object[] learningStageRow(long stageId) {
        for (Map<String, Object> music : services.served("learning_live_catalog",
                WireServices.RHYTHM_MUSIC_ASSETS)) {
            for (Map<String, Object> stage : J.rowsOr(music.get("stage_list"))) {
                if (J.longOr(stage.get("learning_live_stages_id"), Long.MIN_VALUE) == stageId) {
                    return new Object[] {music, stage};
                }
            }
        }
        return null;
    }

    private boolean learningStageCleared(PlayerContext player, long stageId) {
        Object[] found = learningStageRow(stageId);
        long musicId = found == null ? 0L
                : J.longOr(((Map<?, ?>) found[0]).get("music_id"), 0L);
        Map<String, Object> row = learningRow(player, musicId);
        if (row == null) {
            return false;
        }
        for (Map<String, Object> stage : J.rowsOr(row.get("stage_list"))) {
            if (J.longOr(stage.get("learning_live_stages_id"), Long.MIN_VALUE) == stageId
                    && J.truthy(stage.get("is_clear"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 学习关通关：关卡的经验记到歌曲熟练度上（{@code learning_live_progress} 与
     * {@code RhythmMusic} 两处都写，SHOW 界面读后者），关卡转为已通关。
     *
     * @return 首次通关标志位（{@link #RESULT_FIRST_CLEAR}），否则 0
     */
    private int settleLearning(PlayerContext player, long stageId, long musicId) {
        Object[] found = learningStageRow(stageId);
        if (found == null) {
            return 0;
        }
        Map<?, ?> music = (Map<?, ?>) found[0];
        Map<?, ?> stage = (Map<?, ?>) found[1];
        Object seriesId = music.get("learning_live_series_id");

        List<Map<String, Object>> progress = WireServices.rows(player, "learning_live_progress");
        Map<String, Object> row = null;
        for (Map<String, Object> item : progress) {
            if (Objects.equals(J.str(item.get("learning_live_series_id")), J.str(seriesId))) {
                row = item;
                break;
            }
        }
        if (row == null) {
            row = J.map("learning_live_series_id", seriesId, "music_id", musicId,
                    "earned_music_exp", 0, "music_exp_level", 0, "is_mastery", false,
                    "stage_list", new ArrayList<>());
            progress.add(row);
        }
        List<Map<String, Object>> stages = new ArrayList<>(J.rowsOr(row.get("stage_list")));
        Map<String, Object> mine = null;
        for (Map<String, Object> item : stages) {
            if (J.longOr(item.get("learning_live_stages_id"), Long.MIN_VALUE) == stageId) {
                mine = item;
                break;
            }
        }
        boolean firstClear = mine == null || !J.truthy(mine.get("is_clear"));
        if (mine == null) {
            stages.add(J.map("learning_live_stages_id", stageId, "is_lock", false,
                    "is_clear", true));
        } else {
            mine.put("is_clear", true);
        }
        row.put("stage_list", stages);
        long exp = J.longOr(row.get("earned_music_exp"), 0L)
                + J.longOr(stage.get("gain_music_exp"), 0L);
        int level = master.musicLevel(musicId, exp);
        row.put("earned_music_exp", exp);
        row.put("music_exp_level", level);
        row.put("is_mastery", level >= 1);
        player.put("learning_live_progress", progress);

        List<Map<String, Object>> rhythm = WireServices.rows(player, "RhythmMusic");
        Map<String, Object> song = null;
        for (Map<String, Object> item : rhythm) {
            Object ident = item.containsKey("MusicId") ? item.get("MusicId") : item.get("music_id");
            if (J.longOr(ident, Long.MIN_VALUE) == musicId) {
                song = item;
                break;
            }
        }
        if (song == null) {
            song = J.map("MusicId", musicId, "MusicScores", new ArrayList<>(),
                    "HighScore", 0, "HighScoreAchievementStatus", 0);
            rhythm.add(song);
        }
        song.put("MusicMasteryExp", exp);
        song.put("MusicMasteryLevel", level);
        player.put("RhythmMusic", rhythm);
        return firstClear ? RESULT_FIRST_CLEAR : 0;
    }

    // ------------------------------------------------------------------
    // Dream Live
    // ------------------------------------------------------------------

    private Map<String, Object> dreamResult(WireRequest request) {
        QuestState state = typedResult(request.requirePlayer(), request.body(), QUEST_DREAM);
        return J.map("stage_id", state.stageId,
                "quest_live_type", QUEST_DREAM,
                "is_clear", state.questResult,
                "result_love", 0,
                "get_card_id", 0,
                "music_id", state.musicId,
                "play_report", state.playReport,
                "first_clear_flag", false);
    }

    private Map<String, Object> dreamMemberSelect(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        settleBeginnerProgress(player, request.store(), WireShop.BEGINNER_CONDITION_DREAM_LIVE_TOP);
        return J.map("quest_lives", dreamMembers(player));
    }

    private Map<String, Object> dreamNotifyRelease(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        settleBeginnerProgress(player, request.store(),
                WireShop.BEGINNER_CONDITION_MEMBER_RELEASE_CONFIRM);
        return J.emptyMap();
    }

    /** 服务端能渲染的 Dream Live 系列，叠上本账号的解放进度。 */
    private List<Map<String, Object>> dreamMembers(PlayerContext player) {
        List<Map<String, Object>> rows = WireServices.overlay(
                services.served("dream_live_catalog", DREAM_LIVE_ASSETS),
                WireServices.rows(player, "dream_live_members"), "dream_quest_stages_id");
        Map<String, Object> table = master.dreamMaster();
        for (Map<String, Object> row : rows) {
            Map<String, Object> spec = J.nodeOr(
                    table.get(String.valueOf(row.get("dream_quest_series_id"))));
            if (spec != null) {
                dreamRefresh(player, row, spec);
            }
        }
        return rows;
    }

    /** {@code (spec, index)}；未知关卡返回 null。 */
    private Object[] dreamStageSpec(long stageId) {
        for (Object value : master.dreamMaster().values()) {
            if (!(value instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> spec = J.nodeOr(map);
            List<Map<String, Object>> stages = J.rowsOr(spec.get("stages"));
            for (int index = 0; index < stages.size(); index++) {
                if (J.longOr(stages.get(index).get("stage_id"), Long.MIN_VALUE) == stageId) {
                    return new Object[] {spec, index};
                }
            }
        }
        return null;
    }

    private static int dreamReleased(Map<String, Object> row) {
        return (J.truthy(row.get("is_get_card")) ? 1 : 0)
                + J.intOr(row.get("limit_break_times"), 0);
    }

    /** {@code (全部满足, condition_list)}；Grade 段位本地不追踪，按已满足算。 */
    private Object[] dreamConditions(PlayerContext player, Map<String, Object> spec,
                                     Map<String, Object> stage) {
        long fan = 0L;
        Object characterId = spec.get("character_id");
        for (Map<String, Object> row : player.rowsSnapshot("MemberFanLevels")) {
            if (Objects.equals(J.str(row.get("CharactersId")), J.str(characterId))) {
                fan = J.longOr(row.get("MemberFanlevel"), 0L);
                break;
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        boolean met = true;
        long useItem = J.longOr(stage.get("use_item"), 0L);
        String itemName = J.str(stage.get("item_name"));
        for (Map<String, Object> condition : J.rowsOr(stage.get("conditions"))) {
            int kind = J.intOr(condition.get("type"), 0);
            long value = J.longOr(condition.get("value"), 0L);
            long have;
            String text;
            if (DREAM_CONDITION_ITEM.contains(kind)) {
                have = WireShop.itemNum(player, useItem);
                text = String.format("%sを%d個集めよう", itemName == null ? "" : itemName, value);
            } else if (kind == DREAM_CONDITION_GRADE) {
                have = value;
                text = "Gradeを" + DREAM_GRADE_NAMES.getOrDefault((int) value,
                        String.valueOf(value)) + "まで上げよう";
            } else {
                have = fan;
                text = String.format("%sのファンレベルを%dまで上げよう",
                        J.str(spec.get("character_name")), value);
            }
            met = met && have >= value;
            result.add(J.map("condition_text", text,
                    "achieve_count", Math.min(have, value),
                    "is_release", have >= value,
                    "is_display_progress_num", true));
        }
        return new Object[] {met, result};
    }

    private void dreamRefresh(PlayerContext player, Map<String, Object> row,
                              Map<String, Object> spec) {
        List<Map<String, Object>> stages = J.rowsOr(spec.get("stages"));
        if (stages.isEmpty()) {
            return;
        }
        Map<String, Object> stage = stages.get(Math.min(dreamReleased(row), stages.size() - 1));
        row.put("dream_quest_stages_id", stage.get("stage_id"));
        row.put("condition_list", dreamConditions(player, spec, stage)[1]);
    }

    /**
     * 解放 Dream Live 成员的下一步：扣掉该步的道具，第 0 步发 DR 卡、之后每步加一次界限突破，
     * 进度记在 {@code dream_live_members}。
     */
    private Map<String, Object> dreamSetCard(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        long stageId = WireServices.integer(request.body(), "dream_quest_stages_id",
                (Long) null, 1, Integer.MAX_VALUE);
        Object[] found = dreamStageSpec(stageId);
        if (found == null) {
            throw WireError.notFound("Local dream live stage not found");
        }
        Map<String, Object> spec = J.nodeOr(found[0]);
        int index = (Integer) found[1];
        Map<String, Object> catalog = null;
        for (Map<String, Object> row : services.served("dream_live_catalog", DREAM_LIVE_ASSETS)) {
            if (Objects.equals(J.str(row.get("dream_quest_series_id")),
                    J.str(spec.get("series_id")))) {
                catalog = row;
                break;
            }
        }
        if (catalog == null) {
            throw WireError.notFound("Local dream live series not found");
        }
        List<Map<String, Object>> rows = WireServices.rows(player, "dream_live_members");
        Map<String, Object> mine = null;
        for (Map<String, Object> row : rows) {
            if (Objects.equals(J.str(row.get("dream_quest_stages_id")),
                    J.str(catalog.get("dream_quest_stages_id")))) {
                mine = row;
                break;
            }
        }
        if (mine == null) {
            mine = J.map("dream_quest_stages_id", catalog.get("dream_quest_stages_id"),
                    "dream_quest_series_id", spec.get("series_id"),
                    "is_get_card", false, "limit_break_times", 0);
            rows.add(mine);
        }
        if (dreamReleased(mine) != index) {
            throw WireError.badRequest("This dream live stage is not the next one to release");
        }
        List<Map<String, Object>> stages = J.rowsOr(spec.get("stages"));
        Map<String, Object> stage = stages.get(index);
        if (!Boolean.TRUE.equals(dreamConditions(player, spec, stage)[0])) {
            throw WireError.badRequest("Dream live release conditions are not met");
        }
        debitItem(player, J.longOr(stage.get("use_item"), 0L),
                J.longOr(stage.get("use_num"), 0L));

        int cardId = J.intOr(spec.get("card_id"), 0);
        if (index == 0) {
            grantPetalCard(player, cardId);
            mine.put("is_get_card", true);
        } else {
            for (Map<String, Object> card : player.rows("Cards")) {
                if (J.longOr(card.get("CardDatasId"), Long.MIN_VALUE) != cardId) {
                    continue;
                }
                long times = Math.min(J.longOr(card.get("LimitBreakTimes"), 0L) + 1,
                        J.longOr(card.get("MaxLimitBreakTimes"), 0L));
                card.put("LimitBreakTimes", times);
                card.put("IsLimitBreak", times > 0);
                break;
            }
            mine.put("limit_break_times", J.intOr(mine.get("limit_break_times"), 0) + 1);
        }
        player.put("dream_live_members", rows);
        request.store().save(player);
        return J.map("card_id", cardId);
    }

    // ------------------------------------------------------------------
    // Grade Live
    // ------------------------------------------------------------------

    private Map<String, Object> gradeLiveList(WireRequest request) {
        return J.map("grade_live_list", gradeLives(request.requirePlayer()));
    }

    private Map<String, Object> gradeQuestList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        return J.map("quest_season_list", WireServices.overlayNested(
                        services.refs("grade_season_catalog"),
                        WireServices.rows(player, "grade_season_states"),
                        "grade_quest_season_id", "quest_series_list", "grade_quest_series_id",
                        Set.of("play_status", "is_lock", "clear_status"),
                        Set.of("play_status", "is_lock", "clear_num")),
                "point_bonus_list", new ArrayList<>(),
                "is_update_grade_live", false);
    }

    /**
     * 可选段位。主数据里没有段位列表，因此只给「当前段位」一条可选（名字留空），
     * 让客户端至少能打开关卡列表。
     */
    private Map<String, Object> gradeRankList(WireRequest request) {
        String questId = requireId(request.body(), "quest_id");
        if (services.refs("grade_live_catalog").isEmpty()) {
            return J.map("quest_id", questId, "rank_list", new ArrayList<>(),
                    "current_rank", 0, "next_rank", 0);
        }
        gradeSeries(questId);
        Map<String, Object> live = null;
        for (Map<String, Object> row : gradeLives(request.requirePlayer())) {
            if (Objects.equals(J.str(row.get("quest_id")), questId)) {
                live = row;
                break;
            }
        }
        long rank = live == null ? 1L : Math.max(J.longOr(live.get("current_rank"), 0L), 1L);
        return J.map("quest_id", questId,
                "rank_list", new ArrayList<>(List.of(
                        J.map("rank", rank, "rank_name", "", "is_clear", false))),
                "current_rank", live == null ? 0 : live.get("current_rank"),
                "next_rank", 0);
    }

    private List<Map<String, Object>> gradeLives(PlayerContext player) {
        return WireServices.overlay(services.refs("grade_live_catalog"),
                WireServices.rows(player, "grade_live_states"), "quest_id");
    }

    /** {@code _grade_series}：系列必须出现在目录里且是数字 ID。 */
    private long gradeSeries(String questId) {
        List<Map<String, Object>> lives = services.refs("grade_live_catalog");
        boolean known = false;
        for (Map<String, Object> row : lives) {
            if (Objects.equals(J.str(row.get("quest_id")), questId)) {
                known = true;
                break;
            }
        }
        if (!lives.isEmpty() && !known) {
            throw WireError.notFound("Local grade live series not found");
        }
        try {
            return Long.parseLong(questId);
        } catch (NumberFormatException e) {
            throw WireError.notFound("Local grade live series not found");
        }
    }

    private Map<String, Object> gradeStageList(WireRequest request) {
        String questId = requireId(request.body(), "quest_id");
        WireServices.integer(request.body(), "rank", (Long) null, 0, Integer.MAX_VALUE);
        long series = gradeSeries(questId);
        List<Map<String, Object>> all = services.refs("grade_quest_stages");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : all) {
            if (J.longOr(row.get("series_id"), Long.MIN_VALUE) == series) {
                rows.add(row);
            }
        }
        if (!all.isEmpty() && rows.isEmpty()) {
            throw WireError.notFound("Local grade live series not found");
        }
        List<Map<String, Object>> normal = new ArrayList<>();
        Map<String, Object> boss = null;
        for (Map<String, Object> row : rows) {
            if (J.truthy(row.get("is_boss"))) {
                if (boss == null) {
                    boss = row;
                }
            } else {
                normal.add(J.map("stage_id", String.valueOf(row.get("stage_id")),
                        "clear_status", J.longOr(row.get("clear_status"), 0L),
                        "is_lock", J.truthy(row.get("is_lock"))));
            }
        }
        Map<String, Object> bossWire = boss == null ? null : J.map(
                "stage_id", String.valueOf(boss.get("stage_id")),
                "is_lock", J.truthy(boss.get("is_lock")),
                "star_num", 0,
                "stage_reward_list", new ArrayList<>());
        return J.map("normal_stage_list", normal, "boss_stage", bossWire);
    }

    private Map<String, Object> gradeStageData(WireRequest request) {
        long squareId = WireServices.integer(request.body(), "grade_quest_square_id",
                (Long) null, 0, Integer.MAX_VALUE);
        Map<String, Object> square = null;
        for (Map<String, Object> row : services.refs("grade_quest_squares")) {
            if (J.longOr(row.get("grade_quest_square_id"), Long.MIN_VALUE) == squareId) {
                square = row;
                break;
            }
        }
        if (square == null) {
            throw WireError.notFound("Local grade quest square not found");
        }
        long target = J.longOr(square.get("target_id"), 0L);
        Map<String, Object> stage = target == 0 ? null
                : stageRow(services.refs("grade_quest_stages"), target, null, null);
        if (target != 0 && stage == null) {
            throw WireError.notFound("Local grade quest stage not found");
        }
        long livePoint = J.longOr(square.get("live_point"), 0L);
        if (livePoint == 0 && stage != null) {
            livePoint = J.longOr(stage.get("live_point"), 0L);
        }
        return J.map("live_point", livePoint,
                "music_list", musicList(stage == null ? J.emptyMap() : stage,
                        catalogMusicIds()));
    }

    /** 赛季段位排行本地不保存；status 0 是 SeasonGradeStatus 的默认值。 */
    private Map<String, Object> gradeRankingList(WireRequest request) {
        Map<String, Object> body = request.body();
        WireServices.integer(body, "season_grade_id", 0, 0, Integer.MAX_VALUE);
        WireServices.integer(body, "get_rank_type", 0, 0, 2);
        Object target = body.get("target_rank");
        if (target != null && WireServices.strictLong(target) == null) {
            throw WireError.badRequest("target_rank must be an integer or null");
        }
        return J.map("status", 0, "my_rank", 0, "point_rankings", new ArrayList<>());
    }

    private static QuestState typedResult(PlayerContext player, Map<String, Object> body,
                                          int liveType) {
        QuestState state = requireQuest(player, body.get("quest_live_id"), Set.of("done"));
        if (state.liveType != liveType) {
            throw new WireError(409, "The finished local Quest Live is of another type");
        }
        return state;
    }

    // ------------------------------------------------------------------
    // 结算 helper
    // ------------------------------------------------------------------

    /** {@code clear_status}：达到几档星级门槛；没有打包门槛的关卡，正分即一星。 */
    private int questStars(long stageId, Long score) {
        List<Object> thresholds = master.stageScores(stageId);
        boolean hasThresholds = thresholds != null && !thresholds.isEmpty();
        if (score == null) {
            return hasThresholds ? 3 : 1;
        }
        if (!hasThresholds) {
            return score > 0 ? 1 : 0;
        }
        int stars = 0;
        for (Object target : thresholds) {
            long value = J.longOr(target, 0L);
            if (value != 0 && score >= value) {
                stars++;
            }
        }
        return stars;
    }

    /** 记录最好通关星级，返回 {@code first_clear / first_complete} 标志位。 */
    private static int recordStageClear(PlayerContext player, long stageId, int stars) {
        List<Map<String, Object>> rows =
                new ArrayList<>(player.rowsSnapshot("quest_stage_states"));
        Map<String, Object> state = null;
        for (Map<String, Object> row : rows) {
            if (J.longOr(row.get("stage_id"), Long.MIN_VALUE) == stageId) {
                state = row;
                break;
            }
        }
        long before = state == null ? 0L : J.longOr(state.get("clear_status"), 0L);
        int flags = (before == 0 ? RESULT_FIRST_CLEAR : 0)
                | (before < 3 && stars >= 3 ? RESULT_FIRST_COMPLETE : 0);
        if (state == null) {
            rows.add(J.map("stage_id", stageId, "clear_status", stars, "is_lock", false));
            player.put("quest_stage_states", rows);
        } else if (stars > before) {
            state.put("clear_status", stars);
        }
        return flags;
    }

    /**
     * 固定奖励每次全给，随机池抽一个；每条数量是 {@code item_num}（缺省 1）再加 0 或 1 ——
     * 上游实测大约 1:1，对应 Python {@code _roll_quest_drops}。
     */
    private List<Map<String, Object>> rollQuestDrops(long stageId) {
        List<Map<String, Object>> chosen = new ArrayList<>();
        List<Map<String, Object>> pool = new ArrayList<>();
        for (Map<String, Object> reward : master.stageRewards(stageId)) {
            int kind = J.intOr(reward.get("reward_type"), 0);
            if (kind == REWARD_FIXED) {
                chosen.add(reward);
            } else if (kind == REWARD_RANDOM) {
                pool.add(reward);
            }
        }
        if (!pool.isEmpty()) {
            chosen.add(pool.get(ThreadLocalRandom.current().nextInt(pool.size())));
        }
        List<Map<String, Object>> drops = new ArrayList<>(chosen.size());
        for (Map<String, Object> reward : chosen) {
            long num = J.longOr(reward.get("item_num"), 0L);
            if (num == 0) {
                num = 1;
            }
            num += ThreadLocalRandom.current().nextInt(2);
            drops.add(J.map("item_id", J.longOr(reward.get("item_id"), 0L),
                    "item_num", num, "reward_type", reward.get("reward_type")));
        }
        return drops;
    }

    /** 发奖：拿不到的道具跳过，与 Python 的 {@code try/except: continue} 一致。 */
    private void grantQuestDrops(PlayerContext player, List<Map<String, Object>> drops) {
        for (Map<String, Object> drop : drops) {
            try {
                grants.grantItem(player, J.longOr(drop.get("item_id"), 0L),
                        (int) J.longOr(drop.get("item_num"), 0L));
            } catch (IllegalArgumentException ignored) {
                // 本地没有核实的发放路径就跳过这一条，其余照发。
            } catch (WireError error) {
                if (error.status() != 501) {
                    throw error;
                }
                // 与 Python 的「不可核实奖励」一致：只跳过这一条，数据访问故障仍然上抛。
            }
        }
    }

    private static void addStylePoint(PlayerContext player, long gain) {
        long have = player.longOf("StylePoint", 0L);
        WireGrants.setWallet(player, "StylePoint",
                Math.min(have + gain, WireQuestMaster.STYLE_POINT_LIMIT));
    }

    /** 新手任务进度推进；只有真的推进了才落库（Python 的 {@code _settle_beginner_progress}）。 */
    private void settleBeginnerProgress(PlayerContext player, PlayerStore store,
                                        int conditionType) {
        Map<String, Object> before = snapshot(player, List.of("beginner_missions"));
        shop.advance(player, conditionType);
        if (!Objects.equals(before.get("beginner_missions"),
                player.raw("beginner_missions"))) {
            store.save(player);
        }
    }

    private static Map<String, Object> snapshot(PlayerContext player, List<String> keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : keys) {
            Object value = player.raw(key);
            out.put(key, value == null ? null : WireServices.deepCopyJson(value));
        }
        Player entity = player.entity();
        out.put("__jewelFree", entity.jewelFree);
        out.put("__jewelPaid", entity.jewelPaid);
        out.put("__musicPoint", entity.musicPoint);
        out.put("__stickerPointNum", entity.stickerPointNum);
        return out;
    }

    private static void restore(PlayerContext player, Map<String, Object> before) {
        for (Map.Entry<String, Object> entry : before.entrySet()) {
            if (entry.getKey().startsWith("__")) {
                continue;
            }
            if (entry.getValue() == null) {
                player.remove(entry.getKey());
            } else {
                player.put(entry.getKey(), entry.getValue());
            }
        }
        Player entity = player.entity();
        entity.jewelFree = (Long) before.get("__jewelFree");
        entity.jewelPaid = (Long) before.get("__jewelPaid");
        entity.musicPoint = (Long) before.get("__musicPoint");
        entity.stickerPointNum = (Long) before.get("__stickerPointNum");
    }

    // ------------------------------------------------------------------
    // 通用 helper
    // ------------------------------------------------------------------

    private static Map<String, Object> stamina(PlayerContext player) {
        return Stamina.snapshot(player, null);
    }

    private static String requireId(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String text) || text.isBlank() || text.length() > 128) {
            throw WireError.badRequest(
                    key + " must be a non-empty string of at most 128 characters");
        }
        return text;
    }

    /** {@code wire_shop.music_release}：只返回需要 Music Pt. 解放的曲子。 */
    private Map<String, Object> musicRelease(long musicId) {
        Map<String, Object> catalog = defaults.object("shop_catalog")
                .orElseGet(LinkedHashMap::new);
        return J.nodeOr(J.nodeOrEmpty(catalog.get("music_releases"))
                .get(String.valueOf(musicId)));
    }

    /** SIsCa 扣费：{@code JewelPaidGoogle} 存在时优先，绝不与 {@code JewelPaid} 相加。 */
    private static void debitJewels(PlayerContext player, long cost, boolean paidOnly) {
        long free = player.entity().jewelFree;
        boolean google = player.entity().jewelPaidGoogle != 0;
        long paid = google ? player.entity().jewelPaidGoogle : player.entity().jewelPaid;
        if (free < 0 || paid < 0 || (paidOnly ? paid : free + paid) < cost) {
            throw WireError.badRequest(paidOnly ? "Not enough paid SIsCa" : "Not enough SIsCa");
        }
        long freeCost = paidOnly ? 0 : Math.min(free, cost);
        if (freeCost > 0) {
            player.entity().jewelFree = free - freeCost;
        }
        if (cost > freeCost) {
            if (google) {
                player.entity().jewelPaidGoogle = paid - (cost - freeCost);
            } else {
                player.entity().jewelPaid = paid - (cost - freeCost);
            }
        }
    }

    /** 扣背包道具；不够 400（Python {@code wire_shop._debit_item}）。 */
    private static void debitItem(PlayerContext player, long itemId, long quantity) {
        if (quantity <= 0) {
            return;
        }
        Map<String, Object> owned = null;
        for (Map<String, Object> group : player.rowsSnapshot("Items")) {
            for (Map<String, Object> row : J.rowsOr(group.get("UserItemList"))) {
                if (J.longOr(row.get("ItemId"), Long.MIN_VALUE) == itemId) {
                    owned = row;
                    break;
                }
            }
            if (owned != null) {
                break;
            }
        }
        long have = owned == null ? 0L : J.longOr(owned.get("ItemNum"), 0L);
        if (have < quantity) {
            throw WireError.badRequest("Not enough items");
        }
        owned.put("ItemNum", have - quantity);
    }

    /** {@code _grant_petal_card}：已持有同一 {@code CardDatasId} 时不再发。 */
    private void grantPetalCard(PlayerContext player, int cardDatasId) {
        for (Map<String, Object> card : player.rows("Cards")) {
            if (J.longOr(card.get("CardDatasId"), Long.MIN_VALUE) == cardDatasId) {
                return;
            }
        }
        try {
            mint.grant(player, cardDatasId);
        } catch (IllegalArgumentException e) {
            throw WireError.notFound("Local petal-exchange card not found");
        }
    }
}
