package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import com.linklike.server.resource.ClientDefaults;
import com.linklike.server.service.PlayerContext;
import com.linklike.server.service.RefCatalogStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * 商店 / 兑换目录的读取层 —— 对应 Python {@code wire_shop.py} 的
 * {@code overlay_sisca} / {@code overlay_stickers} / {@code overlay_gp} /
 * {@code overlay_item_exchanges} / {@code convert_list} / {@code ticket_list}。
 *
 * <p>条目定义与购买结算共用同一份 {@code client_defaults/shop_catalog.json}，所以列表里显示
 * 的「可买数量」不会和结算规则打架；本机若导入了抓包目录（{@code ref_sisca_products} 等），
 * 则以导入目录的行与顺序为准，与 Python 版一致。
 *
 * <p>限购口径照搬 Python：{@code ResetType} 0（不限）与 1-5（日 / 周 / 月 / 年 / 总量）按
 * {@code LimitNum} 封顶，1-4 用 04:00 JST 日界的周期键；6（剧情解锁）/ 7（歌曲解锁）的可买数
 * 是「还差多少解锁道具」，不是库存。这里只读账号状态，不写。
 */
@Component
public class WireStore {

    public static final int RESET_NOLIMIT = 0;
    public static final int RESET_DAY = 1;
    public static final int RESET_WEEK = 2;
    public static final int RESET_MONTH = 3;
    public static final int RESET_YEAR = 4;
    public static final int RESET_TOTAL = 5;
    public static final int RESET_ADV = 6;
    public static final int RESET_MUSIC = 7;

    public static final int UNLOCK_NONE = 0;
    public static final int UNLOCK_ITEM_NUM = 1;

    /** 观看状态：可观看 / 已看完（Python {@code WATCHABLE_ABLE} / {@code WATCHABLE_FINISHED}）。 */
    private static final Set<Integer> WATCHABLE_DONE = Set.of(1, 5);

    private static final Set<Integer> COUNTED_RESET =
            Set.of(RESET_DAY, RESET_WEEK, RESET_MONTH, RESET_YEAR, RESET_TOTAL);
    private static final Set<Integer> PERIOD_RESET =
            Set.of(RESET_DAY, RESET_WEEK, RESET_MONTH, RESET_YEAR);

    private static final ZoneOffset JST = WireServices.JST;

    private final ClientDefaults defaults;
    private final RefCatalogStore catalogs;

    private volatile Map<String, Object> catalog;

    public WireStore(ClientDefaults defaults, RefCatalogStore catalogs) {
        this.defaults = defaults;
        this.catalogs = catalogs;
    }

    public Map<String, Object> catalog() {
        Map<String, Object> cached = catalog;
        if (cached == null) {
            cached = defaults.object("shop_catalog").orElseGet(LinkedHashMap::new);
            catalog = cached;
        }
        return cached;
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    /** SIsCa 商店条目 —— Python {@code overlay_sisca}。 */
    public List<Map<String, Object>> siscaList(PlayerContext player, Instant now) {
        List<Map<String, Object>> states = player.rowsSnapshot("sisca_store_states");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : catalogRows("sisca_products", "sisca_store_datas_id")) {
            int ident = J.intOr(row.get("sisca_store_datas_id"), 0);
            Map<String, Object> out = new LinkedHashMap<>(row);
            out.put("own_num", WireShop.itemNum(player, rateInt(row, "ItemId")));
            out.put("buyable_num", siscaBuyable(row, player, ident, now));
            Map<String, Object> state = stateRow(states, "sisca_store_datas_id", ident);
            out.put("is_new_item", boolState(state, "is_new_item"));
            out.put("is_sale", boolState(state, "is_sale"));
            result.add(out);
        }
        return result;
    }

