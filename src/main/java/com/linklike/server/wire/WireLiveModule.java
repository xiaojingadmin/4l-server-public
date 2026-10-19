package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireError;
import com.linklike.server.service.PlayerContext;
import com.linklike.server.service.RefCatalogStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 归档 / 直播周边接口 —— 对应 Python {@code wire_services.py} 的归档分支与
 * {@code wire_live.py}。
 *
 * <p>本地没有进行中的直播：{@code get_list} 返回空表，其它以 {@code live_id} 定位一场直播的
 * 接口校验参数后统一 404。归档频道、季度与节目来自运营导入的参考目录（默认空表），账号侧的
 * 观看进度存在 {@code archive_progress}，收藏频道存在 {@code favorite_channels}，
 * With×STATION 的评论也挂在进度行上。
 *
 * <p>购票、礼物、留言卡、星星、奖品、问卷作答、Webview 登录等需要真实定价与结算的写接口
 * 保持 501，且不改任何状态。
 */
@Component
public class WireLiveModule implements WireModule {

    private static final int MAX_COMMENT = 200;
    private static final int MAX_COMMENTS_PER_ARCHIVE = 500;
    private static final long ARCHIVE_POLLING_INTERVAL_MS = 1000L;
    private static final List<Integer> DEFAULT_CAMERAS = List.of(1);

    /** 频道相关的归档接口。 */
    private static final Set<String> CHANNEL_ROUTES = Set.of(
            "/v1/archive/get_channel_list",
            "/v1/archive/get_channel_movie_list",
            "/v1/archive/get_season_list",
            "/v1/archive/set_recommend_channel",
            "/v1/archive/set_cancel_recommend_channel");

    /** 本地没有直播：空表 + 字段名。 */
    private static final Map<String, String> EMPTY_LISTS = Map.of(
            "/v1/withlive/get_list", "withlive_info",
            "/v1/feslive/get_list", "feslive_info");

    /** 以 {@code live_id} 定位「进行中的直播」的接口 -> 请求里的标识字段。 */
    private static final Map<String, String> LIVE_ID_ROUTES = liveIdRoutes();

    /** 需要真实定价 / 结算 / 鉴权的写接口：认证后 501。 */
    private static final Set<String> UNIMPLEMENTED_WRITES = Set.of(
            "/v1/archive/set_purchase_ticket",
            "/v1/archive/withlive_gift",
            "/v1/archive/withlive_prize",
            "/v1/archive/withlive_stars",
            "/v1/withlive/gift",
            "/v1/withlive/message_card",
            "/v1/withlive/prize",
            "/v1/withlive/set_star",
            "/v1/withlive/enquetes/answer",
            "/v1/withstation/gift",
            "/v1/withstation/message_card",
            "/v1/feslive/gift",
            "/v1/feslive/lobby_gift",
            "/v1/feslive/message_card",
            "/v1/feslive/lobby_message_card",
            "/v1/feslive/prize",
            "/v1/feslive/set_prize",
            "/v1/feslive/set_flower_stand",
            "/v1/feslive/enquete",
            "/v1/webview/live/login",
            "/v1/withstation/prize",
            "/v1/withstation/set_star");

    private static final Set<String> STATION_ROUTES = Set.of(
            "/v1/withstation/comment",
            "/v1/withstation/get_gift_shop_list",
            "/v1/withstation/gift_pt_rankings",
            "/v1/withstation/result_ranking");

    private final RefCatalogStore catalogs;

    public WireLiveModule(RefCatalogStore catalogs) {
        this.catalogs = catalogs;
    }

