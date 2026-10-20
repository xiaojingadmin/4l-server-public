package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireError;
import com.linklike.server.service.PlayerContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 商店结算写接口 —— 对应 Python {@code wire_shop.py} 的 {@code gp_prize_purchase} /
 * {@code item_purchase} / {@code item_convert} / {@code sisca_purchase} /
 * {@code sticker_purchase}，以及 {@code wire_services._petal_purchase}。
 *
 * <p>四个兑换接口都要求 {@code x-idempotency-key}：先把账号改成目标状态，再写一条
 * {@code shop_receipts} 回执并落库；同键重放返回原回执，同键换参数返回 409。结算中途失败
 * 会把本次可能改到的账号键（道具、卡片、限购状态、钱包）整体还原，所以客户端看到的是
 * 400/404/501 而不是半成品状态 —— Python 用 {@code deepcopy} 草稿达到同样效果。
 *
 * <p>条目定义来自 {@code client_defaults/shop_catalog.json}（与 {@link WireStore} 的列表同源），
 * 生效时间按 JST 解释。
 */
@Component
public class WireShopModule implements WireModule {

    private static final int ITEM_TYPE_ITEM = 1;
    private static final int ITEM_TYPE_RESOURCE = 3;
    private static final int ITEM_TYPE_MESSAGE_CARD = 9;
    private static final int UNLOCK_NONE = 0;
    private static final int UNLOCK_ITEM_NUM = 1;
    private static final long COIN_LIMIT = 999_999_999L;
    private static final DateTimeFormatter MICROS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    /** 结算可能改写的账号键；失败回滚按这张表走。 */
    private static final List<String> SETTLED_KEYS = List.of(
            "Items", "Cards", "Stickers", "PetalCoinNum",
            "shop_period_states", "item_exchange_states", "sisca_store_states",
            "sticker_exchange_states", "petal_exchanges", "shop_receipts",
            "FanLevelDetails", "MemberFanLevels", "FanLevels", "FanLevel");

    private final WireStore store;
    private final WireCardMint mint;
    private final WireCatalog catalog;
    private final WireServices services;
    private final WireFanLevel.Catalog fanCatalog;

    public WireShopModule(WireStore store, WireCardMint mint, WireCatalog catalog,
                          WireServices services, WireFanLevel.Catalog fanCatalog) {
        this.store = store;
        this.mint = mint;
        this.catalog = catalog;
        this.services = services;
        this.fanCatalog = fanCatalog;
    }

    @Override
    public void register(WireRouter router) {
        router.register("/v1/gp_prize_exchange/set_purchase", this::gpPrizePurchase);
        router.register("/v1/item_exchange/set_purchase", this::itemPurchase);
        router.register("/v1/item_exchange/set_limit_break_material_convert", this::itemConvert);
        router.register("/v1/sisca_store/set_purchase", this::siscaPurchase);
        router.register("/v1/sticker_exchange/set_purchase", this::stickerPurchase);
        router.register("/v1/petal_exchange/set_purchase", this::petalPurchase);
    }

    // ------------------------------------------------------------------
    // GP 奖品兑换
    // ------------------------------------------------------------------

