package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import com.linklike.server.service.PlayerContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会员粉丝等级成长 —— 对应 Python 版 {@code fanlevel.py}。
 *
 * <p>粉丝点数来自 {@code CardSeries} 主数据（新卡的 {@code ObtainFanLvPt}、
 * {@code EvolutionNFanLvPt}、{@code LimitBreakNFanLvPt}），按角色累计成经验值；
 * {@code MemberFanLevels} 主数据把总量映射成 {@code MemberFanLevel}（低于第一行是 1 级，
 * 封顶 999）。
 *
 * <p>每角色状态是 {@code FanLevelDetails}（{@code ProfileGetFanLevelInfo} 的行：
 * {@code MemberFanExperience}、{@code MemberFanLevel}、带赛季点数历史的
 * {@code EarnProgressList}）。{@code MemberFanLevels}、{@code FanLevels}、每张卡的
 * {@code MemberFanLevel} 和账号 {@code FanLevel}（各角色等级之和）都与它保持一致。
 * With×MEETS / Fes×LIVE 点数（EarnType 1/2）与赛季点数存量（6）本地没有来源。
 *
 * <p><b>与 Python 版的差距</b>：点数换算依赖 {@code admin/catalog.py} 的 master 目录
 * （{@code master_cards} / {@code master_card_series} / {@code master_member_fan_levels}），
 * Java 侧还没导入。这里按 Python 版「目录不可用」的分支运行：经验照记、等级不动、
 * 卡片的 {@code MemberFanLevel} 取账号已有等级。等 master 目录移植后接上
 * {@link Catalog} 即可，调用点无需改动。
 */
public final class WireFanLevel {

    private WireFanLevel() {
    }

    public static final int EARN_WITH_MEETS = 1;
    public static final int EARN_FES_LIVE = 2;
    public static final int EARN_NEW_CARD = 3;
    public static final int EARN_EVOLUTION = 4;
    public static final int EARN_LIMIT_BREAK = 5;
    public static final int EARN_STOCK = 6;

    public static final List<Integer> EARN_TYPES = List.of(
            EARN_WITH_MEETS, EARN_FES_LIVE, EARN_NEW_CARD, EARN_EVOLUTION, EARN_LIMIT_BREAK);

    /** 参与草稿 / 落库的字段，对应 Python 的 {@code fanlevel.FIELDS}。 */
    public static final List<String> FIELDS = List.of(
            "FanLevelDetails", "MemberFanLevels", "FanLevels", "FanLevel");

    /**
     * 粉丝点数的 master 目录。
     *
     * <p>Python 版是 {@code admin/catalog.py} 的 {@code Catalog}。移植前为 null，
     * 表示「本机没有可核对的 CardSeries 点数」，此时不臆造点数。
     */
    public interface Catalog {

        /** 达到 {@code experience} 点时应有的 {@code MemberFanLevel}。 */
        int memberFanLevel(long experience);

        /** {@code MemberFanLevel} 对应的经验门槛。 */
        long memberFanExperience(int level);

        /** {@code CardDatasId} 对应的 CardDatas 主数据行，缺失返回 null。 */
        Map<String, Object> card(Object cardDatasId);

        /** {@code CardSeriesId} 对应的 CardSeries 主数据行，缺失返回 null。 */
        Map<String, Object> cardSeries(Object cardSeriesId);
    }

