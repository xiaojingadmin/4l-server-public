package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireModels;
import com.linklike.server.service.PlayerContext;
import com.linklike.server.service.RefCatalogStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 新卡实例生成 —— 对应 Python {@code wire_catalog.mint_card_instance} 与
 * {@code admin/grants.py} 的 {@code mint_card}。
 *
 * <p>两条路径与 Python 一致：客户端包内 {@code getCardList} fixture 有的卡（每位角色的初始服装）
 * 直接复用 fixture 的属性、只换一个实例 ID；其余走 5.1.0 主数据 {@code CardDatas} 现场拼一张
 * 1 级、0 界限突破的新卡。主数据来自随仓库分发的
 * {@code client_defaults/card_master_5.1.0.json}（由
 * {@code tools/export_card_master.py} 从 {@code data/cards.db} + {@code data/master_5.1.0.db}
 * 导出，等价于 Python 侧 {@code admin/catalog.py} 建的 {@code master_*} 表）。
 *
 * <p>主数据里没有这张卡时抛 {@link IllegalArgumentException}，调用方按
 * 「本地没有这张卡」处理（Python 抛的是 {@code ValueError}）。
 */
@Component
public class WireCardMint {

    private static final String INITIAL_CARDS = "initial_cards";

    private final RefCatalogStore catalogs;
    private final WireModels models;
    private final WireCardMaster masterData;
    private final WireFanLevel.Catalog fanCatalog;

    public WireCardMint(RefCatalogStore catalogs, WireModels models, WireCardMaster masterData,
                        WireFanLevel.Catalog fanCatalog) {
        this.catalogs = catalogs;
        this.models = models;
        this.masterData = masterData;
        this.fanCatalog = fanCatalog;
    }

    /** 卡主数据（{@code cards} / {@code rarities} / {@code characters} / 上限表）。 */
    public Map<String, Object> master() {
        return masterData.master();
    }

    /** 主数据 {@code CardDatas} 行；缺失返回 null。 */
    public Map<String, Object> card(long cardDatasId) {
        return masterData.card(cardDatasId);
    }

    /**
     * 现场生成一张新卡实例（{@code UserCardData} 属性名）。
     *
     * @throws IllegalArgumentException 主数据里没有这张卡
     */
    public Map<String, Object> mint(long cardDatasId) {
        Map<String, Object> fixture = catalogs.get(INITIAL_CARDS, cardDatasId);
        if (fixture != null) {
            Map<String, Object> card = models.toProperties("UserCardData", fixture);
            card.put("DCardDatasId", UUID.randomUUID().toString());
            card.put("CardDatasId", cardDatasId);
            return card;
        }
        Map<String, Object> row = card(cardDatasId);
        if (row == null) {
            throw new IllegalArgumentException("Unknown card " + cardDatasId);
        }
        long series = J.longOr(row.get("CardSeriesId"), 0L);
        Map<String, Object> rarity = J.nodeOr(
                J.nodeOrEmpty(master().get("rarities")).get(String.valueOf(row.get("Rarity"))));
        Map<String, Object> character = J.nodeOr(
                J.nodeOrEmpty(master().get("characters")).get(String.valueOf(row.get("CharactersId"))));

        List<Map<String, Object>> skills = new ArrayList<>();
        // 技能三种类型：1=SpecialAppeal、2=Skill、3=Attribute。第三项的 series 就是
        // AttributeId，与 Python admin/grants.py 的 mint_card 一致，不是笔误。
        Object[][] skillSeries = {
                {1, row.get("SpecialAppealSeriesId")},
                {2, row.get("SkillSeriesId")},
                {3, row.get("AttributeId")},
        };
        for (Object[] spec : skillSeries) {
            long skillSeriesId = J.longOr(spec[1], 0L);
            if (skillSeriesId == 0L) {
                continue;
            }
            skills.add(J.map("SkillType", spec[0], "CardSkillSeriesId", spec[1],
                    "SkillLevel", 1, "MaxSkillLevel", skillCap(skillSeriesId)));
        }

        List<Map<String, Object>> rhythm = new ArrayList<>();
        Object[][] rhythmSeries = {
                {1, row.get("CenterSkillSeriesId")},
                {2, row.get("RhythmGameSkillSeriesId")},
                {3, row.get("CenterAttributeSeriesId")},
        };
        for (Object[] spec : rhythmSeries) {
            if (J.longOr(spec[1], 0L) != 0L) {
                rhythm.add(J.map("RhythmGameSkillType", spec[0], "SkillLevel", 1));
            }
        }

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("DCardDatasId", UUID.randomUUID().toString());
        card.put("CardDatasId", row.get("Id"));
        card.put("CardName", row.get("Name"));
        card.put("StyleLevel", 1);
        card.put("MaxStyleLevel", maxStyle(rarity,
                J.intOr(row.get("EvolveTimes"), 0), J.intOr(row.get("CardLevelLimitAddition"), 0)));
        card.put("LimitBreakTimes", 0);
        card.put("MaxLimitBreakTimes", limitBreakCap(series));
        card.put("CardParameters", J.map(
                "Smile", row.get("InitialSmile"),
                "Pure", row.get("InitialPure"),
                "Cool", row.get("InitialCool"),
                "Mental", row.get("InitialMental"),
                "BeatPoint", row.get("BeatPoint")));
        card.put("SkillList", skills);
        card.put("CharacterId", row.get("CharactersId"));
        card.put("GenerationsId", J.intOr(character.get("GenerationsId"), 0));
        card.put("SeriesType", J.intOr(character.get("SeriesType"), 0));
        card.put("CardSortOrder", 0);
        card.put("CharacterBonus", J.map(
                "CharacterId", row.get("CharactersId"),
                "MusicMasteryBonus", 0, "LoveCorrectionValue", 0,
                "MusicMasteryBonusList", new ArrayList<>(), "SeasonFanLevel", 0));
        card.put("IsEvolvePossible", false);
        card.put("IsEvolveMax", false);
        card.put("MemberFanLevel", 1);
        card.put("IsLimitBreak", false);
        card.put("IsStyleLevelUp", false);
        card.put("RhythmGameSkillList", rhythm);
        return card;
    }

    /** 往账号里放一张新卡并返回它（Python {@code apply_present_contents} 的 {@code item_type==2}）。 */
    public Map<String, Object> grant(PlayerContext player, long cardDatasId) {
        Map<String, Object> card = mint(cardDatasId);
        List<Map<String, Object>> cards = new ArrayList<>(player.rows("Cards"));
        cards.add(card);
        player.put("Cards", cards);
        WireFanLevel.obtained(player, fanCatalog, card);
        return card;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private int skillCap(long series) {
        return J.intOr(J.nodeOrEmpty(master().get("skill_caps")).get(String.valueOf(series)), 1);
    }

    private int limitBreakCap(long series) {
        return J.intOr(J.nodeOrEmpty(master().get("limit_break_caps")).get(String.valueOf(series)), 0);
    }

    /** {@code cardrarities.EvolutionN_MaxLevel}，从当前进化阶往上找第一个非 0 的行。 */
    private static int maxStyle(Map<String, Object> rarity, int evolve, int addition) {
        if (rarity == null || rarity.isEmpty()) {
            return 1;
        }
        for (int step = evolve; step < 5; step++) {
            int value = J.intOr(rarity.get("Evolution" + step + "_MaxLevel"), 0);
            if (value != 0) {
                return value + addition;
            }
        }
        return 1 + addition;
    }
}