    /** 贴纸兑换 —— Python {@code overlay_stickers}。 */
    public List<Map<String, Object>> stickerList(PlayerContext player) {
        Set<Object> owned = new HashSet<>();
        for (Map<String, Object> sticker : player.rowsSnapshot("Stickers")) {
            owned.add(sticker.get("StickersId"));
        }
        Set<Object> having = new HashSet<>();
        for (Map<String, Object> state : player.rowsSnapshot("sticker_exchange_states")) {
            if (J.boolOr(state.get("is_having"), false)) {
                having.add(state.get("sticker_exchanges_id"));
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : catalogRows("sticker_exchanges", "sticker_exchanges_id")) {
            Map<String, Object> out = new LinkedHashMap<>(row);
            Object product = rate(row, "ProductStickerId");
            out.put("is_having", having.contains(row.get("sticker_exchanges_id"))
                    || (product != null && owned.contains(product)));
            result.add(out);
        }
        return result;
    }

    /** GP 奖品兑换 —— Python {@code overlay_gp}：已拥有同系列卡片时标记 {@code is_having}。 */
    public List<Map<String, Object>> gpList(PlayerContext player) {
        Set<Object> owned = ownedSeries(player);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : catalogRows("gp_prizes", "gp_prize_exchanges_id")) {
            Map<String, Object> out = new LinkedHashMap<>(row);
            Object series = rate(row, "card_series_id");
            out.put("is_having", J.boolOr(row.get("is_having"), false)
                    || (series != null && owned.contains(series)));
            result.add(out);
        }
        return result;
    }

