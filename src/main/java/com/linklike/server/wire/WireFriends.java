package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import com.linklike.server.service.PlayerContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 好友 / 关注关系的读取 helper —— 对应 Python 版 {@code wire_services.py} 里的
 * {@code FRIEND_STATUS_*} 常量与 {@code _follow_ids} / {@code _friend_ids} /
 * {@code _outgoing_requests} / {@code friend_request_status}。
 *
 * <p>只包含 {@code /v1/profile/get_info} 需要的部分；好友列表、申请与关注等写接口
 * （{@code wire_services.py} 的其余部分）还没移植，相关路径仍是显式 501。
 */
public final class WireFriends {

    private WireFriends() {
    }

    public static final int FRIEND_STATUS_NOT_REQUESTED = 0;
    public static final int FRIEND_STATUS_REQUESTABLE = 1;
    public static final int FRIEND_STATUS_REQUESTED = 2;
    public static final int FRIEND_STATUS_WAITING = 3;
    public static final int FRIEND_STATUS_ALREADY = 4;

    /** 关注列表（去重保序）。 */
    public static List<Object> followIds(PlayerContext player) {
        return distinct(player.raw("FollowedPlayers"));
    }

    /** 好友列表（去重保序）。 */
    public static List<Object> friendIds(PlayerContext player) {
        return distinct(player.raw("FriendPlayers"));
    }

    /** 自己发出的好友申请。 */
    public static List<Map<String, Object>> outgoingRequests(PlayerContext player) {
        return J.rowsOr(player.raw("FriendOutgoingRequests"));
    }

    /**
     * 两个账号之间的好友申请状态，对应 Python 版
     * {@code wire_services.friend_request_status}。
     */
    public static int friendRequestStatus(PlayerContext viewer, PlayerContext target) {
        if (viewer.id().equals(target.id())) {
            return FRIEND_STATUS_NOT_REQUESTED;
        }
        if (containsId(friendIds(viewer), target.id())
                || containsId(friendIds(target), viewer.id())) {
            return FRIEND_STATUS_ALREADY;
        }
        for (Map<String, Object> row : outgoingRequests(viewer)) {
            if (target.id().equals(String.valueOf(row.get("PlayerId")))) {
                return FRIEND_STATUS_REQUESTED;
            }
        }
        for (Map<String, Object> row : outgoingRequests(target)) {
            if (viewer.id().equals(String.valueOf(row.get("PlayerId")))) {
                return FRIEND_STATUS_WAITING;
            }
        }
        return FRIEND_STATUS_REQUESTABLE;
    }

    /**
     * 去重保序，对应 Python 的 {@code list(dict.fromkeys(values))}。
     *
     * <p>与 Python 一样保留 {@code null}（它也是一个键值），不做过滤。
     */
    private static List<Object> distinct(Object value) {
        LinkedHashSet<Object> seen = new LinkedHashSet<>();
        for (Object item : J.listOrEmpty(value)) {
            seen.add(item);
        }
        return new ArrayList<>(seen);
    }

    public static boolean containsId(List<Object> ids, Object value) {
        for (Object candidate : ids) {
            if (candidate != null && String.valueOf(candidate).equals(String.valueOf(value))) {
                return true;
            }
        }
        return false;
    }
}