    private Map<String, Object> gpPrizePurchase(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        int ident = integer(request, "gp_prize_exchanges_id", 1, Integer.MAX_VALUE);
        String key = idempotencyKey(request, "GP prize exchanges");
        String fingerprint = fingerprint("/v1/gp_prize_exchange/set_purchase", ident);
        Map<String, Object> replay = replay(player, key, fingerprint, "GP prize exchange");
        if (replay != null) {
            return replay;
        }
        Map<String, Object> rate = prepare(spec("gp_prizes", ident), false, false);
        Map<String, Object> before = snapshot(player);
        try {
            debitItem(player, requireItem(rate.get("MaterialItemId")),
                    intOf(rate.get("MaterialItemNum")));
            grantCard(player, intOf(rate.get("ProductItemId")));
            return commit(player, request, key, fingerprint,
                    "/v1/gp_prize_exchange/set_purchase",
                    J.map("gp_prize_ticket_id", catalog().get("gp_prize_ticket_kind"),
                            "user_ticket_num",
                            itemNum(player, intOf(rate.get("MaterialItemId")))));
        } catch (RuntimeException e) {
            restore(player, before);
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // 道具兑换
    // ------------------------------------------------------------------

    private Map<String, Object> itemPurchase(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        int ident = integer(request, "item_exchanges_id", 1, Integer.MAX_VALUE);
        int num = integer(request, "num", 1, 99);
        String key = idempotencyKey(request, "item exchanges");
        String fingerprint = fingerprint("/v1/item_exchange/set_purchase", ident, num);
        Map<String, Object> replay = replay(player, key, fingerprint, "item exchange");
        if (replay != null) {
            return replay;
        }
        Instant now = Instant.now();
        Map<String, Object> rate = prepare(spec("item_exchanges", ident), true, false);
        Map<String, Object> before = snapshot(player);
        try {
            if (store.locked(rate, player, now)) {
                throw WireError.badRequest("Exchange is locked");
            }
            int reset = intOf(rate.get("ResetType"));
            if (reset == WireStore.RESET_ADV || reset == WireStore.RESET_MUSIC) {
                if (store.exchangeRemaining(rate, player, ident, WireStore.Kind.ITEM, now) < num) {
                    throw WireError.badRequest("Exchange limit reached");
                }
            } else {
                consumePeriod(player, rate, ident, WireStore.Kind.ITEM, num, now);
            }
            debitItem(player, requireItem(rate.get("MaterialItemId")),
                    intOf(rate.get("MaterialItemNum")) * num);
            credit(player, intOf(rate.get("ProductItemId")),
                    intOf(rate.get("ProductItemNum")) * num);
            return commit(player, request, key, fingerprint,
                    "/v1/item_exchange/set_purchase",
                    J.map("user_product_item_num",
                                    itemNum(player, intOf(rate.get("ProductItemId"))),
                            "user_material_item_num",
                                    itemNum(player, intOf(rate.get("MaterialItemId")))));
        } catch (RuntimeException e) {
            restore(player, before);
            throw e;
        }
    }

    /** Python {@code item_convert}：同稀有度的突破素材按批次兑换成道具。 */
    private Map<String, Object> itemConvert(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String key = idempotencyKey(request, "limit-break material conversion");
        String fingerprint = fingerprint("/v1/item_exchange/set_limit_break_material_convert");
        Map<String, Object> replay = replay(player, key, fingerprint,
                "limit-break material conversion");
        if (replay != null) {
            return replay;
        }
        Map<String, Object> before = snapshot(player);
        try {
            Map<Integer, List<List<Long>>> buckets = new TreeMap<>();
            for (Map.Entry<String, Object> entry
                    : J.nodeOrEmpty(catalog().get("lb_materials")).entrySet()) {
                long ident = J.longOr(entry.getKey(), 0L);
                long have = itemNum(player, ident);
                if (have > 0) {
                    buckets.computeIfAbsent(J.intOr(entry.getValue(), 0),
                            ignored -> new ArrayList<>()).add(List.of(ident, have));
                }
            }
            Map<Long, Long> granted = new TreeMap<>();
            for (Object entry : J.listOrEmpty(catalog().get("convert_rates"))) {
                Map<String, Object> rate = J.nodeOrEmpty(entry);
                int batch = intOf(rate.get("LimitBreakMaterialNum"));
                long product = J.longOr(rate.get("ProductItemId"), 0L);
                long qty = J.longOr(rate.get("ProductItemNum"), 0L);
                if (batch <= 0) {
                    continue;
                }
                // Python 对 [(id, have)] 直接 sorted()：先按 id、再按存量升序。
                List<List<Long>> stacks = new ArrayList<>(buckets.getOrDefault(
                        intOf(rate.get("LimitBreakMaterialRarity")), List.of()));
                stacks.sort(Comparator.<List<Long>>comparingLong(row -> row.get(0))
                        .thenComparingLong(row -> row.get(1)));
                long total = stacks.stream().mapToLong(row -> row.get(1)).sum();
                long times = total / batch;
                if (times <= 0) {
                    continue;
                }
                long remain = times * batch;
                for (List<Long> stack : stacks) {
                    long take = Math.min(stack.get(1), remain);
                    if (take > 0) {
                        debitItem(player, requireItem(stack.get(0)), (int) take);
                        remain -= take;
                    }
                    if (remain <= 0) {
                        break;
                    }
                }
                credit(player, product, (int) (qty * times));
                granted.merge(product, qty * times, Long::sum);
            }
            List<Map<String, Object>> items = new ArrayList<>(granted.size());
            granted.forEach((ident, num) -> items.add(J.map("item_id", ident, "get_num", num)));
            return commit(player, request, key, fingerprint,
                    "/v1/item_exchange/set_limit_break_material_convert",
                    J.map("get_item_list", items));
        } catch (RuntimeException e) {
            restore(player, before);
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // SIsCa 商店 / 贴纸兑换 / 花瓣兑换
    // ------------------------------------------------------------------

    private Map<String, Object> siscaPurchase(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        int ident = integer(request, "sisca_store_datas_id", 1, Integer.MAX_VALUE);
        int num = integer(request, "purchase_num", 1, 99);
        String key = idempotencyKey(request, "SIsCa store purchases");
        String fingerprint = fingerprint("/v1/sisca_store/set_purchase", ident, num);
        Map<String, Object> replay = replay(player, key, fingerprint, "SIsCa store purchase");
        if (replay != null) {
            return replay;
        }
        Instant now = Instant.now();
        Map<String, Object> rate = prepare(spec("sisca_products", ident), false, true);
        Map<String, Object> before = snapshot(player);
        try {
            int reset = intOf(rate.get("ResetType"));
            if (reset == WireStore.RESET_ADV || reset == WireStore.RESET_MUSIC) {
                if (store.siscaBuyable(rate, player, ident, now) < num) {
                    throw WireError.badRequest("Exchange limit reached");
                }
            } else {
                consumePeriod(player, rate, ident, WireStore.Kind.SISCA, num, now);
            }
            WireGachaModule.debitJewels(player, (long) intOf(rate.get("Price")) * num,
                    J.boolOr(rate.get("IsPaidSIsCaOnly"), false));
            credit(player, intOf(rate.get("ItemId")), intOf(rate.get("ItemNum")) * num);
            Map<String, Object> owned = itemRow(player, intOf(rate.get("ItemId")));
            return commit(player, request, key, fingerprint,
                    "/v1/sisca_store/set_purchase",
                    J.map("sisca_info", J.map(
                                    "free", player.entity().jewelFree,
                                    "paid", paidJewels(player)),
                            "user_item_info", J.map(
                                    "user_item_id",
                                            owned == null ? null : owned.get("UserItemId"),
                                    "item_id", rate.get("ItemId"),
                                    "item_num", owned == null ? 0L : owned.get("ItemNum"))));
        } catch (RuntimeException e) {
            restore(player, before);
            throw e;
        }
    }

    private Map<String, Object> stickerPurchase(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        int ident = integer(request, "sticker_exchanges_id", 1, Integer.MAX_VALUE);
        String key = idempotencyKey(request, "sticker exchanges");
        String fingerprint = fingerprint("/v1/sticker_exchange/set_purchase", ident);
        Map<String, Object> replay = replay(player, key, fingerprint, "sticker exchange");
        if (replay != null) {
            return replay;
        }
        Map<String, Object> rate = prepare(spec("sticker_exchanges", ident), false, false);
        Object product = rate.get("ProductStickerId");
        Map<String, Object> before = snapshot(player);
        try {
            for (Map<String, Object> sticker : player.rows("Stickers")) {
                if (product != null && product.equals(sticker.get("StickersId"))) {
                    throw WireError.badRequest("Sticker already owned");
                }
            }
            long points = WireGrants.walletValue(player, "StickerPointNum");
            int cost = intOf(rate.get("MaterialItemNum"));
            if (points < cost) {
                throw WireError.badRequest("Not enough sticker points");
            }
            WireGrants.setWallet(player, "StickerPointNum", points - cost);
            grantSticker(player, product);
            updateStickerState(player, ident);
            return commit(player, request, key, fingerprint,
                    "/v1/sticker_exchange/set_purchase",
                    J.map("sticker_point_num",
                            WireGrants.walletValue(player, "StickerPointNum")));
        } catch (RuntimeException e) {
            restore(player, before);
            throw e;
        }
    }

    /** Python {@code _petal_purchase}：花瓣币按「第几次兑换」阶梯计价，不带幂等键。 */
    private Map<String, Object> petalPurchase(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        int series = integer(request, "card_series_id", 1, Integer.MAX_VALUE);
        int num = integer(request, "num", 1, 99);
        Map<String, Object> group = null;
        Map<String, Object> listing = null;
        for (Map<String, Object> candidate : services.refs("petal_exchange_catalog")) {
            for (Map<String, Object> row : J.rowsOr(candidate.get("limit_break_exchange_list"))) {
                if (J.intOr(row.get("card_series_id"), Integer.MIN_VALUE) == series) {
                    group = candidate;
                    listing = row;
                    break;
                }
            }
            if (listing != null) {
                break;
            }
        }
        if (listing == null) {
            throw WireError.notFound("Local petal-exchange listing not found");
        }
        Object cardId = listing.get("card_datas_id");
        if (!(cardId instanceof Integer) && !(cardId instanceof Long)) {
            throw WireError.notFound("Local petal-exchange card not found");
        }
        long cardDatasId = J.longOr(cardId, 0L);
        Map<String, Object> master = mint.card(cardDatasId);
        if (master == null) {
            throw WireError.badRequest("No petal-exchange rate for this card");
        }
        int cost = petalCost(J.intOr(master.get("Rarity"), 0), petalTotal(player, series), num);
        long coins = player.longOf("PetalCoinNum", 0L);
        if (coins < cost) {
            throw WireError.badRequest("Not enough petal coins");
        }
        Map<String, Object> before = snapshot(player);
        try {
            player.put("PetalCoinNum", coins - cost);
            grantPetalCard(player, cardDatasId);
            bumpPetalState(player, J.intOr(group.get("character_id"), 0), listing, num);
            request.store().save(player);
        } catch (RuntimeException e) {
            restore(player, before);
            throw e;
        }
        return J.map("result", true);
    }

    // ------------------------------------------------------------------
    // 条目校验（Python {@code _prepare}）
    // ------------------------------------------------------------------

    private Map<String, Object> prepare(Map<String, Object> row, boolean item, boolean sisca) {
        if (row == null) {
            throw WireError.notFound("Local shop listing not found");
        }
        Object blocked = row.get("exchange_block_reason");
        if (blocked instanceof String reason && !reason.isEmpty()) {
            throw new WireError(501, reason);
        }
        int reset = intOf(row.get("ResetType"));
        if (reset < WireStore.RESET_NOLIMIT || reset > WireStore.RESET_MUSIC) {
            throw new WireError(501,
                    "Exchange reset schedule " + reset + " has not been verified");
        }
        if (reset == WireStore.RESET_ADV && intOf(row.get("AdvUnlockNeed")) <= 0) {
            throw new WireError(501, "Adventure unlock item has no matching AdvDatas rows");
        }
        if (reset == WireStore.RESET_MUSIC && intOf(row.get("MusicUnlockNeed")) <= 0) {
            throw new WireError(501, "Music unlock item has no matching Musics rows");
        }
        int unlock = intOf(row.get("UnlockConditions"));
        if (item && unlock != UNLOCK_NONE && unlock != UNLOCK_ITEM_NUM) {
            throw new WireError(501, "Exchange unlock conditions have not been verified");
        }
        if (item) {
            int productType = intOf(row.get("ProductItemType"));
            if (productType != ITEM_TYPE_ITEM && productType != ITEM_TYPE_RESOURCE) {
                throw new WireError(501,
                        "Exchange product type " + productType + " has not been verified");
            }
        }
        if (sisca) {
            int itemType = intOf(row.get("ItemType"));
            if (itemType != ITEM_TYPE_ITEM && itemType != ITEM_TYPE_RESOURCE
                    && itemType != ITEM_TYPE_MESSAGE_CARD) {
                throw new WireError(501,
                        "SIsCa store product type " + itemType + " has not been verified");
            }
        }
        if (!available(row, Instant.now())) {
            throw WireError.notFound("Local shop listing is outside its availability period");
        }
        return row;
    }

    private static boolean available(Map<String, Object> row, Instant now) {
        Instant start = parseTime(row.get("StartTime"));
        Instant end = parseTime(row.get("EndTime"));
        if (start == null || end == null) {
            return false;
        }
        return !now.isBefore(start) && now.isBefore(end);
    }

    private static Instant parseTime(Object value) {
        if (!(value instanceof String text) || text.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // 目录里写的是带 +09:00 偏移的 ISO-8601，落到下面的分支。
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // 不带偏移的本地时间：Python 的 fromisoformat 会补 UTC。
        }
        try {
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 限购扣减（Python {@code _consume_period} / {@code _consume_remaining}）
    // ------------------------------------------------------------------

    private void consumePeriod(PlayerContext player, Map<String, Object> row, int ident,
                               WireStore.Kind kind, int num, Instant now) {
        int cap = store.limitCap(row);
        if (cap <= 0) {
            return;
        }
        String period = store.periodKey(row, now);
        if (period.isEmpty()) {
            String remainingKey = kind == WireStore.Kind.ITEM
                    ? "remaining_exchange_num" : "buyable_num";
            String identKey = kind == WireStore.Kind.ITEM
                    ? "item_exchanges_id" : "sisca_store_datas_id";
            String stateKey = kind == WireStore.Kind.ITEM
                    ? "item_exchange_states" : "sisca_store_states";
            Map<String, Object> blank = kind == WireStore.Kind.ITEM
                    ? J.map("user_product_item_num", 0L, "user_material_item_num", 0L,
                            "is_lock", false)
                    : J.map("own_num", 0L, "is_new_item", false, "is_sale", false);
            consumeRemaining(player.rows(stateKey), identKey, remainingKey, ident, cap, num, blank);
            return;
        }
        List<Map<String, Object>> states = player.rows("shop_period_states");
        Map<String, Object> stored = null;
        for (Map<String, Object> candidate : states) {
            if (kind.code.equals(candidate.get("listing_kind"))
                    && J.intOr(candidate.get("listing_id"), -1) == ident) {
                stored = candidate;
                break;
            }
        }
        if (stored == null || !period.equals(stored.get("period_key"))) {
            if (stored == null) {
                stored = J.map("listing_kind", kind.code, "listing_id", ident);
                states.add(stored);
            }
            stored.put("period_key", period);
            stored.put("remaining", cap);
        }
        long left = J.longOr(stored.get("remaining"), 0L);
        if (left < num) {
            throw WireError.badRequest("Exchange limit reached");
        }
        stored.put("remaining", left - num);
    }

    private static void consumeRemaining(List<Map<String, Object>> states, String identKey,
                                         String remainingKey, int ident, int cap, int num,
                                         Map<String, Object> blank) {
        if (cap <= 0) {
            return;
        }
        Map<String, Object> row = null;
        for (Map<String, Object> candidate : states) {
            if (J.intOr(candidate.get(identKey), -1) == ident) {
                row = candidate;
                break;
            }
        }
        if (row == null) {
            row = new LinkedHashMap<>(blank);
            row.put(identKey, ident);
            row.put(remainingKey, cap);
            states.add(row);
        }
        long left = J.longOr(row.get(remainingKey), 0L);
        if (left < num) {
            throw WireError.badRequest("Exchange limit reached");
        }
        row.put(remainingKey, left - num);
    }

    // ------------------------------------------------------------------
    // 道具与卡片
    // ------------------------------------------------------------------

    private Map<String, Object> requireItem(Object ident) {
        Map<String, Object> item = J.nodeOr(
                J.nodeOrEmpty(catalog().get("items")).get(String.valueOf(ident)));
        if (item == null) {
            throw new WireError(501, "Missing verified item definition for this exchange");
        }
        return item;
    }

    private static void debitItem(PlayerContext player, Map<String, Object> item, int quantity) {
        if (quantity <= 0) {
            return;
        }
        Map<String, Object> owned = itemRow(player, intOf(item.get("Id")));
        long have = owned == null ? 0L : J.longOr(owned.get("ItemNum"), 0L);
        if (have < quantity) {
            throw WireError.badRequest("Not enough items");
        }
        owned.put("ItemNum", have - quantity);
    }

    private void credit(PlayerContext player, long ident, int quantity) {
        if (creditItem(player, requireItem(ident), quantity) != quantity) {
            throw WireError.badRequest("Cannot grant the full product quantity");
        }
    }

    /** Python {@code wire_gacha._credit_item}：按 {@code ItemCategory} 归组，按 {@code LimitNum} 截断。 */
    private static int creditItem(PlayerContext player, Map<String, Object> item, int quantity) {
        if (quantity <= 0) {
            return 0;
        }
        int category = intOf(item.get("ItemCategory"));
        long itemId = J.longOr(item.get("Id"), 0L);
        List<Map<String, Object>> groups = player.rows("Items");
        Map<String, Object> group = null;
        for (Map<String, Object> row : groups) {
            if (J.intOr(row.get("ItemCategory"), Integer.MIN_VALUE) == category) {
                group = row;
                break;
            }
        }
        if (group == null) {
            group = J.map("ItemCategory", category, "UserItemList", new ArrayList<>());
            groups.add(group);
        }
        List<Map<String, Object>> owned = mutableRows(group, "UserItemList");
        Map<String, Object> row = null;
        for (Map<String, Object> candidate : owned) {
            if (J.longOr(candidate.get("ItemId"), Long.MIN_VALUE) == itemId) {
                row = candidate;
                break;
            }
        }
        int before = row == null ? 0 : J.intOr(row.get("ItemNum"), 0);
        int limit = intOf(item.get("LimitNum"));
        int credited = Math.max(0, Math.min(quantity, limit - before));
        if (credited > 0) {
            if (row == null) {
                row = J.map("UserItemId", UUID.randomUUID().toString(), "ItemId", itemId,
                        "ItemType", item.get("ItemType"), "Rarity", item.get("Rarity"),
                        "ItemNum", 0, "ResourceFileName", "");
                owned.add(row);
            }
            row.put("ItemNum", before + credited);
        }
        return credited;
    }

    private static Map<String, Object> itemRow(PlayerContext player, long ident) {
        for (Map<String, Object> group : player.rowsSnapshot("Items")) {
            for (Map<String, Object> row : J.rowsOr(group.get("UserItemList"))) {
                if (J.longOr(row.get("ItemId"), Long.MIN_VALUE) == ident) {
                    return row;
                }
            }
        }
        return null;
    }

    private static long itemNum(PlayerContext player, long ident) {
        Map<String, Object> row = itemRow(player, ident);
        return row == null ? 0L : J.longOr(row.get("ItemNum"), 0L);
    }

    /** Python {@code _grant_card}：已有同系列卡片时改发重复素材与花瓣币。 */
    private void grantCard(PlayerContext player, int ident) {
        Map<String, Object> info = J.nodeOr(
                J.nodeOrEmpty(catalog().get("cards")).get(String.valueOf(ident)));
        if (info == null) {
            throw new WireError(501, "Missing verified card definition for this exchange");
        }
        if (!ownedSeries(player).contains(J.longOr(info.get("card_series_id"), 0L))) {
            mint.grant(player, ident);
            return;
        }
        Map<String, Object> material = J.nodeOr(
                J.nodeOrEmpty(catalog().get("items"))
                        .get(String.valueOf(info.get("material_item_id"))));
        if (material == null) {
            throw new WireError(501, "Missing verified duplicate-material definition");
        }
        int materialNum = intOf(info.get("material_num"));
        if (creditItem(player, material, materialNum) != materialNum) {
            throw WireError.badRequest("Cannot grant the full product quantity");
        }
        long before = player.longOf("PetalCoinNum", 0L);
        long coins = Math.min(J.longOr(info.get("coin_num"), 0L),
                Math.max(0L, COIN_LIMIT - before));
        if (coins > 0) {
            player.put("PetalCoinNum", before + coins);
        }
    }

    /** Python {@code _grant_sticker}：已拥有则跳过，否则按主数据补一条贴纸行。 */
    private void grantSticker(PlayerContext player, Object ident) {
        Map<String, Object> row = J.nodeOr(
                J.nodeOrEmpty(catalog().get("stickers")).get(String.valueOf(ident)));
        if (row == null) {
            throw new WireError(501, "Missing verified sticker definition");
        }
        List<Map<String, Object>> stickers = player.rows("Stickers");
        for (Map<String, Object> sticker : stickers) {
            if (ident != null && ident.equals(sticker.get("StickersId"))) {
                return;
            }
        }
        stickers.add(J.map(
                "StickersId", row.get("Id"),
                "Name", row.get("Name"),
                "Text", row.get("Text"),
                "CategoryType", row.get("CategoryType"),
                "CategoryName", row.get("CategoryName"),
                "CharacterId", row.get("CharactersId"),
                "IsVariant", row.get("IsVariant"),
                "SeasonId", row.get("SeasonId"),
                "RequirementText", row.get("RequirementText"),
                "RequirementNum", row.get("RequirementValue"),
                "RequirementProgress", 0L,
                "VariantRequirementText", row.get("EditRequirementText"),
                "VariantRequirementNum", row.get("EditRequirementValue"),
                "VariantRequirementProgress", 0L,
                "IsOwned", true,
                "IsAvailableVariant", false,
                "CreateTime", MICROS.format(Instant.now())));
    }

    private static void updateStickerState(PlayerContext player, int ident) {
        List<Map<String, Object>> states = player.rows("sticker_exchange_states");
        Map<String, Object> state = null;
        for (Map<String, Object> row : states) {
            if (J.intOr(row.get("sticker_exchanges_id"), -1) == ident) {
                state = row;
                break;
            }
        }
        if (state == null) {
            state = J.map("sticker_exchanges_id", ident, "is_having", false);
            states.add(state);
        }
        state.put("is_having", true);
    }

    /** Python {@code _grant_petal_card}：已拥有该卡则不发，否则造一张并结算粉丝点。 */
    private void grantPetalCard(PlayerContext player, long cardDatasId) {
        for (Map<String, Object> card : player.rows("Cards")) {
            if (J.longOr(card.get("CardDatasId"), Long.MIN_VALUE) == cardDatasId) {
                return;
            }
        }
        Map<String, Object> card;
        try {
            card = mint.mint(cardDatasId);
        } catch (IllegalArgumentException e) {
            throw WireError.notFound("Local petal-exchange card not found");
        }
        player.rows("Cards").add(card);
        WireFanLevel.obtained(player, fanCatalog, card);
    }

    private Set<Long> ownedSeries(PlayerContext player) {
        Map<String, Object> index = J.nodeOrEmpty(catalog().get("card_series_by_id"));
        Set<Long> owned = new HashSet<>();
        for (Map<String, Object> card : player.rowsSnapshot("Cards")) {
            Object ident = card.get("CardDatasId");
            if (ident != null && index.containsKey(String.valueOf(ident))) {
                owned.add(J.longOr(index.get(String.valueOf(ident)), 0L));
            }
        }
        return owned;
    }

    // ------------------------------------------------------------------
    // 花瓣兑换
    // ------------------------------------------------------------------

    private int petalTotal(PlayerContext player, int seriesId) {
        for (Map<String, Object> group : player.rowsSnapshot("petal_exchanges")) {
            for (Map<String, Object> row : J.rowsOr(group.get("limit_break_exchange_list"))) {
                if (J.intOr(row.get("card_series_id"), Integer.MIN_VALUE) == seriesId) {
                    return J.intOr(row.get("total_num"), 0);
                }
            }
        }
        return 0;
    }

    /**
     * 第 {@code start+1} 到 {@code start+num} 次兑换的累计价格 —— Python {@code _petal_cost}。
     *
     * <p>价格随兑换次数阶梯上升；任何一档没有费率行时整笔拒绝（400），不退化成免费。
     */
    private int petalCost(int rarity, int start, int num) {
        List<Map<String, Object>> rates = catalog.petalExchangeRates();
        int total = 0;
        for (int step = 0; step < num; step++) {
            int index = start + step + 1;
            Integer price = null;
            for (Map<String, Object> row : rates) {
                if (J.intOr(row.get("rarity"), Integer.MIN_VALUE) != rarity) {
                    continue;
                }
                Object lower = row.get("exchange_limit_lower");
                Object upper = row.get("exchange_limit_upper");
                if (lower instanceof Integer low && upper instanceof Integer high
                        && low <= index && index <= high) {
                    price = J.integer(row.get("price"));
                    break;
                }
            }
            if (price == null) {
                throw WireError.badRequest("No petal-exchange rate for copy " + index);
            }
            total += price;
        }
        return total;
    }

    private static void bumpPetalState(PlayerContext player, int characterId,
                                       Map<String, Object> listing, int num) {
        List<Map<String, Object>> groups = player.rows("petal_exchanges");
        Map<String, Object> group = null;
        for (Map<String, Object> row : groups) {
            if (J.intOr(row.get("character_id"), Integer.MIN_VALUE) == characterId) {
                group = row;
                break;
            }
        }
        if (group == null) {
            group = J.map("character_id", characterId,
                    "limit_break_exchange_list", new ArrayList<>());
            groups.add(group);
        }
        List<Map<String, Object>> inner = mutableRows(group, "limit_break_exchange_list");
        int series = J.intOr(listing.get("card_series_id"), 0);
        Map<String, Object> row = null;
        for (Map<String, Object> candidate : inner) {
            if (J.intOr(candidate.get("card_series_id"), Integer.MIN_VALUE) == series) {
                row = candidate;
                break;
            }
        }
        if (row == null) {
            row = J.map("card_series_id", series,
                    "card_datas_id", J.longOr(listing.get("card_datas_id"), 0L),
                    "total_num", 0L, "limit_break_times", 0L, "item_num", 0L);
            inner.add(row);
        }
        row.put("total_num", J.longOr(row.get("total_num"), 0L) + num);
    }

    // ------------------------------------------------------------------
    // 幂等 / 落库
    // ------------------------------------------------------------------

    private String idempotencyKey(WireRequest request, String what) {
        String key = request.idempotencyKey();
        if (key == null || key.length() > 128) {
            throw WireError.badRequest("x-idempotency-key is required for local " + what);
        }
        return key;
    }

    private static Map<String, Object> replay(PlayerContext player, String key,
                                              String fingerprint, String what) {
        for (Map<String, Object> receipt : player.rowsSnapshot("shop_receipts")) {
            if (!key.equals(receipt.get("idempotency_key"))) {
                continue;
            }
            if (!fingerprint.equals(receipt.get("fingerprint"))) {
                throw WireError.conflict(
                        "Idempotency key already used for a different " + what);
            }
            return J.nodeOrEmpty(receipt.get("response_body"));
        }
        return null;
    }

    private Map<String, Object> commit(PlayerContext player, WireRequest request, String key,
                                       String fingerprint, String path,
                                       Map<String, Object> response) {
        player.rows("shop_receipts").add(J.map(
                "idempotency_key", key,
                "fingerprint", fingerprint,
                "path", path,
                "response_body", WireServices.deepCopyJson(response)));
        request.store().recordIdempotency(player.id(), key, path, fingerprint, response);
        request.store().save(player);
        return response;
    }

    private static Map<String, Object> snapshot(PlayerContext player) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : SETTLED_KEYS) {
            Object value = player.raw(key);
            out.put(key, value == null ? null : WireServices.deepCopyJson(value));
        }
        out.put("__jewelFree", player.entity().jewelFree);
        out.put("__jewelPaid", player.entity().jewelPaid);
        out.put("__jewelPaidGoogle", player.entity().jewelPaidGoogle);
        out.put("__musicPoint", player.entity().musicPoint);
        out.put("__stickerPointNum", player.entity().stickerPointNum);
        return out;
    }

    private static void restore(PlayerContext player, Map<String, Object> before) {
        for (String key : SETTLED_KEYS) {
            Object value = before.get(key);
            if (value == null) {
                player.remove(key);
            } else {
                player.put(key, value);
            }
        }
        player.entity().jewelFree = (Long) before.get("__jewelFree");
        player.entity().jewelPaid = (Long) before.get("__jewelPaid");
        player.entity().jewelPaidGoogle = (Long) before.get("__jewelPaidGoogle");
        player.entity().musicPoint = (Long) before.get("__musicPoint");
        player.entity().stickerPointNum = (Long) before.get("__stickerPointNum");
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 目录里某个兑换表的条目行（与 {@link WireStore} 同一份 {@code shop_catalog}）。 */
    private Map<String, Object> spec(String table, int ident) {
        return J.nodeOr(J.nodeOrEmpty(catalog().get(table)).get(String.valueOf(ident)));
    }

    private Map<String, Object> catalog() {
        return store.catalog();
    }

    /** Python {@code wire_gacha._debit_jewels} 的口径：Google 余额覆盖旧别名，两者不相加。 */
    private static long paidJewels(PlayerContext player) {
        return player.entity().jewelPaidGoogle != 0
                ? player.entity().jewelPaidGoogle : player.entity().jewelPaid;
    }

    private static int integer(WireRequest request, String key, int minimum, int maximum) {
        return WireServices.integer(request.body(), key, (Integer) null, minimum, maximum);
    }

    private static int intOf(Object value) {
        return J.intOr(value, 0);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mutableRows(Map<String, Object> node, String key) {
        Object value = node.get(key);
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) (List<?>) list;
        }
        List<Map<String, Object>> created = new ArrayList<>();
        node.put(key, created);
        return created;
    }

    private static String fingerprint(Object... parts) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < parts.length; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(parts[index]);
        }
        json.append(']');
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