    @Override
    public void register(WireRouter router) {
        for (String path : CHANNEL_ROUTES) {
            router.register(path, this::channelRoute);
        }
        EMPTY_LISTS.forEach((path, key) -> router.register(path,
                request -> {
                    request.requirePlayer();
                    return J.map(key, new ArrayList<>());
                }));
        LIVE_ID_ROUTES.forEach((path, key) -> router.register(path,
                request -> {
                    requirePlayer(request);
                    id(request.body().get(key), key);
                    throw WireError.notFound(
                            "No live stream is running on the local server");
                }));
        for (String path : UNIMPLEMENTED_WRITES) {
            router.register(path, request -> {
                requirePlayer(request);
                throw new WireError(501, "Ticket purchase, gifts, message cards, stars, "
                        + "prizes, enquete answers and webview login require verified pricing, "
                        + "live-service state and settlement rules; no state was changed");
            });
        }
        for (String path : STATION_ROUTES) {
            router.register(path, this::stationRoute);
        }

        router.register("/v1/archive/get_archive_list", this::archiveList);
        router.register("/v1/archive/get_with_station_list", this::stationList);
        router.register("/v1/archive/get_home", this::archiveHome);
        router.register("/v1/archive/get_ranking_list", this::rankingList);
        router.register("/v1/archive/get_with_station_data", this::stationData);
        router.register("/v1/archive/get_with_archive_data", this::withArchiveData);
        router.register("/v1/archive/get_fes_archive_data", this::fesArchiveData);
        router.register("/v1/archive/withlive_info", this::withliveInfo);
        router.register("/v1/archive/get_fes_timeline_data", this::fesTimeline);
        router.register("/v1/archive/set_fes_camera", this::setFesCamera);
        router.register("/v1/withstation/withstation_info", this::stationInfo);
    }

    // ------------------------------------------------------------------
    // 频道（archive/get_channel_*）
    // ------------------------------------------------------------------

