package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireCodec;
import com.linklike.server.protocol.WireError;
import com.linklike.server.resource.ClientDefaults;
import com.linklike.server.service.PlayerContext;
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
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 礼物箱领取 —— 对应 Python {@code wire_services.py} 的 {@code present_box/set_item} 与
 * {@code present_box/set_item_all}，以及 {@code wire_catalog.apply_present_contents}。
 *
 * <p>只有本地有核实发放路径的礼物类型能领取：背包道具（ItemType 1/3/4/9）走
 * {@link WireGrants#grantItem}，卡（2）走 {@link WireCardMint#grant}，歌曲（5）与贴纸（6）
 * 按主数据补一条收藏记录。其余类型（表情、Grade 点数…）领不了：请求成功但该条进
 * {@code ReceiptFailedItems}，账号状态不变 —— 与 Python 返回 0 的语义一致。
 *
 * <p>领取成功会把礼物从 {@code presents} 移到 {@code present_history} 并顺带纠正
 * {@code PresentBoxCount}。
 */
@Component
public class WirePresentModule implements WireModule {

    /** Python {@code wire_services.PRESENT_PAGE_SIZE}。 */
    private static final int PRESENT_PAGE_SIZE = 50;
    private static final int RECEIPT_FAILED = 0;
    private static final int RECEIPT_SUCCESS = 1;

    private static final DateTimeFormatter MICRO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    private final WireCodec codec;
    private final WireGrants grants;
    private final WireCardMint mint;
    private final WireStore store;
    private final ClientDefaults defaults;

    public WirePresentModule(WireCodec codec, WireGrants grants, WireCardMint mint,
                             WireStore store, ClientDefaults defaults) {
        this.codec = codec;
        this.grants = grants;
        this.mint = mint;
        this.store = store;
        this.defaults = defaults;
    }

    @Override
    public void register(WireRouter router) {
        router.register("/v1/present_box/set_item", this::setItem);
        router.register("/v1/present_box/set_item_all", this::setItemAll);
    }

    private Map<String, Object> setItem(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = request.requireStr("d_present_boxes_id", 128);
        Map<String, Object> row = findPendingPresent(player, ident);
        if (row == null) {
            throw WireError.notFound("Local present not found");
        }
        boolean ok = receive(player, row);
        request.store().save(player);
        Map<String, Object> item = ok ? receivedItem(row) : WireServices.presentItem(row);
        return codec.encode("PresentBoxSetItemResponse", J.map(
                "Result", ok ? RECEIPT_SUCCESS : RECEIPT_FAILED,
                "ReceiptSuccessItem", ok ? item : null,
                "ReceiptFailedItem", ok ? null : item,
                "GradeUpData", null,
                "IsNotReceivedGradeReward",
                player.boolOf("IsNotReceivedGradeReward", false)));
    }

    private Map<String, Object> setItemAll(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        int page = WireServices.integer(request.body(), "page", 1, 1, Integer.MAX_VALUE);
        Object withExpire = request.body().get("is_receive_with_expire");
        if (withExpire != null && !(withExpire instanceof Boolean)) {
            throw WireError.badRequest("is_receive_with_expire must be a boolean or null");
        }
        boolean allowExpired = Boolean.TRUE.equals(withExpire);

        List<Map<String, Object>> pending = new ArrayList<>();
        for (Map<String, Object> row : player.rows("presents")) {
            if (!receipted(row)) {
                pending.add(row);
            }
        }
        int from = (int) Math.min(((long) page - 1) * PRESENT_PAGE_SIZE, pending.size());
        int to = (int) Math.min((long) page * PRESENT_PAGE_SIZE, pending.size());

        List<Map<String, Object>> success = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        List<Map<String, Object>> exclude = new ArrayList<>();
        for (Map<String, Object> row : pending.subList(from, to)) {
            if (expired(row) && !allowExpired) {
                exclude.add(WireServices.presentItem(row));
                continue;
            }
            if (receive(player, row)) {
                success.add(receivedItem(row));
            } else {
                failed.add(WireServices.presentItem(row));
            }
        }
        if (!success.isEmpty()) {
            request.store().save(player);
        }
        return codec.encode("PresentBoxSetItemAllResponse", J.map(
                "Result", success.isEmpty() ? RECEIPT_FAILED : RECEIPT_SUCCESS,
                "ReceiptSuccessItems", success,
                "ReceiptFailedItems", failed,
                "ReceiptExcludeItems", exclude,
                "GradeUpData", null,
                "IsNotReceivedGradeReward",
                player.boolOf("IsNotReceivedGradeReward", false)));
    }

    // ------------------------------------------------------------------
    // 领取
    // ------------------------------------------------------------------

    /** 把一条礼物转进背包/收藏；本地没有发放路径时返回 false 且不改账号。 */
    private boolean receive(PlayerContext player, Map<String, Object> row) {
        int itemType = J.intOr(pick(row, "ItemType", "item_type"), 0);
        long itemId = J.longOr(pick(row, "ItemId", "item_id"), 0L);
        int num = J.intOr(pick(row, "Num", "num", "item_num"), 1);
        try {
            apply(player, itemType, itemId, Math.max(num, 1));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return false;
        }
        removePresent(player, presentIdent(row));
        List<Map<String, Object>> history = player.rows("present_history");
        history.add(receivedItem(row));
        player.put("present_history", history);
        return true;
    }

    /** Python {@code apply_present_contents}。 */
    private void apply(PlayerContext player, int itemType, long itemId, int num) {
        if (itemType == WireGrants.ITEM_TYPE_CARD) {
            mint.grant(player, itemId);
            return;
        }
        if (WireGrants.INVENTORY_TYPES.contains(itemType)) {
            grants.grantItem(player, itemId, num);
            return;
        }
        if (itemType == ITEM_TYPE_MUSIC) {
            grantMusic(player, itemId);
            return;
        }
        if (itemType == ITEM_TYPE_STICKER) {
            grantSticker(player, itemId);
            return;
        }
        throw new IllegalArgumentException("unsupported present item_type " + itemType);
    }

    private static final int ITEM_TYPE_MUSIC = 5;
    private static final int ITEM_TYPE_STICKER = 6;

    /** Python {@code grants.grant_music}：收藏与节奏游戏两处都补一条。 */
    private void grantMusic(PlayerContext player, long musicId) {
        Map<String, Object> titles = defaults.object("music_titles").orElseGet(LinkedHashMap::new);
        Object title = titles.get(String.valueOf(musicId));
        if (title == null) {
            throw new IllegalArgumentException("Unknown music " + musicId);
        }
        List<Map<String, Object>> history = player.rows("MusicHistory");
        if (history.stream().noneMatch(row -> J.longOr(row.get("MusicsId"), Long.MIN_VALUE) == musicId)) {
            history.add(J.map("MusicsId", musicId, "IsMastery", false, "PlayCount", 0L));
            player.put("MusicHistory", history);
        }
        List<Map<String, Object>> scores = player.rows("RhythmMusic");
        if (scores.stream().noneMatch(row -> J.longOr(row.get("MusicId"), Long.MIN_VALUE) == musicId)) {
            scores.add(J.map("MusicId", musicId, "MusicScores", new ArrayList<>(),
                    "HighScore", 0L, "MusicMasteryLevel", 0L));
            player.put("RhythmMusic", scores);
        }
    }

    /** Python {@code grants.grant_sticker}：已拥有则跳过，否则按主数据补一条贴纸行。 */
    private void grantSticker(PlayerContext player, long stickerId) {
        Map<String, Object> row = J.nodeOr(J.nodeOrEmpty(store.catalog().get("stickers"))
                .get(String.valueOf(stickerId)));
        if (row == null) {
            throw new IllegalArgumentException("Unknown sticker " + stickerId);
        }
        List<Map<String, Object>> stickers = player.rows("Stickers");
        for (Map<String, Object> sticker : stickers) {
            if (J.longOr(sticker.get("StickersId"), Long.MIN_VALUE) == stickerId) {
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
                "CreateTime", MICRO_UTC.format(Instant.now().atOffset(ZoneOffset.UTC))));
        player.put("Stickers", stickers);
    }

    private static void removePresent(PlayerContext player, String ident) {
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> row : player.rows("presents")) {
            if (!ident.equals(presentIdent(row))) {
                kept.add(row);
            }
        }
        player.put("presents", kept);
        player.entity().presentBoxCount = kept.size();
    }

    private static Map<String, Object> findPendingPresent(PlayerContext player, String ident) {
        for (Map<String, Object> row : player.rows("presents")) {
            if (ident.equals(presentIdent(row)) && !receipted(row)) {
                return row;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 行字段
    // ------------------------------------------------------------------

    private static Map<String, Object> receivedItem(Map<String, Object> row) {
        Map<String, Object> item = WireServices.presentItem(row);
        item.put("ReceiptedTime", MICRO_UTC.format(Instant.now().atOffset(ZoneOffset.UTC)));
        return item;
    }

    /** {@code DPresentBoxesId}：Python 两种拼写都认。 */
    private static String presentIdent(Map<String, Object> row) {
        Object value = row.containsKey("DPresentBoxesId")
                ? row.get("DPresentBoxesId") : row.get("d_present_boxes_id");
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean receipted(Map<String, Object> row) {
        return J.truthy(pick(row, "ReceiptedTime", "receipted_time"));
    }

    /** 领取期限已过；期限缺失或格式不认识时视为不过期（与 Python 一致）。 */
    private static boolean expired(Map<String, Object> row) {
        Object value = pick(row, "ReceiptLimitTime", "receipt_limit_time");
        if (!J.truthy(value)) {
            return false;
        }
        String text = String.valueOf(value).replace("Z", "+00:00");
        OffsetDateTime when;
        try {
            when = OffsetDateTime.parse(text);
        } catch (DateTimeParseException e) {
            try {
                // Python 把不带时区的时间当作 UTC（datetime.replace(tzinfo=utc)）。
                when = LocalDateTime.parse(text).atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                return false;
            }
        }
        return when.isBefore(OffsetDateTime.now(ZoneOffset.UTC));
    }

    private static Object pick(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            if (row.containsKey(key) && row.get(key) != null) {
                return row.get(key);
            }
        }
        return null;
    }
}
