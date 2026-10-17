package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireCodec;
import com.linklike.server.protocol.WireError;
import com.linklike.server.service.PlayerContext;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code wire_decks.py} 的移植：账号自己的 Stage 卡组。
 *
 * <p>与 Python 版一一对应，包括这些容易改错的点：
 * <ul>
 *   <li>卡组槽位写入用 {@code d_deck_cards_id} 标识；为空时生成新的 UUID，重复即 400；
 *   <li>{@code slot_no} 必须是正整数且在一副卡组内唯一；
 *   <li>{@code deck_name} 允许空串，但必须是不超过 128 字符的字符串；
 *   <li>对局进行中（{@code ActiveQuest} 不是 prep/done 的六段状态）拒绝改卡组，返回 409；
 *   <li>复制卡组时槽位 ID 全部重新生成；
 *   <li>设置王牌卡 / 自动编组会推进新手任务进度。
 * </ul>
 *
 * <p>响应模型名与 Python 版一致（{@code DeckSetPositionResponse} 等），字段编码交给
 * {@link WireCodec}，因此线上 snake_case 字段不需要在这里手写。
 */
@Component
public class WireDeckModule implements WireModule {

    private static final int MAX_SLOTS = 32;
    private static final int MAX_ID_LENGTH = 128;

    private static final Set<String> WRITABLE_DECK_FIELDS = Set.of(
            "deck_name", "deck_no", "ace_card", "deck_cards_list", "is_change_deck_cards");

    private final WireCodec codec;
    private final WireShop shop;

    public WireDeckModule(WireCodec codec, WireShop shop) {
        this.codec = codec;
        this.shop = shop;
    }

    @Override
    public void register(WireRouter router) {
        router.register("/v1/user/deck/get_card_list", this::getCardList);
        router.register("/v1/user/deck/get_list", this::getList);
        router.register("/v1/user/deck/modify_deck_list", this::modifyDeckList);
        router.register("/v1/user/deck/set_delete_deck", this::deleteDeck);
        router.register("/v1/user/deck/set_deck", this::setDeck);
        router.register("/v1/user/deck/set_position", this::slotWrite);
        router.register("/v1/user/deck/set_side_style", this::slotWrite);
        router.register("/v1/user/deck/remove_side_style", this::slotWrite);
        router.register("/v1/user/deck/set_copy_deck", this::slotWrite);
        router.register("/v1/user/deck/notify_auto_deck", this::notifyAutoDeck);
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    private Map<String, Object> getCardList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Object generations = request.body().containsKey("generations_id")
                ? request.arg("generations_id") : 0;
        Object order = request.body().containsKey("member_order")
                ? request.arg("member_order") : 0;
        if (strictInt(generations) == null || strictInt(order) == null) {
            throw WireError.badRequest("Invalid generations_id or member_order");
        }
        return encode("DeckGetCardListResponse", J.map(
                "UserCardDataList", player.rowsSnapshot("Cards"),
                "MainDCardDatasId", ownedId(request.arg("main_d_card_datas_id")),
                "Side1DCardDatasId", ownedId(request.arg("side1_d_card_datas_id")),
                "Side2DCardDatasId", ownedId(request.arg("side2_d_card_datas_id"))));
    }

