package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import com.linklike.server.service.PlayerContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 已注册本地账号的一次性初始发放 —— 对应 Python 版 {@code wire_initial.py}。
 *
 * <p>空账号进不了两个 Live 模式：
 * <ul>
 *   <li>School Idol Show（{@code /v1/rhythm_game/home}）：客户端
 *       {@code UserDataRepository.CreateUserDataIfNeededAsync} 会读 {@code allOwnCards[0]}
 *       做 {@code check_style_level_up}，所以 {@code card_data_list} 至少要有一张卡，
 *       节奏卡组与成员粉丝等级列表也要同时有值；
 *   <li>School Idol Stage（{@code /v1/out_quest_live/get_quest_top}）：入口按钮跟随
 *       {@code *_live_status} 标志（{@code LiveStatusFlagType}：0 不可进入、1 可进入、
 *       2 维护中）。
 * </ul>
 *
 * <p>主数据卡 ID 来自客户端包内的 {@code getCardList} fixture（
 * {@link WireCatalog#seedInitialCards} -> {@code ref_initial_cards}）；实例 ID 在这里生成。
 * Live 状态与日常券数量沿用上游 5.1.0 抓到的开放账号；体力 100/100 是官方新号的
 * {@code CommonApiCache} 响应头值；Stage 卡组按 5.1.0 的 {@code DeckMemberPositions} /
 * {@code Generations} 主数据（{@code EditOnlyDisplay==0}）生成。即使
 * {@code InitialGrantDone} 已经置位，下次登录也会把缺的部分补上。
 */
public final class WireInitial {

    private WireInitial() {
    }

    /** 上游 {@code /v1/rhythm_game/home} 抓包里 {@code deck_card_list} 的 {@code slot_no} 是 1..6。 */
    public static final int RHYTHM_DECK_SLOTS = 6;

    /** 上游 {@code /v1/out_quest_live/daily/get_stage_select} 抓包的 {@code user_daily_ticket_info} num/max。 */
    public static final int DAILY_TICKETS = 3;

    /** 官方新号响应头里的 {@code user_stamina}（{@code capture/20260909-120518-c0a2f0.json}）。 */
    public static final int STAMINA = 100;

    /** {@code LiveStatusFlagType.LiveStatusFlagTypeAvailable}。 */
    public static final int LIVE_STATUS_AVAILABLE = 1;

    /** 上游 {@code get_quest_top} 抓包里报开放（1）的入口；Grade Challenge、Grand Prix 与 Raid Event 当时是 0，这里不动。 */
    public static final List<String> OPEN_LIVE_STATUS_KEYS = List.of(
            "StandardLiveStatus", "DailyLiveStatus", "MusicLiveStatus",
            "GradeLiveStatus", "DreamLiveStatus");

    // ------------------------------------------------------------------
    // 发放
    // ------------------------------------------------------------------

    /**
     * 对已注册账号执行（或补齐）核实过的初始发放；账号有变化时返回 true（调用方负责落库）。
     *
     * <p>对应 Python 版 {@code wire_initial.grant(player, db)}。
     */
    public static boolean grant(PlayerContext player, WireCatalog catalog) {
        if (!player.entity().registrationComplete) {
            return false;
        }
        boolean changed = false;

        List<Map<String, Object>> cards = player.rows("Cards");
        if (cards.isEmpty()) {
            List<Map<String, Object>> seeded = initialCards(catalog);
            if (!seeded.isEmpty()) {
                cards = seeded;
                player.put("Cards", cards);
                changed = true;
                WireFanLevel.Catalog fanCatalog = WireFanLevel.catalog();
                for (Map<String, Object> card : cards) {
                    WireFanLevel.cardObtained(player, fanCatalog, card);
                }
                cards = player.rows("Cards");
            }
        }
        if (!cards.isEmpty() && !player.truthy("RhythmGameDecks")) {
            player.put("RhythmGameDecks", rhythmDeck(cards));
            changed = true;
        }
        if (!cards.isEmpty() && !player.truthy("Decks")) {
            List<Map<String, Object>> decks = stageDecks(cards, catalog);
            if (!decks.isEmpty()) {
                player.put("Decks", decks);
                changed = true;
            }
        }
        if (player.entity().staminaMax == 0) {
            player.entity().staminaMax = STAMINA;
            player.entity().staminaNow = STAMINA;
            changed = true;
        }
        for (String key : OPEN_LIVE_STATUS_KEYS) {
            if (!player.truthy(key)) {
                player.put(key, LIVE_STATUS_AVAILABLE);
                changed = true;
            }
        }
        if (!player.truthy("DailyTicketMax")) {
            player.put("DailyTicketMax", DAILY_TICKETS);
            player.put("DailyTicketNum", DAILY_TICKETS);
            changed = true;
        }
        if (changed || !player.entity().initialGrantDone) {
            player.entity().initialGrantDone = true;
            return true;
        }
        return false;
    }

    /** 由打包 fixture 生成的、属于本账号的新卡实例。 */
    public static List<Map<String, Object>> initialCards(WireCatalog catalog) {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (Map<String, Object> row : catalog.initialCards()) {
            Map<String, Object> card = new LinkedHashMap<>(row);
            card.put("DCardDatasId", UUID.randomUUID().toString());
            cards.add(card);
        }
        return cards;
    }

    /** 一个 6 槽的节奏游戏卡组，对应 Python 版 {@code RhythmGameDecks} 的初始值。 */
    private static List<Map<String, Object>> rhythmDeck(List<Map<String, Object>> cards) {
        List<Map<String, Object>> slots = new ArrayList<>();
        int slot = 1;
        for (Map<String, Object> card : cards) {
            if (slot > RHYTHM_DECK_SLOTS) {
                break;
            }
            slots.add(J.map("RhythmGameDeckCardsId", UUID.randomUUID().toString(),
                    "DCardDatasId", card.get("DCardDatasId"), "SlotNo", slot));
            slot++;
        }
        Map<String, Object> deck = J.map("RhythmGameDeckId", UUID.randomUUID().toString(),
                "Name", "", "DeckNo", 1, "DeckCardList", slots);
        return new ArrayList<>(List.of(deck));
    }

    /**
     * 每个 Live 世代一个 Stage 卡组，槽位来自客户端主数据。
     *
     * <p>对应 Python 版 {@code wire_initial.stage_decks}：按角色取第一张卡填槽，第一个
     * 填上的卡作为 Ace；没有对应角色的槽位留空串。
     */
    public static List<Map<String, Object>> stageDecks(List<Map<String, Object>> cards,
                                                       WireCatalog catalog) {
        Map<String, Map<String, Object>> byCharacter = new LinkedHashMap<>();
        for (Map<String, Object> card : cards) {
            byCharacter.putIfAbsent(String.valueOf(card.get("CharacterId")), card);
        }
        List<Map<String, Object>> decks = new ArrayList<>();
        for (Map<String, Object> generation : catalog.stageDeckSlots()) {
            String ace = "";
            List<Map<String, Object>> slots = new ArrayList<>();
            for (Map<String, Object> slot : J.rowsOr(generation.get("slots"))) {
                Map<String, Object> card = byCharacter.get(String.valueOf(slot.get("character_id")));
                String ident = card == null ? "" : J.str(card.get("DCardDatasId"));
                if (ident != null && !ident.isEmpty() && ace.isEmpty()) {
                    ace = ident;
                }
                slots.add(J.map(
                        "DDeckCardsId", UUID.randomUUID().toString(),
                        "DCardDatasId", ident == null ? "" : ident,
                        "SlotNo", slot.get("slot_no"),
                        "GradeCardBonusValue", 0,
                        "GradeCardBonusLimitUp", 0,
                        "SideStyle1DCardDatasId", "",
                        "SideStyle1GradeCardBonusValue", 0,
                        "SideStyle1GradeCardBonusLimitUp", 0,
                        "SideStyle2DCardDatasId", "",
                        "SideStyle2GradeCardBonusValue", 0,
                        "SideStyle2GradeCardBonusLimitUp", 0));
            }
            decks.add(J.map(
                    "DDeckDatasId", UUID.randomUUID().toString(),
                    "DeckName", "",
                    "DeckNo", 1,
                    "GenerationsId", generation.get("generations_id"),
                    "AceCard", ace,
                    "DeckCardsList", slots,
                    "IsChangeDeckCards", false,
                    "GradeBonusValue", 0));
        }
        return decks;
    }
}
