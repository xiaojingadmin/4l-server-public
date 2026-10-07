package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import com.linklike.server.service.PlayerContext;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 本地账号的读取模型（资料 / 首页）—— 对应 Python 版 {@code wire_state.py}。
 *
 * <p>这些是「本地未拥有 / 未游玩」的账号状态，不是官方服务端的抓包；这里不会编造 master
 * 数据 ID、拥有关系、购买记录或已完成的玩法进度。
 *
 * <p>{@link #profile} 与 {@link #home} 都依赖资料模板（{@code profile_defaults} 目录）。
 * 客户端抽取产物缺失时模板为 null，这两个方法抛 {@link IllegalStateException}；调用方在
 * 调用前用 {@link #profileTemplateAvailable()} 判断并返回 501，不拿空对象糊过去。
 */
@Component
public class WireState {

    /** Python {@code datetime.isoformat()} 的秒级形态（再加 Z）。 */
    private static final DateTimeFormatter SECOND_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private static final DateTimeFormatter MICRO_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    /** 客户端约定的生日默认值（Python 版同字面量）。 */
    private static final String DEFAULT_BIRTHDAY = "0001-01-01T00:00:00Z";

    private final WireCatalog catalog;

    public WireState(WireCatalog catalog) {
        this.catalog = catalog;
    }

    /** 资料模板（客户端抽取产物）是否可用。 */
    public boolean profileTemplateAvailable() {
        return catalog.profileTemplate() != null;
    }

    /** 与 Python {@code wire_state.timestamp} 等价：UTC ISO-8601，整数秒不带小数位。 */
    public static String timestamp(long epochSeconds) {
        Instant moment = Instant.ofEpochSecond(epochSeconds);
        return moment.getNano() == 0
                ? SECOND_FORMAT.format(moment.atOffset(ZoneOffset.UTC))
                : MICRO_FORMAT.format(moment.atOffset(ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------
    // 资料
    // ------------------------------------------------------------------

    /**
     * {@code ProfileInfo} 的本地形态，对应 Python 版 {@code wire_state.profile}。
     *
     * @throws IllegalStateException 资料模板（客户端抽取产物）缺失时
     */
    public Map<String, Object> profile(PlayerContext player) {
        Map<String, Object> template = catalog.profileTemplate();
        if (template == null) {
            throw new IllegalStateException("资料模板未导入（缺 base_res_content_getProfileInfo.json）");
        }
        List<Map<String, Object>> friendCards = new ArrayList<>();
        Map<String, Object> friendCard = J.nodeOrEmpty(player.raw("FriendCard"));
        for (FriendCardSlot slot : FRIEND_CARD_SLOTS) {
            Object ident = friendCard.get(slot.key());
            Map<String, Object> card = findCard(player, ident);
            if (card != null) {
                friendCards.add(J.map("CardType", slot.cardType(), "UserCardInfo", card));
            }
        }

        long created = player.entity().created;
        // Python 的 player.get('LastLogin', created)：LastLogin 是 NULL 列时读取会省略该键，
        // 于是回落到 created。Java 的列是 long（NULL 落成 0），所以 0 要按「未登录过」处理，
        // 否则新建后还没登录过的账号会下发 1970-01-01。
        long lastLogin = player.entity().lastLogin > 0 ? player.entity().lastLogin : created;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("PlayerName", player.entity().playerName);
        out.put("PlayerId", player.id());
        out.put("UserType", player.entity().temporary ? 0 : 1);
        out.put("ProfileIconPartsInfo", orDefault(player.raw("ProfileIconPartsInfo"),
                template.get("profile_icon_parts_info")));
        out.put("ProfileCardPartsInfo", orDefault(player.raw("ProfileCardPartsInfo"),
                template.get("profile_card_parts_info")));
        out.put("FanLevel", player.entity().fanLevel == null ? 0L : player.entity().fanLevel);
        out.put("Birthday", orDefault(player.raw("Birthday"), DEFAULT_BIRTHDAY));
        out.put("IsBirthdayChangeable", player.boolOf("IsBirthdayChangeable", true));
        out.put("EntryTime", timestamp(created));
        out.put("LastLoginDate", timestamp(lastLogin));
        out.put("Comment", orDefault(player.entity().comment, ""));
        out.put("SearchGuildKey", orDefault(player.raw("SearchGuildKey"), ""));
        out.put("GuildName", orDefault(player.raw("GuildName"), ""));
        out.put("StickerNum", player.rowsSnapshot("Stickers").size());
        out.put("CardNum", player.rowsSnapshot("Cards").size());
        out.put("DreamStyleNum", player.intOf("DreamStyleNum", 0));
        out.put("FriendNum", player.entity().friendCount);
        out.put("FriendMaxNum", template.get("friend_max_num"));
        out.put("MusicNum", player.rowsSnapshot("MusicHistory").size());
        out.put("MusicMaxNum", template.get("music_max_num"));
        out.put("StandardLiveClearNum", player.intOf("StandardLiveClearNum", 0));
        out.put("StandardLiveTopClearNum", player.intOf("StandardLiveTopClearNum", 0));
        out.put("StandardLiveMaxNum", template.get("standard_live_max_num"));
        out.put("StandardLiveStarTotalNum", player.intOf("StandardLiveStarTotalNum", 0));
        out.put("GradeLiveClearNum", player.intOf("GradeLiveClearNum", 0));
        out.put("GradeLiveSeriesTopClearRankList", player.rowsSnapshot("GradeLiveSeriesTopClearRankList"));
        out.put("GradeLiveTopClearNum", player.intOf("GradeLiveTopClearNum", 0));
        out.put("GradeLiveMaxNum", player.intOf("GradeLiveMaxNum", 0));
        out.put("FanLevelList", player.rowsSnapshot("FanLevels"));
        out.put("FriendCardInfo", friendCards);
        return out;
    }

    /**
     * 好友名片两种槽位（Stage 卡 / 节奏游戏卡）在协议里的 {@code CardType}。
     *
     * <p>顺序是协议的一部分：Python 遍历固定元组 {@code ((1, ...), (2, ...))}，所以
     * CardType 1 一定排在 2 前面。这里用 List 而不是 {@code Map.copyOf}，后者不保证迭代顺序。
     */
    private static final List<FriendCardSlot> FRIEND_CARD_SLOTS = List.of(
            new FriendCardSlot(1, "SchoolIdolStageCardId"),
            new FriendCardSlot(2, "RhythmGameCardId"));

    private record FriendCardSlot(int cardType, String key) {
    }

    // ------------------------------------------------------------------
    // 首页
    // ------------------------------------------------------------------

    /**
     * {@code HomeGetHomeResponse} 的本地形态，对应 Python 版 {@code wire_state.home}。
     *
     * @throws IllegalStateException 资料模板（客户端抽取产物）缺失时
     */
    public Map<String, Object> home(PlayerContext player) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("PlanList", catalog.homePlans());
        out.put("IsLoginBonus", !player.rowsSnapshot("LoginBonuses").isEmpty());
        out.put("ProfileInfo", profile(player));
        out.put("IsFinishTutorial", player.boolOf("TutorialComplete", false));
        out.put("IsFirstHome", !player.boolOf("HomeVisited", false));
        out.put("MembershipUpdateConfirmList", player.rowsSnapshot("MembershipUpdates"));
        out.put("MembershipPendingList", player.rowsSnapshot("MembershipPending"));
        out.put("QuestQuitInfo", player.raw("QuestQuitInfo"));
        out.put("FinishedSimpleTutorialList", player.rowsSnapshot("FinishedTutorials"));
        out.put("BeginnerMissionStatus", player.intOf("BeginnerMissionStatus", 0));
        out.put("IsNotWatchedAdvFromCurrentSeason",
                player.boolOf("IsNotWatchedAdvFromCurrentSeason", false));
        out.put("HasDailyTicket", player.intOf("DailyTicketNum", 0) > 0);
        out.put("HasGrandPrixPlayableCount", player.intOf("GrandPrixPlayableCount", 0) > 0);
        out.put("LatestNewsId", orDefault(player.raw("LatestNewsId"), ""));
        out.put("HighlightedBadgeInfo", player.raw("HighlightedBadgeInfo"));
        out.put("ExpiredLimitedGachaTicketIdConfirmList",
                player.rowsSnapshot("ExpiredLimitedGachaTickets"));
        out.put("StandardQuestAreasId", player.intOf("StandardQuestAreasId", 0));
        out.put("StandardQuestStagesId", player.intOf("StandardQuestStagesId", 0));
        return out;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static Map<String, Object> findCard(PlayerContext player, Object ident) {
        if (ident == null || "".equals(ident)) {
            return null;
        }
        for (Map<String, Object> card : player.rowsSnapshot("Cards")) {
            if (String.valueOf(ident).equals(String.valueOf(card.get("DCardDatasId")))) {
                return card;
            }
        }
        return null;
    }

    /** Python 的 {@code player.get(key, fallback)}：键不存在（或为 null）时取兜底值。 */
    private static Object orDefault(Object value, Object fallback) {
        return value == null ? fallback : value;
    }
}