    private Map<String, Object> getList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        return encode("DeckGetListResponse", J.map(
                "DeckList", player.rowsSnapshot("Decks"),
                "UserCardDataList", player.rowsSnapshot("Cards"),
                "RentalDeckList", player.rowsSnapshot("RentalDecks"),
                "RentalCardDataList", player.rowsSnapshot("RentalCards")));
    }

    // ------------------------------------------------------------------
    // 删除 / 自动编组
    // ------------------------------------------------------------------

    private Map<String, Object> deleteDeck(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = requiredText(request.body(), "d_deck_datas_id");
        if (findDeck(player.rowsSnapshot("Decks"), ident) == null) {
            throw new WireError(404, "Local deck not found");
        }
        if (liveBlocksDecks(player)) {
            throw new WireError(409, "Deck changes are blocked during a local game");
        }

        Object previous = player.raw("Decks");
        List<Map<String, Object>> remaining = new ArrayList<>();
        for (Map<String, Object> deck : player.rowsSnapshot("Decks")) {
            if (!ident.equals(deck.get("DDeckDatasId"))) {
                remaining.add(deck);
            }
        }
        player.put("Decks", remaining);
        try {
            request.store().save(player);
        } catch (RuntimeException e) {
            restore(player, "Decks", previous);
            throw e;
        }
        return encode("DeckSetDeleteResponse", J.map("Result", true));
    }

    private Map<String, Object> notifyAutoDeck(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        shop.advance(player, WireShop.BEGINNER_CONDITION_AUTO_ORGANIZATION);
        request.store().save(player);
        return new LinkedHashMap<>();
    }

    // ------------------------------------------------------------------
    // 槽位写入
    // ------------------------------------------------------------------

    private Map<String, Object> slotWrite(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        if (liveBlocksDecks(player)) {
            throw new WireError(409, "Deck changes are blocked during a local game");
        }
        List<Map<String, Object>> updated = WireServices.rows(player, "Decks");
        Set<String> ownedCards = ownedCardIds(player);
        String path = request.path();

        if (path.endsWith("/set_position")) {
            Map<String, Object> deck = requireDeck(updated,
                    requiredText(request.body(), "d_deck_datas_id"));
            Long source = strictInt(request.arg("position_id"));
            Long target = strictInt(request.arg("slot_no"));
            if (source == null || target == null || source < 1 || target < 1) {
                throw WireError.badRequest("position_id and slot_no must be positive integers");
            }
            Map<Long, Map<String, Object>> slots = new LinkedHashMap<>();
            for (Map<String, Object> slot : deckCards(deck)) {
                slots.put(J.longOr(slot.get("SlotNo"), 0), slot);
            }
            if (!slots.containsKey(source)) {
                throw new WireError(404, "Local deck slot not found");
            }
            if (!source.equals(target)) {
                slots.get(source).put("SlotNo", target);
                if (slots.containsKey(target)) {
                    slots.get(target).put("SlotNo", source);
                }
            }
            deckCards(deck).sort((left, right) -> Long.compare(
                    J.longOr(left.get("SlotNo"), 0), J.longOr(right.get("SlotNo"), 0)));
            commit(request, updated, null);
            return encode("DeckSetPositionResponse", J.map("Result", true));
        }

        if (path.endsWith("/set_side_style") || path.endsWith("/remove_side_style")) {
            SlotRef ref = requireSlot(updated, requiredText(request.body(), "d_deck_cards_id"));
            int side = sideStyleNo(request.arg("side_style_no"));
            String cardId = "";
            if (path.endsWith("/set_side_style")) {
                cardId = requiredText(request.body(), "d_user_cards_id");
                if (!ownedCards.contains(cardId)) {
                    throw WireError.badRequest("Side style card is not owned");
                }
                Object main = ref.slot().get("DCardDatasId");
                Object other = ref.slot().get("SideStyle" + (3 - side) + "DCardDatasId");
                if (cardId.equals(main) || cardId.equals(other)) {
                    throw WireError.badRequest(
                            "Side style card is already placed in this slot");
                }
            }
            ref.slot().put("SideStyle" + side + "DCardDatasId", cardId);
            commit(request, updated, null);
            String model = cardId.isEmpty()
                    ? "DeckRemoveSideStyleResponse" : "DeckSetSideStyleResponse";
            return encode(model, J.map("Result", true));
        }

        // set_copy_deck：把同一世代的一副卡组复制到 deck_no，槽位全部换新 ID。
        Map<String, Object> source = requireDeck(updated,
                requiredText(request.body(), "d_deck_datas_id"));
        Long number = strictInt(request.arg("deck_no"));
        if (number == null || number < 0) {
            throw WireError.badRequest("Invalid deck_no");
        }
        if (number.equals(strictInt(source.get("DeckNo")))) {
            throw WireError.badRequest("deck_no must differ from the source deck");
        }
        Map<String, Object> target = null;
        for (Map<String, Object> row : updated) {
            if (sameInt(row.get("GenerationsId"), source.get("GenerationsId"))
                    && sameInt(row.get("DeckNo"), number)) {
                target = row;
                break;
            }
        }
        if (target == null) {
            target = new LinkedHashMap<>();
            target.put("DDeckDatasId", UUID.randomUUID().toString());
            target.put("DeckName", "");
            target.put("DeckNo", number);
            target.put("GenerationsId", J.intOr(source.get("GenerationsId"), 0));
            target.put("AceCard", "");
            target.put("DeckCardsList", new ArrayList<Map<String, Object>>());
            target.put("IsChangeDeckCards", false);
            target.put("GradeBonusValue", 0);
            updated.add(target);
        }
        target.put("AceCard", source.get("AceCard") == null ? "" : source.get("AceCard"));
        List<Map<String, Object>> copied = new ArrayList<>();
        for (Map<String, Object> slot : deckCards(source)) {
            Map<String, Object> copy = new LinkedHashMap<>(slot);
            copy.put("DDeckCardsId", UUID.randomUUID().toString());
            copied.add(copy);
        }
        target.put("DeckCardsList", copied);
        commit(request, updated, null);
        return encode("DeckSetCopyDeckResponse", J.map("Result", true));
    }

    // ------------------------------------------------------------------
    // 创建 / 批量修改
    // ------------------------------------------------------------------

    private Map<String, Object> setDeck(WireRequest request) {
        request.requirePlayer();
        Map<String, Object> deck = J.nodeOr(request.arg("deck_data"));
        if (deck == null) {
            throw WireError.badRequest("deck_data must be an object");
        }
        Map<String, Object> change = new LinkedHashMap<>();
        for (String key : List.of("d_deck_datas_id", "deck_name", "deck_no", "generations_id",
                "ace_card", "deck_cards_list", "is_change_deck_cards")) {
            if (deck.containsKey(key)) {
                change.put(key, deck.get(key));
            }
        }
        change.putIfAbsent("d_deck_datas_id", "");
        change.putIfAbsent("deck_cards_list", new ArrayList<>());
        modify(request, List.of(change));
        return encode("DeckSetCreateResponse", J.map("Result", true));
    }

    private Map<String, Object> modifyDeckList(WireRequest request) {
        Object raw = request.arg("modify_deck_list");
        List<Object> changes = J.listOr(raw);
        if (changes == null || changes.isEmpty() || changes.size() > 100) {
            throw WireError.badRequest("modify_deck_list must contain 1 to 100 decks");
        }
        return modify(request, changes);
    }

    private Map<String, Object> modify(WireRequest request, List<Object> changes) {
        PlayerContext player = request.requirePlayer();
        if (liveBlocksDecks(player)) {
            throw new WireError(409, "Deck changes are blocked during a local game");
        }

        List<Map<String, Object>> updated = WireServices.rows(player, "Decks");
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> deck : updated) {
            byId.put(String.valueOf(deck.get("DDeckDatasId")), deck);
        }
        Set<String> ownedCards = ownedCardIds(player);

        boolean missionsPresent = player.has("beginner_missions");
        List<Map<String, Object>> missions = WireShop.copyMissions(player);
        Set<String> seen = new HashSet<>();

        for (Object rawChange : changes) {
            Map<String, Object> change = J.nodeOr(rawChange);
            if (change == null) {
                throw WireError.badRequest("Each deck change must be an object");
            }
            Object rawIdent = change.get("d_deck_datas_id");
            if (!(rawIdent instanceof String ident) || ident.length() > MAX_ID_LENGTH) {
                throw WireError.badRequest("Deck identifiers must be strings");
            }
            Map<String, Object> deck;
            if (!ident.isEmpty()) {
                if (seen.contains(ident)) {
                    throw WireError.badRequest("Local deck not found or duplicate");
                }
                deck = byId.get(ident);
                if (deck == null) {
                    throw new WireError(404, "Local deck not found or duplicate");
                }
            } else {
                Object rawGeneration = change.containsKey("generations_id")
                        ? change.get("generations_id") : 0;
                Object rawNumber = change.containsKey("deck_no") ? change.get("deck_no") : 1;
                Long generation = strictInt(rawGeneration);
                Long number = strictInt(rawNumber);
                if (generation == null || number == null) {
                    throw WireError.badRequest("Invalid generations_id or deck_no");
                }
                deck = null;
                for (Map<String, Object> row : updated) {
                    if (sameInt(row.get("GenerationsId"), generation)
                            && sameInt(row.get("DeckNo"), number)) {
                        deck = row;
                        break;
                    }
                }
                if (deck == null) {
                    ident = UUID.randomUUID().toString();
                    deck = new LinkedHashMap<>();
                    deck.put("DDeckDatasId", ident);
                    deck.put("DeckName", "");
                    deck.put("DeckNo", number);
                    deck.put("GenerationsId", generation);
                    deck.put("AceCard", "");
                    deck.put("DeckCardsList", new ArrayList<Map<String, Object>>());
                    deck.put("IsChangeDeckCards", false);
                    deck.put("GradeBonusValue", 0);
                    updated.add(deck);
                    byId.put(ident, deck);
                } else {
                    ident = String.valueOf(deck.get("DDeckDatasId"));
                    if (seen.contains(ident)) {
                        throw WireError.badRequest("Deck identifiers must be unique");
                    }
                }
            }
            seen.add(ident);

            Map<String, Object> wire = codec.encode("Deck", deck);
            for (String key : change.keySet()) {
                if (!wire.containsKey(key)) {
                    throw WireError.badRequest("Unknown or unavailable deck field");
                }
            }
            for (Map.Entry<String, Object> entry : change.entrySet()) {
                String key = entry.getKey();
                if ("d_deck_datas_id".equals(key) || WRITABLE_DECK_FIELDS.contains(key)) {
                    continue;
                }
                if (!sameJsonType(entry.getValue(), wire.get(key))
                        || !jsonEquals(entry.getValue(), wire.get(key))) {
                    throw WireError.badRequest(
                            "Generation and calculated deck fields are read-only");
                }
            }

            // 按请求里的字段顺序处理：Python 版就是遍历 change.items()，而 ace_card 的
            // 「必须在本次卡组里」校验会受 deck_cards_list 是否先写入影响。
            for (Map.Entry<String, Object> entry : change.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if ("d_deck_datas_id".equals(key)) {
                    continue;
                }
                switch (key) {
                    case "deck_name" -> {
                        if (!(value instanceof String name) || name.length() > MAX_ID_LENGTH) {
                            throw WireError.badRequest("Invalid deck_name");
                        }
                        deck.put("DeckName", name);
                    }
                    case "deck_no" -> {
                        Long number = strictInt(value);
                        if (number == null || number < 0) {
                            throw WireError.badRequest("Invalid deck_no");
                        }
                        deck.put("DeckNo", number);
                    }
                    case "deck_cards_list" -> deck.put("DeckCardsList",
                            slots(value, ownedCards));
                    case "ace_card" -> {
                        if (!(value instanceof String ace) || ace.length() > MAX_ID_LENGTH) {
                            throw WireError.badRequest("Invalid ace_card");
                        }
                        if (!ace.isEmpty()) {
                            if (!ownedCards.contains(ace) || !deckContainsCard(deck, ace)) {
                                throw WireError.badRequest(
                                        "Ace card must be owned and present in this deck");
                            }
                            Object previous = deck.get("AceCard");
                            if (!ace.equals(previous)) {
                                shop.advance(missions,
                                        WireShop.BEGINNER_CONDITION_ACE_CARD, 0, 1);
                            }
                        }
                        deck.put("AceCard", ace);
                    }
                    case "is_change_deck_cards" -> {
                        if (value != null && !(value instanceof Boolean)) {
                            throw WireError.badRequest("Invalid is_change_deck_cards");
                        }
                        deck.put("IsChangeDeckCards", Boolean.TRUE.equals(value));
                    }
                    default -> {
                        // 只读字段：前面已经与当前值逐项比对过，这里不写。
                    }
                }
            }
        }        Set<String> numbers = new HashSet<>();
        for (Map<String, Object> deck : updated) {
            String key = J.intOr(deck.get("GenerationsId"), 0) + ":" + J.longOr(deck.get("DeckNo"), 0);
            if (!numbers.add(key)) {
                throw WireError.badRequest("Duplicate deck number within a generation");
            }
        }

        List<Map<String, Object>> savedMissions =
                (missionsPresent || !missions.isEmpty()) ? missions : null;
        commit(request, updated, savedMissions);
        return encode("DeckModifyDeckListResponse", J.map("DeckList", updated));
    }

    // ------------------------------------------------------------------
    // 卡组 / 槽位结构
    // ------------------------------------------------------------------

    /**
     * {@code _slots}：校验并规范化客户端提交的卡组槽位。
     *
     * <p>生成的字段名必须与 {@code DeckCards} 模型一致；等级加成固定 0（本机没有可核实的
     * 计算规则，与 Python 版相同）。
     */
    static List<Map<String, Object>> slots(Object rawCards, Set<String> ownedCards) {
        List<Object> cards = J.listOr(rawCards);
        if (cards == null || cards.size() > MAX_SLOTS) {
            throw WireError.badRequest("Invalid deck_cards_list");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<Long> numbers = new HashSet<>();
        for (Object raw : cards) {
            Map<String, Object> card = J.nodeOr(raw);
            if (card == null) {
                throw WireError.badRequest("Each deck slot must be an object");
            }
            Object rawSlot = orEmpty(card.get("d_deck_cards_id"));
            if (!(rawSlot instanceof String slot) || slot.length() > MAX_ID_LENGTH) {
                throw WireError.badRequest("Invalid or duplicate deck slot");
            }
            if (slot.isEmpty()) {
                slot = UUID.randomUUID().toString();
            }
            if (!seen.add(slot)) {
                throw WireError.badRequest("Invalid or duplicate deck slot");
            }

            Object rawCardId = orEmpty(card.get("d_card_datas_id"));
            if (!(rawCardId instanceof String cardId) || cardId.length() > MAX_ID_LENGTH) {
                throw WireError.badRequest("Invalid d_card_datas_id");
            }
            if (!cardId.isEmpty() && !ownedCards.contains(cardId)) {
                throw WireError.badRequest("Card is not owned");
            }

            Long slotNo = strictInt(card.get("slot_no"));
            if (slotNo == null || slotNo < 1 || !numbers.add(slotNo)) {
                throw WireError.badRequest("Invalid slot_no");
            }

            List<String> sides = new ArrayList<>(2);
            for (String key : List.of("side_style1_d_card_datas_id",
                    "side_style2_d_card_datas_id")) {
                Object rawSide = orEmpty(card.get(key));
                if (!(rawSide instanceof String side) || side.length() > MAX_ID_LENGTH
                        || (!side.isEmpty() && !ownedCards.contains(side))) {
                    throw WireError.badRequest("Side style card is not owned");
                }
                sides.add(side);
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("DDeckCardsId", slot);
            row.put("DCardDatasId", cardId);
            row.put("SlotNo", slotNo);
            row.put("GradeCardBonusValue", 0);
            row.put("GradeCardBonusLimitUp", 0);
            row.put("SideStyle1DCardDatasId", sides.get(0));
            row.put("SideStyle1GradeCardBonusValue", 0);
            row.put("SideStyle1GradeCardBonusLimitUp", 0);
            row.put("SideStyle2DCardDatasId", sides.get(1));
            row.put("SideStyle2GradeCardBonusValue", 0);
            row.put("SideStyle2GradeCardBonusLimitUp", 0);
            result.add(row);
        }
        return result;
    }

    private static Map<String, Object> findDeck(List<Map<String, Object>> decks, String ident) {
        for (Map<String, Object> deck : decks) {
            if (ident.equals(deck.get("DDeckDatasId"))) {
                return deck;
            }
        }
        return null;
    }

    private static Map<String, Object> requireDeck(List<Map<String, Object>> decks,
                                                   String ident) {
        Map<String, Object> deck = findDeck(decks, ident);
        if (deck == null) {
            throw new WireError(404, "Local deck not found");
        }
        return deck;
    }

    private static SlotRef requireSlot(List<Map<String, Object>> decks, String ident) {
        for (Map<String, Object> deck : decks) {
            for (Map<String, Object> slot : deckCards(deck)) {
                if (ident.equals(slot.get("DDeckCardsId"))) {
                    return new SlotRef(deck, slot);
                }
            }
        }
        throw new WireError(404, "Local deck slot not found");
    }

    /** 卡组里的槽位列表（真实引用，排序/修改会写回卡组）。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> deckCards(Map<String, Object> deck) {
        Object value = deck.get("DeckCardsList");
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        List<Map<String, Object>> created = new ArrayList<>();
        deck.put("DeckCardsList", created);
        return created;
    }

    private static boolean deckContainsCard(Map<String, Object> deck, String cardId) {
        for (Map<String, Object> slot : deckCards(deck)) {
            if (cardId.equals(slot.get("DCardDatasId"))) {
                return true;
            }
        }
        return false;
    }

    private record SlotRef(Map<String, Object> deck, Map<String, Object> slot) {
    }

    private static Set<String> ownedCardIds(PlayerContext player) {
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> card : player.rowsSnapshot("Cards")) {
            Object ident = card.get("DCardDatasId");
            if (ident instanceof String text) {
                ids.add(text);
            }
        }
        return ids;
    }

    private static boolean liveBlocksDecks(PlayerContext player) {
        Object raw = player.raw("ActiveQuest");
        if (!J.truthy(raw)) {
            return false;
        }
        String[] parts = String.valueOf(raw).split(":");
        return !(parts.length >= 6
                && ("prep".equals(parts[0]) || "done".equals(parts[0])));
    }

    // ------------------------------------------------------------------
    // 写入 / 回滚
    // ------------------------------------------------------------------

    private void commit(WireRequest request, List<Map<String, Object>> updated,
                        List<Map<String, Object>> missions) {
        PlayerContext player = request.player();
        Object previousDecks = player.raw("Decks");
        Object previousMissions = player.raw("beginner_missions");
        player.put("Decks", updated);
        if (missions != null) {
            player.put("beginner_missions", missions);
        }
        try {
            request.store().save(player);
        } catch (RuntimeException e) {
            restore(player, "Decks", previousDecks);
            restore(player, "beginner_missions", previousMissions);
            throw e;
        }
    }

    private static void restore(PlayerContext player, String key, Object value) {
        if (value == null) {
            player.remove(key);
        } else {
            player.put(key, value);
        }
    }

    // ------------------------------------------------------------------
    // 取值 / 校验
    // ------------------------------------------------------------------

    private Map<String, Object> encode(String model, Map<String, Object> data) {
        return codec.encode(model, data);
    }

    /** Python {@code _text}：必填、字符串、长度上限 128（允许纯空白）。 */
    private static String requiredText(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String text) || text.isEmpty()
                || text.length() > MAX_ID_LENGTH) {
            throw WireError.badRequest("Invalid " + key);
        }
        return text;
    }

    /** Python {@code _owned_id}：None 视为空串，其余必须是字符串。 */
    private static String ownedId(Object value) {
        if (value == null) {
            return "";
        }
        if (!(value instanceof String text) || text.length() > MAX_ID_LENGTH) {
            throw WireError.badRequest("Invalid card identifier");
        }
        return text;
    }

    /** Python {@code slot.get(key) or ''}：None/false/0/空集合都落成空串。 */
    private static Object orEmpty(Object value) {
        if (value == null || Boolean.FALSE.equals(value)
                || (value instanceof Number number && number.doubleValue() == 0)) {
            return "";
        }
        if (value instanceof String text && text.isEmpty()) {
            return "";
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return "";
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return "";
        }
        return value;
    }

    /** Python {@code type(value) is int}：只接受 JSON 整型，布尔与浮点不算。 */
    static Long strictInt(Object value) {
        if (value instanceof Integer number) {
            return number.longValue();
        }
        if (value instanceof Long number) {
            return number;
        }
        if (value instanceof Short number) {
            return number.longValue();
        }
        if (value instanceof Byte number) {
            return number.longValue();
        }
        if (value instanceof BigInteger number
                && number.bitLength() < Long.SIZE) {
            return number.longValue();
        }
        return null;
    }

    /** Python {@code side_style_no not in (1, 2)}；布尔按 Python 语义等同 1/0。 */
    private static int sideStyleNo(Object value) {
        double number;
        if (value instanceof Boolean flag) {
            number = flag ? 1 : 0;
        } else if (value instanceof Number raw) {
            number = raw.doubleValue();
        } else {
            throw WireError.badRequest("side_style_no must be 1 or 2");
        }
        if (number != 1 && number != 2) {
            throw WireError.badRequest("side_style_no must be 1 or 2");
        }
        return (int) number;
    }

    private static boolean sameInt(Object left, Object right) {
        Long a = strictInt(left);
        Long b = strictInt(right);
        return a != null && b != null && a.longValue() == b.longValue();
    }

    /** Python 的 {@code type(a) is type(b)} 在 JSON 数值上的近似：所有整型同一类。 */
    private static boolean sameJsonType(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof Number && right instanceof Number) {
            return true;
        }
        return left.getClass().equals(right.getClass());
    }

    /** Python 的 {@code value != wire[key]}；数值按数值比较。 */
    private static boolean jsonEquals(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return a.longValue() == b.longValue();
        }
        return Objects.equals(left, right);
    }
}
