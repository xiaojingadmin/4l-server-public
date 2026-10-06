package com.linklike.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklike.server.service.PlayerContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 构建 {@code /v1} 成功响应的游戏响应头，对应 Python 版
 * {@code server.build_common_game_headers}。
 *
 * <p>客户端用这些头刷新 {@code CommonApiCache}（名称、货币、体力、各个入口的开放状态），
 * 缺失会让首页显示成空数据。
 */
@Component
public class GameHeaders {

    /** 每个 {@code *_status} 对应一个首页入口（0 = 维护/锁定，1 = 开放）。 */
    public static final List<String> LAUNCHER_INFO_KEYS = List.of(
            "quest_live_status", "archive_status", "activity_status", "gacha_status",
            "mission_status", "present_box_status", "shop_status", "sisca_store_status",
            "collection_status", "sticker_status", "comic_status", "circle_status",
            "information_status", "friend_status", "train_status", "deck_status",
            "school_idol_show_status");

    /** 状态键：玩家的 LauncherInfo 覆盖项存在这个状态行里。 */
    public static final String LAUNCHER_INFO_STATE_KEY = "LauncherInfo";

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
                    .withZone(ZoneOffset.UTC);

    private final ObjectMapper mapper;

    public GameHeaders(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 官方新号抓包得到的默认值：除社团外全部开放。 */
    public static Map<String, Object> launcherDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (String key : LAUNCHER_INFO_KEYS) {
            defaults.put(key, "circle_status".equals(key) ? 0 : 1);
        }
        return defaults;
    }

    /** 该玩家的 LauncherInfo：默认值叠加账号里存过的覆盖项（只接受 0..3）。 */
    public Map<String, Object> launcherInfo(PlayerContext context) {
        Map<String, Object> launcher = launcherDefaults();
        if (context == null) {
            return launcher;
        }
        Map<String, Object> stored = context.node(LAUNCHER_INFO_STATE_KEY);
        if (stored == null) {
            return launcher;
        }
        for (String key : LAUNCHER_INFO_KEYS) {
            Object value = stored.get(key);
            if (value instanceof Number number) {
                int parsed = number.intValue();
                if (parsed >= 0 && parsed <= 3) {
                    launcher.put(key, parsed);
                }
            }
        }
        return launcher;
    }

    /** 会话级响应头：资源版本与服务器时间。 */
    public Map<String, String> sessionHeaders(String resourceSignature) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (resourceSignature != null && !resourceSignature.isEmpty()) {
            headers.put("x-res-version", resourceSignature);
        }
        headers.put("x-server-date", HTTP_DATE.format(Instant.now()));
        return headers;
    }

    /**
     * 玩家级响应头。{@code /v1/user/login} 不携带这些（客户端此时还没有缓存）。
     */
    public Map<String, String> playerHeaders(PlayerContext context) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (context == null) {
            return headers;
        }
        PlayerContext ctx = context;
        var player = ctx.entity();

        Map<String, Object> stamina = new LinkedHashMap<>();
        stamina.put("stamina_now", player.staminaNow);
        stamina.put("stamina_max", player.staminaMax);
        stamina.put("stamina_recovery_time",
                player.staminaRecoveryTime == null || player.staminaRecoveryTime.isEmpty()
                        ? Instant.now().atOffset(ZoneOffset.UTC).format(ISO_MILLIS)
                        : player.staminaRecoveryTime);

        long paid = player.jewelPaidGoogle != 0 ? player.jewelPaidGoogle : player.jewelPaid;

        headers.put("name", Base64.getEncoder()
                .encodeToString(ctx.name().getBytes(StandardCharsets.UTF_8)));
        headers.put("fan_level", String.valueOf(player.fanLevel == null ? 0 : player.fanLevel));
        headers.put("presentbox_count", String.valueOf(player.presentBoxCount));
        headers.put("jewel_free", String.valueOf(player.jewelFree));
        headers.put("jewel_paid_google", String.valueOf(paid));
        headers.put("user_stamina", toJson(stamina));
        headers.put("is_already_read_friend_request", bool(player.isAlreadyReadFriendRequest));
        headers.put("is_already_read_shop_new_arrival", bool(player.isAlreadyReadShopNewArrival));
        headers.put("is_grand_prix_open", bool(player.isGrandPrixOpen));
        headers.put("is_circle_invite_user", bool(player.isCircleInviteUser));
        headers.put("is_circle_approve_user", bool(player.isCircleApproveUser));
        headers.put("is_circle_dissolution_user", bool(player.isCircleDissolutionUser));
        headers.put("is_already_read_sisca_shop_new_arrival", bool(player.isAlreadyReadSiscaShopNewArrival));
        headers.put("is_already_read_membership_new_arrival", bool(player.isAlreadyReadMembershipNewArrival));
        headers.put("is_already_read_petal_exchange_new_arrival", bool(player.isAlreadyReadPetalExchangeNewArrival));
        headers.put("is_already_read_item_store_new_arrival", bool(player.isAlreadyReadItemStoreNewArrival));
        headers.put("new_gacha_unreadable_type", String.valueOf(player.newGachaUnreadableType));
        headers.put("latest_chat_order_id", String.valueOf(player.latestChatOrderId));
        headers.put("exists_circle_approval_pending_from_user", bool(player.existsCircleApprovalPendingFromUser));
        headers.put("launcher_info", toJson(launcherInfo(ctx)));
        headers.put("is_party_gacha_underway", bool(player.isPartyGachaUnderway));
        headers.put("chapter_rank_id", String.valueOf(player.chapterRankId));
        return headers;
    }

    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC);

    private static String bool(boolean value) {
        return value ? "true" : "false";
    }

    private String toJson(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应头 JSON 序列化失败", e);
        }
    }
}
