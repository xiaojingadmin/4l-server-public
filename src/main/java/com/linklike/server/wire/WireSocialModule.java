package com.linklike.server.wire;

import com.linklike.server.config.ServerProperties;
import com.linklike.server.domain.Player;
import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireCodec;
import com.linklike.server.protocol.WireError;
import com.linklike.server.service.PlayerContext;
import com.linklike.server.service.PlayerStore;
import com.linklike.server.service.RefCatalogStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 关注与好友 —— 对应 Python {@code wire_services.py} 的 {@code _follow} / {@code _friends}。
 *
 * <p>本地账号之间直接建立关系：关注列表存 {@code FollowedPlayers}，好友列表存
 * {@code FriendPlayers}，自己发出的申请存 {@code FriendOutgoingRequests}（对方的待审批申请
 * 就存在他自己那一行里，因此不需要反向表）。上限取 {@code follow_limit} /
 * {@code follower_limit} 配置；好友上限取资料模板的 {@code friend_max_num}，缺省 30。
 */
@Component
public class WireSocialModule implements WireModule {

    public static final int FOLLOW_TYPE_FOLLOW = 1;
    public static final int FOLLOW_TYPE_UNFOLLOW = 2;
    public static final int FOLLOW_TYPE_UNFOLLOW_FOLLOWER = 3;

    public static final int FOLLOW_ORDER_FAN = 1;
    public static final int FOLLOW_ORDER_LOGIN = 2;
    public static final int FOLLOW_ORDER_STYLE = 3;
    public static final int FOLLOW_ORDER_SKILL = 4;

    public static final int FRIEND_ORDER_REGIST = 1;
    public static final int FRIEND_ORDER_LOGIN = 2;
    public static final int FRIEND_ORDER_RANK = 3;

    private static final int PACKAGED_FRIEND_MAX = 30;

    private final WireCodec codec;
    private final WireCatalog catalog;
    private final ServerProperties config;

    public WireSocialModule(WireCodec codec, WireCatalog catalog, ServerProperties config) {
        this.codec = codec;
        this.catalog = catalog;
        this.config = config;
    }

    @Override
    public void register(WireRouter router) {
        router.register("/v1/follow/set_follow", this::setFollow);
        router.register("/v1/follow/get_list", this::followList);
        router.register("/v1/follow/get_follower_list", this::followerList);
        router.register("/v1/follow/search_id", this::followSearchId);
        router.register("/v1/follow/live_chat_group_list", this::liveChatGroups);

        router.register("/v1/friend/get_list", this::friendList);
        router.register("/v1/friend/get_request_list", this::friendRequestList);
        router.register("/v1/friend/search_name", this::friendSearchName);
        router.register("/v1/friend/search_player_id", this::friendSearchPlayerId);
        router.register("/v1/friend/search_recommend", this::friendSearchRecommend);
        router.register("/v1/friend/set_request", this::friendRequest);
        router.register("/v1/friend/set_request_cancel", this::friendRequestCancel);
        router.register("/v1/friend/set_approval_request", this::friendApproval);
        router.register("/v1/friend/set_approval_request_all", this::friendApprovalAll);
        router.register("/v1/friend/set_refuse_request_all", this::friendRefuseAll);
        router.register("/v1/friend/set_break_off", this::friendBreakOff);
        router.register("/v1/friend/update_request_view_history", this::friendReadHistory);
    }

    // ------------------------------------------------------------------
    // 关注
    // ------------------------------------------------------------------

