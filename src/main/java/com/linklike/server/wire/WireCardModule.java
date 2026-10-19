package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireCodec;
import com.linklike.server.protocol.WireError;
import com.linklike.server.service.PlayerContext;
import com.linklike.server.service.PlayerStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 卡片成长与展示 —— 对应 Python {@code wire_cards.py}。
 *
 * <p>成长成本全部来自 5.1.0 主数据（{@link WireCardMaster}）：进化按目标卡、界限突破按
 * 「系列 + 次数」、技能按「系列 + 类型 + 当前等级」、节奏游戏技能按「LvUpItemId + 等级」。
 * 素材从账号自己的 {@code Items} 里扣，和卡片改动在同一次落库中完成；成本行没有随仓库
 * 打包时回 501，不会当成零成本免费结算。
 *
 * <p>风格等级消耗 {@code StylePoint}（任务 Live 产出），花费是该卡
 * {@code ExperienceType} 累计经验表里目标等级减当前等级的差值。
 *
 * <p>失败时账号保持原样：Python 在 {@code deepcopy} 出来的草稿上结算，Java 侧先记快照，
 * 落库抛异常就整体还原（{@link #restore}）。
 */
@Component
public class WireCardModule implements WireModule {

    /** Python {@code RHYTHM_SKILL_FIELDS}：类型 -> (系列字段, 升级素材字段)。 */
    private static final Map<Integer, String[]> RHYTHM_SKILL_FIELDS = Map.of(
            1, new String[] {"CenterSkillSeriesId", "CenterSkillLvUpItemId"},
            2, new String[] {"RhythmGameSkillSeriesId", "RhythmGameSkillLvUpItemId"});

    /** 结算会写到的账号键；失败时整体还原。 */
    private static final List<String> SETTLED_KEYS = List.of(
            "Cards", "Items", "beginner_missions", "StylePoint",
            "FanLevelDetails", "MemberFanLevels", "FanLevels", "FanLevel");

    private final WireCardMaster master;
    private final WireShop shop;
    private final WireCodec codec;
    private final WireFanLevel.Catalog fanCatalog;

    public WireCardModule(WireCardMaster master, WireShop shop, WireCodec codec,
                          WireFanLevel.Catalog fanCatalog) {
        this.master = master;
        this.shop = shop;
        this.codec = codec;
        this.fanCatalog = fanCatalog;
    }

    @Override
    public void register(WireRouter router) {
        router.register("/v1/user/card/check_style_level_up", this::checkStyleLevelUp);
        router.register("/v1/user/card/style_level_up", this::styleLevelUp);
        router.register("/v1/user/card/check_skill_level_up", this::checkSkillLevelUp);
        router.register("/v1/user/card/skill_level_up", this::skillLevelUp);
        router.register("/v1/user/card/check_limit_break", this::checkLimitBreak);
        router.register("/v1/user/card/limit_break", this::limitBreak);
        router.register("/v1/user/card/check_evolution", this::checkEvolution);
        router.register("/v1/user/card/evolution", this::evolution);
        router.register("/v1/user/card/rhythm_game_skill_level_up", this::rhythmSkillLevelUp);
        router.register("/v1/user/card/get_detail_rental", this::detailRental);
        router.register("/v1/card/get_display_data", this::getDisplayData);
        router.register("/v1/card/set_display_data", this::setDisplayData);
    }

    // ------------------------------------------------------------------
    // 风格等级
    // ------------------------------------------------------------------

    private Map<String, Object> checkStyleLevelUp(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> card = ownedCard(player, request.body());
        long current = J.longOr(card.get("StyleLevel"), 0L);
        long cap = J.longOr(card.get("MaxStyleLevel"), 0L);
        long have = stylePoints(player);
        boolean possible = false;
        if (current < cap) {
            Long step = styleCost(master.card(card.get("CardDatasId")),
                    current, current + 1);
            possible = step != null && have >= step;
        }
        return codec.encode("CheckStyleLevelUpResponse", J.map(
                "IsPossible", possible, "CurrentStyleLevel", current,
                "SelectableMaxLevel", cap, "StylePoint", have));
    }

    private Map<String, Object> styleLevelUp(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> card = WireServices.copyRow(ownedCard(player, request.body()));
        long level = positive(request.body(), "style_level");
        long current = J.longOr(card.get("StyleLevel"), 0L);
        long cap = J.longOr(card.get("MaxStyleLevel"), 0L);
        if (!(current < level && level <= cap)) {
            throw WireError.badRequest(
                    "style_level must exceed the current level and stay within max_style_level");
        }
        Long cost = styleCost(requireMaster(card), current, level);
        if (cost == null) {
            throw new WireError(501,
                    "Style level cost for this step is not packaged locally; no state was changed");
        }
        long have = stylePoints(player);
        if (have < cost) {
            throw WireError.badRequest(String.format(
                    "Not enough style points (have %d, need %d)", have, cost));
        }
        card.put("StyleLevel", level);
        card.put("IsStyleLevelUp", level < cap);

        Map<String, Object> before = snapshot(player);
        try {
            List<Map<String, Object>> missions = WireShop.copyMissions(player);
            shop.raise(missions, WireShop.BEGINNER_CONDITION_MAX_STYLE_LEVEL, (int) level);
            replace(player, card, null, missions, have - cost, null);
            request.store().save(player);
        } catch (RuntimeException e) {
            restore(player, before);
            throw e;
        }
        return codec.encode("StyleLevelUpResponse", J.map("Result", true));
    }

    /** {@code check_style_level_up} 的 {@code StylePoint} 钱包值。 */
    private static long stylePoints(PlayerContext player) {
        return Math.max(0L, player.longOf("StylePoint", 0L));
    }

    /**
     * 从 {@code current} 升到 {@code target} 需要的 スタイルPt.；卡片的
     * {@code ExperienceType} 或任一端等级没有打包时返回 null。
     */
    private Long styleCost(Map<String, Object> cardMaster, long current, long target) {
        if (cardMaster == null) {
            return null;
        }
        List<Map<String, Object>> rows = J.rowsOr(master.cardLevels()
                .get(String.valueOf(J.intOr(cardMaster.get("ExperienceType"), 0))));
        if (rows.isEmpty()) {
            return null;
        }
        Map<Long, Long> levels = new LinkedHashMap<>();
        levels.put(1L, 0L);
        for (Map<String, Object> row : rows) {
            levels.put(J.longOr(row.get("level"), 0L), J.longOr(row.get("cumulative"), 0L));
        }
        Long from = levels.get(current);
        Long to = levels.get(target);
        if (from == null || to == null) {
            return null;
        }
        return to - from;
    }

    // ------------------------------------------------------------------
    // 技能等级
    // ------------------------------------------------------------------

    private Map<String, Object> checkSkillLevelUp(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> card = ownedCard(player, request.body());
        int skillType = skillType(request.body());
        Map<String, Object> skill = skill(card, skillType);
        long current = skill == null ? 0 : J.longOr(skill.get("SkillLevel"), 0L);
        long cap = skill == null ? 0 : J.longOr(skill.get("MaxSkillLevel"), 0L);
        List<List<Long>> costs = List.of();
        boolean possible = false;
        if (skill != null && current < cap) {
            Map<String, Object> row = requireMaster(card);
            costs = master.skillCost(row.get("CardSeriesId"), skillType, (int) current);
            if (costs != null) {
                costs = merge(costs);
                possible = affordable(player, costs);
            } else {
                costs = List.of();
            }
        }
        return codec.encode("CheckSkillLevelUpResponse", J.map(
                "IsPossible", possible, "CurrentSkillLevel", current,
                "SelectableMaxLevel", cap,
                "UserItemList", userItems(player, costs),
                "RequiredItemList", requiredItems(costs)));
    }

    private Map<String, Object> skillLevelUp(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> card = WireServices.copyRow(ownedCard(player, request.body()));
        int skillType = skillType(request.body());
        long level = positive(request.body(), "skill_level");
        Map<String, Object> skill = skill(card, skillType);
        if (skill == null) {
            throw WireError.notFound("Card has no skill of this type");
        }
        long current = J.longOr(skill.get("SkillLevel"), 0L);
        long cap = J.longOr(skill.get("MaxSkillLevel"), 0L);
        if (!(current < level && level <= cap)) {
            throw WireError.badRequest(
                    "skill_level must exceed the current level and stay within max_skill_level");
        }
        Map<String, Object> row = requireMaster(card);
        List<List<Long>> costs = steps(level, current, lv ->
                master.skillCost(row.get("CardSeriesId"), skillType, lv));
        List<Map<String, Object>> items = debit(player, costs);
        skill.put("SkillLevel", level);

        Map<String, Object> before = snapshot(player);
        try {
            replace(player, card, items, null, null, null);
            request.store().save(player);
        } catch (RuntimeException e) {
            restore(player, before);
            throw e;
        }
        return codec.encode("SkillLevelUpResponse", J.map("Result", true));
    }

    // ------------------------------------------------------------------
    // 界限突破
    // ------------------------------------------------------------------

    private Map<String, Object> checkLimitBreak(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> card = ownedCard(player, request.body());
        int current = J.intOr(card.get("LimitBreakTimes"), 0);
        int cap = J.intOr(card.get("MaxLimitBreakTimes"), 0);
        List<List<Long>> costs = List.of();
        boolean possible = false;
        if (current < cap) {
            Map<String, Object> row = requireMaster(card);
            List<List<Long>> step = master.limitBreakCost(row.get("CardSeriesId"), current + 1);
            if (step != null) {
                costs = merge(step);
                possible = affordable(player, costs);
            }
        }
        return codec.encode("CheckLimitBreakResponse", J.map(
                "IsPossible", possible, "CurrentLimitBreakTimes", current,
                "SelectableLimitBreakTimes", Math.max(cap - current, 0),
                "UserItemList", userItems(player, costs),
                "RequiredItemList", requiredItems(costs)));
    }

    private Map<String, Object> limitBreak(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> card = WireServices.copyRow(ownedCard(player, request.body()));
        // 客户端发的是「突破后的次数」（afterLimitBreak.Value），不是增量。
        int target = (int) positive(request.body(), "limit_break_step");
        int current = J.intOr(card.get("LimitBreakTimes"), 0);
        int cap = J.intOr(card.get("MaxLimitBreakTimes"), 0);
        if (!(current < target && target <= cap)) {
            throw WireError.badRequest("limit_break_step must exceed the current count "
                    + "and stay within max_limit_break_times");
        }
        Map<String, Object> row = requireMaster(card);
        List<List<Long>> costs = steps(target, current + 1L, lv -> master.limitBreakCost(
                J.longOr(row.get("CardSeriesId"), 0L), (int) lv));
        List<Map<String, Object>> items = debit(player, costs);
        card.put("LimitBreakTimes", target);
        card.put("IsLimitBreak", target < cap);

        List<Integer> times = new ArrayList<>();
        for (int time = current + 1; time <= target; time++) {
            times.add(time);
        }
        long points = WireFanLevel.limitBreakPoints(
                master.cardSeries(row.get("CardSeriesId")), times);
        Map<String, Object> fan = fanGain(player, fanCatalog, card, points, WireFanLevel.EARN_LIMIT_BREAK);

        Map<String, Object> before = snapshot(player);
        try {
            replace(player, card, items, null, null, fan);
            request.store().save(player);
        } catch (RuntimeException e) {
            restore(player, before);
            throw e;
        }
        return codec.encode("LimitBreakResponse", J.map("Result", true));
    }

    // ------------------------------------------------------------------
    // 进化
    // ------------------------------------------------------------------

    private Map<String, Object> checkEvolution(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> card = ownedCard(player, request.body());
        Evolution check = evolutionCheck(player, card);
        List<List<Long>> costs = check.costs == null ? List.of() : check.costs;
        return codec.encode("CheckEvolutionResponse", J.map(
                "IsPossible", check.possible, "UserItemList", userItems(player, costs),
                "RequiredItemList", requiredItems(costs),
                "NextMaxStyleLevel", check.nextMaxStyleLevel));
    }

    private Map<String, Object> evolution(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> card = WireServices.copyRow(ownedCard(player, request.body()));
        Evolution check = evolutionCheck(player, card);
        if (check.next == null) {
            throw WireError.badRequest("Card is already at its final evolution");
        }
        if (check.costs == null) {
            throw new WireError(501,
                    "Evolution cost for this stage is not packaged locally; no state was changed");
        }
        long styleLevel = J.longOr(card.get("StyleLevel"), 0L);
        if (styleLevel < J.longOr(card.get("MaxStyleLevel"), 0L)) {
            throw WireError.badRequest("style_level must reach max_style_level before evolution");
        }
        if (WireFanLevel.level(player, card.get("CharacterId")) < check.requiredFanLevel) {
            throw WireError.badRequest(String.format(
                    "Member fan level %d is required for this evolution", check.requiredFanLevel));
        }
        List<Map<String, Object>> items = debit(player, check.costs);
        card.put("CardDatasId", check.next.get("Id"));
        card.put("CardName", check.next.get("Name"));
        card.put("MaxStyleLevel", check.nextMaxStyleLevel);
        card.put("IsStyleLevelUp", styleLevel < check.nextMaxStyleLevel);

        long points = WireFanLevel.evolutionPoints(check.series,
                check.next.get("EvolveTimes"));
        Map<String, Object> fan = fanGain(player, fanCatalog, card, points, WireFanLevel.EARN_EVOLUTION);

        Map<String, Object> before = snapshot(player);
        try {
            replace(player, card, items, null, null, fan);
            Evolution further = evolutionCheck(player, card);
            card.put("IsEvolveMax", further.next == null);
            card.put("IsEvolvePossible", further.possible);

            List<Map<String, Object>> missions = WireShop.copyMissions(player);
            shop.advance(missions, WireShop.BEGINNER_CONDITION_TOTAL_EVOLUTION);
            replace(player, card, null, missions, null, null);
            request.store().save(player);
        } catch (RuntimeException e) {
            restore(player, before);
            throw e;
        }
        return codec.encode("EvolutionResponse", J.map("Result", true));
    }

    /** {@code _evolution_check} 的结果。 */
    private record Evolution(boolean possible, long nextMaxStyleLevel, Map<String, Object> next,
                             List<List<Long>> costs, Map<String, Object> series,
                             long requiredFanLevel) {
    }

    private Evolution evolutionCheck(PlayerContext player, Map<String, Object> card) {
        Map<String, Object> current = master.card(card.get("CardDatasId"));
        Map<String, Object> next = current == null ? null : master.nextStage(current);
        Map<String, Object> series = current == null ? null
                : master.cardSeries(current.get("CardSeriesId"));
        if (next == null) {
            return new Evolution(false, 0, null, null, series, 0);
        }
        long nextMax = maxStyle(master.rarity(next.get("Rarity")),
                J.intOr(next.get("EvolveTimes"), 0), J.intOr(next.get("CardLevelLimitAddition"), 0));
        List<List<Long>> raw = master.evolutionCost(next.get("Id"));
        List<List<Long>> costs = raw == null ? null : merge(raw);
        long required = J.longOr(
                series == null ? null : series.get("Evolution"
                        + J.intOr(next.get("EvolveTimes"), 0) + "RequiredFanLv"), 0L);
        boolean possible = J.longOr(card.get("StyleLevel"), 0L)
                >= J.longOr(card.get("MaxStyleLevel"), 0L)
                && WireFanLevel.level(player, card.get("CharacterId")) >= required
                && costs != null && affordable(player, costs);
        return new Evolution(possible, nextMax, next, costs, series, required);
    }

    /** Python {@code grants._max_style}：从当前进化阶往上找第一个非 0 的上限。 */
    private static long maxStyle(Map<String, Object> rarity, int evolve, int addition) {
        if (rarity == null || rarity.isEmpty()) {
            return 1;
        }
        for (int step = evolve; step < 5; step++) {
            int value = J.intOr(rarity.get("Evolution" + step + "_MaxLevel"), 0);
            if (value != 0) {
                return value + addition;
            }
        }
        return 1L + addition;
    }

    // ------------------------------------------------------------------
    // 节奏游戏技能
    // ------------------------------------------------------------------

    private Map<String, Object> rhythmSkillLevelUp(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> body = request.body();
        Map<String, Object> card = WireServices.copyRow(ownedCard(player, body));
        int kind = WireServices.integer(body, "rhythm_game_skill_type", (Integer) null, 1, 2);
        String[] fields = RHYTHM_SKILL_FIELDS.get(kind);
        long level = positive(body, "skill_level");
        Map<String, Object> skill = null;
        for (Map<String, Object> row : J.rowsOr(card.get("RhythmGameSkillList"))) {
            if (J.intOr(row.get("RhythmGameSkillType"), -1) == kind) {
                skill = row;
                break;
            }
        }
        if (skill == null) {
            throw WireError.notFound("Card has no rhythm-game skill of this type");
        }
        long current = J.longOr(skill.get("SkillLevel"), 0L);
        Map<String, Object> row = requireMaster(card);
        int cap = master.rhythmSkillCap(row.get(fields[0]));
        if (!(current < level && level <= cap)) {
            throw WireError.badRequest(String.format(
                    "skill_level must exceed the current level and stay within %d", cap));
        }
        List<List<Long>> costs = steps(level, current, lv ->
                master.rhythmSkillCost(row.get(fields[1]), (int) lv));
        List<Map<String, Object>> items = debit(player, costs);
        skill.put("SkillLevel", level);

        Map<String, Object> before = snapshot(player);
        try {
            replace(player, card, items, null, null, null);
            request.store().save(player);
        } catch (RuntimeException e) {
            restore(player, before);
            throw e;
        }
        return codec.encode("RhythmGameSkillLevelUpResponse", J.map("Result", true));
    }

    // ------------------------------------------------------------------
    // 租借卡与展示卡
    // ------------------------------------------------------------------

    private Map<String, Object> detailRental(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        long ident = WireServices.integer(request.body(), "rental_card_datas_id", (Long) null,
                1, Long.MAX_VALUE);
        for (Map<String, Object> row : player.rows("RentalCards")) {
            if (J.longOr(row.get("RentalCardDatasId"), Long.MIN_VALUE) == ident) {
                return codec.encode("GetDetailRentalResponse", J.map("RentalCardData", row));
            }
        }
        throw WireError.notFound("Local rental card not found");
    }

    private Map<String, Object> getDisplayData(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        long series = positive(request.body(), "base_card_series_id");
        Map<String, Object> row = displayRow(player, series);
        if (row == null) {
            Map<Long, Object> ids = seriesIds(series);
            Map<String, Object> owned = null;
            for (Map<String, Object> card : player.rows("Cards")) {
                if (ids.containsKey(J.longOr(card.get("CardDatasId"), Long.MIN_VALUE))) {
                    owned = card;
                    break;
                }
            }
            if (owned == null) {
                throw WireError.notFound("No owned card in this series");
            }
            row = J.map("BaseCardSeriesId", series,
                    "DisplayCardId", displayId(owned));
        }
        return codec.encode("CardGetInfoResponse", row);
    }

    private Map<String, Object> setDisplayData(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> body = request.body();
        long series = positive(body, "base_card_series_id");
        long display = positive(body, "display_card_id");
        Map<Long, Object> ids = seriesIds(series);
        List<Map<String, Object>> owned = new ArrayList<>();
        for (Map<String, Object> card : player.rows("Cards")) {
            if (ids.containsKey(J.longOr(card.get("CardDatasId"), Long.MIN_VALUE))) {
                owned.add(card);
            }
        }
        if (owned.isEmpty()) {
            throw WireError.notFound("No owned card in this series");
        }
        if (!ids.containsKey(display)) {
            throw WireError.badRequest("display_card_id is not a card of this series");
        }
        Map<String, Object> before = displayRow(player, series);
        long beforeId = before == null ? displayId(owned.get(0))
                : J.longOr(before.get("DisplayCardId"), 0L);

        List<Map<String, Object>> settings = new ArrayList<>();
        for (Map<String, Object> row : player.rows("card_display_settings")) {
            if (J.longOr(row.get("BaseCardSeriesId"), Long.MIN_VALUE) != series) {
                settings.add(row);
            }
        }
        settings.add(J.map("BaseCardSeriesId", series, "DisplayCardId", display));

        List<Map<String, Object>> updated = new ArrayList<>();
        for (Map<String, Object> card : player.rows("Cards")) {
            if (ids.containsKey(J.longOr(card.get("CardDatasId"), Long.MIN_VALUE))) {
                Map<String, Object> copy = new LinkedHashMap<>(card);
                copy.put("DisplayCardId", display);
                updated.add(copy);
            } else {
                updated.add(card);
            }
        }
        Map<String, Object> snapshot = snapshot(player);
        try {
            player.put("card_display_settings", settings);
            player.put("Cards", updated);
            request.store().save(player);
        } catch (RuntimeException e) {
            restore(player, snapshot);
            throw e;
        }
        return codec.encode("CardSetInfoResponse", J.map("BaseCardSeriesId", series,
                "BeforeDisplayCardId", beforeId, "AfterDisplayCardId", display));
    }

    private static Map<String, Object> displayRow(PlayerContext player, long series) {
        for (Map<String, Object> row : player.rows("card_display_settings")) {
            if (J.longOr(row.get("BaseCardSeriesId"), Long.MIN_VALUE) == series) {
                return row;
            }
        }
        return null;
    }

    private static long displayId(Map<String, Object> card) {
        long value = J.longOr(card.get("DisplayCardId"), 0L);
        return value != 0 ? value : J.longOr(card.get("CardDatasId"), 0L);
    }

    /** 该系列全部卡 ID；系列没有卡行时 404。 */
    private Map<Long, Object> seriesIds(long series) {
        List<Map<String, Object>> rows = master.seriesCards(series);
        if (rows.isEmpty()) {
            throw WireError.notFound("Local card series not found");
        }
        Map<Long, Object> ids = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            ids.put(J.longOr(row.get("Id"), 0L), row.get("Id"));
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // 素材 / 快照
    // ------------------------------------------------------------------

    private static Map<String, Object> ownedCard(PlayerContext player, Map<String, Object> body) {
        Object ident = body.get("d_card_datas_id");
        if (!(ident instanceof String text) || text.isEmpty() || text.length() > 128) {
            throw WireError.badRequest("d_card_datas_id must be a non-empty string");
        }
        for (Map<String, Object> card : player.rowsSnapshot("Cards")) {
            if (text.equals(J.str(card.get("DCardDatasId")))) {
                return card;
            }
        }
        throw WireError.notFound("Local card not found");
    }

    private int skillType(Map<String, Object> body) {
        int value = WireServices.integer(body, "skill_type", (Integer) null, 1, 2);
        return value;
    }

    private static Map<String, Object> skill(Map<String, Object> card, int skillType) {
        for (Map<String, Object> row : J.rowsOr(card.get("SkillList"))) {
            if (J.intOr(row.get("SkillType"), -1) == skillType) {
                return row;
            }
        }
        return null;
    }

    /** Python {@code _level}：正整数。 */
    private static long positive(Map<String, Object> body, String key) {
        return WireServices.integer(body, key, (Long) null, 1, Long.MAX_VALUE);
    }

    /** Python {@code _master}：卡主数据缺行时 501（不拿缺省值结算）。 */
    private Map<String, Object> requireMaster(Map<String, Object> card) {
        Map<String, Object> row = master.card(card.get("CardDatasId"));
        if (row == null) {
            throw new WireError(501,
                    "Card master row is not packaged locally; no state was changed");
        }
        return row;
    }

    /** 合并同一素材的多条成本，保持首次出现的顺序，丢弃 0 数量。 */
    private static List<List<Long>> merge(List<List<Long>> costs) {
        Map<Long, Long> total = new LinkedHashMap<>();
        for (List<Long> cost : costs) {
            total.merge(cost.get(0), cost.get(1), Long::sum);
        }
        List<List<Long>> out = new ArrayList<>();
        total.forEach((item, num) -> {
            if (num > 0) {
                out.add(List.of(item, num));
            }
        });
        return out;
    }

    /** 连续多个等级的合并成本；任一步缺主数据行即 501。 */
    private List<List<Long>> steps(long target, long current,
                                   java.util.function.IntFunction<List<List<Long>>> lookup) {
        List<List<Long>> costs = new ArrayList<>();
        for (long level = current; level < target; level++) {
            List<List<Long>> step = lookup.apply((int) level);
            if (step == null) {
                throw new WireError(501,
                        "Growth cost for this step is not packaged locally; no state was changed");
            }
            costs.addAll(step);
        }
        return merge(costs);
    }

    private static boolean affordable(PlayerContext player, List<List<Long>> costs) {
        for (List<Long> cost : costs) {
            if (WireShop.itemNum(player, cost.get(0)) < cost.get(1)) {
                return false;
            }
        }
        return true;
    }

    /** {@code _debit}：扣素材后的 {@code Items} 副本；不足时 400。 */
    private static List<Map<String, Object>> debit(PlayerContext player, List<List<Long>> costs) {
        List<Map<String, Object>> items = WireServices.rows(player, "Items");
        for (List<Long> cost : costs) {
            long itemId = cost.get(0);
            long need = cost.get(1);
            Map<String, Object> row = null;
            for (Map<String, Object> group : items) {
                for (Map<String, Object> owned : J.rowsOr(group.get("UserItemList"))) {
                    if (J.longOr(owned.get("ItemId"), Long.MIN_VALUE) == itemId) {
                        row = owned;
                        break;
                    }
                }
                if (row != null) {
                    break;
                }
            }
            long have = row == null ? 0 : J.longOr(row.get("ItemNum"), 0L);
            if (have < need) {
                throw WireError.badRequest(String.format(
                        "Not enough materials (item %d: have %d, need %d)", itemId, have, need));
            }
            row.put("ItemNum", have - need);
        }
        return items;
    }

    private static List<Map<String, Object>> userItems(PlayerContext player,
                                                       List<List<Long>> costs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<Long> cost : costs) {
            out.add(J.map("ItemId", cost.get(0), "OwnNum", WireShop.itemNum(player, cost.get(0))));
        }
        return out;
    }

    private static List<Map<String, Object>> requiredItems(List<List<Long>> costs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int start = 0; start < costs.size(); start += 3) {
            Map<String, Object> row = new LinkedHashMap<>();
            int index = 1;
            for (List<Long> cost : costs.subList(start, Math.min(start + 3, costs.size()))) {
                row.put("CostItemId" + index, cost.get(0));
                row.put("CostNum" + index, cost.get(1));
                index++;
            }
            out.add(row);
        }
        return out;
    }

    /** 把一张卡换成新版本，并按需写回素材 / 任务 / 风格点 / 粉丝等级。 */
    private void replace(PlayerContext player, Map<String, Object> card,
                         List<Map<String, Object>> items,
                         List<Map<String, Object>> missions, Long stylePoints,
                         Map<String, Object> fan) {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (Map<String, Object> row : player.rows("Cards")) {
            if (Objects.equals(J.str(row.get("DCardDatasId")), J.str(card.get("DCardDatasId")))) {
                cards.add(card);
            } else {
                cards.add(row);
            }
        }
        if (fan != null) {
            applyFan(player, fan);
            cards = WireFanLevel.applyToCards(player, cards);
        }
        player.put("Cards", cards);
        if (items != null) {
            player.put("Items", items);
        }
        if (missions != null) {
            player.put("beginner_missions", missions);
        }
        if (stylePoints != null) {
            player.put("StylePoint", stylePoints);
        }
    }

    private static void applyFan(PlayerContext player, Map<String, Object> fan) {
        player.put("FanLevelDetails", fan.get("FanLevelDetails"));
        player.put("MemberFanLevels", fan.get("MemberFanLevels"));
        player.put("FanLevels", fan.get("FanLevels"));
        if (fan.get("FanLevel") instanceof Number number) {
            player.entity().fanLevel = number.longValue();
        }
    }

    /** 卡片的粉丝点数草稿（{@code fanlevel.draft} + {@code add_points}）。 */
    private static Map<String, Object> fanGain(PlayerContext player,
                                               WireFanLevel.Catalog fanCatalog,
                                               Map<String, Object> card, long points, int earnType) {
        Map<String, Object> fan = WireFanLevel.draft(player, card.get("CharacterId"));
        WireFanLevel.addPoints(fan, fanCatalog, card.get("CharacterId"), points,
                earnType, J.str(card.get("CardName")) == null ? "" : J.str(card.get("CardName")),
                player.longOf("SeasonId", 0L));
        return fan;
    }

    private static Map<String, Object> snapshot(PlayerContext player) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : SETTLED_KEYS) {
            Object value = player.raw(key);
            out.put(key, value == null ? null : WireServices.deepCopyJson(value));
        }
        out.put("__fanLevel", player.entity().fanLevel);
        return out;
    }

    private static void restore(PlayerContext player, Map<String, Object> snapshot) {
        for (String key : SETTLED_KEYS) {
            Object value = snapshot.get(key);
            if (value == null) {
                player.remove(key);
            } else {
                player.put(key, value);
            }
        }
        if (snapshot.get("__fanLevel") instanceof Long level) {
            player.entity().fanLevel = level;
        }
    }
}
