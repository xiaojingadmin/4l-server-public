package com.linklike.server.wire;

import com.linklike.server.config.ServerProperties;
import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireCodec;
import com.linklike.server.service.PlayerContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 商店 / 兑换 / 章节首页 / 关注推荐 / 收藏画廊的读取接口 —— 对应 Python
 * {@code wire_services.py} 与 {@code wire_account.py} 里对应的分支。
 *
 * <p>这些分支在 Python 版都直接返回 snake_case 结构（不调用 {@code encode}），因此这里同样
 * 直接拼线上字段名，不再走 {@link WireCodec}：目录行本身已经是线上形态，走模型编码会把
 * 目录里模型没声明的字段（兑换价、限购、解锁条件）裁掉。
 */
@Component
public class WireStoreModule implements WireModule {

    private final WireStore store;
    private final WireServices services;
    private final ServerProperties config;

    public WireStoreModule(WireStore store, WireServices services, ServerProperties config) {
        this.store = store;
        this.services = services;
        this.config = config;
    }

    @Override
    public void register(WireRouter router) {
        router.register("/v1/shop/get_list", this::shopList);
        router.register("/v1/sisca_store/get_list", this::siscaList);
        router.register("/v1/sticker_exchange/get_list", this::stickerList);
        router.register("/v1/gp_prize_exchange/get_list", this::gpList);
        router.register("/v1/item_exchange/get_list", this::itemExchangeList);
        router.register("/v1/item_exchange/get_list_new", this::itemExchangeList);
        router.register("/v1/item_exchange/get_limit_break_material_convert_list",
                this::convertList);
        router.register("/v1/select_ticket_exchange/get_list", this::selectTicketList);
        router.register("/v1/petal_exchange/get_list", this::petalList);

        router.register("/v1/chapter/home", this::chapterHome);
        router.register("/v1/follow/recommend_list", this::recommendList);
        router.register("/v1/collection/get_gallary_list", this::galleryList);
    }

    // ------------------------------------------------------------------
    // 商店 / 兑换
    // ------------------------------------------------------------------

    /** Python {@code shop/get_list}：运营导入的商店入口，未导入时为空表。 */
    private Map<String, Object> shopList(WireRequest request) {
        request.requirePlayer();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : services.refs("shops")) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("shop_type", row.getOrDefault("shop_type", 0));
            out.put("name", row.getOrDefault("name", ""));
            out.put("order_id", row.getOrDefault("order_id", 0));
            out.put("is_maintenance", row.getOrDefault("is_maintenance", false));
            out.put("is_exist_products", row.getOrDefault("is_exist_products", false));
            rows.add(out);
        }
        return J.map("shop_list", rows, "is_convertible_limit_break_material", false);
    }

    private Map<String, Object> siscaList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        return J.map("item_list", store.siscaList(player, Instant.now()));
    }

    private Map<String, Object> stickerList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        return J.map(
                "sticker_point_num", WireGrants.walletValue(player, "StickerPointNum"),
                "sticker_exchange_list", store.stickerList(player));
    }

    private Map<String, Object> gpList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        return J.map(
                "user_ticket_list", store.ticketList(player),
                "gp_prize_exchange_list", store.gpList(player));
    }

    private Map<String, Object> itemExchangeList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        return J.map(
                "item_exchange_list", store.itemExchangeList(player, Instant.now()),
                "is_convertible_limit_break_material", false);
    }

    private Map<String, Object> convertList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        return J.map("convert_limit_break_material_list", store.convertList(player));
    }

    private Map<String, Object> selectTicketList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        return J.map("select_ticket_exchange_list", store.selectTicketList(player));
    }

    /**
     * Python {@code _petal_list}：目录行叠账号的兑换次数，并把已拥有卡片的突破数覆盖到
     * {@code limit_break_times}（目录里存的是导入时的抽样值）。
     */
    private Map<String, Object> petalList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        List<Map<String, Object>> groups = WireServices.overlayNested(
                services.refs("petal_exchange_catalog"),
                WireServices.rows(player, "petal_exchanges"),
                "character_id", "limit_break_exchange_list", "card_series_id",
                Set.of("total_num", "limit_break_times", "item_num"), Set.of());

        Map<String, Map<String, Object>> owned = new LinkedHashMap<>();
        for (Map<String, Object> card : player.rowsSnapshot("Cards")) {
            if (card.get("CardDatasId") != null) {
                owned.put(String.valueOf(card.get("CardDatasId")), card);
            }
        }
        for (Map<String, Object> group : groups) {
            for (Map<String, Object> row : J.rowsOr(group.get("limit_break_exchange_list"))) {
                Map<String, Object> card = owned.get(String.valueOf(row.get("card_datas_id")));
                if (card != null && card.get("LimitBreakTimes") != null) {
                    row.put("limit_break_times", card.get("LimitBreakTimes"));
                }
            }
        }
        return J.map(
                "petal_coin_num", WireGrants.walletValue(player, "PetalCoinNum"),
                "character_petal_exchange_list", groups);
    }

    // ------------------------------------------------------------------
    // 章节首页 / 关注推荐 / 收藏画廊
    // ------------------------------------------------------------------

    /**
     * Python {@code chapter/home}：本地没有章节点数与排名的来源，因此排名固定为 0、
     * 分类列表直接用账号自己的 {@code chapter_categories} 行（与 Python 完全相同）。
     */
    private Map<String, Object> chapterHome(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        return J.map(
                "chapter_id", 0,
                "rank_info", J.map(
                        "current_rank", 0L, "current_point", 0L, "is_max_rank", false),
                "category_list", player.rowsSnapshot("chapter_categories"));
    }

    /** Python {@code follow/recommend_list}：本地没有推荐排行，上限与关注列表同一条配置。 */
    private Map<String, Object> recommendList(WireRequest request) {
        request.requirePlayer();
        return J.map(
                "RecommendInfoList", new ArrayList<>(),
                "MaxRecommendCount", (long) config.getFollowLimit());
    }

    /** Python {@code collection/get_gallary_list}：导入画册目录 + 账号的阅读状态。 */
    private Map<String, Object> galleryList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        return J.map("gallary_info_list", WireServices.overlay(
                services.served("gallery", WireServices.COMIC_ASSETS),
                player.rowsSnapshot("gallery_entries"), "comics_id"));
    }
}
