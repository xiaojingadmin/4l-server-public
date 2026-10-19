package com.linklike.server.wire;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklike.server.domain.Player;
import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireCodec;
import com.linklike.server.protocol.WireError;
import com.linklike.server.config.ServerProperties;
import com.linklike.server.resource.ClientDefaults;
import com.linklike.server.resource.ResourceCatalogLabels;
import com.linklike.server.service.PlayerContext;
import com.linklike.server.service.PlayerStore;
import com.linklike.server.service.RefCatalogStore;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code wire_account.py} 的移植：首页设置、公告、背包/推送、礼物盒、资料设置、
 * 收藏浏览、商店目录读取与章节读取。
 *
 * <p>所有写入都只改本账号自己的状态；现实货币购买、点数库存和奖励领取仍显式返回 501
 * （与 Python 版同样的文案），不伪造结算成功。
 *
 * <p>两处依赖本机运营导入的参考目录：公告（{@code announces}）与商店条目
 * （{@code item_stores}/{@code jewel_shop_items}/{@code memberships}）。目录为空时下发空
 * 列表，不编造内容。{@code /v1/item_exchange/*} 的限购结算在 {@link WireShopModule}。
 *
 * <p>{@code /v1/common/get_header_announs} 在 Python 版返回的是 JSON 数组，所以这里是
 * 少数返回 {@code Object}（列表）而非 {@code Map} 的 handler。
 */
@Component
public class WireAccountModule implements WireModule {

    private static final Logger log = LoggerFactory.getLogger(WireAccountModule.class);

    private static final int MAX_TEXT = 8192;
    private static final int MAX_ID_LENGTH = 128;
    private static final int PAGE_SIZE = 20;
    private static final int DECORATION_CARD = 1;
    private static final String CARD_HOME_MOVIE = "picture_ur_home_%d.usm";

    private static final String UNIMPLEMENTED_MESSAGE =
            "Real-money purchases, point-stock use and reward claims require verified "
                    + "pricing, eligibility and atomic settlement rules; no state was changed";

    private static final List<String> UNIMPLEMENTED_WRITES = List.of(
            "/v1/profile/use_fanlevel_point_stocks",
            "/v1/jewel_shop/set_purchase",
            "/v1/jewel_shop/set_membership_purchase",
            "/v1/chapter/claim_point_reward",
            "/v1/chapter/claim_point_rewards",
            "/v1/chapter/receive_mission_point",
            "/v1/chapter/receive");

    private static final DateTimeFormatter BIRTHDAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
    private static final DateTimeFormatter MICRO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    private final WireCodec codec;
    private final WireShop shop;
    private final WireCatalog catalog;
    private final WireServices services;
    private final RefCatalogStore catalogs;
    private final PlayerStore players;
    private final ResourceCatalogLabels resourceLabels;
    private final WireGrants grants;
    private final ClientDefaults defaults;
    private final ServerProperties config;
    private final ObjectMapper mapper;

    public WireAccountModule(WireCodec codec, WireShop shop, WireCatalog catalog,
                             WireServices services, RefCatalogStore catalogs,
                             PlayerStore players, ResourceCatalogLabels resourceLabels,
                             WireGrants grants, ClientDefaults defaults,
                             ServerProperties config, ObjectMapper mapper) {
        this.codec = codec;
        this.shop = shop;
        this.catalog = catalog;
        this.services = services;
        this.catalogs = catalogs;
        this.players = players;
        this.resourceLabels = resourceLabels;
        this.grants = grants;
        this.defaults = defaults;
        this.config = config;
        this.mapper = mapper;
    }

    /** 物品主表回退缓存：{@code client_defaults/master_items.json} 的 Id -> 行。 */
    private volatile Map<Long, Map<String, Object>> fallbackItems;
    private volatile boolean fallbackLoaded;
    private volatile boolean fallbackQueryWarned;

    @Override
    public void register(WireRouter router) {
        router.register("/v1/home/get_wallpaper_setting", this::getWallpaperSetting);
        router.register("/v1/home/set_wallpaper_setting", this::setWallpaperSetting);
        router.register("/v1/home/set_current_wallpaper_setting", this::setCurrentWallpaper);
        router.register("/v1/home/notify_wallpaper_setting", this::notifyWallpaper);
        router.register("/v1/home/set_clock_setting", this::setClockSetting);
        router.register("/v1/home/set_show_retire", this::setShowRetire);

        router.register("/v1/common/get_header_announs", this::headerAnnounces);
        router.register("/v1/announces/list", this::announceList);
        router.register("/v1/announce/list", this::announceList);
        router.register("/v1/announce/detail", this::announceDetail);

        router.register("/v1/user/item/get_detail", this::itemDetail);
        router.register("/v1/user/items/get_detail", this::itemDetail);
        router.register("/v1/user/jewel/get_history", this::jewelHistory);
        router.register("/v1/user/push/device", this::pushDevice);
        router.register("/v1/user/push/devices", this::pushDevice);
        router.register("/v1/user/set_contents_release_effect_history",
                this::contentsReleaseHistory);

        router.register("/v1/present_box/get_list", this::presentList);
        router.register("/v1/present_box/get_history", this::presentHistory);
        router.register("/v1/present_box/item_detail", this::presentDetail);

        router.register("/v1/profile/set_profile_icon", this::setProfileIcon);
        router.register("/v1/profile/set_profile_card", this::setProfileCard);
        router.register("/v1/profile/set_birthday", request -> setBirthday(request,
                "ProfileSetBirthdayResponse"));
        router.register("/v1/profile/set_my_design_name", this::setMyDesignName);
        router.register("/v1/profile/delete_my_design", this::deleteMyDesign);
        router.register("/v1/profile/get_my_design_icon", this::getMyDesignIcon);
        router.register("/v1/profile/get_fan_level_ranking", this::fanLevelRanking);
        router.register("/v1/profile/set_mute", this::setMute);
        router.register("/v1/profile/set_mute_cancel", this::setMuteCancel);
        router.register("/v1/profile/get_mute_list", this::getMuteList);
        router.register("/v1/profile/set_report", this::setReport);

        router.register("/v1/collection/get_emoji_list", this::emojiList);
        router.register("/v1/collection/set_gallary_data", this::setGalleryData);
        router.register("/v1/collection/set_music_play", this::setMusicPlay);

        router.register("/v1/item_store/get_list", this::itemStoreList);
        router.register("/v1/jewel_shop/get_list", this::jewelShopList);
        router.register("/v1/jewel_shop/get_membership_list", this::membershipList);
        router.register("/v1/jewel_shop/get_birthday", this::jewelBirthday);
        router.register("/v1/jewel_shop/set_birthday", request -> setBirthday(request,
                "JewelShopSetBirthdayResponse"));

        router.register("/v1/gacha/confirm_ticket_expired_time", this::confirmTicketExpired);
        router.register("/v1/account/get_connect_data", this::connectData);
        router.register("/v1/chapter/category_info", this::chapterCategory);
        router.register("/v1/chapter/get_point_rewards", this::pointRewardsWithCurrentPoint);
        router.register("/v1/chapter/get_point_rewards_list", request -> pointRewards(request,
                "ChapterGetPointRewardsListResponse"));

        for (String path : UNIMPLEMENTED_WRITES) {
            router.register(path, request -> {
                throw new WireError(501, UNIMPLEMENTED_MESSAGE);
            });
        }
    }

    // ------------------------------------------------------------------
    // 首页设置
    // ------------------------------------------------------------------

    private Map<String, Object> getWallpaperSetting(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : wallpapers(player)) {
            rows.add(wallpaperModel(row));
        }
        return encode("HomeGetWallpaperSettingResponse", J.map(
                "CurrentHomeWallPaperSettingsId",
                player.str("CurrentWallpaperSettingsId", ""),
                "WallPaperSettingList", rows));
    }

    private Map<String, Object> setWallpaperSetting(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = optionalText(request.body(), "d_home_wall_paper_settings_id",
                MAX_ID_LENGTH);
        String info = requiredText(request.body(), "wallpaper_setting_info", MAX_TEXT);
        List<Map<String, Object>> rows = wallpapers(player);
        if (!ident.isEmpty()) {
            Map<String, Object> row = null;
            for (Map<String, Object> candidate : rows) {
                if (ident.equals(candidate.get("d_home_wall_paper_settings_id"))) {
                    row = candidate;
                    break;
                }
            }
            if (row == null) {
                throw new WireError(404, "Local wallpaper setting not found");
            }
            row.put("wallpaper_setting_info", info);
        } else {
            ident = UUID.randomUUID().toString();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("d_home_wall_paper_settings_id", ident);
            row.put("wallpaper_setting_info", info);
            rows.add(row);
        }
        player.put("wallpaper_settings", rows);
        if (player.str("CurrentWallpaperSettingsId", "").isEmpty()) {
            player.put("CurrentWallpaperSettingsId", ident);
        }
        settleWallpaperMissions(player, info);
        request.store().save(player);
        return encode("HomeSetWallpaperSettingResponse", J.map("Result", true));
    }

    private Map<String, Object> setCurrentWallpaper(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body(), "d_home_wall_paper_settings_id");
        Map<String, Object> found = null;
        for (Map<String, Object> row : wallpapers(player)) {
            if (ident.equals(row.get("d_home_wall_paper_settings_id"))) {
                found = row;
                break;
            }
        }
        if (found == null) {
            throw new WireError(404, "Local wallpaper setting not found");
        }
        player.put("CurrentWallpaperSettingsId", ident);
        settleWallpaperMissions(player,
                String.valueOf(found.getOrDefault("wallpaper_setting_info", "")));
        request.store().save(player);
        return encode("HomeSetCurrentWallpaperSettingResponse", J.map("Result", true));
    }

    private Map<String, Object> notifyWallpaper(WireRequest request) {
        request.requirePlayer();
        for (String key : List.of("wallpaper_detail_setting_info",
                "wallpaper_simple_setting_info", "before_clock_setting_info",
                "after_clock_setting_info")) {
            optionalText(request.body(), key, MAX_TEXT);
        }
        optionalBool(request.body(), "is_view_detail_wallpaper");
        return new LinkedHashMap<>();
    }

    private Map<String, Object> setClockSetting(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> body = request.body();
        Map<String, Object> previous = player.nodeOrEmpty("ClockSetting");

        String ident = optionalText(body, "d_home_clock_settings_id", MAX_ID_LENGTH);
        if (ident.isEmpty()) {
            ident = String.valueOf(previous.getOrDefault("DHomeClockSettingsId", ""));
        }
        if (ident.isEmpty()) {
            ident = UUID.randomUUID().toString();
        }
        Map<String, Object> setting = new LinkedHashMap<>();
        setting.put("DHomeClockSettingsId", ident);
        setting.put("PositionX", (int) WireServices.integer(body, "position_x", 0,
                -100_000, 100_000));
        setting.put("PositionY", (int) WireServices.integer(body, "position_y", 0,
                -100_000, 100_000));
        setting.put("IsBtnDispOn", optionalBool(body, "is_btn_disp_on"));
        setting.put("IsDispOn", optionalBool(body, "is_disp_on"));
        setting.put("TypeId", (int) WireServices.integer(body, "type_id", 0,
                0, Integer.MAX_VALUE));
        setting.put("CollorId", (int) WireServices.integer(body, "collor_id", 0,
                0, Integer.MAX_VALUE));

        if (Boolean.TRUE.equals(setting.get("IsDispOn"))) {
            shop.advance(player, WireShop.BEGINNER_CONDITION_DISPLAY_CLOCK);
        }
        long previousType = J.longOr(previous.get("TypeId"), 0);
        long previousColor = J.longOr(previous.get("CollorId"), 0);
        if ((int) setting.get("TypeId") != previousType
                || (int) setting.get("CollorId") != previousColor) {
            shop.advance(player, WireShop.BEGINNER_CONDITION_CLOCK_DESIGN);
        }
        player.put("ClockSetting", setting);
        request.store().save(player);
        return encode("HomeSetClockSettingResponse", J.map("Result", true));
    }

    private Map<String, Object> setShowRetire(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        player.put("ShowRetire", true);
        request.store().save(player);
        return encode("HomeSetShowRetireResponse", J.map("Result", true));
    }

    private List<Map<String, Object>> wallpapers(PlayerContext player) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : WireServices.rows(player, "wallpaper_settings")) {
            String ident = stringOr(row, "", "d_home_wall_paper_settings_id",
                    "DHomeWallPaperSettingsId");
            String info = stringOr(row, "", "wallpaper_setting_info",
                    "WallpaperSettingInfo");
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("d_home_wall_paper_settings_id", ident);
            normalized.put("wallpaper_setting_info", info);
            rows.add(normalized);
        }
        return rows;
    }

    private static Map<String, Object> wallpaperModel(Map<String, Object> row) {
        return J.map(
                "DHomeWallPaperSettingsId", row.get("d_home_wall_paper_settings_id"),
                "WallpaperSettingInfo", row.get("wallpaper_setting_info"));
    }

    private void settleWallpaperMissions(PlayerContext player, String info) {
        shop.advance(player, WireShop.BEGINNER_CONDITION_WALLPAPER_CHANGE);
        if (animationCardWallpaper(player, info)) {
            shop.advance(player, WireShop.BEGINNER_CONDITION_ANIMATION_CARD_WALLPAPER);
        }
    }

    /**
     * 壁纸是否放了「本账号拥有、且下发资源里存在 UR home movie」的卡。
     * 对应 Python {@code _animation_card_wallpaper}；无法核实（没有解码目录）时返回 false。
     */
    private boolean animationCardWallpaper(PlayerContext player, String info) {
        Set<String> labels = resourceLabels.current();
        if (labels == null) {
            return false;
        }
        Set<Long> owned = new java.util.HashSet<>();
        for (Map<String, Object> card : player.rowsSnapshot("Cards")) {
            Long ident = strictLong(card.get("CardDatasId"));
            if (ident != null) {
                owned.add(ident);
            }
        }
        for (Long cardId : wallpaperCardIds(info)) {
            if (owned.contains(cardId)
                    && labels.contains(String.format(CARD_HOME_MOVIE, cardId))) {
                return true;
            }
        }
        return false;
    }

    /** 壁纸 JSON 里引用的 CardDatas ID；两种编辑器载荷都接受。 */
    private Set<Long> wallpaperCardIds(String info) {
        Set<Long> ids = new java.util.HashSet<>();
        Object parsed;
        try {
            parsed = mapper.readValue(info, Object.class);
        } catch (Exception e) {
            return ids;
        }
        Map<String, Object> data = J.nodeOr(parsed);
        if (data == null) {
            return ids;
        }
        Long type = strictLong(data.get("_Type"));
        Long fileId = strictLong(data.get("CardFileId"));
        if (type != null && type == DECORATION_CARD && fileId != null) {
            ids.add(fileId);
        }
        for (Object item : J.listOrEmpty(data.get("B"))) {
            Map<String, Object> node = J.nodeOr(item);
            if (node == null || strictLong(node.get("A")) == null
                    || strictLong(node.get("A")) != DECORATION_CARD) {
                continue;
            }
            Long first = strictLong(node.get("H"));
            Long second = strictLong(node.get("I"));
            if (first != null) {
                ids.add(first);
            }
            if (second != null) {
                ids.add(second);
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // 公告
    // ------------------------------------------------------------------

    /**
     * Python 版返回的是 JSON 数组 {@code []}（不是 {@code {"announce_list": []}}），
     * {@link WireHandler} 的返回值类型是 {@code Object}，所以这里直接返回空列表。
     */
    private Object headerAnnounces(WireRequest request) {
        request.requirePlayer();
        return new ArrayList<>();
    }

    private Map<String, Object> announceList(WireRequest request) {
        request.requirePlayer();
        int type = (int) WireServices.integer(request.body(), "announce_type", 0,
                0, Integer.MAX_VALUE);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : services.refs("announces")) {
            if (type != 0 && !sameNumber(row.get("announce_type"), type)) {
                continue;
            }
            Map<String, Object> out = new LinkedHashMap<>(row);
            out.remove("contents");
            rows.add(out);
        }
        rows.sort((left, right) -> {
            int priority = Long.compare(J.longOr(right.get("priority"), 0),
                    J.longOr(left.get("priority"), 0));
            if (priority != 0) {
                return priority;
            }
            return String.valueOf(left.getOrDefault("start_time", ""))
                    .compareTo(String.valueOf(right.getOrDefault("start_time", "")));
        });
        return J.map("announce_list", rows);
    }

    private Map<String, Object> announceDetail(WireRequest request) {
        request.requirePlayer();
        String ident = id(request.body(), "announce_id");
        Map<String, Object> row = catalogs.get("announces", ident);
        if (row == null) {
            throw new WireError(404, "Local announcement not found");
        }
        return encode("AnnounceDetailResponse", J.map(
                "MAnnouncesId", row.getOrDefault("m_announces_id", ident),
                "MAnnouncesTitle", row.getOrDefault("m_announces_title", ""),
                "MBannersId", row.getOrDefault("m_banners_id", ""),
                "ResourceFileName", row.getOrDefault("resource_file_name", ""),
                "AnnounceType", row.getOrDefault("announce_type", 0),
                "Contents", row.getOrDefault("contents", ""),
                "StartTime", row.get("start_time")));
    }

    // ------------------------------------------------------------------
    // 背包 / 推送 / 历史
    // ------------------------------------------------------------------

    private Map<String, Object> itemDetail(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body(), "d_item_datas_id");
        Map<String, Object> row = findItem(player, ident);
        if (row == null) {
            throw new WireError(404, "Local item not owned");
        }
        Map<String, Object> master = itemMaster(row.get("ItemId"));
        return encode("UserItemGetDetailResponse", J.map(
                "DItemDatasId", row.containsKey("UserItemId") ? row.get("UserItemId") : ident,
                "ItemType", row.getOrDefault("ItemType", 0),
                "Name", master.get("Name") == null ? "" : master.get("Name"),
                "Description", master.get("Description") == null
                        ? "" : master.get("Description"),
                "ItemNum", row.getOrDefault("ItemNum", 0),
                "QuestList", new ArrayList<>(),
                "LimitDateTime", row.get("LimitDateTime"),
                "TransitionScene", "",
                "ResourceFileName", row.getOrDefault("ResourceFileName", "")));
    }

    /**
     * Python {@code _item_master}：master_items 可用时返回道具主数据行，否则空对象。
     *
     * <p>表不存在、查询失败或这一行缺失都按「本地没有名称」处理，不伪造名字。
     */
    private Map<String, Object> itemMaster(Object itemId) {
        Long ident = J.asLong(itemId);
        if (ident == null) {
            return new LinkedHashMap<>();
        }
        if (grants.available()) {
            try {
                Map<String, Object> row = grants.item(ident);
                if (row != null) {
                    return row;
                }
            } catch (RuntimeException e) {
                // 主表查询不可用：继续走随仓库分发的静态主表，但要留一条告警。
                if (!fallbackQueryWarned) {
                    fallbackQueryWarned = true;
                    log.warn("读取 master_items 失败，物品详情改用静态主表回退（后续不再重复告警）：{}",
                            e.getMessage());
                }
            }
        }
        Map<String, Object> fallback = fallbackItem(ident);
        return fallback == null ? new LinkedHashMap<>() : fallback;
    }

    /**
     * {@code client_defaults/master_items.json} 的按 Id 索引。
     *
     * <p>这份数据由参考服务端的 {@code master.db} 的 {@code items} 表导出；
     * 它让「本机没有 MariaDB master_items」时物品详情仍能显示名称与描述，而不是空串。
     */
    private Map<String, Object> fallbackItem(long ident) {
        Map<Long, Map<String, Object>> cached = fallbackItems;
        if (cached == null) {
            cached = loadFallbackItems();
            fallbackItems = cached;
        }
        return cached.get(ident);
    }

    private Map<Long, Map<String, Object>> loadFallbackItems() {
        String version = config.getClientVersion();
        String versioned = "master_items_" + version;
        List<Map<String, Object>> rows = defaults.rows(versioned);
        String source = versioned;
        if (rows.isEmpty() && !"5.1.0".equals(version)) {
            log.warn("client_defaults/{}.json 缺失，回退到 5.1.0 的 master_items.json",
                    versioned);
            rows = defaults.rows("master_items");
            source = "master_items";
        }
        if (rows.isEmpty() && "5.1.0".equals(version)) {
            rows = defaults.rows("master_items");
            source = "master_items";
        }

        Map<Long, Map<String, Object>> loaded = new java.util.HashMap<>();
        int skipped = 0;
        int duplicates = 0;
        for (Map<String, Object> row : rows) {
            Long key = J.asLong(row.get("Id"));
            if (key == null) {
                skipped++;
            } else if (loaded.putIfAbsent(key, row) != null) {
                duplicates++;
            }
        }
        if (!fallbackLoaded) {
            fallbackLoaded = true;
            if (loaded.isEmpty()) {
                log.warn("client_defaults/{}.json 缺失或为空：物品详情将没有名称/描述", versioned);
            } else if (skipped > 0 || duplicates > 0) {
                log.warn("{} 已加载 {} 行，跳过 {} 行无效 Id，忽略 {} 个重复 Id",
                        source, loaded.size(), skipped, duplicates);
            } else {
                log.info("{} 已加载 {} 行静态物品主表（client-version={}）",
                        source, loaded.size(), version);
            }
        }
        return loaded;
    }

    private Map<String, Object> jewelHistory(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        return encode("UserJewelGetHistoryResponse", J.map(
                "JewelFree", player.entity().jewelFree,
                "JewelPaid", player.entity().jewelPaid,
                "JewelHistories", WireServices.rows(player, "jewel_histories")));
    }

    private Map<String, Object> pushDevice(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String token = optionalText(request.body(), "token", 4096);
        int service = (int) WireServices.integer(request.body(), "service", 0,
                Integer.MIN_VALUE, Integer.MAX_VALUE);
        if (!token.isEmpty()) {
            List<Map<String, Object>> rows = WireServices.rows(player, "push_devices");
            rows.removeIf(row -> token.equals(row.get("token")));
            rows.add(J.map("service", service, "token", token));
            player.put("push_devices", rows);
            request.store().save(player);
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> contentsReleaseHistory(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        int contentsId = (int) WireServices.integer(request.body(), "contents_id", (Integer) null,
                1, Integer.MAX_VALUE);
        List<Object> shown = new ArrayList<>(player.scalarsOrEmpty("contents_release_effects"));
        if (!shown.contains(contentsId)) {
            shown.add(contentsId);
            player.put("contents_release_effects", shown);
            request.store().save(player);
        }
        return encode("UserSetContentsReleaseEffectHistoryResponse", J.map(
                "ContentsId", contentsId, "Status", 1));
    }

    /**
     * Python {@code present_box/get_list}：按存库顺序分页（新奖励追加在尾部），并顺手把
     * {@code PresentBoxCount} 纠正成实际行数 —— Python 版在同分支里落库。
     */
    private Map<String, Object> presentList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        int page = (int) WireServices.integer(request.body(), "page", 1, 1, Integer.MAX_VALUE);
        List<Map<String, Object>> stored = player.rowsSnapshot("presents");
        List<Map<String, Object>> items = new ArrayList<>(stored.size());
        for (Map<String, Object> row : stored) {
            items.add(WireServices.presentItem(row));
        }
        if (player.entity().presentBoxCount != items.size()) {
            player.entity().presentBoxCount = items.size();
            request.store().save(player);
        }
        int from = (int) Math.min(((long) page - 1) * WireServices.PRESENT_PAGE_SIZE, items.size());
        int to = (int) Math.min((long) page * WireServices.PRESENT_PAGE_SIZE, items.size());
        return encode("PresentBoxGetListResponse", J.map(
                "TotalCount", items.size(),
                "Page", page,
                "Items", items.subList(from, to)));
    }

    private Map<String, Object> presentHistory(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        int page = (int) WireServices.integer(request.body(), "page", 1,
                1, Integer.MAX_VALUE);
        List<Map<String, Object>> stored = WireServices.rows(player, "present_history");
        List<Map<String, Object>> items = new ArrayList<>();
        for (int index = stored.size() - 1; index >= 0; index--) {
            items.add(WireServices.presentItem(stored.get(index)));
        }
        int from = (int) Math.min(((long) page - 1) * PAGE_SIZE, items.size());
        int to = (int) Math.min((long) page * PAGE_SIZE, items.size());
        return encode("PresentBoxGetHistoryResponse", J.map(
                "TotalCount", items.size(),
                "Page", page,
                "Items", items.subList(from, to)));
    }

    private Map<String, Object> presentDetail(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body(), "d_present_boxes_id");
        Map<String, Object> row = null;
        for (String key : List.of("presents", "present_history")) {
            for (Map<String, Object> candidate : player.rowsSnapshot(key)) {
                Object value = candidate.containsKey("d_present_boxes_id")
                        ? candidate.get("d_present_boxes_id")
                        : candidate.get("DPresentBoxesId");
                if (ident.equals(value)) {
                    row = candidate;
                    break;
                }
            }
            if (row != null) {
                break;
            }
        }
        if (row == null) {
            throw new WireError(404, "Local present not found");
        }
        Map<String, Object> item = WireServices.presentItem(row);
        return encode("PresentBoxItemDetailResponse", J.map(
                "Item", item, "Num", item.get("Num")));
    }

    // ------------------------------------------------------------------
    // 资料
    // ------------------------------------------------------------------

    private Map<String, Object> setProfileIcon(WireRequest request) {
        return setProfileDesign(request, "IconDesigns", "ProfileIconPartsInfo",
                "ProfileSetProfileIconResponse");
    }

    private Map<String, Object> setProfileCard(WireRequest request) {
        return setProfileDesign(request, "CardDesigns", "ProfileCardPartsInfo",
                "ProfileSetProfileCardResponse");
    }

    private Map<String, Object> setProfileDesign(WireRequest request, String key,
                                                 String partsKey, String model) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body(), "d_profile_my_designs_id");
        List<Map<String, Object>> designs = player.rows(key);
        Map<String, Object> found = null;
        for (Map<String, Object> row : designs) {
            if (ident.equals(row.get("DProfileMyDesignsId"))) {
                found = row;
                break;
            }
        }
        if (found == null) {
            throw new WireError(404, "My design not found");
        }
        for (Map<String, Object> row : designs) {
            row.put("IsSetting", row == found);
        }
        player.put(partsKey, found.getOrDefault("MyDesignPartsInfo", ""));
        if ("CardDesigns".equals(key)) {
            shop.advance(player, WireShop.BEGINNER_CONDITION_PROFILE_CARD);
        }
        request.store().save(player);
        return encode(model, J.map("Result", true));
    }

    private Map<String, Object> setBirthday(WireRequest request, String model) {
        PlayerContext player = request.requirePlayer();
        if (!player.boolOf("IsBirthdayChangeable", true)) {
            throw WireError.badRequest("Birthday can only be set once");
        }
        Instant when = dateTime(request.body(), "birthday");
        Instant now = Instant.now();
        if (when.isAfter(now) || when.atOffset(ZoneOffset.UTC).getYear() < 1900) {
            throw WireError.badRequest("birthday must be a past date");
        }
        player.put("Birthday", BIRTHDAY_FORMAT.format(when.atOffset(ZoneOffset.UTC)));
        player.put("IsBirthdayChangeable", false);
        request.store().save(player);
        return encode(model, J.map("Result", true));
    }

    private Map<String, Object> setMyDesignName(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body(), "d_profile_my_designs_id");
        String name = requiredText(request.body(), "my_design_name", 20);
        DesignRef ref = design(player, ident);
        if (ref == null) {
            throw new WireError(404, "My design not found");
        }
        ref.row().put("MyDesignName", name.strip());
        request.store().save(player);
        return encode("ProfileSetMyDesignNameResponse", J.map("Result", true));
    }

    private Map<String, Object> deleteMyDesign(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body(), "d_profile_my_designs_id");
        DesignRef ref = design(player, ident);
        if (ref == null) {
            throw new WireError(404, "My design not found");
        }
        if (J.truthy(ref.row().get("IsSetting"))) {
            throw WireError.badRequest("The design in use cannot be deleted");
        }
        player.rows(ref.key()).removeIf(row -> row == ref.row());
        request.store().save(player);
        return encode("ProfileDeleteMyDesignResponse", J.map("Result", true));
    }

    private Map<String, Object> getMyDesignIcon(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body(), "d_profile_my_designs_id");
        Map<String, Object> found = null;
        for (Map<String, Object> row : player.rowsSnapshot("IconDesigns")) {
            if (ident.equals(row.get("DProfileMyDesignsId"))) {
                found = row;
                break;
            }
        }
        if (found == null) {
            throw new WireError(404, "My design not found");
        }
        return encode("ProfileGetMyDesignIconResponse", J.map(
                "ProfileIconPartsInfo", found.getOrDefault("MyDesignPartsInfo", "")));
    }

    private Map<String, Object> fanLevelRanking(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        int characterId = (int) WireServices.integer(request.body(), "character_id", (Integer) null,
                1, 99_999);
        List<RankEntry> entries = new ArrayList<>();
        for (Player summary : players.listPlayers()) {
            PlayerContext candidate = players.loadById(summary.playerId);
            if (candidate == null) {
                continue;
            }
            int level = memberFanLevel(candidate, characterId);
            if (level > 0) {
                entries.add(new RankEntry(level, summary.created, summary.playerId, candidate));
            }
        }
        entries.sort((left, right) -> {
            int byLevel = Integer.compare(right.level(), left.level());
            if (byLevel != 0) {
                return byLevel;
            }
            int byCreated = Long.compare(left.created(), right.created());
            if (byCreated != 0) {
                return byCreated;
            }
            return left.playerId().compareTo(right.playerId());
        });

        Map<String, Object> template = catalog.profileTemplate();
        if (template == null) {
            template = Map.of();
        }
        List<Map<String, Object>> rankings = new ArrayList<>();
        int myRank = 0;
        int myLevel = 0;
        for (int index = 0; index < entries.size(); index++) {
            RankEntry entry = entries.get(index);
            int rank = index + 1;
            if (entry.playerId().equals(player.id())) {
                myRank = rank;
                myLevel = entry.level();
            }
            if (rank <= 100) {
                PlayerContext candidate = entry.player();
                rankings.add(J.map(
                        "Rank", rank,
                        "PlayerId", entry.playerId(),
                        "PlayerName", candidate.entity().playerName == null
                                ? "" : candidate.entity().playerName,
                        "ProfileIconPartsInfo", candidate.has("ProfileIconPartsInfo")
                                ? candidate.raw("ProfileIconPartsInfo")
                                : template.getOrDefault("profile_icon_parts_info", ""),
                        "MemberFanLevel", entry.level()));
            }
        }
        return encode("ProfileGetFanLevelRankingResponse", J.map(
                "MyRank", myRank, "MyMemberFanLevel", myLevel, "Rankings", rankings));
    }

    private Map<String, Object> setMute(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        PlayerContext target = target(request, player);
        List<Object> muted = new ArrayList<>(player.scalarsOrEmpty("muted_players"));
        if (!muted.contains(target.id())) {
            muted.add(target.id());
            player.put("muted_players", muted);
            request.store().save(player);
        }
        return encode("ProfileSetMuteResponse", J.map("Result", true));
    }

    private Map<String, Object> setMuteCancel(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body(), "player_id");
        List<Object> muted = new ArrayList<>(player.scalarsOrEmpty("muted_players"));
        PlayerContext resolved = players.loadById(ident);
        Set<String> idents = new java.util.HashSet<>();
        idents.add(ident);
        if (resolved != null) {
            idents.add(resolved.id());
        }
        boolean present = muted.stream()
                .anyMatch(value -> value != null && idents.contains(String.valueOf(value)));
        if (!present) {
            throw new WireError(404, "Player is not muted");
        }
        muted.removeIf(value -> value != null && idents.contains(String.valueOf(value)));
        player.put("muted_players", muted);
        request.store().save(player);
        return encode("ProfileSetMuteCancelResponse", J.map("Result", true));
    }

    private Map<String, Object> getMuteList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object ident : player.scalarsOrEmpty("muted_players")) {
            String value = String.valueOf(ident);
            PlayerContext target = players.loadById(value);
            rows.add(J.map("PlayerId", value,
                    "PlayerName", target == null ? "" : target.name()));
        }
        return encode("ProfileGetMuteListResponse", J.map("MuteList", rows));
    }

    private Map<String, Object> setReport(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        PlayerContext target = target(request, player);
        WireServices.integer(request.body(), "report_type", (Integer) null, 1, 99);
        List<Object> reported = new ArrayList<>(player.scalarsOrEmpty("reported_players"));
        if (!reported.contains(target.id())) {
            reported.add(target.id());
            player.put("reported_players", reported);
            request.store().save(player);
        }
        return encode("ProfileSetReportResponse", J.map("Result", true));
    }

    // ------------------------------------------------------------------
    // 收藏
    // ------------------------------------------------------------------

    private Map<String, Object> emojiList(WireRequest request) {
        request.requirePlayer();
        return encode("GetEmojiListResponse", J.map(
                "EmojiInfoList", services.refs("emoji_categories"),
                // 本地未配置官方 API 回源：与 Python 版 official_api 关闭时一致，发空列表。
                "EmojiList", new ArrayList<>(),
                "MembershipList", new ArrayList<>()));
    }

    private Map<String, Object> setGalleryData(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        int comicsId = (int) WireServices.integer(request.body(), "comics_id", (Integer) null,
                1, Integer.MAX_VALUE);
        Map<String, Object> found = null;
        for (Map<String, Object> row : services.served("gallery", WireServices.COMIC_ASSETS)) {
            if (sameNumber(row.get("comics_id"), comicsId)) {
                found = row;
                break;
            }
        }
        if (found == null) {
            throw new WireError(404, "Comic not present in the local gallery catalog");
        }
        List<Map<String, Object>> rows = WireServices.rows(player, "gallery_entries");
        boolean present = rows.stream().anyMatch(row -> sameNumber(row.get("comics_id"), comicsId));
        if (!present) {
            rows.add(J.map("comics_id", comicsId,
                    "name", found.getOrDefault("name", "")));
            player.put("gallery_entries", rows);
            request.store().save(player);
        }
        return encode("SetGallaryDataResponse", J.map("Result", true));
    }

    private Map<String, Object> setMusicPlay(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        int musicsId = (int) WireServices.integer(request.body(), "musics_id", (Integer) null,
                1, Integer.MAX_VALUE);
        List<Map<String, Object>> history = WireServices.rows(player, "MusicHistory");
        Map<String, Object> row = null;
        for (Map<String, Object> candidate : history) {
            if (sameNumber(candidate.get("MusicsId"), musicsId)) {
                row = candidate;
                break;
            }
        }
        if (row == null) {
            boolean served = false;
            for (Map<String, Object> candidate : services.served("music_collection",
                    WireServices.MUSIC_ASSETS)) {
                if (sameNumber(candidate.get("musics_id"), musicsId)) {
                    served = true;
                    break;
                }
            }
            if (!served) {
                throw new WireError(404,
                        "Music not present in the local collection catalog");
            }
            row = J.map("MusicsId", musicsId, "IsMastery", false, "PlayCount", 0);
            history.add(row);
        }
        row.put("PlayCount", J.intOr(row.get("PlayCount"), 0) + 1);
        row.put("LatestPlayTime", MICRO_UTC.format(Instant.now().atOffset(ZoneOffset.UTC)));
        player.put("MusicHistory", history);
        request.store().save(player);
        return encode("SetMusicPlayResponse", J.map("Result", true));
    }

    // ------------------------------------------------------------------
    // 商店目录
    // ------------------------------------------------------------------

    private Map<String, Object> itemStoreList(WireRequest request) {
        request.requirePlayer();
        return encode("ItemStoreGetListResponse", J.map(
                "BundleId", "", "ItemStoreList", services.refs("item_stores")));
    }

    private Map<String, Object> jewelShopList(WireRequest request) {
        request.requirePlayer();
        return encode("JewelShopGetListResponse", J.map(
                "BundleId", "", "JewelShopItemInfo", services.refs("jewel_shop_items")));
    }

    private Map<String, Object> membershipList(WireRequest request) {
        request.requirePlayer();
        return encode("JewelShopGetMembershipListResponse", J.map(
                "BundleId", "", "MembershipList", services.refs("memberships")));
    }

    private Map<String, Object> jewelBirthday(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> birthday = null;
        if (!player.boolOf("IsBirthdayChangeable", true)
                && J.truthy(player.raw("Birthday"))) {
            Integer age = age(player.raw("Birthday"));
            birthday = J.map(
                    "Birthday", player.raw("Birthday"),
                    "Age", age == null ? 0 : age,
                    "Buyable", false,
                    "TotalAmounts", 0,
                    "Balance", 0);
        }
        return encode("JewelShopGetBirthdayResponse", J.map(
                "Birthday", birthday,
                "HasSiscaChargePendingReceipt", false,
                "HasItemStorePendingReceipt", false));
    }

    // ------------------------------------------------------------------
    // 抽卡 / 账号 / 章节
    // ------------------------------------------------------------------

    private Map<String, Object> confirmTicketExpired(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        int ticketId = (int) WireServices.integer(request.body(), "gacha_ticket_id", (Integer) null,
                1, Integer.MAX_VALUE);
        optionalBool(request.body(), "is_confirm_disabled");
        List<Object> pending = new ArrayList<>(
                player.scalarsOrEmpty("ExpiredLimitedGachaTickets"));
        if (pending.contains(ticketId)) {
            pending.removeIf(value -> sameNumber(value, ticketId));
            player.put("ExpiredLimitedGachaTickets", pending);
            request.store().save(player);
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> connectData(WireRequest request) {
        // 公共版没有 _ext_provider/_ext_key 这类旧状态，和 Python 版「未连携」一致。
        request.requirePlayer();
        return encode("GetConnectDataResponse", J.map(
                "AccountConnectDataList", new ArrayList<>()));
    }

    private Map<String, Object> chapterCategory(WireRequest request) {
        request.requirePlayer();
        int categoryId = (int) WireServices.integer(request.body(), "category_id", (Integer) null,
                1, Integer.MAX_VALUE);
        return encode("ChapterCategoryResponse", J.map(
                "CategoryInfo", J.map(
                        "CategoryId", categoryId,
                        "CurrentPoint", 0,
                        "MaxPoint", 0,
                        "IsCapped", false),
                "MissionList", new ArrayList<>()));
    }

    private Map<String, Object> pointRewards(WireRequest request, String model) {
        request.requirePlayer();
        WireServices.integer(request.body(), "chapter_id", (Integer) null, 1, Integer.MAX_VALUE);
        return encode(model, J.map("PointReward", new ArrayList<>()));
    }

    /** `/v1/chapter/get_point_rewards` 额外校验 current_point（Python 同分支）。 */
    private Map<String, Object> pointRewardsWithCurrentPoint(WireRequest request) {
        request.requirePlayer();
        WireServices.integer(request.body(), "current_point", 0, 0, Integer.MAX_VALUE);
        return pointRewards(request, "ChapterGetPointRewardsResponse");
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private Map<String, Object> encode(String model, Map<String, Object> data) {
        return codec.encode(model, data);
    }

    private static PlayerContext target(WireRequest request, PlayerContext player) {
        String ident = id(request.body(), "player_id");
        if (ident.equals(player.id())) {
            throw WireError.badRequest("player_id must be another account");
        }
        PlayerContext target = request.store().loadById(ident);
        if (target == null) {
            throw new WireError(404, "Local player not found");
        }
        return target;
    }

    private static DesignRef design(PlayerContext player, String ident) {
        for (String key : List.of("CardDesigns", "IconDesigns")) {
            for (Map<String, Object> row : player.rows(key)) {
                if (ident.equals(row.get("DProfileMyDesignsId"))) {
                    return new DesignRef(key, row);
                }
            }
        }
        return null;
    }

    private record DesignRef(String key, Map<String, Object> row) {
    }

    private record RankEntry(int level, long created, String playerId,
                             PlayerContext player) {
    }

    private static int memberFanLevel(PlayerContext player, int characterId) {
        for (Map<String, Object> row : player.rowsSnapshot("MemberFanLevels")) {
            if (sameNumber(row.get("CharactersId"), characterId)) {
                return J.intOr(row.get("MemberFanlevel"), 0);
            }
        }
        return 0;
    }

    private static Integer age(Object birthday) {
        if (!(birthday instanceof String text)) {
            return null;
        }
        Instant born;
        try {
            born = parseInstant(text);
        } catch (RuntimeException e) {
            return null;
        }
        OffsetDateTime today = Instant.now().atOffset(ZoneOffset.UTC);
        OffsetDateTime date = born.atOffset(ZoneOffset.UTC);
        int years = today.getYear() - date.getYear();
        if (today.getMonthValue() < date.getMonthValue()
                || (today.getMonthValue() == date.getMonthValue()
                && today.getDayOfMonth() < date.getDayOfMonth())) {
            years--;
        }
        return years;
    }

    private static Instant dateTime(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String text) || text.isEmpty()) {
            throw WireError.badRequest(key + " must be an ISO 8601 date-time string");
        }
        try {
            return parseInstant(text);
        } catch (RuntimeException e) {
            throw WireError.badRequest(key + " must be an ISO 8601 date-time string");
        }
    }

    private static Instant parseInstant(String value) {
        String normalized = value.replace("Z", "+00:00");
        try {
            return OffsetDateTime.parse(normalized).toInstant();
        } catch (DateTimeParseException ignored) {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        }
    }

    private static Map<String, Object> findItem(PlayerContext player, String ident) {
        for (Map<String, Object> group : player.rowsSnapshot("Items")) {
            for (Map<String, Object> row : J.rowsOr(group.get("UserItemList"))) {
                if (ident.equals(row.get("UserItemId"))
                        || ident.equals(String.valueOf(row.get("ItemId")))) {
                    return row;
                }
            }
        }
        return null;
    }

    private String stringOr(Map<String, Object> row, String fallback, String... keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return fallback;
    }

    private static String id(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String text) || text.strip().isEmpty()
                || text.length() > MAX_ID_LENGTH) {
            throw WireError.badRequest(
                    key + " must be a non-empty string of at most 128 characters");
        }
        return text;
    }

    /** Python {@code wire_account._text}：required=false 时 null 落成空串。 */
    private static String optionalText(Map<String, Object> body, String key, int maximum) {
        Object value = body.get(key);
        if (value == null) {
            return "";
        }
        if (!(value instanceof String text) || text.length() > maximum) {
            throw WireError.badRequest(
                    key + " must be a string of at most " + maximum + " characters");
        }
        return text;
    }

    /** Python {@code wire_account._text} 的 required 分支（非空白、长度上限）。 */
    private static String requiredText(Map<String, Object> body, String key, int maximum) {
        Object value = body.get(key);
        if (!(value instanceof String text) || text.strip().isEmpty()
                || text.length() > maximum) {
            throw WireError.badRequest(
                    key + " must be a string of at most " + maximum + " characters");
        }
        return text;
    }

    private static Boolean optionalBool(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Boolean flag)) {
            throw WireError.badRequest(key + " must be a boolean or null");
        }
        return flag;
    }

    private static Long strictLong(Object value) {
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
        return null;
    }

    private static boolean sameNumber(Object value, long expected) {
        Long parsed = strictLong(value);
        return parsed != null && parsed == expected;
    }
}