    /** 当前可用的目录；移植 master 导入之前恒为 null。 */
    public static Catalog catalog() {
        return null;
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /** 该角色当前的 {@code MemberFanLevel}（账号既没有行也没有该成员卡时为 0）。 */
    public static int level(PlayerContext player, Object character) {
        Map<String, Object> row = memberRow(player, character);
        if (row != null) {
            return J.intOr(row.get("MemberFanlevel"), 0);
        }
        int best = 0;
        for (Map<String, Object> card : player.rows("Cards")) {
            if (sameId(card.get("CharacterId"), character)) {
                best = Math.max(best, J.intOr(card.get("MemberFanLevel"), 0));
            }
        }
        return best;
    }

    private static Map<String, Object> memberRow(PlayerContext player, Object character) {
        for (Map<String, Object> row : player.rows("MemberFanLevels")) {
            if (sameId(row.get("CharactersId"), character)) {
                return row;
            }
        }
        return null;
    }

    private static boolean sameId(Object left, Object right) {
        if (left == null || right == null) {
            return false;
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    /**
     * 粉丝等级字段的副本：保存失败时账号不受影响。
     *
     * <p>{@code character} 非空且账号里没有该成员的行时，用卡片上的等级补一行（对应
     * Python 版 {@code fanlevel.draft}）。
     */
    public static Map<String, Object> draft(PlayerContext player, Object character) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("FanLevelDetails", deepCopy(player.raw("FanLevelDetails")));
        out.put("MemberFanLevels", deepCopy(player.raw("MemberFanLevels")));
        out.put("FanLevels", deepCopy(player.raw("FanLevels")));
        out.put("FanLevel", player.entity().fanLevel);
        if (character != null && memberRow(player, character) == null) {
            int known = level(player, character);
            if (known > 0) {
                List<Map<String, Object>> levels = rowsOf(out.get("MemberFanLevels"));
                levels.add(J.map("CharactersId", character, "MemberFanlevel", known));
                out.put("MemberFanLevels", levels);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /**
     * 给某角色记 {@code points} 点，返回 {@code [调整前等级, 调整后等级]}。
     *
     * <p>既可作用在完整账号上，也可作用在 {@link #draft} 的副本上。
     */
    public static int[] addPoints(PlayerContext player, Catalog catalog, Object character,
                                  long points, int earnType, String message, long seasonId) {
        int before = level(player, character);
        Map<String, Object> detail = detail(player, catalog, character);
        if (points > 0) {
            long experience = J.longOr(detail.get("MemberFanExperience"), 0L) + points;
            detail.put("MemberFanExperience", experience);
            Map<String, Object> progress = null;
            for (Map<String, Object> row : rowsOf(detail.get("EarnProgressList"))) {
                if (J.intOr(row.get("EarnType"), -1) == earnType) {
                    progress = row;
                    break;
                }
            }
            if (progress == null) {
                progress = J.map("EarnType", earnType, "TotalPoint", 0, "SeasonPointHistoryList",
                        new ArrayList<>());
                List<Map<String, Object>> list = rowsOf(detail.get("EarnProgressList"));
                list.add(progress);
                detail.put("EarnProgressList", list);
            }
            progress.put("TotalPoint", J.longOr(progress.get("TotalPoint"), 0L) + points);

            List<Map<String, Object>> seasons = rowsOf(progress.get("SeasonPointHistoryList"));
            Map<String, Object> season = null;
            for (Map<String, Object> row : seasons) {
                if (J.longOr(row.get("SeasonId"), 0L) == seasonId) {
                    season = row;
                    break;
                }
            }
            if (season == null) {
                season = J.map("SeasonId", seasonId, "PointHistoryList", new ArrayList<>());
                seasons.add(season);
            }
            List<Map<String, Object>> history = rowsOf(season.get("PointHistoryList"));
            history.add(J.map("Date", Stamina.formatTime(java.time.Instant.now()),
                    "Message", message, "Point", points));
            season.put("PointHistoryList", history);
            progress.put("SeasonPointHistoryList", seasons);
        }
        if (catalog != null) {
            long experience = J.longOr(detail.get("MemberFanExperience"), 0L);
            detail.put("MemberFanLevel",
                    Math.max(J.intOr(detail.get("MemberFanLevel"), 1),
                            catalog.memberFanLevel(experience)));
        }
        int after = J.intOr(detail.get("MemberFanLevel"), 0);
        mirror(player, character, after);
        return new int[] {before, after};
    }

    /** 账号里每张卡的 {@code MemberFanLevel} 按成员等级刷新（返回同一批卡，可能是新对象）。 */
    public static List<Map<String, Object>> applyToCards(PlayerContext player,
                                                        List<Map<String, Object>> cards) {
        Map<String, Integer> levels = new LinkedHashMap<>();
        for (Map<String, Object> row : player.rows("MemberFanLevels")) {
            levels.put(String.valueOf(row.get("CharactersId")),
                    J.intOr(row.get("MemberFanlevel"), 0));
        }
        List<Map<String, Object>> out = new ArrayList<>(cards.size());
        for (Map<String, Object> card : cards) {
            Integer known = levels.get(String.valueOf(card.get("CharacterId")));
            // Python 是 known != card.get('MemberFanLevel')：字段缺失（None）或不是数字时
            // 都算「不一样」，所以等级 0 也要写进去。用 instanceof Number 而不是容错解析，
            // 是为了让字符串 "1" 与整数 1 依旧算不同（Python 的 1 != '1' 为真）。
            Object current = card.get("MemberFanLevel");
            boolean same = current instanceof Number number && number.doubleValue() == known;
            if (known != null && !same) {
                Map<String, Object> copy = new LinkedHashMap<>(card);
                copy.put("MemberFanLevel", known);
                out.add(copy);
            } else {
                out.add(card);
            }
        }
        return out;
    }

    /**
     * 新卡入账：确保成员行存在，并记 {@code ObtainFanLvPt}；卡片拿到算出来的
     * {@code MemberFanLevel}。对应 Python 版 {@code fanlevel.card_obtained}。
     */
    public static Map<String, Object> cardObtained(PlayerContext player, Catalog catalog,
                                                   Map<String, Object> card) {
        Object character = card.get("CharacterId");
        Map<String, Object> series = seriesRow(catalog, card);
        long obtain = series == null ? 0L : J.longOr(series.get("ObtainFanLvPt"), 0L);
        addPoints(player, catalog, character, obtain, EARN_NEW_CARD,
                J.str(card.get("CardName")) == null ? "" : J.str(card.get("CardName")),
                J.longOr(player.raw("SeasonId"), 0L));
        card.put("MemberFanLevel", level(player, character));
        List<Map<String, Object>> cards = applyToCards(player, player.rows("Cards"));
        player.put("Cards", new ArrayList<>(cards));
        return card;
    }

    /** 用当前 store 打开的目录执行 {@link #cardObtained}（对应 {@code fanlevel.obtained}）。 */
    public static Map<String, Object> obtained(PlayerContext player, Map<String, Object> card) {
        return cardObtained(player, catalog(), card);
    }

    private static Map<String, Object> seriesRow(Catalog catalog, Map<String, Object> card) {
        if (catalog == null) {
            return null;
        }
        Map<String, Object> master = catalog.card(card.get("CardDatasId"));
        return master == null ? null : catalog.cardSeries(master.get("CardSeriesId"));
    }

    public static long evolutionPoints(Map<String, Object> series, Object evolveTimes) {
        if (series == null) {
            return 0L;
        }
        return J.longOr(series.get("Evolution" + J.intOr(evolveTimes, 0) + "FanLvPt"), 0L);
    }

    /** 达到 {@code times} 里每一个界限突破次数累计得到的点数。 */
    public static long limitBreakPoints(Map<String, Object> series, List<Integer> times) {
        if (series == null) {
            return 0L;
        }
        long total = 0L;
        for (Integer time : times) {
            total += J.longOr(series.get("LimitBreak" + time + "FanLvPt"), 0L);
        }
        return total;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static Map<String, Object> detail(PlayerContext player, Catalog catalog,
                                              Object character) {
        List<Map<String, Object>> rows = player.rows("FanLevelDetails");
        for (Map<String, Object> row : rows) {
            if (sameId(row.get("CharacterId"), character)) {
                return row;
            }
        }
        // 从「早于经验记录」的等级起步，这样等级永远不会掉。
        int known = Math.max(level(player, character), 1);
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("CharacterId", character);
        created.put("DSeasonFanLevel", 0);
        created.put("DSeasonFanExperience", 0);
        created.put("MemberFanLevel", known);
        created.put("MemberFanExperience", catalog == null ? 0L : catalog.memberFanExperience(known));
        List<Map<String, Object>> progress = new ArrayList<>();
        for (Integer kind : EARN_TYPES) {
            progress.add(J.map("EarnType", kind, "TotalPoint", 0, "SeasonPointHistoryList",
                    new ArrayList<>()));
        }
        created.put("EarnProgressList", progress);
        rows.add(created);
        return created;
    }

    private static void mirror(PlayerContext player, Object character, int newLevel) {
        List<Map<String, Object>> levels = player.rows("MemberFanLevels");
        Map<String, Object> row = null;
        for (Map<String, Object> candidate : levels) {
            if (sameId(candidate.get("CharactersId"), character)) {
                row = candidate;
                break;
            }
        }
        if (row == null) {
            row = J.map("CharactersId", character, "MemberFanlevel", newLevel);
            levels.add(row);
        } else {
            row.put("MemberFanlevel", newLevel);
        }
        player.put("MemberFanLevels", new ArrayList<>(levels));

        List<Map<String, Object>> infos = player.rows("FanLevels");
        Map<String, Object> info = null;
        for (Map<String, Object> candidate : infos) {
            if (sameId(candidate.get("CharacterId"), character)) {
                info = candidate;
                break;
            }
        }
        if (info == null) {
            info = J.map("CharacterId", character, "DSeasonFanLevel", 0, "MemberFanLevel", newLevel);
            infos.add(info);
        } else {
            info.put("MemberFanLevel", newLevel);
        }
        player.put("FanLevels", new ArrayList<>(infos));

        long total = 0L;
        for (Map<String, Object> candidate : levels) {
            total += J.longOr(candidate.get("MemberFanlevel"), 0L);
        }
        player.entity().fanLevel = total;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(Object value) {
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return new ArrayList<>();
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), deepCopy(item)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        return value;
    }
}