    private Map<String, Object> setFollow(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body(), "player_id");
        int kind = (int) WireServices.integer(request.body(), "follow_type", (Integer) null,
                FOLLOW_TYPE_FOLLOW, FOLLOW_TYPE_UNFOLLOW_FOLLOWER);
        if (ident.equals(player.id())) {
            throw WireError.badRequest("A player cannot follow or unfollow themselves");
        }
        PlayerContext target = request.store().loadById(ident);
        if (target == null) {
            throw WireError.notFound("Local player not found");
        }
        if (kind == FOLLOW_TYPE_UNFOLLOW_FOLLOWER) {
            List<Object> theirs = new ArrayList<>();
            for (Object pid : WireFriends.followIds(target)) {
                if (!player.id().equals(String.valueOf(pid))) {
                    theirs.add(pid);
                }
            }
            if (!theirs.equals(WireFriends.followIds(target))) {
                target.put("FollowedPlayers", theirs);
                request.store().save(target);
            }
            return resultCode(0);
        }
        List<Object> ids = new ArrayList<>(WireFriends.followIds(player));
        if (kind == FOLLOW_TYPE_FOLLOW && !WireFriends.containsId(ids, ident)) {
            if (ids.size() >= config.getFollowLimit()) {
                return resultCode(2);
            }
            if (followers(ident, request.store()).size() >= config.getFollowerLimit()) {
                return resultCode(1);
            }
            ids.add(ident);
        } else if (kind == FOLLOW_TYPE_UNFOLLOW) {
            ids.removeIf(pid -> ident.equals(String.valueOf(pid)));
        }
        player.put("FollowedPlayers", ids);
        request.store().save(player);
        return resultCode(0);
    }

    private Map<String, Object> followList(WireRequest request) {
        return followResponse(request, false);
    }

    private Map<String, Object> followerList(WireRequest request) {
        return followResponse(request, true);
    }

    private Map<String, Object> followResponse(WireRequest request, boolean followers) {
        PlayerContext player = request.requirePlayer();
        List<PlayerContext> targets = new ArrayList<>();
        int maximum;
        if (followers) {
            for (PlayerContext candidate : allPlayers(request.store())) {
                if (WireFriends.containsId(WireFriends.followIds(candidate), player.id())) {
                    targets.add(candidate);
                }
            }
            maximum = config.getFollowerLimit();
        } else {
            for (Object pid : WireFriends.followIds(player)) {
                PlayerContext target = request.store().loadById(String.valueOf(pid));
                if (target != null) {
                    targets.add(target);
                }
            }
            maximum = config.getFollowLimit();
        }
        int offset = (int) WireServices.integer(request.body(), "offset", 0, 0, Integer.MAX_VALUE);
        int limit = (int) WireServices.integer(request.body(), "limit", 100, 1, 1000);
        int order = (int) WireServices.integer(request.body(), "order_type", FOLLOW_ORDER_LOGIN,
                FOLLOW_ORDER_FAN, FOLLOW_ORDER_SKILL);
        boolean desc = booleanOrTrue(request.body().get("desc_order"), "desc_order");

        // Python 先按 PlayerId 排序再按排序键稳定排序，同键时保持 PlayerId 顺序。
        targets.sort(Comparator.comparing(PlayerContext::id));
        Comparator<PlayerContext> key = switch (order) {
            case FOLLOW_ORDER_FAN -> Comparator.comparingLong(
                    target -> target.entity().fanLevel == null ? 0L : target.entity().fanLevel);
            case FOLLOW_ORDER_STYLE -> Comparator.comparingInt(this::styleLevel);
            case FOLLOW_ORDER_SKILL -> Comparator.comparingInt(this::skillLevel);
            default -> Comparator.comparing(WireSocialModule::lastLoginText);
        };
        targets.sort(desc ? key.reversed() : key);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = offset; index < Math.min(targets.size(), offset + limit); index++) {
            rows.add(followProfile(targets.get(index), player));
        }
        return codec.encode(followers ? "FollowGetFollowerListResponse" : "FollowGetListResponse",
                J.map(followers ? "FollowerInfoList" : "FollowInfoList", rows,
                        followers ? "MaxFollowerCount" : "MaxFollowCount", (long) maximum));
    }

    private Map<String, Object> followSearchId(WireRequest request) {
        request.requirePlayer();
        String ident = id(request.body(), "player_id");
        boolean found = request.store().loadById(ident) != null;
        return codec.encode("FollowSearchIdResponse", J.map("ResultCode", found ? 0 : 1));
    }

    private Map<String, Object> liveChatGroups(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        List<Object> groupX = new ArrayList<>();
        List<Object> groupY = new ArrayList<>();
        for (Map<String, Object> row : player.rowsSnapshot("live_chat_groups")) {
            if ("x".equals(row.get("group"))) {
                groupX.add(row.get("group_id"));
            } else if ("y".equals(row.get("group"))) {
                groupY.add(row.get("group_id"));
            }
        }
        return J.map("group_x", groupX, "group_y", groupY);
    }

    /** Python {@code _follow_profile}。 */
    private Map<String, Object> followProfile(PlayerContext target, PlayerContext player) {
        List<Map<String, Object>> friendCards = friendCards(target);
        return J.map(
                "PlayerId", target.id(),
                "Name", text(target.entity().playerName),
                "FanLevel", target.entity().fanLevel == null ? 0L : target.entity().fanLevel,
                "Comment", text(target.entity().comment),
                "ProfileIconPartsInfo", target.str("ProfileIconPartsInfo", ""),
                "LastLoginDateTime", lastLoginText(target),
                "FriendCardInfo", friendCards,
                "FollowStatus", WireFriends.containsId(WireFriends.followIds(player), target.id())
                        ? 1 : 0,
                "FollowerStatus", WireFriends.containsId(WireFriends.followIds(target), player.id())
                        ? 1 : 0);
    }

    /** Python {@code _selected_friend_cards}：好友名片两个槽位里的卡。 */
    private List<Map<String, Object>> friendCards(PlayerContext target) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> selected = J.nodeOrEmpty(target.raw("FriendCard"));
        for (Map.Entry<String, Integer> slot : Map.of(
                "SchoolIdolStageCardId", 1, "RhythmGameCardId", 2).entrySet()) {
            Object ident = selected.get(slot.getKey());
            if (ident == null) {
                continue;
            }
            for (Map<String, Object> card : target.rowsSnapshot("Cards")) {
                if (String.valueOf(ident).equals(String.valueOf(card.get("DCardDatasId")))) {
                    out.add(J.map("CardType", slot.getValue(),
                            "UserCardInfo", WireServices.deepCopyJson(card)));
                    break;
                }
            }
        }
        return out;
    }

    private int styleLevel(PlayerContext target) {
        int best = 0;
        for (Map<String, Object> card : selectedFriendCards(target)) {
            Integer level = J.integer(firstValue(card, "StyleLevel", "style_level"));
            best = Math.max(best, level == null ? 0 : level);
        }
        return best;
    }

    private int skillLevel(PlayerContext target) {
        int best = 0;
        for (Map<String, Object> card : selectedFriendCards(target)) {
            Object skills = card.containsKey("SkillList") ? card.get("SkillList") : card.get("skill_list");
            for (Map<String, Object> skill : J.rowsOr(skills)) {
                Integer level = J.integer(firstValue(skill, "SkillLevel", "skill_level"));
                best = Math.max(best, level == null ? 0 : level);
            }
        }
        return best;
    }

    private List<Map<String, Object>> selectedFriendCards(PlayerContext target) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> selected = J.nodeOrEmpty(target.raw("FriendCard"));
        for (String key : List.of("SchoolIdolStageCardId", "RhythmGameCardId")) {
            Object ident = selected.get(key);
            if (ident == null) {
                continue;
            }
            for (Map<String, Object> card : target.rowsSnapshot("Cards")) {
                if (String.valueOf(ident).equals(String.valueOf(card.get("DCardDatasId")))) {
                    out.add(card);
                    break;
                }
            }
        }
        return out;
    }

    private static Object firstValue(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            if (row.containsKey(key) && row.get(key) != null) {
                return row.get(key);
            }
        }
        return null;
    }

    private List<PlayerContext> followers(String ident, PlayerStore store) {
        List<PlayerContext> result = new ArrayList<>();
        for (PlayerContext candidate : allPlayers(store)) {
            if (WireFriends.containsId(WireFriends.followIds(candidate), ident)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private List<PlayerContext> allPlayers(PlayerStore store) {
        List<PlayerContext> result = new ArrayList<>();
        for (Player summary : store.listPlayers()) {
            PlayerContext candidate = store.loadById(summary.playerId);
            if (candidate != null) {
                result.add(candidate);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 好友
    // ------------------------------------------------------------------

    private Map<String, Object> friendList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        int order = (int) WireServices.integer(request.body(), "order_type", FRIEND_ORDER_LOGIN,
                FRIEND_ORDER_REGIST, FRIEND_ORDER_RANK);
        boolean desc = booleanOrTrue(request.body().get("desc_order"), "desc_order");
        List<PlayerContext> targets = new ArrayList<>();
        for (Object pid : WireFriends.friendIds(player)) {
            PlayerContext target = request.store().loadById(String.valueOf(pid));
            if (target != null) {
                targets.add(target);
            }
        }
        targets.sort(Comparator.comparing(PlayerContext::id));
        Comparator<PlayerContext> key = switch (order) {
            case FRIEND_ORDER_REGIST -> Comparator.comparingLong(target -> target.entity().created);
            case FRIEND_ORDER_RANK -> Comparator.comparingLong(
                    target -> target.entity().playerLevel);
            default -> Comparator.comparing(WireSocialModule::lastLoginText);
        };
        targets.sort(desc ? key.reversed() : key);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (PlayerContext target : targets) {
            rows.add(friendInfo(target, player, ""));
        }
        return codec.encode("FriendGetListResponse",
                J.map("FriendInfoList", rows, "MaxFriendCount", (long) friendMax()));
    }

    private Map<String, Object> friendRequestList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        List<Map<String, Object>> waiting = new ArrayList<>();
        List<Map<String, Object>> requesting = new ArrayList<>();
        for (Incoming incoming : incomingRequests(player, request.store())) {
            waiting.add(requestInfo(incoming.owner(), String.valueOf(
                    incoming.request().get("DFriendRequestsId"))));
        }
        for (Map<String, Object> row : WireFriends.outgoingRequests(player)) {
            PlayerContext target = request.store().loadById(String.valueOf(row.get("PlayerId")));
            if (target != null) {
                requesting.add(requestInfo(target,
                        text(row.get("DFriendRequestsId"))));
            }
        }
        int cap = friendMax();
        return codec.encode("FriendGetRequestListResponse", J.map(
                "FriendRequestInfoData", J.map(
                        "WaitingList", waiting, "RequestingList", requesting),
                "MaxFriendWaitingCount", (long) cap,
                "MaxFriendRequestingCount", (long) cap));
    }

    private Map<String, Object> friendSearchName(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String name = id(request.body(), "name");
        List<Map<String, Object>> matches = new ArrayList<>();
        for (PlayerContext target : allPlayers(request.store())) {
            if (target.id().equals(player.id())) {
                continue;
            }
            if (name.equals(target.entity().playerName)) {
                matches.add(friendInfo(target, player, ""));
            }
        }
        return codec.encode("FriendSearchNameResponse", J.map("FriendInfoList", matches));
    }

    private Map<String, Object> friendSearchPlayerId(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body(), "player_id");
        PlayerContext target = request.store().loadById(ident);
        if (target == null) {
            throw WireError.notFound("Local player not found");
        }
        return codec.encode("FriendSearchPlayerIdResponse",
                J.map("FriendInfo", friendInfo(target, player, "")));
    }

    private Map<String, Object> friendSearchRecommend(WireRequest request) {
        request.requirePlayer();
        return codec.encode("FriendSearchRecommendResponse",
                J.map("FriendInfoList", new ArrayList<>()));
    }

    private Map<String, Object> friendRequest(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body(), "player_id");
        if (ident.equals(player.id())) {
            throw WireError.badRequest("A player cannot send a friend request to themselves");
        }
        PlayerContext target = request.store().loadById(ident);
        if (target == null) {
            throw WireError.notFound("Local player not found");
        }
        int status = WireFriends.friendRequestStatus(player, target);
        if (status == WireFriends.FRIEND_STATUS_ALREADY
                || status == WireFriends.FRIEND_STATUS_REQUESTED) {
            return boolResult(true);
        }
        if (status == WireFriends.FRIEND_STATUS_WAITING) {
            throw WireError.badRequest("Incoming friend request must be approved instead");
        }
        int cap = friendMax();
        if (WireFriends.friendIds(player).size() >= cap
                || WireFriends.friendIds(target).size() >= cap) {
            return boolResult(false);
        }
        if (WireFriends.outgoingRequests(player).size() >= cap) {
            return boolResult(false);
        }
        if (incomingRequests(target, request.store()).size() >= cap) {
            return boolResult(false);
        }
        List<Map<String, Object>> requests = new ArrayList<>(WireFriends.outgoingRequests(player));
        requests.add(J.map("DFriendRequestsId", UUID.randomUUID().toString().replace("-", ""),
                "PlayerId", ident));
        player.put("FriendOutgoingRequests", requests);
        target.entity().isAlreadyReadFriendRequest = false;
        request.store().save(target);
        request.store().save(player);
        return boolResult(true);
    }

    private Map<String, Object> friendRequestCancel(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Set<String> wanted = new HashSet<>(idList(request.body(), "d_friend_requests_id_list"));
        List<Map<String, Object>> rows = WireFriends.outgoingRequests(player);
        Set<String> have = new HashSet<>();
        for (Map<String, Object> row : rows) {
            have.add(text(row.get("DFriendRequestsId")));
        }
        if (!have.containsAll(wanted)) {
            throw WireError.notFound("Local friend request not found");
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!wanted.contains(text(row.get("DFriendRequestsId")))) {
                kept.add(row);
            }
        }
        player.put("FriendOutgoingRequests", kept);
        request.store().save(player);
        return boolResult(true);
    }

    private Map<String, Object> friendApproval(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body(), "d_friend_requests_id");
        Map<String, PlayerContext> incoming = incomingMap(player, request.store());
        PlayerContext owner = incoming.get(ident);
        if (owner == null) {
            throw WireError.notFound("Local friend request not found");
        }
        if (!befriend(player, owner)) {
            return boolResult(false);
        }
        request.store().save(owner);
        request.store().save(player);
        return boolResult(true);
    }

    private Map<String, Object> friendApprovalAll(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        List<String> wanted = idList(request.body(), "d_friend_requests_id_list");
        Map<String, PlayerContext> incoming = incomingMap(player, request.store());
        for (String ident : wanted) {
            if (!incoming.containsKey(ident)) {
                throw WireError.notFound("Local friend request not found");
            }
        }
        for (String ident : wanted) {
            PlayerContext owner = incoming.get(ident);
            boolean ok = befriend(player, owner);
            request.store().save(owner);
            if (!ok) {
                request.store().save(player);
                return boolResult(false);
            }
        }
        request.store().save(player);
        return boolResult(true);
    }

    private Map<String, Object> friendRefuseAll(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        List<String> wanted = idList(request.body(), "d_friend_requests_id_list");
        Map<String, PlayerContext> incoming = incomingMap(player, request.store());
        for (String ident : wanted) {
            if (!incoming.containsKey(ident)) {
                throw WireError.notFound("Local friend request not found");
            }
        }
        for (String ident : wanted) {
            PlayerContext owner = incoming.get(ident);
            owner.put("FriendOutgoingRequests", withoutRequest(owner, ident));
            request.store().save(owner);
        }
        request.store().save(player);
        return boolResult(true);
    }

    private Map<String, Object> friendBreakOff(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body(), "player_id");
        PlayerContext target = request.store().loadById(ident);
        if (target == null) {
            throw WireError.notFound("Local player not found");
        }
        player.put("FriendPlayers", withoutFriend(WireFriends.friendIds(player), ident));
        target.put("FriendPlayers", withoutFriend(WireFriends.friendIds(target), player.id()));
        dropRequestsBetween(player, target);
        player.entity().friendCount = WireFriends.friendIds(player).size();
        target.entity().friendCount = WireFriends.friendIds(target).size();
        request.store().save(target);
        request.store().save(player);
        return boolResult(true);
    }

    private Map<String, Object> friendReadHistory(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        player.entity().isAlreadyReadFriendRequest = true;
        request.store().save(player);
        return Map.of();
    }

    /** Python {@code _befriend}：双向加好友并清掉两人之间的申请。 */
    private boolean befriend(PlayerContext player, PlayerContext other) {
        int cap = friendMax();
        List<Object> mine = new ArrayList<>(WireFriends.friendIds(player));
        List<Object> theirs = new ArrayList<>(WireFriends.friendIds(other));
        if (WireFriends.containsId(mine, other.id())) {
            return true;
        }
        if (mine.size() >= cap || theirs.size() >= cap) {
            return false;
        }
        mine.add(other.id());
        theirs.add(player.id());
        player.put("FriendPlayers", mine);
        other.put("FriendPlayers", theirs);
        dropRequestsBetween(player, other);
        player.entity().friendCount = mine.size();
        other.entity().friendCount = theirs.size();
        return true;
    }

    private static List<Object> withoutFriend(List<Object> ids, String ident) {
        List<Object> kept = new ArrayList<>();
        for (Object pid : ids) {
            if (!ident.equals(String.valueOf(pid))) {
                kept.add(pid);
            }
        }
        return kept;
    }

    private static List<Map<String, Object>> withoutRequest(PlayerContext player, String ident) {
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> row : WireFriends.outgoingRequests(player)) {
            if (!ident.equals(text(row.get("DFriendRequestsId")))) {
                kept.add(row);
            }
        }
        return kept;
    }

    private static void dropRequestsBetween(PlayerContext left, PlayerContext right) {
        left.put("FriendOutgoingRequests", withoutRequest(left, right.id(), "PlayerId"));
        right.put("FriendOutgoingRequests", withoutRequest(right, left.id(), "PlayerId"));
    }

    private static List<Map<String, Object>> withoutRequest(PlayerContext player, String value,
                                                            String field) {
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> row : WireFriends.outgoingRequests(player)) {
            if (!value.equals(String.valueOf(row.get(field)))) {
                kept.add(row);
            }
        }
        return kept;
    }

    private record Incoming(PlayerContext owner, Map<String, Object> request) {
    }

    /** Python {@code _incoming_requests}：别人发给 {@code player} 的申请。 */
    private List<Incoming> incomingRequests(PlayerContext player, PlayerStore store) {
        List<Incoming> result = new ArrayList<>();
        for (PlayerContext owner : allPlayers(store)) {
            if (owner.id().equals(player.id())) {
                continue;
            }
            for (Map<String, Object> request : WireFriends.outgoingRequests(owner)) {
                if (player.id().equals(String.valueOf(request.get("PlayerId")))
                        && J.truthy(request.get("DFriendRequestsId"))) {
                    result.add(new Incoming(owner, request));
                }
            }
        }
        return result;
    }

    private Map<String, PlayerContext> incomingMap(PlayerContext player, PlayerStore store) {
        Map<String, PlayerContext> result = new LinkedHashMap<>();
        for (Incoming incoming : incomingRequests(player, store)) {
            result.put(text(incoming.request().get("DFriendRequestsId")), incoming.owner());
        }
        return result;
    }

    private Map<String, Object> friendInfo(PlayerContext target, PlayerContext player,
                                           String requestId) {
        String ident = requestId == null || requestId.isEmpty()
                ? friendRecordId(player.id(), target.id()) : requestId;
        return J.map(
                "DFriendsId", ident,
                "PlayerId", target.id(),
                "Name", text(target.entity().playerName),
                "PlayerLevel", (long) target.entity().playerLevel,
                "UserType", target.entity().temporary ? 0 : 1,
                "Comment", text(target.entity().comment),
                "ProfileIconPartsInfo", target.str("ProfileIconPartsInfo", ""),
                "LastLogin", lastLoginText(target),
                "FriendNum", WireFriends.friendIds(target).size(),
                "FriendRequestStatus", WireFriends.friendRequestStatus(player, target));
    }

    private Map<String, Object> requestInfo(PlayerContext target, String requestId) {
        return J.map(
                "DFriendRequestsId", requestId,
                "PlayerId", target.id(),
                "Name", text(target.entity().playerName),
                "PlayerLevel", (long) target.entity().playerLevel,
                "Comment", text(target.entity().comment),
                "ProfileIconPartsInfo", target.str("ProfileIconPartsInfo", ""),
                "LastLogin", lastLoginText(target));
    }

    private static String friendRecordId(String left, String right) {
        String lo = left.compareTo(right) <= 0 ? left : right;
        String hi = left.compareTo(right) <= 0 ? right : left;
        return "friend:" + lo + ":" + hi;
    }

    private int friendMax() {
        Map<String, Object> template = catalog.profileTemplate();
        if (template != null && J.truthy(template.get("friend_max_num"))) {
            return J.intOr(template.get("friend_max_num"), PACKAGED_FRIEND_MAX);
        }
        return PACKAGED_FRIEND_MAX;
    }

    // ------------------------------------------------------------------
    // 参数
    // ------------------------------------------------------------------

    private static String id(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String text) || text.isBlank() || text.length() > 128) {
            throw WireError.badRequest(
                    key + " must be a non-empty string of at most 128 characters");
        }
        return text;
    }

    private static List<String> idList(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof List<?> list)) {
            throw WireError.badRequest(key + " must be a list of strings");
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank() || text.length() > 128) {
                throw WireError.badRequest(
                        key + " entries must be non-empty strings of at most 128 characters");
            }
            out.add(text);
        }
        return out;
    }

    private static boolean booleanOrTrue(Object value, String key) {
        if (value == null) {
            return true;
        }
        if (!(value instanceof Boolean flag)) {
            throw WireError.badRequest(key + " must be a boolean or null");
        }
        return flag;
    }

    private Map<String, Object> resultCode(int code) {
        return codec.encode("FollowSetFollowResponse", J.map("ResultCode", code));
    }

    private Map<String, Object> boolResult(boolean ok) {
        return codec.encode("FriendSetCommonResponse", J.map("Result", ok));
    }

    private static String text(Object value) {
        String text = J.str(value);
        return text == null ? "" : text;
    }

    /** Python {@code _timestamp}：秒级时间戳转 ISO-8601（UTC、带 {@code Z}）。 */
    static String timestampText(Object value) {
        if (value instanceof String text) {
            return text;
        }
        return WireState.timestamp(J.longOr(value, 0L));
    }

    private static String lastLoginText(PlayerContext target) {
        long lastLogin = target.entity().lastLogin > 0
                ? target.entity().lastLogin : target.entity().created;
        return WireState.timestamp(lastLogin);
    }
}
