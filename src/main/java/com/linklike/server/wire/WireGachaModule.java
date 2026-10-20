package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireError;
import com.linklike.server.resource.ClientDefaults;
import com.linklike.server.service.PlayerContext;
import com.linklike.server.service.PlayerStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * ガチャ（抽卡 / 交换 / 天井）—— 对应 Python {@code wire_gacha.py}。
 *
 * <p>卡池、概率、价格、交换率全部来自随仓库分发的抽取结果
 * {@code client_defaults/gacha_catalog.json} 与 {@code select_ticket_master.json}
 * （Python 侧是 {@code build/client_defaults/} 下的同名文件）。没有可核实定义的卡池
 * 一律 501，不编造概率、不发假奖励。
 *
 * <h2>幂等</h2>
 * 抽卡与两种交换都要求 {@code x-idempotency-key}，回执存在账号里
 * （{@code gacha_receipts} / {@code select_ticket_exchange_receipts} /
 * {@code gacha_point_exchange_receipts}）。同一 key 重复请求会重放原回执；同一个 key
 * 换了参数则是 409。
 *
 * <h2>原子性</h2>
 * Python 在 {@code deepcopy(player)} 的草稿上结算，成功才 {@code save_player} 并整体
 * 写回。Java 侧用 {@link #begin(PlayerContext)} 记录快照，失败时 {@link #rollback} 还原，
 * 保证直接调用 service 的路径（例如测试、后台脚本）同样不会留下半截状态。
 */
@Component
public class WireGachaModule implements WireModule {

    /** ペタルコイン 的持有上限（卡池公告）与道具 ID。 */
    private static final int COIN_LIMIT = 999999999;
    private static final int PETAL_COIN_ITEM_ID = 3007001;

    /** 交换次数没有个人上限时的标记值。 */
    private static final int UNLIMITED_EXCHANGE = -1;

    private static final String GACHA_BANNER_ASSET = "image_gacha_banner_{gacha_series_id}";
    private static final String SELECT_TICKET_ASSET =
            "image_selectticket_banner_{select_ticket_series_id}";
    private static final String CARD_ASSET = "image_card_middle_vertical_{card_datas_id}";

    private static final ZoneOffset JST = ZoneOffset.ofHours(9);

    /** Python {@code now.strftime('%Y-%m-%dT%H:%M:%S.%fZ')}。 */
    private static final DateTimeFormatter RECEIPT_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    /** 结算会写到的账号键；失败时整体还原。 */
    private static final List<String> SETTLED_KEYS = List.of(
            "Items", "Cards", "PetalCoinNum", "FanLevelDetails", "MemberFanLevels",
            "FanLevels", "FanLevel", "gacha_states", "gacha_points", "gacha_receipts",
            "select_ticket_balances", "select_ticket_exchange_receipts",
            "gacha_point_exchange_receipts", "beginner_missions", "gacha_prize_receipts");

    private final WireServices services;
    private final WireCardMint mint;
    private final WireShop shop;
    private final ClientDefaults defaults;

    public WireGachaModule(WireServices services, WireCardMint mint, WireShop shop,
                           ClientDefaults defaults) {
        this.services = services;
        this.mint = mint;
        this.shop = shop;
        this.defaults = defaults;
    }

    @Override
    public void register(WireRouter router) {
        router.register("/v1/gacha/get_series_list", this::seriesList);
        router.register("/v1/gacha/get_history", this::history);
        router.register("/v1/gacha/get_lottery_chance", this::lotteryChance);
        router.register("/v1/webview/gacha/get_detail", this::detail);
        router.register("/v1/gacha/get_exchange_card_having_list", this::exchangeCardHavingList);
        router.register("/v1/gacha/get_guarantee_point_list", this::guaranteePointList);
        router.register("/v1/gacha/set_purchase", this::purchase);
        router.register("/v1/gacha/set_select_ticket_exchange", this::selectTicketExchange);
        router.register("/v1/gacha/set_guarantee_point_exchange", this::guaranteePointExchange);
        router.register("/v1/gacha/set_prize_receive", this::setPrizeReceive);
    }

    // ------------------------------------------------------------------
    // 静态目录
    // ------------------------------------------------------------------

    private Map<String, Object> catalog() {
        return defaults.object("gacha_catalog").orElseGet(LinkedHashMap::new);
    }

    private Map<String, Object> selectTicketMaster() {
        return defaults.object("select_ticket_master").orElseGet(LinkedHashMap::new);
    }

    private List<Map<String, Object>> pools() {
        return J.rowsOr(catalog().get("pools"));
    }

    private Map<String, Object> cards() {
        return J.nodeOrEmpty(catalog().get("cards"));
    }

    private Map<String, Object> items() {
        return J.nodeOrEmpty(catalog().get("items"));
    }

    private Map<String, Object> cardSeriesById() {
        return J.nodeOrEmpty(catalog().get("card_series_by_id"));
    }

    private Map<String, Object> findPool(long seriesId) {
        for (Map<String, Object> pool : pools()) {
            if (J.longOr(pool.get("gacha_series_id"), Long.MIN_VALUE) == seriesId) {
                return pool;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 天井奖池（GachaPrizes / GachaPrizeDatas）
    // ------------------------------------------------------------------

    /** {@code ClientDefaults} 里 master 表的行缓存；两张表各读一次。 */
    private volatile Map<Long, Map<Long, Map<String, Object>>> prizesBySeries;

    private static List<Map<String, Object>> tableRows(Map<String, Object> master, String table) {
        Map<String, Object> node = J.nodeOrEmpty(master.get("tables"));
        return J.rowsOr(J.nodeOrEmpty(node.get(table)).get("rows"));
    }

    private Map<String, Object> gachaMaster() {
        return defaults.object("gacha_master_5.1.0-R2604100").orElseGet(LinkedHashMap::new);
    }

    /**
     * 各卡池的天井档位：{@code GachaPrizeId -> {play_count, items}}。
     *
     * <p>档位由 {@code GachaPrizes.GachaPlayCount} 决定，奖励明细在
     * {@code GachaPrizeDatas}。两张表必须自洽（每档都有明细、Id 不重复），否则按缺失处理 ——
     * 与本地其它静态表一致，不猜阈值、不发假奖励。
     */
    private Map<Long, Map<Long, Map<String, Object>>> prizesBySeries() {
        Map<Long, Map<Long, Map<String, Object>>> cached = prizesBySeries;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (prizesBySeries == null) {
                prizesBySeries = loadPrizes();
            }
            return prizesBySeries;
        }
    }

    private Map<Long, Map<Long, Map<String, Object>>> loadPrizes() {
        Map<String, Object> master = gachaMaster();
        Map<Long, List<Map<String, Object>>> items = new LinkedHashMap<>();
        for (Map<String, Object> row : tableRows(master, "gachaprizedatas.tsv")) {
            items.computeIfAbsent(J.longOr(row.get("GachaPrizeId"), -1L), key -> new ArrayList<>())
                    .add(row);
        }
        for (List<Map<String, Object>> rows : items.values()) {
            rows.sort(java.util.Comparator.comparingInt(row -> J.intOr(row.get("OrderId"), 0)));
        }
        Map<Long, Map<Long, Map<String, Object>>> out = new LinkedHashMap<>();
        for (Map<String, Object> row : tableRows(master, "gachaprizes.tsv")) {
            long prizeId = J.longOr(row.get("Id"), -1L);
            long seriesId = J.longOr(row.get("GachaSeriesId"), -1L);
            List<Map<String, Object>> detail = items.get(prizeId);
            if (prizeId < 0 || seriesId < 0 || detail == null) {
                continue;
            }
            out.computeIfAbsent(seriesId, key -> new LinkedHashMap<>())
                    .put(prizeId, J.map(
                            "gacha_prize_id", prizeId,
                            "play_count", J.longOr(row.get("GachaPlayCount"), 0L),
                            "items", detail));
        }
        return out;
    }

    /** 该卡池已达成（抽数够）的档位，按 {@code play_count} 升序。 */
    private List<Map<String, Object>> reachedPrizes(long seriesId, long execCount) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> prize : prizesBySeries()
                .getOrDefault(seriesId, Map.of()).values()) {
            if (J.longOr(prize.get("play_count"), Long.MAX_VALUE) <= execCount) {
                rows.add(prize);
            }
        }
        rows.sort(java.util.Comparator.comparingLong(
                prize -> J.longOr(prize.get("play_count"), 0L)));
        return rows;
    }

    /** 账号里该卡池已领取的档位 id（{@code received_gacha_prize_list} 的真实来源）。 */
    private static List<Object> receivedPrizes(PlayerContext player, long seriesId) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> row : player.rowsSnapshot("gacha_prize_receipts")) {
            if (J.longOr(row.get("gacha_series_id"), Long.MIN_VALUE) != seriesId) {
                continue;
            }
            for (Object id : J.listOrEmpty(row.get("received_gacha_prize_list"))) {
                out.add(J.longOr(id, 0L));
            }
        }
        return out;
    }

    /** {@code _time}：主数据里是 {@code 2025-11-20 03:00:00}（UTC，无时区后缀）。 */
    private static Instant parseMasterTime(Object value) {
        String text = J.str(value);
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim().replace(' ', 'T');
        if (normalized.endsWith("Z")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            return LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code _active}：{@code StartTime <= now < EndTime}。 */
    private static boolean active(Map<String, Object> pool, Instant now) {
        Map<String, Object> master = J.nodeOrEmpty(pool.get("master"));
        Instant start = parseMasterTime(master.get("StartTime"));
        Instant end = parseMasterTime(master.get("EndTime"));
        if (start == null || end == null) {
            return false;
        }
        return !now.isBefore(start) && now.isBefore(end);
    }

    // ------------------------------------------------------------------
    // 账号状态 helper
    // ------------------------------------------------------------------

    private static Map<String, Object> stateRow(PlayerContext player, long seriesId) {
        for (Map<String, Object> row : player.rowsSnapshot("gacha_states")) {
            if (J.longOr(row.get("gacha_series_id"), Long.MIN_VALUE) == seriesId) {
                return row;
            }
        }
        return J.map("gacha_series_id", seriesId, "exec_count", 0, "paid_day", "",
                "paid_count", 0);
    }

    private static long pointOf(PlayerContext player, long exchangePointId) {
        for (Map<String, Object> row : player.rowsSnapshot("gacha_points")) {
            if (J.longOr(row.get("exchange_point_id"), Long.MIN_VALUE) == exchangePointId) {
                return J.longOr(row.get("num"), 0L);
            }
        }
        return 0L;
    }

    /** JST 04:00 换日后的日期串，对应 Python {@code day_key}。 */
    private static String dayKey(Instant now) {
        return now.atOffset(JST).minusHours(4).toLocalDate().toString();
    }

    /** 当日剩余的有偿单抽次数；非有偿池恒为 0。 */
    private static int paidRemaining(Map<String, Object> pool, Map<String, Object> state,
                                     Instant now) {
        Map<String, Object> price = J.nodeOrEmpty(pool.get("price"));
        if (!J.truthy(price.get("PaidSIsCaOnlyGachaFlag"))) {
            return 0;
        }
        int count = Objects.equals(J.str(state.get("paid_day")), dayKey(now))
                ? J.intOr(state.get("paid_count"), 0) : 0;
        return Math.max(0, J.intOr(price.get("LimitedPaidGachaCount"), 0) - count);
    }

    /** 池子通用券的持有数（{@code _ticket_count}）。 */
    private static int ticketCount(PlayerContext player, Map<String, Object> pool) {
        Object ident = pool.get("common_ticket_item_id");
        long itemId = J.longOr(ident, 0L);
        if (itemId == 0) {
            return 0;
        }
        int total = 0;
        for (Map<String, Object> group : player.rowsSnapshot("Items")) {
            for (Map<String, Object> row : J.rowsOr(group.get("UserItemList"))) {
                if (J.longOr(row.get("ItemId"), Long.MIN_VALUE) == itemId) {
                    total += J.intOr(row.get("ItemNum"), 0);
                }
            }
        }
        return total;
    }

    /** {@code _debit_tickets}：按顺序从各分组里扣券。 */
    private static void debitTickets(PlayerContext player, Map<String, Object> pool, int count) {
        long itemId = J.longOr(pool.get("common_ticket_item_id"), 0L);
        if (itemId == 0) {
            throw new WireError(501, "Missing verified common-ticket definition");
        }
        if (ticketCount(player, pool) < count) {
            throw WireError.badRequest("Not enough gacha tickets");
        }
        int left = count;
        for (Map<String, Object> group : player.rows("Items")) {
            for (Map<String, Object> row : J.rowsOr(group.get("UserItemList"))) {
                if (J.longOr(row.get("ItemId"), Long.MIN_VALUE) != itemId) {
                    continue;
                }
                int debit = Math.min(left, Math.max(0, J.intOr(row.get("ItemNum"), 0)));
                row.put("ItemNum", J.intOr(row.get("ItemNum"), 0) - debit);
                left -= debit;
            }
        }
        if (left != 0) {
            throw WireError.badRequest("Invalid gacha ticket inventory");
        }
    }

    /** 已持有的卡系列（抽卡去重按系列算，与 Python 一致）。 */
    private Set<Long> ownedSeries(PlayerContext player) {
        Map<String, Object> index = cardSeriesById();
        Set<Long> owned = new java.util.HashSet<>();
        for (Map<String, Object> card : player.rowsSnapshot("Cards")) {
            long series = J.longOr(index.get(String.valueOf(card.get("CardDatasId"))),
                    Long.MIN_VALUE);
            if (series != Long.MIN_VALUE) {
                owned.add(series);
            }
        }
        return owned;
    }

    /**
     * {@code _credit_item}：按 {@code ItemCategory} 归组、按 {@code ItemId} 累加，
     * 并按主数据 {@code LimitNum} 截断，返回实际记入的数量。
     */
    private static int creditItem(PlayerContext player, Map<String, Object> item, int quantity) {
        if (quantity <= 0 || item == null || item.isEmpty()) {
            return 0;
        }
        int category = J.intOr(item.get("ItemCategory"), 0);
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
        int limit = J.intOr(item.get("LimitNum"), 0);
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mutableRows(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        List<Map<String, Object>> created = new ArrayList<>();
        parent.put(key, created);
        return created;
    }

    /**
     * 抽卡扣 SIsCa：{@code JewelPaidGoogle} 已初始化时覆盖 {@code JewelPaid}，绝不相加。
     *
     * <p>Python 的判据是 {@code is not None}；数据库列不可空，用「非 0」近似，
     * 与 {@code GameHeaders} / 任务模块的既有约定一致。
     */
    /** Python {@code wire_gacha._debit_jewels}：商店结算也要用，因此包内可见。 */
    static void debitJewels(PlayerContext player, long cost, boolean paidOnly) {
        boolean google = player.entity().jewelPaidGoogle != 0;
        long free = player.entity().jewelFree;
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

    // ------------------------------------------------------------------
    // 读接口
    // ------------------------------------------------------------------

    /** 卡池列表，叠上本账号的抽取计数与保有点数。 */
    private List<Map<String, Object>> seriesRows(PlayerContext player, Instant now) {
        Set<String> labels = services.resourceLabels();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> pool : pools()) {
            long sid = J.longOr(pool.get("gacha_series_id"), 0L);
            Map<String, Object> price = J.nodeOrEmpty(pool.get("price"));
            if (!active(pool, now)) {
                continue;
            }
            if (labels != null && !labels.contains("image_gacha_banner_" + sid)) {
                continue;
            }
            Map<String, Object> state = stateRow(player, sid);
            int remaining = paidRemaining(pool, state, now);
            boolean enabled = !J.truthy(pool.get("purchase_block_reason"));
            out.add(J.map(
                    "gacha_series_id", sid,
                    "user_exchange_point_num", pointOf(player,
                            J.longOr(J.nodeOrEmpty(pool.get("master"))
                                    .get("GachaExchangePointId"), 0L)),
                    "user_series_exec_count", state.get("exec_count"),
                    "received_gacha_prize_list", receivedPrizes(player, sid),
                    "user_single_ticket_num", ticketCount(player, pool),
                    "user_consective_ticket_num", 0,
                    "is_single_enable", enabled && J.longOr(price.get("SingleGachaPrice"), 0L) > 0,
                    "is_consective_enable",
                    enabled && J.longOr(price.get("ConsectiveGachaPrice"), 0L) > 0,
                    "is_paid_enable", enabled && remaining > 0,
                    "single_campaign_id", 0,
                    "consective_campaign_id", 0,
                    "remaining_single_campaign_count", 0,
                    "remaining_consective_campaign_count", 0,
                    "remaining_limited_gacha_count", 0,
                    "remaining_limited_consective_gacha_count", 0,
                    "remaining_limited_paid_gacha_count", remaining,
                    "card_list", J.listOrEmpty(pool.get("card_list")),
                    "time_limit_gacha_end_time", null,
                    "remaining_rotation_count", 0));
        }
        return out;
    }

    private Map<String, Object> seriesList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        return J.map(
                "convert_exchange_point_list", WireServices.rows(player,
                        "gacha_point_conversions"),
                "expired_limited_gacha_ticket_id_confirm_list", WireServices.rows(player,
                        "expired_gacha_tickets"),
                "gacha_series_list", seriesRows(player, Instant.now()),
                "select_ticket_series_list", services.served("select_ticket_series",
                        List.of(SELECT_TICKET_ASSET)));
    }

    /** 抽取历史：按回执倒序，附带每条结果的 bonus 明细。 */
    private Map<String, Object> history(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> receipts =
                new ArrayList<>(player.rowsSnapshot("gacha_receipts"));
        java.util.Collections.reverse(receipts);
        for (Map<String, Object> receipt : receipts) {
            Map<Object, List<Map<String, Object>>> bonuses = new LinkedHashMap<>();
            for (Map<String, Object> bonus : J.rowsOr(receipt.get("bonuses"))) {
                bonuses.put(bonus.get("order_id"),
                        J.rowsOr(bonus.get("gacha_bonus_item_result_list")));
            }
            List<Map<String, Object>> results = new ArrayList<>();
            for (Map<String, Object> result : J.rowsOr(receipt.get("results"))) {
                List<Map<String, Object>> items = bonuses.getOrDefault(
                        result.get("order_id"), List.of());
                results.add(J.map("order_id", result.get("order_id"),
                        "card_datas_id", result.get("card_datas_id"),
                        "gacha_bonus_result_history_list",
                        WireServices.deepCopyJson(items)));
            }
            out.add(J.map("gacha_series_id", receipt.get("gacha_series_id"),
                    "gacha_date_time", receipt.get("gacha_date_time"),
                    "gacha_price_type", receipt.get("gacha_price_type"),
                    "gacha_price_num", receipt.get("gacha_price_num"),
                    "gacha_result_history_info_list", results));
        }
        return J.map("gacha_result_history_list", out);
    }

    /** 概率表：{@code groups} 与抽取时用的 {@code detail.rates} 逐个对应。 */
    private Map<String, Object> lotteryChance(WireRequest request) {
        long seriesId = WireServices.integer(request.body(), "gacha_series_id", (Long) null,
                1, Integer.MAX_VALUE);
        Map<String, Object> pool = findPool(seriesId);
        if (pool == null) {
            throw WireError.notFound("Local gacha probability definition not found");
        }
        List<Map<String, Object>> groups = J.rowsOr(pool.get("groups"));
        List<Map<String, Object>> rates = J.rowsOr(
                J.nodeOrEmpty(pool.get("detail")).get("rates"));
        List<Map<String, Object>> probabilities = new ArrayList<>();
        List<Map<String, Object>> gifts = new ArrayList<>();
        for (int index = 0; index < groups.size(); index++) {
            Map<String, Object> group = groups.get(index);
            Map<String, Object> source = index < rates.size() ? rates.get(index)
                    : new LinkedHashMap<>();
            String title = J.intOr(group.get("min"), 0) + "-" + J.intOr(group.get("max"), 0);

            List<Map<String, Object>> rarities = new ArrayList<>();
            for (Map<String, Object> rarity : J.rowsOr(group.get("rarities"))) {
                rarities.add(J.map("rarity", rarity.get("rarity"),
                        "prob", rarity.get("weight")));
            }
            List<Map<String, Object>> cardRows = J.rowsOr(group.get("cards"));
            List<Map<String, Object>> rawCards = J.rowsOr(source.get("cards"));
            List<Map<String, Object>> cardProbs = new ArrayList<>();
            for (int cardIndex = 0; cardIndex < cardRows.size(); cardIndex++) {
                Map<String, Object> row = cardRows.get(cardIndex);
                Map<String, Object> raw = cardIndex < rawCards.size()
                        ? rawCards.get(cardIndex) : new LinkedHashMap<>();
                cardProbs.add(J.map("card_datas_id", row.get("card_datas_id"),
                        "card_rarity", row.get("rarity"),
                        "card_name", raw.get("name"),
                        "prob", row.get("weight"),
                        "order_id", cardIndex + 1));
            }
            probabilities.add(J.map("title", title,
                    "gacha_prob_rarity_list", rarities,
                    "gacha_prob_card_list", cardProbs));

            List<Map<String, Object>> bonusRows = new ArrayList<>();
            List<Map<String, Object>> giftRows = J.rowsOr(group.get("gifts"));
            List<Map<String, Object>> rawItems = J.rowsOr(source.get("items"));
            for (int giftIndex = 0; giftIndex < giftRows.size(); giftIndex++) {
                Map<String, Object> row = giftRows.get(giftIndex);
                Map<String, Object> raw = giftIndex < rawItems.size()
                        ? rawItems.get(giftIndex) : new LinkedHashMap<>();
                bonusRows.add(J.map("grade", row.get("grade"),
                        "prob", row.get("weight"),
                        "gacha_bonus_item_list", new ArrayList<>(List.of(J.map(
                                "item_type", 1,
                                "item_id", row.get("item_id"),
                                "item_name", raw.get("name"),
                                "item_num", row.get("item_num"))))));
            }
            gifts.add(J.map("title", title, "gacha_bonus_list", bonusRows));
        }
        return J.map("gacha_prob_list", probabilities, "gacha_bonus_prob_list", gifts);
    }

    /** ガチャ詳細（WebView 用）：整份 {@code detail} 原样下发。 */
    private Map<String, Object> detail(WireRequest request) {
        long seriesId = WireServices.integer(request.body(), "gacha_series_id", (Long) null,
                1, Integer.MAX_VALUE);
        Map<String, Object> pool = findPool(seriesId);
        if (pool == null) {
            throw WireError.notFound("Local gacha probability definition not found");
        }
        return J.nodeOrEmpty(WireServices.deepCopyJson(pool.get("detail")));
    }

    /**
     * 交换所里每张候选卡的持有情况。
     *
     * <p>持有判定按系列（{@code gacha_exchange_cards} 的 {@code card_datas_id}）与
     * 账号 {@code Cards} 里的 {@code CardDatasId} 比对，与 Python 一致。
     */
    private Map<String, Object> exchangeCardHavingList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Set<Long> owned = new java.util.HashSet<>();
        for (Map<String, Object> card : player.rowsSnapshot("Cards")) {
            owned.add(J.longOr(card.get("CardDatasId"), Long.MIN_VALUE));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : services.served("gacha_exchange_cards",
                List.of(CARD_ASSET))) {
            out.add(J.map("card_datas_id", row.get("card_datas_id"),
                    "card_rarity", row.getOrDefault("card_rarity", ""),
                    "card_name", row.getOrDefault("card_name", ""),
                    "is_having", owned.contains(row.get("card_datas_id"))));
        }
        return J.map("exchange_card_list", out);
    }

    /** 天井（保有点数）兑换列表：{@code remaining_exchange_num = -1} 表示不限次。 */
    private Map<String, Object> guaranteePointList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> body = request.body();
        long exchangePointId = WireServices.integer(body, "exchange_point_id", (Long) null,
                1, Integer.MAX_VALUE);
        long seriesId = WireServices.integer(body, "gacha_series_id", (Long) null,
                1, Integer.MAX_VALUE);
        Map<String, Object> pool = findPool(seriesId);
        if (pool == null || J.longOr(J.nodeOrEmpty(pool.get("master"))
                .get("GachaExchangePointId"), Long.MIN_VALUE) != exchangePointId) {
            throw WireError.notFound("Local gacha exchange point definition not found");
        }
        Set<Long> owned = ownedSeries(player);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> rate : J.rowsOr(pool.get("exchange_rates"))) {
            out.add(J.map("exchange_point_rate_id", rate.get("Id"),
                    "remaining_exchange_num", UNLIMITED_EXCHANGE,
                    "is_having", owned.contains(J.longOr(rate.get("card_series_id"), 0L))));
        }
        return J.map("exchange_item_list", out);
    }

    // ------------------------------------------------------------------
    // 抽卡
    // ------------------------------------------------------------------

    private Map<String, Object> purchase(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> body = request.body();
        long seriesId = WireServices.integer(body, "gacha_series_id", (Long) null,
                1, Integer.MAX_VALUE);
        int kind = WireServices.integer(body, "gacha_set_purchase_type", (Integer) null, 1, 5);
        int campaign = WireServices.integer(body, "campaign_id", 0,
                0, Integer.MAX_VALUE);
        String key = requireIdempotencyKey(request,
                "x-idempotency-key is required for local gacha purchases");
        String fingerprint = fingerprint(seriesId, kind, campaign);

        Map<String, Object> replay = findReceipt(player, "gacha_receipts", key);
        if (replay != null) {
            if (!Objects.equals(replay.get("fingerprint"), fingerprint)) {
                throw WireError.conflict(
                        "Idempotency key already used for a different gacha purchase");
            }
            return purchaseResponse(player, replay, Instant.now());
        }
        if (campaign != 0) {
            throw new WireError(501,
                    "Gacha campaigns require a verified local campaign definition");
        }

        Instant now = Instant.now();
        Map<String, Object> pool = findPool(seriesId);
        if (pool == null || !containsPool(player, seriesId, now)) {
            throw WireError.notFound(
                    "Local gacha pool unavailable or outside its availability period");
        }
        if (J.truthy(pool.get("purchase_block_reason"))) {
            throw new WireError(501, String.valueOf(pool.get("purchase_block_reason")));
        }
        Map<String, Object> master = J.nodeOrEmpty(pool.get("master"));
        Map<String, Object> price = J.nodeOrEmpty(pool.get("price"));
        Map<String, Object> state = WireServices.copyRow(stateRow(player, seriesId));

        int count;
        int cost;
        int priceType;
        if (kind == 4 || kind == 5) {
            count = kind == 4 ? 1 : J.intOr(price.get("ConsectiveGachaTimes"), 0);
            cost = count;
            priceType = 3;
        } else if (kind == 3) {
            if (paidRemaining(pool, state, now) <= 0
                    || J.intOr(price.get("LimitedPaidGachaResetType"), 0) != 1) {
                throw WireError.badRequest("Paid daily gacha unavailable or already used today");
            }
            count = J.intOr(price.get("PaidSIsCaOnlyGachaTimes"), 0);
            cost = J.intOr(price.get("PaidSIsCaOnlyGachaPrice"), 0);
            priceType = 1;
        } else if (kind == 2) {
            count = J.intOr(price.get("ConsectiveGachaTimes"), 0);
            cost = J.intOr(price.get("ConsectiveGachaPrice"), 0);
            priceType = 2;
        } else {
            count = 1;
            cost = J.intOr(price.get("SingleGachaPrice"), 0);
            priceType = 2;
        }
        if (count < 1 || count > 10 || cost <= 0) {
            throw new WireError(501, "Missing supported local gacha price");
        }
        if (J.longOr(state.get("exec_count"), 0L) > Integer.MAX_VALUE - (long) count) {
            throw WireError.badRequest("Gacha execution counter limit reached");
        }

        Map<String, Object> before = begin(player);
        try {
            return settlePurchase(player, request.store(), pool, master, price, state, seriesId,
                    kind, count, cost, priceType, key, fingerprint, now);
        } catch (RuntimeException e) {
            rollback(player, before);
            throw e;
        }
    }

    private Map<String, Object> settlePurchase(PlayerContext player, PlayerStore store,
                                               Map<String, Object> pool,
                                               Map<String, Object> master,
                                               Map<String, Object> price,
                                               Map<String, Object> state, long seriesId,
                                               int kind, int count, int cost, int priceType,
                                               String key, String fingerprint, Instant now) {
        if (kind == 4 || kind == 5) {
            debitTickets(player, pool, cost);
        } else {
            debitJewels(player, cost, kind == 3);
        }
        Map<String, Object> cardIndex = cards();
        Map<String, Object> itemIndex = items();
        Set<Long> owned = ownedSeries(player);
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> bonuses = new ArrayList<>();
        for (int order = 1; order <= count; order++) {
            Map<String, Object> group = groupFor(pool, order);
            long cardId = drawCard(group);
            Map<String, Object> card = J.nodeOr(cardIndex.get(String.valueOf(cardId)));
            if (card == null) {
                throw new WireError(501, "Missing verified gacha card definition");
            }
            long series = J.longOr(card.get("card_series_id"), 0L);
            boolean isNew = !owned.contains(series);
            if (isNew) {
                mint.grant(player, cardId);
                owned.add(series);
            }
            long coinBefore = player.longOf("PetalCoinNum", 0L);
            long coinNum = isNew ? 0L
                    : Math.min(J.longOr(card.get("coin_num"), 0L),
                            Math.max(0L, COIN_LIMIT - coinBefore));
            if (coinNum > 0) {
                player.put("PetalCoinNum", coinBefore + coinNum);
            }

            long materialId = J.longOr(card.get("material_item_id"), 0L);
            int materialNum = isNew ? 0 : J.intOr(card.get("material_num"), 0);
            Map<String, Object> pickup = J.nodeOr(
                    J.nodeOrEmpty(pool.get("pickup_material_bonus")).get(String.valueOf(series)));
            int bonusQty = pickup == null ? 0 : J.intOr(pickup.get("quantity"), 0);
            if (bonusQty > 0) {
                if (J.longOr(pickup.get("material_item_id"), Long.MIN_VALUE) != materialId) {
                    throw new WireError(501,
                            "Pickup material bonus identity does not match this card");
                }
                materialNum += bonusQty;
            }
            materialNum = creditItem(player,
                    J.nodeOr(itemIndex.get(String.valueOf(materialId))), materialNum);
            results.add(J.map("order_id", order,
                    "card_datas_id", cardId,
                    "is_new", isNew,
                    "coin_num", coinNum,
                    "material_limitbreak_item_id", materialNum > 0 ? materialId : 0,
                    "material_limitbreak_num", materialNum));

            List<Map<String, Object>> gifts = J.rowsOr(group.get("gifts"));
            if (!gifts.isEmpty()) {
                Map<String, Object> gift = weightedChoice(gifts);
                long giftItem = J.longOr(gift.get("item_id"), 0L);
                int credited = creditItem(player,
                        J.nodeOr(itemIndex.get(String.valueOf(giftItem))),
                        J.intOr(gift.get("item_num"), 0));
                bonuses.add(J.map("order_id", order,
                        "grade", gift.get("grade"),
                        "gacha_bonus_item_result_list", new ArrayList<>(List.of(
                                J.map("item_type", 1, "item_id", giftItem,
                                        "item_num", credited)))));
            }
        }

        long exchangePointId = J.longOr(master.get("GachaExchangePointId"), 0L);
        long pointBefore = pointOf(player, exchangePointId);
        int gained = kind != 3 || J.truthy(price.get("PaidSIsCaOnlyGachaPointFlag")) ? count : 0;
        long pointAfter = pointBefore + gained;
        if (pointAfter > Integer.MAX_VALUE) {
            throw WireError.badRequest("Gacha exchange point limit reached");
        }
        List<Map<String, Object>> points = new ArrayList<>();
        for (Map<String, Object> row : player.rowsSnapshot("gacha_points")) {
            if (J.longOr(row.get("exchange_point_id"), Long.MIN_VALUE) != exchangePointId) {
                points.add(row);
            }
        }
        points.add(J.map("exchange_point_id", exchangePointId, "num", pointAfter));
        player.put("gacha_points", points);

        state.put("exec_count", J.intOr(state.get("exec_count"), 0) + count);
        if (kind == 3) {
            int used = Objects.equals(J.str(state.get("paid_day")), dayKey(now))
                    ? J.intOr(state.get("paid_count"), 0) : 0;
            state.put("paid_count", used + 1);
            state.put("paid_day", dayKey(now));
        }
        List<Map<String, Object>> states = new ArrayList<>();
        for (Map<String, Object> row : player.rowsSnapshot("gacha_states")) {
            if (J.longOr(row.get("gacha_series_id"), Long.MIN_VALUE) != seriesId) {
                states.add(row);
            }
        }
        states.add(state);
        player.put("gacha_states", states);

        Map<String, Object> receipt = J.map(
                "idempotency_key", key,
                "fingerprint", fingerprint,
                "gacha_series_id", seriesId,
                "purchase_type", kind,
                "gacha_date_time", RECEIPT_TIME.format(now.atOffset(ZoneOffset.UTC)),
                "gacha_price_type", priceType,
                "gacha_price_num", cost,
                "exchange_point_id", exchangePointId,
                "point_before", pointBefore,
                "point_after", pointAfter,
                "single_ticket_after", ticketCount(player, pool),
                "consective_ticket_after", 0,
                "results", results,
                "bonuses", bonuses);
        List<Map<String, Object>> receipts =
                new ArrayList<>(player.rowsSnapshot("gacha_receipts"));
        receipts.add(receipt);
        player.put("gacha_receipts", receipts);

        Map<String, Object> response = purchaseResponse(player, receipt, now);
        // 抽卡次数计入新手任务 100（按本次抽出张数累加）。
        shop.advance(player, WireShop.BEGINNER_CONDITION_TOTAL_GACHA, 0, results.size());
        store.saveWithIdempotency(player, key, "/v1/gacha/set_purchase",
                fingerprint, response);
        return response;
    }

    private boolean containsPool(PlayerContext player, long seriesId, Instant now) {
        for (Map<String, Object> row : seriesRows(player, now)) {
            if (J.longOr(row.get("gacha_series_id"), Long.MIN_VALUE) == seriesId) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> purchaseResponse(PlayerContext player, Map<String, Object> receipt,
                                                 Instant now) {
        return J.map(
                "gacha_result_list", WireServices.deepCopyJson(receipt.get("results")),
                "gacha_guarantee_point", J.map(
                        "exchange_point_id", receipt.get("exchange_point_id"),
                        "user_guarantee_point_num_before", receipt.get("point_before"),
                        "user_guarantee_point_num_after", receipt.get("point_after")),
                "gacha_bonus_result_list", WireServices.deepCopyJson(receipt.get("bonuses")),
                "user_single_ticket_num", receipt.get("single_ticket_after"),
                "user_consective_ticket_num", receipt.get("consective_ticket_after"),
                "gacha_campaign_info_list", new ArrayList<>(),
                "gacha_series_list", seriesRows(player, now));
    }

    private static Map<String, Object> groupFor(Map<String, Object> pool, int order) {
        Map<String, Object> found = null;
        int matches = 0;
        for (Map<String, Object> group : J.rowsOr(pool.get("groups"))) {
            int min = J.intOr(group.get("min"), 0);
            int max = J.intOr(group.get("max"), 0);
            if (min <= order && order <= max) {
                found = group;
                matches++;
            }
        }
        if (matches != 1 || found == null) {
            throw new WireError(501,
                    "Missing verified gacha probability for this draw position");
        }
        return found;
    }

    /** {@code _draw_card}：先按权重抽稀有度，再在该稀有度里按权重抽卡。 */
    private static long drawCard(Map<String, Object> group) {
        String rarity = String.valueOf(weightedChoice(J.rowsOr(group.get("rarities")))
                .get("rarity"));
        List<Map<String, Object>> pool = new ArrayList<>();
        for (Map<String, Object> row : J.rowsOr(group.get("cards"))) {
            if (rarity.equals(String.valueOf(row.get("rarity")))) {
                pool.add(row);
            }
        }
        if (pool.isEmpty()) {
            throw new WireError(501, "Missing verified gacha probability for this rarity");
        }
        return J.longOr(weightedChoice(pool).get("card_datas_id"), 0L);
    }

    private static Map<String, Object> weightedChoice(List<Map<String, Object>> rows) {
        long total = 0L;
        for (Map<String, Object> row : rows) {
            Object weight = row.get("weight");
            Long parsed = WireServices.strictLong(weight);
            if (parsed == null || parsed <= 0) {
                throw new WireError(501, "Invalid gacha weights");
            }
            total += parsed;
        }
        if (total <= 0) {
            throw new WireError(501, "Invalid gacha weights");
        }
        long position = ThreadLocalRandom.current().nextLong(total);
        for (Map<String, Object> row : rows) {
            position -= J.longOr(row.get("weight"), 0L);
            if (position < 0) {
                return row;
            }
        }
        throw new IllegalStateException("Unreachable weighted draw");
    }

    // ------------------------------------------------------------------
    // 交换
    // ------------------------------------------------------------------

    private Map<String, Object> selectTicketExchange(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        long rateId = WireServices.integer(request.body(), "select_ticket_exchange_rate_id",
                (Long) null, 1, Long.MAX_VALUE);
        String key = requireIdempotencyKey(request,
                "x-idempotency-key is required for local select ticket exchanges");
        String fingerprint = fingerprint(rateId);

        Map<String, Object> replay = findReceipt(player, "select_ticket_exchange_receipts", key);
        if (replay != null) {
            if (!Objects.equals(replay.get("fingerprint"), fingerprint)) {
                throw WireError.conflict(
                        "Idempotency key already used for a different select ticket exchange");
            }
            return J.map("receipt_items",
                    WireServices.deepCopyJson(replay.get("receipt_items")));
        }
        Map<String, Object> master = selectTicketMaster();
        Map<String, Object> rate = J.nodeOr(
                J.nodeOrEmpty(master.get("rates")).get(String.valueOf(rateId)));
        if (rate == null) {
            throw WireError.notFound("Local select ticket exchange rate not found");
        }
        Map<String, Object> series = J.nodeOr(J.nodeOrEmpty(master.get("series"))
                .get(String.valueOf(rate.get("SelectTicketSeriesId"))));
        if (series == null) {
            throw WireError.notFound("Local select ticket exchange rate not found");
        }
        Instant now = Instant.now();
        if (!within(series.get("StartTime"), series.get("EndTime"), now)) {
            throw WireError.notFound(
                    "Local select ticket exchange is outside its availability period");
        }
        long seriesId = J.longOr(series.get("Id"), 0L);
        Map<String, Object> balance = findTicketRow(player, seriesId);
        if (balance == null || J.longOr(balance.get("user_select_ticket_num"), 0L) < 1) {
            throw WireError.badRequest("Not enough select tickets");
        }

        Map<String, Object> before = begin(player);
        try {
            Map<String, Object> ticket = findTicketRow(player, seriesId);
            if (ticket == null) {
                ticket = J.map("select_ticket_series_id", seriesId,
                        "user_select_ticket_num", 0);
                player.rows("select_ticket_balances").add(ticket);
            }
            ticket.put("user_select_ticket_num",
                    J.longOr(ticket.get("user_select_ticket_num"), 0L) - 1);
            List<Map<String, Object>> items = exchangeRewards(player, rate);
            Map<String, Object> receipt = J.map(
                    "idempotency_key", key,
                    "fingerprint", fingerprint,
                    "select_ticket_exchange_rate_id", rateId,
                    "exchange_date_time", RECEIPT_TIME.format(now.atOffset(ZoneOffset.UTC)),
                    "receipt_items", items);
            List<Map<String, Object>> receipts =
                    new ArrayList<>(player.rowsSnapshot("select_ticket_exchange_receipts"));
            receipts.add(receipt);
            player.put("select_ticket_exchange_receipts", receipts);
            shop.advance(player, WireShop.BEGINNER_CONDITION_SELECT_TICKET);
            Map<String, Object> response = J.map("receipt_items",
                    WireServices.deepCopyJson(items));
            request.store().saveWithIdempotency(player, key,
                    "/v1/gacha/set_select_ticket_exchange", fingerprint, response);
            return response;
        } catch (RuntimeException e) {
            rollback(player, before);
            throw e;
        }
    }

    private Map<String, Object> guaranteePointExchange(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        long rateId = WireServices.integer(request.body(), "exchange_point_rate_id",
                (Long) null, 1, Integer.MAX_VALUE);
        String key = requireIdempotencyKey(request,
                "x-idempotency-key is required for local guarantee point exchanges");
        String fingerprint = fingerprint(rateId);

        Map<String, Object> replay = findReceipt(player, "gacha_point_exchange_receipts", key);
        if (replay != null) {
            if (!Objects.equals(replay.get("fingerprint"), fingerprint)) {
                throw WireError.conflict(
                        "Idempotency key already used for a different guarantee point exchange");
            }
            return J.map("receipt_items",
                    WireServices.deepCopyJson(replay.get("receipt_items")));
        }
        Object[] found = findExchangeRate(rateId);
        if (found == null) {
            throw WireError.notFound("Local gacha exchange rate not found");
        }
        Map<String, Object> pool = J.nodeOr(found[0]);
        Map<String, Object> rate = J.nodeOr(found[1]);
        if (J.truthy(rate.get("exchange_block_reason"))) {
            throw new WireError(501, String.valueOf(rate.get("exchange_block_reason")));
        }
        Instant now = Instant.now();
        if (!active(pool, now) || !within(rate.get("StartTime"), rate.get("EndTime"), now)) {
            throw WireError.notFound(
                    "Local gacha exchange is outside its availability period");
        }
        long exchangePointId = J.longOr(J.nodeOrEmpty(pool.get("master"))
                .get("GachaExchangePointId"), 0L);
        long price = J.longOr(rate.get("ExchangePrice"), 0L);
        long pointBefore = pointOf(player, exchangePointId);
        if (pointBefore < price) {
            throw WireError.badRequest("Not enough gacha exchange points");
        }

        Map<String, Object> before = begin(player);
        try {
            List<Map<String, Object>> points = new ArrayList<>();
            for (Map<String, Object> row : player.rowsSnapshot("gacha_points")) {
                if (J.longOr(row.get("exchange_point_id"), Long.MIN_VALUE) != exchangePointId) {
                    points.add(row);
                }
            }
            points.add(J.map("exchange_point_id", exchangePointId, "num", pointBefore - price));
            player.put("gacha_points", points);
            List<Map<String, Object>> items = exchangeRewards(player, rate);
            Map<String, Object> receipt = J.map(
                    "idempotency_key", key,
                    "fingerprint", fingerprint,
                    "exchange_point_rate_id", rateId,
                    "exchange_point_id", exchangePointId,
                    "point_before", pointBefore,
                    "point_after", pointBefore - price,
                    "exchange_date_time", RECEIPT_TIME.format(now.atOffset(ZoneOffset.UTC)),
                    "receipt_items", items);
            List<Map<String, Object>> receipts =
                    new ArrayList<>(player.rowsSnapshot("gacha_point_exchange_receipts"));
            receipts.add(receipt);
            player.put("gacha_point_exchange_receipts", receipts);
            Map<String, Object> response = J.map("receipt_items",
                    WireServices.deepCopyJson(items));
            request.store().saveWithIdempotency(player, key,
                    "/v1/gacha/set_guarantee_point_exchange", fingerprint, response);
            return response;
        } catch (RuntimeException e) {
            rollback(player, before);
            throw e;
        }
    }

    /**
     * 天井奖励领取 —— {@code GachaPrizes.GachaPlayCount} 是门槛，{@code exec_count} 是进度，
     * 三个字段都来自可核实的主数据与账号状态，不猜。
     *
     * <p>只接受「抽数已达门槛且这一档没领过」的请求；同一档重复领取回 400，
     * 与 Python 侧任务领奖的判定口径一致。
     */
    private Map<String, Object> setPrizeReceive(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        long seriesId = WireServices.integer(request.body(), "gacha_series_id", (Long) null,
                1, Integer.MAX_VALUE);
        long prizeId = WireServices.integer(request.body(), "gacha_prize_id", (Long) null,
                1, Long.MAX_VALUE);

        Map<String, Object> prize = prizesBySeries()
                .getOrDefault(seriesId, Map.of()).get(prizeId);
        if (prize == null) {
            throw WireError.notFound("Local gacha prize not found");
        }
        long execCount = J.longOr(stateRow(player, seriesId).get("exec_count"), 0L);
        if (execCount < J.longOr(prize.get("play_count"), Long.MAX_VALUE)) {
            throw WireError.badRequest("Gacha prize " + prizeId + " is not reachable yet");
        }
        List<Object> received = receivedPrizes(player, seriesId);
        if (received.contains(prizeId)) {
            throw WireError.badRequest("Gacha prize " + prizeId + " has already been received");
        }

        Map<String, Object> before = begin(player);
        try {
            for (Map<String, Object> item : J.rowsOr(prize.get("items"))) {
                shop.grantReward(player, J.map(
                        "ItemType", J.intOr(item.get("ItemType"), 0),
                        "ItemId", J.intOr(item.get("ItemId"), 0),
                        "Num", J.intOr(item.get("ItemNum"), 0)));
            }
            List<Map<String, Object>> receipts =
                    new ArrayList<>(player.rowsSnapshot("gacha_prize_receipts"));
            Map<String, Object> row = null;
            for (Map<String, Object> candidate : receipts) {
                if (J.longOr(candidate.get("gacha_series_id"), Long.MIN_VALUE) == seriesId) {
                    row = candidate;
                    break;
                }
            }
            if (row == null) {
                row = J.map("gacha_series_id", seriesId, "received_gacha_prize_list",
                        new ArrayList<>());
                receipts.add(row);
            }
            List<Object> owned = new ArrayList<>(J.listOrEmpty(
                    row.get("received_gacha_prize_list")));
            owned.add(prizeId);
            row.put("received_gacha_prize_list", owned);
            player.put("gacha_prize_receipts", receipts);
            request.store().save(player);
            return J.map("result", true);
        } catch (RuntimeException e) {
            rollback(player, before);
            throw e;
        }
    }

    /**
     * 兑换奖励：卡片（类型 2）走「新卡整套发、重复则发界限突破素材 + ペタルコイン」，
     * 其他类型直接加道具。回执形状与上游抓包一致。
     */
    private List<Map<String, Object>> exchangeRewards(PlayerContext player,
                                                      Map<String, Object> rate) {
        long kind = J.longOr(rate.get("ExchangeItemType"), 0L);
        long ident = J.longOr(rate.get("ExchangeItemId"), 0L);
        long quantity = J.longOr(rate.get("ExchangeItemQuantity"), 0L);
        List<Map<String, Object>> items = new ArrayList<>();
        if (kind != 2) {
            Map<String, Object> item = J.nodeOr(items().get(String.valueOf(ident)));
            if (item == null) {
                throw new WireError(501,
                        "Missing verified item definition for this exchange");
            }
            int credited = creditItem(player, item, (int) quantity);
            items.add(J.map("get_item_type", kind, "get_id", ident,
                    "get_num", credited, "is_new", false));
            return items;
        }
        int bonusQty = J.intOr(rate.get("BonusItemQuantity"), 0);
        long series = J.longOr(rate.get("card_series_id"), 0L);
        if (!ownedSeries(player).contains(series)) {
            mint.grant(player, ident);
            items.add(J.map("get_item_type", 2, "get_id", ident,
                    "get_num", quantity, "is_new", true));
            Map<String, Object> material = J.nodeOr(
                    items().get(String.valueOf(J.longOr(rate.get("material_item_id"), 0L))));
            int credited = material != null && bonusQty > 0
                    ? creditItem(player, material, bonusQty) : 0;
            appendMaterialReceipt(items, J.longOr(rate.get("material_item_id"), 0L), credited);
            return items;
        }
        Map<String, Object> material = J.nodeOr(
                items().get(String.valueOf(J.longOr(rate.get("material_item_id"), 0L))));
        int materialNum = material == null ? 0
                : creditItem(player, material,
                        J.intOr(rate.get("material_num"), 0) + bonusQty);
        appendMaterialReceipt(items, J.longOr(rate.get("material_item_id"), 0L), materialNum);
        long coinBefore = player.longOf("PetalCoinNum", 0L);
        long coinNum = Math.min(J.longOr(rate.get("coin_num"), 0L),
                Math.max(0L, COIN_LIMIT - coinBefore));
        if (coinNum > 0) {
            player.put("PetalCoinNum", coinBefore + coinNum);
            items.add(J.map("get_item_type", 3, "get_id", PETAL_COIN_ITEM_ID,
                    "get_num", coinNum, "is_new", false));
        }
        return items;
    }

    /** 同类素材的连续回执行合并成一条。 */
    private static void appendMaterialReceipt(List<Map<String, Object>> items, long materialId,
                                              int quantity) {
        if (quantity == 0) {
            return;
        }
        if (!items.isEmpty()) {
            Map<String, Object> last = items.get(items.size() - 1);
            if (J.longOr(last.get("get_item_type"), Long.MIN_VALUE) == 4
                    && J.longOr(last.get("get_id"), Long.MIN_VALUE) == materialId) {
                last.put("get_num", J.longOr(last.get("get_num"), 0L) + quantity);
                return;
            }
        }
        items.add(J.map("get_item_type", 4, "get_id", materialId,
                "get_num", quantity, "is_new", false));
    }

    private Object[] findExchangeRate(long rateId) {
        for (Map<String, Object> pool : pools()) {
            for (Map<String, Object> rate : J.rowsOr(pool.get("exchange_rates"))) {
                if (J.longOr(rate.get("Id"), Long.MIN_VALUE) == rateId) {
                    return new Object[] {pool, rate};
                }
            }
        }
        return null;
    }

    private static Map<String, Object> findTicketRow(PlayerContext player, long seriesId) {
        for (Map<String, Object> row : player.rowsSnapshot("select_ticket_balances")) {
            if (J.longOr(row.get("select_ticket_series_id"), Long.MIN_VALUE) == seriesId) {
                return row;
            }
        }
        return null;
    }

    private static Map<String, Object> findReceipt(PlayerContext player, String key,
                                                   String idempotencyKey) {
        for (Map<String, Object> row : player.rowsSnapshot(key)) {
            if (Objects.equals(J.str(row.get("idempotency_key")), idempotencyKey)) {
                return row;
            }
        }
        return null;
    }

    private static boolean within(Object start, Object end, Instant now) {
        Instant from = parseMasterTime(start);
        Instant to = parseMasterTime(end);
        if (from == null || to == null) {
            return false;
        }
        return !now.isBefore(from) && now.isBefore(to);
    }

    // ------------------------------------------------------------------
    // 幂等 / 原子性
    // ------------------------------------------------------------------

    private static String requireIdempotencyKey(WireRequest request, String message) {
        String key = request.header("x-idempotency-key");
        if (key == null || key.isBlank() || key.length() > 128) {
            throw WireError.badRequest(message);
        }
        return key;
    }

    /** 请求参数的指纹：同一幂等键换了参数要拒绝。 */
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

    /** 结算前的账号快照，对应 Python 的 {@code deepcopy(player)} 草稿。 */
    private static Map<String, Object> begin(PlayerContext player) {
        Map<String, Object> before = new LinkedHashMap<>();
        for (String key : SETTLED_KEYS) {
            Object value = player.raw(key);
            before.put(key, value == null ? null : WireServices.deepCopyJson(value));
        }
        com.linklike.server.domain.Player entity = player.entity();
        before.put("__jewelFree", entity.jewelFree);
        before.put("__jewelPaid", entity.jewelPaid);
        before.put("__jewelPaidGoogle", entity.jewelPaidGoogle);
        before.put("__musicPoint", entity.musicPoint);
        before.put("__stickerPointNum", entity.stickerPointNum);
        before.put("__fanLevel", entity.fanLevel);
        return before;
    }

    private static void rollback(PlayerContext player, Map<String, Object> before) {
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
        com.linklike.server.domain.Player entity = player.entity();
        entity.jewelFree = (Long) before.get("__jewelFree");
        entity.jewelPaid = (Long) before.get("__jewelPaid");
        entity.jewelPaidGoogle = (Long) before.get("__jewelPaidGoogle");
        entity.musicPoint = (Long) before.get("__musicPoint");
        entity.stickerPointNum = (Long) before.get("__stickerPointNum");
        entity.fanLevel = (Long) before.get("__fanLevel");
    }
}
