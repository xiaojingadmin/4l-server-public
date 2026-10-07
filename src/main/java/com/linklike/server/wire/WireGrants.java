package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import com.linklike.server.service.PlayerContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 结算奖励的发放路径 —— 对应 Python 版 {@code admin/grants.py} 里的
 * {@code receivable_item} / {@code grant_item}（以及它们用到的类型常量）。
 *
 * <p>目前只移植了「节奏游戏结算发奖」需要的那一段：{@code ItemType} 属于背包的行给出
 * 库存实例或钱包余额，其余行按「本地没有可核实的发放路径」拒绝（Python 抛
 * {@code ValueError}，调用方跳过该条奖励而不是整单失败）。发卡、表情、歌曲、邮件
 * （{@code grant_card} / {@code grant_sticker} / {@code grant_music} / {@code grant_present}）
 * 移植时继续往这里加。
 *
 * <h2>master 道具目录从哪来</h2>
 * Python 的 {@code catalog.Catalog()} 读 {@code master_items} 等表，这些表由
 * {@code admin/catalog.py} 从客户端资源缓存解码后写进数据库。Java 版还没有移植这条解码
 * 链路，所以 {@link #available()} 直接查同一个库（两边默认都是 {@code linkura_5_1_0}）
 * 里 Python 侧已提取好的 {@code master_items}：
 *
 * <ul>
 *   <li>表在：按它校验并发放，与 Python 行为一致；
 *   <li>表不在（例如只跑过 Java 版、库是空的）：{@link #available()} 返回 false，调用方
 *       据此返回 500 —— 与 Python 侧「{@code Catalog()} 构造不出来」时的同一句文案。
 *       这里不静默降级成「发空奖励」：结算成功但奖励没到账，客户端会显示一份拿不到的
 *       奖励清单。
 * </ul>
 *
 * <p>表是否存在只探测一次并缓存；{@code items} 每行按需查询（Python 里也是每次查一行，
 * {@code Catalog.item} 走 {@code SELECT * FROM master_items WHERE Id=?}）。
 */
@Component
public class WireGrants {

    private static final Logger log = LoggerFactory.getLogger(WireGrants.class);

    // Wire ItemType 枚举（dump.cs Org.OpenAPITools.Model.ItemType）。
    public static final int ITEM_TYPE_ITEM = 1;
    public static final int ITEM_TYPE_CARD = 2;
    public static final int ITEM_TYPE_RESOURCE = 3;
    public static final int ITEM_TYPE_LIMIT_BREAK = 4;

    /** 能进背包（{@code Items} 列表）的道具类型。 */
    public static final Set<Integer> INVENTORY_TYPES =
            Set.of(ITEM_TYPE_ITEM, ITEM_TYPE_RESOURCE, ITEM_TYPE_LIMIT_BREAK, 9);

    /**
     * 主表说「背包道具」、但本地记在账号标量钱包里的行 —— Python {@code WALLET_ITEMS}。
     *
     * <p>键是主数据道具 ID，值是账号状态键（{@link #WALLET_COLUMNS} 里的是独立列，
     * 其余在 {@code player_state} 里按 JSON 行存）。
     */
    public static final Map<Integer, String> WALLET_ITEMS = Map.of(
            1002001, "JewelFree",        // SIsCa
            1001001, "JewelPaid",        // SIsCa（有偿）
            3004000, "StylePoint",       // スタイルPt.
            3007001, "PetalCoinNum",     // ペタルコイン
            3009000, "MusicPoint",       // ミュージックPt.
            3009001, "StickerPointNum"); // ステッカー交換Pt.

    /** 在 {@code players} 表里有独立列的钱包键；其余钱包键存在 {@code player_state}。 */
    private static final Set<String> WALLET_COLUMNS =
            Set.of("JewelFree", "JewelPaid", "MusicPoint", "StickerPointNum");

    private static final String ITEM_QUERY =
            "SELECT Id, Name, ItemType, ItemCategory, Rarity, LimitNum "
                    + "FROM master_items WHERE Id = ?";

    private final JdbcTemplate jdbc;

    /** {@code master_items} 是否可用；null 表示还没探测过。 */
    private volatile Boolean masterAvailable;

    public WireGrants(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // master 目录
    // ------------------------------------------------------------------

    /**
     * master 道具目录是否可用。表不存在时返回 false 且记一条警告，调用方负责拒绝结算。
     *
     * <p>只探测一次：这张表要么在（Python 侧提取过），要么不在（这台机器没提取过），
     * 运行期间不会变。
     */
    public boolean available() {
        Boolean cached = masterAvailable;
        if (cached == null) {
            cached = probe();
            masterAvailable = cached;
        }
        return cached;
    }

    private boolean probe() {
        try {
            Long found = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = DATABASE() AND table_name = 'master_items'",
                    Long.class);
            if (found == null || found == 0) {
                log.warn("库里没有 master_items（Python 侧 admin/catalog.py 的提取产物）："
                        + "节奏游戏结算不发奖励，直接按「master catalog is unavailable」拒绝整单。");
                return false;
            }
            return true;
        } catch (DataAccessException e) {
            log.warn("查询 master_items 是否存在失败：{}", e.getMostSpecificCause().getMessage());
            return false;
        }
    }

    /** 主数据道具行；本地没有这一行时返回 null。 */
    public Map<String, Object> item(long itemId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(ITEM_QUERY, itemId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (DataAccessException e) {
            throw new IllegalStateException("读取 master_items 失败", e);
        }
    }

    // ------------------------------------------------------------------
    // 发放
    // ------------------------------------------------------------------

    /**
     * 校验一个道具 ID 能不能记进本地账号 —— 对应 Python {@code receivable_item}。
     *
     * <p>能记的返回主数据行；本地没有发放路径的（未知 ID、非背包类型、没有本地钱包的
     * 客户端资源）抛 {@link IllegalArgumentException}，调用方跳过这一条。
     */
    public Map<String, Object> receivableItem(long itemId) {
        Map<String, Object> item = item(itemId);
        if (item == null) {
            throw new IllegalArgumentException("Unknown item " + itemId);
        }
        int kind = J.intOr(item.get("ItemType"), 0);
        if (WALLET_ITEMS.containsKey((int) itemId)) {
            return item;
        }
        if (!INVENTORY_TYPES.contains(kind)) {
            throw new IllegalArgumentException(String.format(
                    "%s (ItemType %d) cannot be granted: no verified inventory path",
                    nameOf(item, itemId), kind));
        }
        if (kind == ITEM_TYPE_RESOURCE && J.intOr(item.get("ItemCategory"), 0) == 0) {
            throw new IllegalArgumentException(String.format(
                    "%s is a client-side resource without a local wallet", nameOf(item, itemId)));
        }
        return item;
    }

    /**
     * 把 {@code num} 个道具记进账号 —— 对应 Python {@code grant_item}。
     *
     * <p>钱包里的行（{@code WALLET_ITEMS}）只加余额；其余按 {@code ItemCategory} 归组进
     * {@code Items}，同 ID 的行累加数量并按主数据的 {@code LimitNum} 截断。
     *
     * @return 记进去的那一行（钱包行返回 {@code ItemId} / {@code ItemType} / {@code ItemNum} /
     *         {@code Wallet} 的摘要，与 Python 一致）
     */
    public Map<String, Object> grantItem(PlayerContext player, long itemId, int num) {
        Map<String, Object> item = receivableItem(itemId);
        if (num < 1) {
            throw new IllegalArgumentException("num must be >= 1");
        }
        String wallet = WALLET_ITEMS.get((int) itemId);
        if (wallet != null) {
            long value = walletValue(player, wallet) + num;
            setWallet(player, wallet, value);
            return J.map("ItemId", itemId, "ItemType", item.get("ItemType"),
                    "ItemNum", value, "Wallet", wallet);
        }
        int category = J.intOr(item.get("ItemCategory"), 0);
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
        if (row == null) {
            row = J.map("UserItemId", UUID.randomUUID().toString(),
                    "ItemId", itemId,
                    "ItemType", item.get("ItemType"),
                    "Rarity", item.get("Rarity"),
                    "ItemNum", 0,
                    "ResourceFileName", "");
            owned.add(row);
        }
        long total = J.longOr(row.get("ItemNum"), 0) + num;
        long limit = J.longOr(item.get("LimitNum"), 0);
        if (limit > 0) {
            total = Math.min(total, limit);
        }
        row.put("ItemNum", total);
        return row;
    }

    // ------------------------------------------------------------------
    // 钱包
    // ------------------------------------------------------------------

    /** 钱包余额；列式与 JSON 行的键都走这里。 */
    public static long walletValue(PlayerContext player, String key) {
        switch (key) {
            case "JewelFree":
                return player.entity().jewelFree;
            case "JewelPaid":
                return player.entity().jewelPaid;
            case "MusicPoint":
                return player.entity().musicPoint;
            case "StickerPointNum":
                return player.entity().stickerPointNum;
            default:
                return player.longOf(key, 0);
        }
    }

    /** 写钱包余额；列式键写实体字段，其余写账号状态（请求末尾统一落库）。 */
    public static void setWallet(PlayerContext player, String key, long value) {
        if (!WALLET_COLUMNS.contains(key)) {
            player.put(key, value);
            return;
        }
        switch (key) {
            case "JewelFree":
                player.entity().jewelFree = value;
                break;
            case "JewelPaid":
                player.entity().jewelPaid = value;
                break;
            case "MusicPoint":
                player.entity().musicPoint = value;
                break;
            default:
                player.entity().stickerPointNum = value;
                break;
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static String nameOf(Map<String, Object> item, long itemId) {
        String name = J.nonEmpty(item.get("Name"));
        return name == null ? String.valueOf(itemId) : name;
    }

    /**
     * 取节点下可写的行列表（缺失时建一个挂上去）。
     *
     * <p>不能用 {@code J.rowsOr}：那个返回的是新表，往里加行不会反映到账号状态里。
     */
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
}
