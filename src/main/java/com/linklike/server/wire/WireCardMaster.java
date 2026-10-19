package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import com.linklike.server.resource.ClientDefaults;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 卡片成长用的主数据查询，对应 Python {@code admin/catalog.py} 里被 {@code wire_cards}
 * 用到的那几个方法。
 *
 * <p>数据来自随仓库分发的 {@code card_master_5.1.0.json}（由
 * {@code tools/export_card_master.py} 从 {@code data/master_5.1.0.db} 生成）。成长成本
 * 在导出时就已经整理成 {@code [[item_id, num], ...]}，与 Python 的返回形状一致。
 *
 * <p>缺行一律返回 {@code null}：调用方据此回 501（「这一步的成本没有随仓库打包」），
 * 而不是当成零成本白送。
 */
@Component
public class WireCardMaster {

    private final ClientDefaults defaults;
    private volatile Map<String, Object> master;

    public WireCardMaster(ClientDefaults defaults) {
        this.defaults = defaults;
    }

    private Map<String, Object> master() {
        Map<String, Object> cached = master;
        if (cached == null) {
            cached = defaults.object("card_master_5.1.0").orElseGet(LinkedHashMap::new);
            master = cached;
        }
        return cached;
    }

    private Map<String, Object> table(String name) {
        return J.nodeOrEmpty(master().get(name));
    }

    /** {@code CardDatas} 行；缺失返回 null。 */
    public Map<String, Object> card(Object cardDatasId) {
        if (cardDatasId == null) {
            return null;
        }
        return J.nodeOr(table("cards").get(String.valueOf(cardDatasId)));
    }

    /** {@code CardSeries} 行；缺失返回 null。 */
    public Map<String, Object> cardSeries(Object cardSeriesId) {
        if (cardSeriesId == null) {
            return null;
        }
        return J.nodeOr(table("series").get(String.valueOf(cardSeriesId)));
    }

    /** {@code CardRarity} 行；缺失返回 null。 */
    public Map<String, Object> rarity(Object rarityId) {
        return rarityId == null ? null : J.nodeOr(table("rarities").get(String.valueOf(rarityId)));
    }

    /** 同一系列、同一「平行卡」形态下，下一进化阶段的卡行；没有则 null。 */
    public Map<String, Object> nextStage(Map<String, Object> current) {
        if (current == null) {
            return null;
        }
        int parallel = J.intOr(current.get("IsParallelCard"), 0);
        int evolve = J.intOr(current.get("EvolveTimes"), 0);
        for (Map<String, Object> row : seriesCards(current.get("CardSeriesId"))) {
            if (J.intOr(row.get("IsParallelCard"), 0) == parallel
                    && J.intOr(row.get("EvolveTimes"), 0) == evolve + 1) {
                return row;
            }
        }
        return null;
    }

    /** 同一卡系列的全部卡行（按主数据顺序）。 */
    public List<Map<String, Object>> seriesCards(Object cardSeriesId) {
        String series = String.valueOf(cardSeriesId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : table("cards").values().stream()
                .map(J::nodeOr).toList()) {
            if (row != null && series.equals(String.valueOf(row.get("CardSeriesId")))) {
                rows.add(row);
            }
        }
        return rows;
    }

    /** {@code SkillData.MaxSkillLevel}（{@code CardSkillSeriesId}）。 */
    public int skillCap(Object cardSkillSeriesId) {
        return J.intOr(table("skill_caps").get(String.valueOf(cardSkillSeriesId)), 0);
    }

    /** {@code MaxLimitBreakTimes}（{@code CardSeriesId}）。 */
    public int limitBreakCap(Object cardSeriesId) {
        return J.intOr(table("limit_break_caps").get(String.valueOf(cardSeriesId)), 0);
    }

    /** {@code RhythmGameSkillSeriesId} 的技能上限。 */
    public int rhythmSkillCap(Object skillSeriesId) {
        return J.intOr(growth("rhythm_skill_caps").get(String.valueOf(skillSeriesId)), 0);
    }

    /** 进化到 {@code targetCardId} 需要的素材；未打包返回 null。 */
    public List<List<Long>> evolutionCost(Object targetCardId) {
        return costs("evolution_materials", String.valueOf(targetCardId));
    }

    /** 第 {@code times} 次界限突破需要的素材；未打包返回 null。 */
    public List<List<Long>> limitBreakCost(Object cardSeriesId, int times) {
        return costs("limit_break_materials", cardSeriesId + "|" + times);
    }

    /** 技能从 {@code level} 升一级需要的素材；未打包返回 null。 */
    public List<List<Long>> skillCost(Object cardSeriesId, int skillType, int level) {
        return costs("skill_level_up_materials",
                cardSeriesId + "|" + skillType + "|" + level);
    }

    /** 节奏游戏技能从 {@code level} 升一级需要的素材；未打包返回 null。 */
    public List<List<Long>> rhythmSkillCost(Object itemSeriesId, int level) {
        return costs("rhythm_skill_costs", itemSeriesId + "|" + level);
    }

    /** 导出的 {@code [[item_id, num], ...]} 行；缺键返回 null，空成本行返回空表。 */
    private List<List<Long>> costs(String table, String key) {
        Object value = growth(table).get(key);
        if (value == null) {
            return null;
        }
        List<List<Long>> out = new ArrayList<>();
        for (Object entry : J.listOrEmpty(value)) {
            List<Object> pair = J.listOr(entry);
            if (pair != null && pair.size() >= 2) {
                out.add(List.of(J.longOr(pair.get(0), 0L), J.longOr(pair.get(1), 0L)));
            }
        }
        return out;
    }

    /** {@code MemberFanLevels} 主数据行；没打包时是空表（等级门槛一律回落到 1）。 */
    public List<Map<String, Object>> fanLevels() {
        return J.rowsOr(master().get("member_fan_levels"));
    }

    /** 风格等级累计经验表 {@code {ExperienceType: {level: cumulative}}}。 */
    public Map<String, Object> cardLevels() {
        return table("card_levels");
    }

    private Map<String, Object> growth(String name) {
        return J.nodeOrEmpty(table("growth").get(name));
    }
}