    /** 道具兑换 —— Python {@code overlay_item_exchanges}。 */
    public List<Map<String, Object>> itemExchangeList(PlayerContext player, Instant now) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : catalogRows("item_exchanges", "item_exchanges_id")) {
            int ident = J.intOr(row.get("item_exchanges_id"), 0);
            Map<String, Object> out = new LinkedHashMap<>(row);
            out.put("user_product_item_num", WireShop.itemNum(player, rateInt(row, "ProductItemId")));
            out.put("user_material_item_num", WireShop.itemNum(player, rateInt(row, "MaterialItemId")));
            out.put("remaining_exchange_num",
                    exchangeRemaining(row, player, ident, Kind.ITEM, now));
            out.put("is_lock", locked(row, player, now));
            result.add(out);
        }
        return result;
    }

    /** 选择券兑换 —— Python {@code select_ticket_exchange/get_list}。 */
    public List<Map<String, Object>> selectTicketList(PlayerContext player) {
        List<Map<String, Object>> balances = player.rowsSnapshot("select_ticket_balances");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : selectTicketRows()) {
            int ident = J.intOr(row.get("select_ticket_series_id"), 0);
            Map<String, Object> out = new LinkedHashMap<>(row);
            Map<String, Object> state = stateRow(balances, "select_ticket_series_id", ident);
            out.put("user_select_ticket_num",
                    state == null ? 0 : J.intOr(state.get("user_select_ticket_num"), 0));
            result.add(out);
        }
        return result;
    }

    /** 极限突破素材存量（按稀有度汇总）—— Python {@code convert_list}。 */
    public List<Map<String, Object>> convertList(PlayerContext player) {
        Map<Integer, Long> buckets = new TreeMap<>();
        for (Map.Entry<String, Object> entry : J.nodeOrEmpty(catalog().get("lb_materials")).entrySet()) {
            long num = WireShop.itemNum(player, J.intOr(entry.getKey(), 0));
            if (num > 0) {
                buckets.merge(J.intOr(entry.getValue(), 0), num, Long::sum);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>(buckets.size());
        buckets.forEach((rarity, num) -> rows.add(J.map(
                "rarity", rarity, "limit_break_material_num", num)));
        return rows;
    }

    /** GP 兑换券余额 —— Python {@code ticket_list}。 */
    public List<Map<String, Object>> ticketList(PlayerContext player) {
        int ident = J.intOr(catalog().get("gp_prize_ticket_item_id"), 0);
        if (ident == 0) {
            return List.of();
        }
        int num = WireShop.itemNum(player, ident);
        return num == 0 ? List.of() : List.of(J.map("item_id", ident, "user_num", num));
    }

    // ------------------------------------------------------------------
    // 限购
    // ------------------------------------------------------------------

    /** Python {@code _sisca_buyable}：{@code -1} 表示不限购。 */
    public int siscaBuyable(Map<String, Object> rate, PlayerContext player, int ident, Instant now) {
        int reset = rateInt(rate, "ResetType");
        if (reset == RESET_ADV || reset == RESET_MUSIC) {
            return exchangeRemaining(rate, player, ident, Kind.SISCA, now);
        }
        if (limitCap(rate) <= 0) {
            return -1;
        }
        return remainingOf(rate, player, ident, Kind.SISCA, now);
    }

    /** Python {@code _exchange_remaining}。 */
    public int exchangeRemaining(Map<String, Object> rate, PlayerContext player, int ident,
                                 Kind kind, Instant now) {
        int reset = rateInt(rate, "ResetType");
        long itemId = rateInt(rate, kind == Kind.ITEM ? "ProductItemId" : "ItemId");
        if (reset == RESET_ADV) {
            return advBuyable(player, itemId, rateInt(rate, "AdvUnlockNeed"));
        }
        if (reset == RESET_MUSIC) {
            return musicBuyable(player, itemId, rateInt(rate, "MusicUnlockNeed"));
        }
        return remainingOf(rate, player, ident, kind, now);
    }

    /** Python {@code adv_buyable}：还差多少「剧情解锁道具」。 */
    public int advBuyable(PlayerContext player, long itemId, int need) {
        return Math.max(0, need - WireShop.itemNum(player, itemId) - advSpent(player, itemId));
    }

    /** Python {@code music_buyable}：还差多少 Music Pt.。 */
    public int musicBuyable(PlayerContext player, long itemId, int need) {
        return Math.max(0, need - WireShop.itemNum(player, itemId));
    }

    /** Python {@code adv_spent}：已解锁剧情花掉的同种解锁道具。 */
    public int advSpent(PlayerContext player, long itemId) {
        int total = 0;
        for (Object value : J.nodeOrEmpty(catalog().get("adv_unlocks")).values()) {
            Map<String, Object> spec = J.nodeOrEmpty(value);
            if (rateInt(spec, "AdvUnlockItemId") == itemId
                    && rateInt(spec, "AdvUnlockItemNum") > 0
                    && advUnlocked(player, J.intOr(spec.get("Id"), 0))) {
                total += rateInt(spec, "AdvUnlockItemNum");
            }
        }
        return total;
    }

    /** Python {@code adv_unlocked}：该话已可观看或已看完。 */
    public boolean advUnlocked(PlayerContext player, int advId) {
        for (Map<String, Object> month : player.rowsSnapshot("activity_records")) {
            for (Map<String, Object> row : J.rowsOr(month.get("adv_info_list"))) {
                if (J.intOr(row.get("adv_data_id"), 0) == advId) {
                    return WATCHABLE_DONE.contains(J.intOr(row.get("watchable_status"), 0));
                }
            }
        }
        return false;
    }

    /** Python {@code _locked}：前置条目还能兑换时视为锁定。 */
    public boolean locked(Map<String, Object> rate, PlayerContext player, Instant now) {
        if (rateInt(rate, "UnlockConditions") == UNLOCK_NONE) {
            return false;
        }
        Map<String, Object> prerequisite = J.nodeOrEmpty(
                J.nodeOrEmpty(catalog().get("item_exchanges"))
                        .get(String.valueOf(rateInt(rate, "UnlockConditionValue"))));
        if (prerequisite.isEmpty()) {
            return true;
        }
        return exchangeRemaining(prerequisite, player,
                J.intOr(prerequisite.get("Id"), 0), Kind.ITEM, now) > 0;
    }

    /** Python {@code _limit_cap}：0 表示不限购。 */
    public int limitCap(Map<String, Object> row) {
        int reset = rateInt(row, "ResetType");
        int limit = rateInt(row, "LimitNum");
        if (limit <= 0) {
            return 0;
        }
        if (COUNTED_RESET.contains(reset) || reset == RESET_NOLIMIT) {
            return limit;
        }
        return 0;
    }

    public int remainingOf(Map<String, Object> row, PlayerContext player, int ident, Kind kind,
                           Instant now) {
        int cap = limitCap(row);
        if (cap <= 0) {
            return 0;
        }
        String period = periodKey(row, now);
        if (!period.isEmpty()) {
            for (Map<String, Object> state : player.rowsSnapshot("shop_period_states")) {
                if (kind.code.equals(state.get("listing_kind"))
                        && J.intOr(state.get("listing_id"), -1) == ident) {
                    return period.equals(state.get("period_key"))
                            ? J.intOr(state.get("remaining"), 0) : cap;
                }
            }
            return cap;
        }
        String remainingKey = kind == Kind.ITEM ? "remaining_exchange_num" : "buyable_num";
        String stateKey = kind == Kind.ITEM ? "item_exchange_states" : "sisca_store_states";
        String idKey = kind == Kind.ITEM ? "item_exchanges_id" : "sisca_store_datas_id";
        for (Map<String, Object> state : player.rowsSnapshot(stateKey)) {
            if (J.intOr(state.get(idKey), -1) == ident) {
                return J.intOr(state.get(remainingKey), 0);
            }
        }
        return cap;
    }

    /** Python {@code _period_key}：04:00 JST 日界下的日 / 周 / 月 / 年键。 */
    public String periodKey(Map<String, Object> row, Instant now) {
        int reset = rateInt(row, "ResetType");
        if (!PERIOD_RESET.contains(reset)) {
            return "";
        }
        OffsetDateTime clock = (now == null ? Instant.now() : now)
                .atOffset(JST).minusHours(4);
        switch (reset) {
            case RESET_DAY:
                return "d:" + clock.toLocalDate();
            case RESET_WEEK:
                return String.format("w:%d-%02d",
                        clock.get(IsoFields.WEEK_BASED_YEAR),
                        clock.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            case RESET_MONTH:
                return String.format("m:%d-%02d", clock.getYear(), clock.getMonthValue());
            default:
                return "y:" + clock.getYear();
        }
    }

    /** 条目类型（Python {@code KIND_ITEM} / {@code KIND_SISCA}）。 */
    public enum Kind {
        ITEM("item_exchange"),
        SISCA("sisca");

        /** 账号状态里的 {@code listing_kind} 取值。 */
        public final String code;

        Kind(String code) {
            this.code = code;
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 目录行：优先用抓包导入的参考目录（顺序与客户端一致），没有导入时由
     * {@code shop_catalog} 生成，两种来源都用同一个 {@code Id} 键。
     */
    private List<Map<String, Object>> catalogRows(String catalogKey, String idKey) {
        List<Map<String, Object>> imported = new ArrayList<>();
        for (Map<String, Object> row : importedRows(catalogKey)) {
            if (row.get(idKey) != null) {
                imported.add(row);
            }
        }
        if (!imported.isEmpty()) {
            return imported;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Object> entry : J.nodeOrEmpty(catalog().get(catalogKey)).entrySet()) {
            Map<String, Object> spec = J.nodeOrEmpty(entry.getValue());
            if (spec.isEmpty()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(idKey, J.intOr(spec.get("Id"), J.intOr(entry.getKey(), 0)));
            row.putAll(spec);
            rows.add(row);
        }
        rows.sort(Comparator.comparingInt(row -> J.intOr(row.get(idKey), 0)));
        return rows;
    }

    /**
     * 选择券系列：与 Python 一致只下发抓包导入的目录行。没有导入目录时为空表 ——
     * 「券种定义」与「账号余额」是两回事，凭空生成券种会让客户端显示并不存在的兑换活动。
     */
    private List<Map<String, Object>> selectTicketRows() {
        return catalogRows("select_ticket_series", "select_ticket_series_id");
    }

    private List<Map<String, Object>> importedRows(String catalogKey) {
        List<Map<String, Object>> rows = catalogs.list(catalogKey);
        return rows == null ? List.of() : rows;
    }

    private static Map<String, Object> stateRow(List<Map<String, Object>> rows, String key,
                                                int ident) {
        for (Map<String, Object> row : rows) {
            if (J.intOr(row.get(key), -1) == ident) {
                return row;
            }
        }
        return null;
    }

    private static boolean boolState(Map<String, Object> state, String key) {
        return state != null && J.boolOr(state.get(key), false);
    }

    /** 账号已拥有的卡系列集合（Python {@code _owned_series}）。 */
    public Set<Object> ownedSeries(PlayerContext player) {
        Map<String, Object> index = J.nodeOrEmpty(catalog().get("card_series_by_id"));
        Set<Object> owned = new HashSet<>();
        for (Map<String, Object> card : player.rowsSnapshot("Cards")) {
            Object ident = card.get("CardDatasId");
            if (ident != null && index.containsKey(String.valueOf(ident))) {
                owned.add(index.get(String.valueOf(ident)));
            }
        }
        return owned;
    }

    /**
     * 条目里的结算字段。抓包目录只含线上字段，结算字段（{@code ProductItemId} 等）在
     * {@code shop_catalog} 的同 {@code Id} 行里。
     */
    private Object rate(Map<String, Object> row, String key) {
        if (row.containsKey(key)) {
            return row.get(key);
        }
        return rateSpec(row).get(key);
    }

    private int rateInt(Map<String, Object> row, String key) {
        Object value = rate(row, key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static int rateInt(Map<String, Object> row, String key, int fallback) {
        Object value = row.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private Map<String, Object> rateSpec(Map<String, Object> row) {
        int ident = J.intOr(row.get("Id"), 0);
        for (String table : List.of("sisca_products", "item_exchanges", "gp_prizes",
                "sticker_exchanges")) {
            Map<String, Object> spec = J.nodeOrEmpty(
                    J.nodeOrEmpty(catalog().get(table)).get(String.valueOf(ident)));
            if (!spec.isEmpty()) {
                return spec;
            }
        }
        return Map.of();
    }
}