    private Map<String, Object> channelRoute(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String path = request.path();
        if (path.equals("/v1/archive/get_season_list")) {
            Map<Integer, List<Map<String, Object>>> seasons = new LinkedHashMap<>();
            for (Map<String, Object> row : catalogs.list("archives")) {
                int season = J.intOr(row.get("season_id"), 0);
                if (season != 0) {
                    seasons.computeIfAbsent(season, ignored -> new ArrayList<>()).add(row);
                }
            }
            List<Integer> keys = new ArrayList<>(seasons.keySet());
            keys.sort(Comparator.reverseOrder());
            List<Map<String, Object>> out = new ArrayList<>();
            for (int season : keys) {
                List<Map<String, Object>> rows = seasons.get(season);
                out.add(J.map("season_id", season, "archive_count", rows.size(),
                        "is_watched", watched(player, rows)));
            }
            return J.map("season_list", out);
        }
        if (path.equals("/v1/archive/get_channel_movie_list")) {
            int channelId = integer(request.body(), "live_channels_id", 1);
            Map<String, Object> channel = channel(channelId);
            List<Map<String, Object>> rows = channelArchives(player, channelId);
            rows.sort(Comparator.comparing(
                    (Map<String, Object> row) -> J.str(row.getOrDefault("live_start_time",
                            row.getOrDefault("start_time", ""))),
                    Comparator.nullsFirst(Comparator.<String>naturalOrder())).reversed());
            List<Map<String, Object>> live = new ArrayList<>();
            List<Map<String, Object>> trailers = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                if ("live".equals(row.get("home_category"))) {
                    live.add(row);
                } else if ("trailer".equals(row.get("home_category"))) {
                    trailers.add(row);
                }
            }
            boolean favorite = favoriteIds(player).contains(channelId);
            return J.map(
                    "name", text(channel.get("name")),
                    "description", text(channel.get("description")),
                    "banner_image_url", text(channel.get("banner_image_url")),
                    "icon_asset_bundle", text(channel.get("icon_asset_bundle")),
                    "icon_resource_file_name", text(channel.get("icon_resource_file_name")),
                    "is_favorite", favorite,
                    "live_archive_list", live,
                    "trailer_archive_list", trailers,
                    "archive_list", rows,
                    "is_watched", watched(player, rows));
        }
        if (path.equals("/v1/archive/get_channel_list")) {
            Set<Integer> favorites = favoriteIds(player);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> channel : catalogs.list("live_channels")) {
                int ident = J.intOr(channel.get("live_channels_id"), 0);
                out.add(J.map(
                        "live_channels_id", ident,
                        "name", text(channel.get("name")),
                        "is_favorite", favorites.contains(ident),
                        "is_watched", watched(player, channelArchives(player, ident))));
            }
            return J.map("channel_list", out);
        }

        int channelId = integer(request.body(), "live_channels_id", 1);
        List<Map<String, Object>> favorites = new ArrayList<>();
        for (Map<String, Object> row : player.rows("favorite_channels")) {
            if (J.intOr(row.get("live_channels_id"), -1) != channelId) {
                favorites.add(row);
            }
        }
        if (path.equals("/v1/archive/set_recommend_channel")) {
            Map<String, Object> channel = channel(channelId);
            favorites.add(J.map(
                    "live_channels_id", channelId,
                    "name", text(channel.get("name")),
                    "is_watched", watched(player, channelArchives(player, channelId))));
        } else if (!favoriteIds(player).contains(channelId)) {
            throw WireError.notFound("Channel is not a favorite of this account");
        }
        player.put("favorite_channels", favorites);
        request.store().save(player);
        return Map.of();
    }

    private Map<String, Object> channel(int ident) {
        Map<String, Object> row = catalogs.get("live_channels", ident);
        if (row == null) {
            throw WireError.notFound("Local live channel not found");
        }
        return row;
    }

    private List<Map<String, Object>> channelArchives(PlayerContext player, int channelId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : catalogs.list("archives")) {
            if (J.intOr(row.get("live_channels_id"), -1) == channelId) {
                Map<String, Object> copy = new LinkedHashMap<>(row);
                copy.putAll(archiveProgress(player, row));
                rows.add(copy);
            }
        }
        return rows;
    }

    private Set<Integer> favoriteIds(PlayerContext player) {
        Set<Integer> ids = new java.util.HashSet<>();
        for (Map<String, Object> row : player.rowsSnapshot("favorite_channels")) {
            ids.add(J.intOr(row.get("live_channels_id"), 0));
        }
        return ids;
    }

    private static boolean watched(PlayerContext player, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            Object ident = row.get("archives_id");
            if (ident == null) {
                ident = row.get("live_id");
            }
            if (J.intOr(progressRow(player, ident).get("current_play_time_second"), 0) <= 0) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 归档列表 / 首页 / 排行
    // ------------------------------------------------------------------

    private Map<String, Object> archiveList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        return listResponse(player, request.body(), false);
    }

    private Map<String, Object> stationList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        return listResponse(player, request.body(), true);
    }

    private Map<String, Object> listResponse(PlayerContext player, Map<String, Object> body,
                                             boolean station) {
        List<Map<String, Object>> data = archiveQuery(
                catalogs.list(station ? "with_stations" : "archives"), body);
        for (Map<String, Object> row : data) {
            row.putAll(archiveProgress(player, row));
        }
        int offset = integer(body, "offset", 0);
        int limit = boundedInteger(body, "limit", 100, 1, 1000);
        List<Map<String, Object>> page = new ArrayList<>();
        for (int index = offset; index < Math.min(data.size(), offset + limit); index++) {
            page.add(data.get(index));
        }
        return J.map(
                station ? "with_station_list" : "archive_list", page,
                "filterable_characters", catalogs.list("archive_filter_characters"),
                "sortable_fields", catalogs.list("archive_sort_fields"));
    }

    private Map<String, Object> archiveHome(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        List<Map<String, Object>> archives = catalogs.list("archives");
        List<Map<String, Object>> stations = catalogs.list("with_stations");
        archives.forEach(row -> row.putAll(archiveProgress(player, row)));
        stations.forEach(row -> row.putAll(archiveProgress(player, row)));
        List<Map<String, Object>> live = new ArrayList<>();
        List<Map<String, Object>> recommend = new ArrayList<>();
        List<Map<String, Object>> trailers = new ArrayList<>();
        for (Map<String, Object> row : archives) {
            Object category = row.get("home_category");
            if ("live".equals(category)) {
                live.add(row);
            } else if ("recommend".equals(category)) {
                recommend.add(row);
            } else if ("trailer".equals(category)) {
                trailers.add(row);
            }
        }
        return J.map(
                "favorite_channel_list", player.rowsSnapshot("favorite_channels"),
                "live_archive_list", live,
                "music_video_list", catalogs.list("music_videos"),
                "recommend_archive_list", recommend,
                "trailer_archive_list", trailers,
                "with_station_list", stations);
    }

    /** Python {@code archive/get_ranking_list}：本地不持有应援排行，返回「未参加」形态。 */
    private Map<String, Object> rankingList(WireRequest request) {
        request.requirePlayer();
        integer(request.body(), "member_id", 0);
        Object period = request.body().getOrDefault("target_period", "current");
        if (!(period instanceof String text) || text.length() > 32) {
            throw WireError.badRequest("target_period must be a short string");
        }
        return J.map(
                "status", 0,
                "ranking_start_time", null,
                "ranking_end_time", null,
                "my_rank_status", "not_participated",
                "my_rank", 0,
                "cheer_point", 0,
                "cheer_rewards", new ArrayList<>(),
                "cheer_rankings", new ArrayList<>());
    }

    // ------------------------------------------------------------------
    // 归档详情
    // ------------------------------------------------------------------

    private Map<String, Object> stationData(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body().get("withstation_id"), "withstation_id");
        Map<String, Object> archive = station(ident);
        Map<String, Object> progress = progressRow(player, ident);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.putAll(fields(archive, defaults(
                "timelines", new ArrayList<>(), "gift_pt_rankings", new ArrayList<>(),
                "live_location_id", 0, "costume_ids", new ArrayList<>(),
                "timeline_ids", new ArrayList<>(), "video_url", "", "chapters",
                new ArrayList<>(), "has_extra", false, "gift_star_reward_segments",
                new ArrayList<>(), "gift_stars_threshold_for_extra_admission", 0,
                "total_play_time_second_without_extra", 0,
                "total_play_time_second_including_extra", 0, "start_time", "", "title", "",
                "description", "", "content_code", 0, "viewer_count", 0, "total_gift_pt", "",
                "characters", new ArrayList<>(), "is_archive_published", false,
                "is_horizontal", false, "cover_image_url", "", "bgmAcbPath", "",
                "emoji_list", new ArrayList<>(), "membership_list", new ArrayList<>(),
                "enquetes", new ArrayList<>())));
        result.putAll(fields(progress, defaults(
                "has_extra_admission", false, "current_play_time_second", 0,
                "user_gift_pt", 0, "user_gift_pt_ranking", 0, "has_admission", false)));
        result.put("user_icon_parts_info", player.str("ProfileIconPartsInfo", ""));
        result.put("user_profile", J.map(
                "name", player.str("PlayerName", ""),
                "icon_parts_info", player.str("ProfileIconPartsInfo", "")));
        result.put("max_season_fan_level", player.intOf("MaxSeasonFanLevel", 0));
        return result;
    }

    private Map<String, Object> withArchiveData(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body().get("archives_id"), "archives_id");
        Map<String, Object> archive = archive(ident);
        Map<String, Object> progress = progressRow(player, ident);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(
                archiveCommon(archive, player, progress));
        List<Map<String, Object>> chapters = new ArrayList<>();
        for (Map<String, Object> chapter : J.rowsOr(result.get("chapters"))) {
            chapters.add(fields(chapter, defaults(
                    "name", "", "play_time_second", 0, "is_extra", false)));
        }
        result.put("chapters", chapters);
        result.putAll(fields(archive, defaults(
                "has_extra", false, "gift_star_reward_segments", new ArrayList<>(),
                "gift_stars_threshold_for_extra_admission", 0,
                "total_play_time_second_without_extra", 0,
                "total_play_time_second_including_extra", 0,
                "is_archive_published", false, "is_horizontal", false)));
        int gift = J.intOr(progress.get("user_gift_pt"), 0);
        result.put("user_gift_pt_without_archive", gift);
        result.put("user_gift_pt_including_archive", gift);
        return result;
    }

    private Map<String, Object> fesArchiveData(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body().get("archives_id"), "archives_id");
        Map<String, Object> archive = archive(ident);
        Map<String, Object> progress = progressRow(player, ident);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(
                archiveCommon(archive, player, progress));
        result.putAll(fields(archive, defaults(
                "total_play_time_second", 0, "ticket_rank", 0,
                "selectable_camera_types", new ArrayList<>(DEFAULT_CAMERAS))));
        List<Integer> cameras = new ArrayList<>();
        for (Object value : J.listOr(result.get("selectable_camera_types"))) {
            Integer camera = J.integer(value);
            if (camera != null) {
                cameras.add(camera);
            }
        }
        if (cameras.isEmpty()) {
            cameras = new ArrayList<>(DEFAULT_CAMERAS);
        }
        result.put("selectable_camera_types", cameras);
        Object current = progress.get("camera_type");
        result.put("user_gift_pt", J.intOr(progress.get("user_gift_pt"), 0));
        result.put("current_camera_type",
                current != null && cameras.contains(J.integer(current)) ? current : cameras.get(0));
        result.put("camera_focus_character_id", J.intOr(progress.get("focus_character_id"), 0));
        return result;
    }

    /** 两个归档详情共用的成员 —— Python {@code _archive_common}。 */
    private LinkedHashMap<String, Object> archiveCommon(Map<String, Object> archive,
                                                        PlayerContext player,
                                                        Map<String, Object> progress) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.putAll(fields(archive, defaults(
                "timelines", new ArrayList<>(), "gift_pt_rankings", new ArrayList<>(),
                "live_location_id", 0, "costume_ids", new ArrayList<>(),
                "timeline_ids", new ArrayList<>(), "archive_url", "", "video_url", "",
                "chapters", new ArrayList<>(), "unixtime_live_rec_started", 0,
                "live_start_time", "", "title", "", "description", "", "content_code", 0,
                "viewer_count", 0, "total_gift_pt", "", "characters", new ArrayList<>(),
                "enquetes", new ArrayList<>())));
        if (J.str(result.get("title")) == null || J.str(result.get("title")).isEmpty()) {
            result.put("title", text(archive.get("name")));
        }
        result.putAll(fields(progress, defaults(
                "has_extra_admission", false, "current_play_time_second", 0,
                "user_gift_pt_ranking", 0)));
        result.put("user_icon_parts_info", player.str("ProfileIconPartsInfo", ""));
        return result;
    }

    // ------------------------------------------------------------------
    // 播放进度
    // ------------------------------------------------------------------

    private Map<String, Object> withliveInfo(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body().get("live_id"), "live_id");
        int playTimeMs = integer(request.body(), "play_time_ms", 0);
        Map<String, Object> archive = archive(ident);
        recordProgress(player, request, ident, playTimeMs / 1000);
        return J.map(
                "timelines", new ArrayList<>(),
                "polling_interval", ARCHIVE_POLLING_INTERVAL_MS,
                "has_extra_admission", archiveProgress(player, archive).get("has_extra_admission"),
                "is_archive_published", published(archive));
    }

    private Map<String, Object> fesTimeline(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body().get("archives_id"), "archives_id");
        int playTime = integer(request.body(), "play_time_second", 0);
        WireServices.integer(request.body(), "timeline_unixtime", 0L, 0L, 1L << 62);
        archive(ident);
        recordProgress(player, request, ident, playTime);
        return J.map("timelines", new ArrayList<>(),
                "polling_interval", ARCHIVE_POLLING_INTERVAL_MS);
    }

    private Map<String, Object> setFesCamera(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body().get("live_id"), "live_id");
        int camera = integer(request.body(), "camera_type", 0);
        int focus = integer(request.body(), "focus_character_id", 0);
        Map<String, Object> archive = archive(ident);
        List<Integer> cameras = new ArrayList<>();
        for (Object value : J.listOr(archive.get("selectable_camera_types"))) {
            Integer parsed = J.integer(value);
            if (parsed != null) {
                cameras.add(parsed);
            }
        }
        if (cameras.isEmpty()) {
            cameras.addAll(DEFAULT_CAMERAS);
        }
        if (!cameras.contains(camera)) {
            throw WireError.badRequest("camera_type is not selectable for this archive");
        }
        recordProgress(player, request, ident, null, camera, focus);
        return J.map("camera_type", camera, "focus_character_id", focus);
    }

    private Map<String, Object> stationInfo(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body().get("live_id"), "live_id");
        int playTimeMs = integer(request.body(), "play_time_ms", 0);
        Map<String, Object> station = station(ident);
        recordProgress(player, request, ident, playTimeMs / 1000);
        return J.map(
                "timelines", timelines(player, ident),
                "polling_interval_ms", 1000,
                "has_extra_admission", archiveProgress(player, station).get("has_extra_admission"),
                "is_archive_published", !J.str(station.get("video_url")).isEmpty()
                        && station.get("video_url") != null);
    }

    private List<Map<String, Object>> timelines(PlayerContext player, String ident) {
        List<Map<String, Object>> comments = new ArrayList<>(
                J.rowsOr(progressRow(player, ident).get("comments")));
        comments.sort(Comparator.comparingLong(
                row -> J.longOr(row.get("play_time_ms"), 0L)));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : comments) {
            int playTimeMs = J.intOr(row.get("play_time_ms"), 0);
            out.add(J.map(
                    "timeline_id", text(row.get("timeline_id")),
                    "name_color", "",
                    "background_color", "",
                    "text_color", "",
                    "animation", J.map(
                            "icon_normal_twinkle", false, "icon_strong_twinkle", false,
                            "gift_box_falling", false),
                    "type", 1,
                    "sender_type", 1,
                    "user_player_id", player.str("PlayerId", ""),
                    "user_name", player.str("PlayerName", ""),
                    "play_time_ms", playTimeMs,
                    "body", text(row.get("body")),
                    "text_size", 0,
                    "is_text_bold", false,
                    "item_id", 0,
                    "amount", 0,
                    "gift_pt", 0,
                    "user_icon_parts_info", player.str("ProfileIconPartsInfo", ""),
                    "display_user_icon", false,
                    "asset_icon_parts_info", "",
                    "image_size", 0,
                    "play_time_second", playTimeMs / 1000));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // With×STATION 互动
    // ------------------------------------------------------------------

    private Map<String, Object> stationRoute(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String path = request.path();
        if (path.equals("/v1/withstation/comment")) {
            return comment(player, request);
        }
        if (path.equals("/v1/withstation/get_gift_shop_list")) {
            station(id(request.body().get("m_withstations_id"), "m_withstations_id"));
            // 本地没有可核实的礼物定价，商店为空。
            return J.map(
                    "gift_shop_item_info", new ArrayList<>(),
                    "message_card_shop_info", new ArrayList<>(),
                    "gift_box_borders", new ArrayList<>());
        }
        String ident = id(request.body().get("live_id"), "live_id");
        station(ident);
        Map<String, Object> progress = progressRow(player, ident);
        if (path.equals("/v1/withstation/gift_pt_rankings")) {
            return J.map(
                    "gift_pt_rankings", new ArrayList<>(),
                    "user_gift_pt", J.intOr(progress.get("user_gift_pt"), 0),
                    "user_gift_pt_ranking", 0,
                    "user_icon_parts_info", player.str("ProfileIconPartsInfo", ""),
                    "total_gift_pt", "0",
                    "polling_interval", 60);
        }
        return J.map("borders", new ArrayList<>(), "user_rank", 0, "ranking", new ArrayList<>());
    }

    private Map<String, Object> comment(PlayerContext player, WireRequest request) {
        String ident = id(request.body().get("live_id"), "live_id");
        station(ident);
        Object comment = request.body().get("comment");
        if (!(comment instanceof String text) || text.isBlank() || text.length() > MAX_COMMENT) {
            throw WireError.badRequest("comment must be a non-empty string of at most "
                    + MAX_COMMENT + " characters");
        }
        int playTimeMs = integer(request.body(), "play_time_ms", 0);
        List<Map<String, Object>> rows = player.rows("archive_progress");
        Map<String, Object> row = null;
        for (Map<String, Object> candidate : rows) {
            if (ident.equals(String.valueOf(candidate.get("withstation_id")))) {
                row = candidate;
                break;
            }
        }
        if (row == null) {
            row = J.map("withstation_id", ident);
            rows.add(row);
        }
        List<Map<String, Object>> comments = new ArrayList<>(J.rowsOr(row.get("comments")));
        if (comments.size() >= MAX_COMMENTS_PER_ARCHIVE) {
            throw WireError.conflict("Comment limit reached for this archive");
        }
        String timelineId = UUID.randomUUID().toString();
        comments.add(J.map("timeline_id", timelineId, "play_time_ms", playTimeMs,
                "body", text));
        row.put("comments", comments);
        request.store().save(player);
        return J.map(
                "timeline_id", timelineId,
                "name_color", "",
                "text_color", "",
                "background_color", "",
                "comment", text,
                "timeline_time_ms", playTimeMs);
    }

    // ------------------------------------------------------------------
    // 目录查询
    // ------------------------------------------------------------------

    /** Python {@code _archive_query}：客户端的筛选与排序。 */
    private static List<Map<String, Object>> archiveQuery(List<Map<String, Object>> data,
                                                          Map<String, Object> body) {
        Object characters = body.get("characters");
        List<Map<String, Object>> filters = characters == null
                ? List.of() : J.rowsOr(characters);
        if (characters != null && !(characters instanceof List)) {
            throw WireError.badRequest("characters must be a list of {character_id} objects");
        }
        Set<Integer> wanted = new java.util.HashSet<>();
        for (Map<String, Object> filter : filters) {
            Integer characterId = J.integer(filter.get("character_id"));
            if (characterId == null) {
                throw WireError.badRequest(
                        "characters must be a list of {character_id} objects");
            }
            wanted.add(characterId);
        }
        List<Map<String, Object>> rows = new ArrayList<>(data);
        if (!wanted.isEmpty()) {
            // 没有卡司数据的行保留：上游列表行本来就不带 character_list。
            List<Map<String, Object>> kept = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Set<Integer> cast = new java.util.HashSet<>();
                for (Map<String, Object> member : J.rowsOr(row.get("character_list"))) {
                    Integer characterId = J.integer(member.get("character_id"));
                    if (characterId != null) {
                        cast.add(characterId);
                    }
                }
                if (cast.isEmpty()) {
                    kept.add(row);
                } else {
                    for (Integer characterId : cast) {
                        if (wanted.contains(characterId)) {
                            kept.add(row);
                            break;
                        }
                    }
                }
            }
            rows = kept;
        }
        for (String key : List.of("season_id", "live_type")) {
            Object value = body.get(key);
            if (J.truthy(value)) {
                int expected = boundedInteger(body, key, 0, 0, Integer.MAX_VALUE);
                rows.removeIf(row -> J.intOr(row.get(key), Integer.MIN_VALUE) != expected);
            }
        }
        for (Map.Entry<String, String> pair : Map.of(
                "has_withlive_extra", "has_extra",
                "has_withlive_extra_admission", "has_extra_admission").entrySet()) {
            Object value = body.get(pair.getKey());
            if (value == null) {
                continue;
            }
            if (!(value instanceof Boolean expected)) {
                throw WireError.badRequest(pair.getKey() + " must be a boolean");
            }
            rows.removeIf(row -> J.boolOr(row.get(pair.getValue()), false) != expected);
        }
        Object sortValue = body.get("sort");
        String sort = J.nonEmpty(sortValue) == null ? "live_start_time" : J.str(sortValue);
        Object orderValue = body.get("order");
        String order = J.nonEmpty(orderValue) == null ? "desc" : J.str(orderValue);
        if (!(sortValue == null || sortValue instanceof String)
                || !(orderValue == null || orderValue instanceof String)
                || !(order.equals("asc") || order.equals("desc"))) {
            throw WireError.badRequest("sort must be a field name and order asc or desc");
        }
        Comparator<Map<String, Object>> comparator = Comparator.comparing(
                row -> J.str(row.getOrDefault(sort, row.getOrDefault("start_time", ""))),
                Comparator.nullsFirst(Comparator.<String>naturalOrder()));
        rows.sort(order.equals("desc") ? comparator.reversed() : comparator);
        return rows;
    }

    // ------------------------------------------------------------------
    // 账号进度
    // ------------------------------------------------------------------

    private static Map<String, Object> progressRow(PlayerContext player, Object ident) {
        for (Map<String, Object> row : player.rowsSnapshot("archive_progress")) {
            if (ident != null && ident.equals(row.get("withstation_id"))) {
                return row;
            }
        }
        return Map.of();
    }

    /** Python {@code _archive_progress}：账号在一场归档 / 节目上的观看状态。 */
    private static Map<String, Object> archiveProgress(PlayerContext player,
                                                       Map<String, Object> live) {
        Object ident = live.get("withstation_id");
        if (ident == null) {
            ident = live.get("archives_id");
        }
        if (ident == null) {
            ident = live.get("live_id");
        }
        Map<String, Object> progress = progressRow(player, ident);
        return J.map(
                "has_extra_admission", J.boolOr(progress.get("has_extra_admission"), false),
                "earned_star_count", J.intOr(progress.get("earned_star_count"), 0));
    }

    /**
     * 记录观看进度 —— Python {@code _record_progress}。
     *
     * <p>值没变化时不落库，避免客户端每次轮询都写一次账号。
     */
    private static void recordProgress(PlayerContext player, WireRequest request, String ident,
                                       Integer playTimeSecond) {
        recordProgress(player, request, ident, playTimeSecond, null, null);
    }

    private static void recordProgress(PlayerContext player, WireRequest request, String ident,
                                       Integer playTimeSecond, Integer camera, Integer focus) {
        List<Map<String, Object>> rows = player.rows("archive_progress");
        Map<String, Object> row = null;
        for (Map<String, Object> candidate : rows) {
            if (ident.equals(String.valueOf(candidate.get("withstation_id")))) {
                row = candidate;
                break;
            }
        }
        if (row == null) {
            row = J.map("withstation_id", ident);
            rows.add(row);
        }
        boolean changed = false;
        if (playTimeSecond != null
                && !playTimeSecond.equals(J.intOr(row.get("current_play_time_second"), 0))) {
            row.put("current_play_time_second", playTimeSecond);
            changed = true;
        }
        if (camera != null && !camera.equals(J.intOr(row.get("camera_type"), 0))) {
            row.put("camera_type", camera);
            changed = true;
        }
        if (focus != null && !focus.equals(J.intOr(row.get("focus_character_id"), 0))) {
            row.put("focus_character_id", focus);
            changed = true;
        }
        if (changed) {
            request.store().save(player);
        }
    }

    private Map<String, Object> archive(String ident) {
        Map<String, Object> row = catalogs.get("archives", ident);
        if (row == null) {
            throw WireError.notFound("Local archive not found");
        }
        return row;
    }

    private Map<String, Object> station(String ident) {
        Map<String, Object> row = catalogs.get("with_stations", ident);
        if (row == null) {
            throw WireError.notFound("Local with-station archive not found");
        }
        return row;
    }

    private static boolean published(Map<String, Object> archive) {
        return J.boolOr(archive.get("is_archive_published"), false)
                || J.truthy(archive.get("video_url"))
                || J.truthy(archive.get("archive_url"));
    }

    // ------------------------------------------------------------------
    // 参数 / 字段
    // ------------------------------------------------------------------

    private static PlayerContext requirePlayer(WireRequest request) {
        return request.requirePlayer();
    }

    private static String id(Object value, String key) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 128) {
            throw WireError.badRequest(
                    key + " must be a non-empty string of at most 128 characters");
        }
        return text;
    }

    private static int integer(Map<String, Object> body, String key, int fallback) {
        Object value = body.containsKey(key) ? body.get(key) : null;
        if (value == null) {
            return fallback;
        }
        Integer parsed = J.integer(value);
        if (parsed == null || parsed < 0) {
            throw WireError.badRequest(key + " must be an integer from 0 to 2147483647");
        }
        return parsed;
    }

    private static int boundedInteger(Map<String, Object> body, String key, int fallback,
                                      int minimum, int maximum) {
        Object value = body.containsKey(key) ? body.get(key) : null;
        Integer parsed = value == null ? fallback : J.integer(value);
        if (parsed == null || parsed < minimum || parsed > maximum) {
            throw WireError.badRequest(String.format(
                    "%s must be an integer from %d to %d", key, minimum, maximum));
        }
        return parsed;
    }

    private static String text(Object value) {
        String text = J.str(value);
        return text == null ? "" : text;
    }

    /** {@code {字段: 默认值}} 的有序字面量。 */
    private static LinkedHashMap<String, Object> defaults(Object... keyValues) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            out.put((String) keyValues[index], keyValues[index + 1]);
        }
        return out;
    }

    /** Python {@code _fields}：按默认表的键取值，缺失时用默认值。 */
    private static Map<String, Object> fields(Map<String, Object> row,
                                              Map<String, Object> defaults) {
        Map<String, Object> out = new LinkedHashMap<>();
        defaults.forEach((key, fallback) -> {
            Object value = row.containsKey(key) ? row.get(key) : fallback;
            out.put(key, WireServices.deepCopyJson(value));
        });
        return out;
    }

    private static Map<String, String> liveIdRoutes() {
        Map<String, String> routes = new LinkedHashMap<>();
        for (String path : List.of(
                "/v1/withlive/enter", "/v1/withlive/live_info",
                "/v1/withlive/live_subinfo", "/v1/withlive/comment",
                "/v1/withlive/connect_token", "/v1/withlive/gift_pt_rankings",
                "/v1/withlive/result_ranking")) {
            routes.put(path, "live_id");
        }
        routes.put("/v1/withlive/get_gift_shop_list", "m_withlives_id");
        routes.put("/v1/withstation/live_info", "live_id");
        routes.put("/v1/withstation/live_subinfo", "live_id");
        for (String path : List.of(
                "/v1/feslive/enter", "/v1/feslive/lobby", "/v1/feslive/lobby_info",
                "/v1/feslive/lobby_subinfo", "/v1/feslive/lobby_announcement",
                "/v1/feslive/lobby_comment", "/v1/feslive/live_info",
                "/v1/feslive/live_subinfo", "/v1/feslive/live_announcement",
                "/v1/feslive/comment", "/v1/feslive/circle_chat/comment",
                "/v1/feslive/clap", "/v1/feslive/connect_token",
                "/v1/feslive/penlight_color", "/v1/feslive/set_camera",
                "/v1/feslive/gift_pt_rankings", "/v1/feslive/grand_prix_ranking",
                "/v1/feslive/out_quest_live_ranking")) {
            routes.put(path, "live_id");
        }
        routes.put("/v1/feslive/get_gift_shop_list", "m_feslives_id");
        routes.put("/v1/webview/live/enter", "live_id");
        routes.put("/v1/webview/live/live_info", "live_id");
        return routes;
    }
}
