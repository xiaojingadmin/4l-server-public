package com.linklike.server.wire;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklike.server.domain.Player;
import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireError;
import com.linklike.server.service.PlayerContext;
import com.linklike.server.service.PlayerStore;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 社团（Circle / Guild）—— 对应 Python {@code wire_circle.py} 的完整移植。
 *
 * <p>社团记录只存在队长账号上：{@code GuildId} / {@code SearchGuildKey} / {@code GuildName} /
 * {@code GuildComment} / {@code GuildPolicy*} / {@code GuildIconPartsInfo} / {@code ChatRoomsId}
 * 描述社团本身，{@code circle_members} / {@code circle_chat_logs} / {@code circle_invitations} /
 * {@code circle_join_requests} 挂在队长下；普通成员只保存 {@code GuildId} /
 * {@code SearchGuildKey} / {@code GuildName}，读取时按 {@code GuildId} 找到队长再展开。
 * 转让队长时整份记录搬到新队长。
 *
 * <p>本地没有社团排行与对抗赛积分的结算规则，也没有官方 {@code get_emoji_list} 通道：
 * {@code rank} / {@code point} / 成员排行保持 0 或空，{@code guild_rank} 固定最低档 E，
 * {@code emoji_list} / {@code membership_list} 为空表。
 *
 * <p>与 Python 的一处差异：Python 在写多个账号时先深拷贝、再逐个保存，失败会回滚已保存的
 * 账号；Java 直接改已装载的账号并按同样的顺序保存，中途保存失败时先前的账号保持已写入
 * （数据库写入失败本身不是本地可恢复的状态）。
 */
@Component
public class WireCircleModule implements WireModule {

    static final int MAX_MEMBERS = 30;
    static final int MAX_NAME = 20;
    static final int MAX_COMMENT = 200;
    static final int MAX_CHAT = 200;
    static final int MAX_CHAT_LOGS = 500;
    static final int MAX_REQUEST_NUM = 99;
    static final int PAGE_SIZE = 20;
    static final long POLLING_INTERVAL = 5000;
    static final int SEARCH_KEY_LENGTH = 9;
    static final String SEARCH_KEY_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    static final int POSITION_MEMBER = 0;
    static final int POSITION_LEADER = 1;
    static final int POSITION_SUB_LEADER = 2;
    static final int GUILD_RANK_E = 1;
    static final List<Integer> POLICY_ACTIONS = List.of(1, 2, 3, 4, 5);
    static final int RECRUITING_AUTOMATIC = 1;
    static final int RECRUITING_APPROVAL = 2;
    static final int RECRUITING_SOLICITATION_ONLY = 3;
    static final List<Integer> RECRUITING_POLICIES = List.of(1, 2, 3);

    static final int CHAT_MESSAGE = 1;
    static final int CHAT_ITEM_REQUEST = 3;
    static final int CHAT_CIRCLE_CREATE = 11;
    static final int CHAT_CIRCLE_SET_SETTING = 12;
    static final int CHAT_CIRCLE_MEMBER_JOIN = 13;
    static final int CHAT_CIRCLE_MEMBER_OUT = 14;
    static final int CHAT_CIRCLE_TRANSFER_LEADER = 15;
    static final int CHAT_CIRCLE_SET_POSITION = 16;

    static final List<String> CIRCLE_FIELDS = List.of("GuildId", "SearchGuildKey", "GuildName",
            "GuildComment", "GuildPolicyAction", "GuildPolicyRecruiting", "GuildIconPartsInfo",
            "ChatRoomsId");
    static final List<String> CIRCLE_LISTS = List.of("circle_members", "circle_chat_logs",
            "circle_invitations", "circle_join_requests");
    static final List<String> MEMBER_FIELDS = List.of("GuildId", "SearchGuildKey", "GuildName");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter MICRO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    private final PlayerStore players;
    private final ObjectMapper mapper;

    public WireCircleModule(PlayerStore players, ObjectMapper mapper) {
        this.players = players;
        this.mapper = mapper;
    }

    @Override
    public void register(WireRouter router) {
        router.register("/v1/circle/get_list", this::search);
        router.register("/v1/circle/get_info", this::info);
        router.register("/v1/circle/get_detail", this::detail);
        router.register("/v1/circle/get_invite_list", this::inviteList);
        router.register("/v1/circle/get_invite_and_join_info", this::inviteAndJoinInfo);
        router.register("/v1/circle/get_circle_top_info", this::topInfo);
        router.register("/v1/circle/get_chat_log_list", this::chatLogList);

        router.register("/v1/circle/set_create", this::create);
        router.register("/v1/circle/set_join", this::join);
        router.register("/v1/circle/set_out", this::out);
        router.register("/v1/circle/set_setting", this::setSetting);
        router.register("/v1/circle/set_invite", this::invite);
        router.register("/v1/circle/set_cancel_invite", this::cancelInvite);
        router.register("/v1/circle/set_approve_invite", request -> answerInvite(request, true));
        router.register("/v1/circle/set_reject_invite", request -> answerInvite(request, false));
        router.register("/v1/circle/set_approve_join", request -> answerJoin(request, true));
        router.register("/v1/circle/set_reject_join", request -> answerJoin(request, false));
        router.register("/v1/circle/set_cancel_join", this::cancelJoin);
        router.register("/v1/circle/set_position", request -> setPosition(request, null));
        router.register("/v1/circle/set_dismissal",
                request -> setPosition(request, POSITION_MEMBER));
        router.register("/v1/circle/set_transfer_leader", this::transferLeader);
        router.register("/v1/circle/set_expulsion", this::expel);
        router.register("/v1/circle/set_dissolution", this::dissolve);
        router.register("/v1/circle/set_chat_message", this::chat);
        router.register("/v1/circle/set_item_request", this::itemRequest);
        router.register("/v1/circle/set_donation", this::donation);
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /** Python {@code _search}：本地社团的简单条件过滤 + 分页。 */
    private Map<String, Object> search(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> conditions = searchConditions(request.arg("search_conditions"));
        long page = WireServices.integer(conditions, "page_num", 1L, 0L, Long.MAX_VALUE);
        page = Math.max(page, 1);

        Object name = conditions.get("guild_name");
        Object action = conditions.get("guild_policy_action");
        Object recruiting = conditions.get("guild_policy_recruiting");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PlayerContext holder : holders(request.store())) {
            if (name instanceof String text && !text.isBlank()
                    && !text.toLowerCase().contains(
                            orEmpty(holder.raw("GuildName")).toLowerCase())) {
                continue;
            }
            if (action instanceof Integer value && value != 0
                    && J.intOr(holder.raw("GuildPolicyAction"), 0) != value) {
                continue;
            }
            if (recruiting instanceof Integer value && value != 0
                    && J.intOr(holder.raw("GuildPolicyRecruiting"), 0) != value) {
                continue;
            }
            rows.add(circleInfo(holder, player));
        }
        long total = Math.max((rows.size() + PAGE_SIZE - 1) / PAGE_SIZE, 1);
        int start = (int) ((page - 1) * PAGE_SIZE);
        List<Map<String, Object>> pageRows = new ArrayList<>();
        for (int index = start; index < Math.min(rows.size(), start + PAGE_SIZE); index++) {
            pageRows.add(rows.get(index));
        }
        return J.map("circle_info_list", pageRows, "page_num", page, "total_page", total);
    }

    private Map<String, Object> info(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        PlayerContext holder = holderByKey(request.store(), request.body());
        return J.map("circle_info", circleInfo(holder, player));
    }

    private Map<String, Object> detail(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        PlayerContext holder = holderByKey(request.store(), request.body());
        return J.map("circle_info", circleInfo(holder, player),
                "circle_member_list", members(holder, request.store(), player),
                "circle_ranking_info", ranking());
    }

    private Map<String, Object> inviteList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Pending found = findInvitation(request.store(), player.id());
        List<Map<String, Object>> rows = new ArrayList<>();
        if (found != null) {
            rows.add(invitationInfo(found.holder(), found.row()));
        }
        return J.map("invitations_list", rows);
    }

    private Map<String, Object> inviteAndJoinInfo(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        PlayerContext holder = holderByKey(request.store(), request.body());
        if (position(holder, player.id()) == null) {
            throw WireError.conflict("This account is not a member of that circle");
        }
        return J.map(
                "invite_info_list", inviteJoinRows(holder, "circle_invitations",
                        request.store(), player),
                "join_info_list", inviteJoinRows(holder, "circle_join_requests",
                        request.store(), player));
    }

    private Map<String, Object> topInfo(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        PlayerContext holder = ownHolder(player, request.store());
        Map<String, Object> itemResult = holder == null
                ? J.map("item_id", 0, "num", 0) : takeRequestResult(player, request.store());
        if (holder == null) {
            return J.map("circle_info", emptyCircleInfo(),
                    "circle_member_list", new ArrayList<>(),
                    "circle_ranking_info", ranking(),
                    "item_request_result", itemResult,
                    "emoji_list", new ArrayList<>(), "membership_list", new ArrayList<>());
        }
        return J.map("circle_info", circleInfo(holder, player),
                "circle_member_list", members(holder, request.store(), player),
                "circle_ranking_info", ranking(),
                "item_request_result", itemResult,
                "emoji_list", new ArrayList<>(), "membership_list", new ArrayList<>());
    }

    private Map<String, Object> chatLogList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        PlayerContext holder = ownHolder(player, request.store());
        List<Map<String, Object>> rows = new ArrayList<>();
        if (holder != null) {
            rows.addAll(J.rowsOr(holder.raw("circle_chat_logs")));
        }
        long latest = 0;
        for (Map<String, Object> row : rows) {
            latest = Math.max(latest, J.longOr(row.get("order_id"), 0L));
        }
        long diff = WireServices.integer(request.body(), "diff_order_id", 0L, 0L, Long.MAX_VALUE);
        if (Boolean.TRUE.equals(request.arg("is_item_request"))) {
            rows.removeIf(row -> J.intOr(row.get("chat_type"), 0) != CHAT_ITEM_REQUEST);
        }
        rows.removeIf(row -> J.longOr(row.get("order_id"), 0L) <= diff);
        rows.sort(Comparator.comparingLong(
                row -> -J.longOr(row.get("order_id"), 0L)));

        LinkedHashSet<Object> authorIds = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            if (J.truthy(row.get("player_id"))) {
                authorIds.add(row.get("player_id"));
            }
        }
        List<Map<String, Object>> authors = new ArrayList<>();
        for (Object pid : authorIds) {
            PlayerContext target = request.store().loadById(String.valueOf(pid));
            if (target == null) {
                continue;
            }
            authors.add(J.map(
                    "player_id", pid,
                    "player_name", text(target.entity().playerName),
                    "user_type", userType(target),
                    "profile_icon_parts_info", target.str("ProfileIconPartsInfo", ""),
                    "is_mute_player", muted(player).contains(String.valueOf(pid))));
        }
        return J.map("circle_chat_log_list", rows,
                "commenter_info_list", authors,
                "polling_interval", holder == null ? 0L : POLLING_INTERVAL,
                "latest_chat_order_id", latest);
    }

    /** Python {@code _take_request_result}：捐赠到账只上报一次，读完即清空。 */
    private Map<String, Object> takeRequestResult(PlayerContext player, PlayerStore store) {
        List<Map<String, Object>> pending =
                J.rowsOr(player.raw("circle_item_request_results"));
        if (pending.isEmpty()) {
            return J.map("item_id", 0, "num", 0);
        }
        int itemId = J.intOr(pending.get(pending.size() - 1).get("item_id"), 0);
        long num = 0;
        for (Map<String, Object> row : pending) {
            if (J.intOr(row.get("item_id"), 0) == itemId) {
                num += J.longOr(row.get("num"), 0L);
            }
        }
        player.put("circle_item_request_results", new ArrayList<>());
        store.save(player);
        return J.map("item_id", itemId, "num", num);
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    private Map<String, Object> create(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> setting = setting(request.body());
        if (J.truthy(player.raw("GuildId"))) {
            throw WireError.conflict("This account is already in a circle");
        }
        if (findJoinRequest(request.store(), player.id()) != null) {
            throw WireError.conflict("Cancel the pending join request first");
        }
        player.put("GuildName", setting.get("GuildName"));
        player.put("GuildComment", setting.get("GuildComment"));
        player.put("GuildPolicyAction", setting.get("GuildPolicyAction"));
        player.put("GuildPolicyRecruiting", setting.get("GuildPolicyRecruiting"));
        player.put("GuildIconPartsInfo", setting.get("GuildIconPartsInfo"));
        player.put("GuildId", UUID.randomUUID().toString());
        player.put("SearchGuildKey", newSearchKey(request.store()));
        player.put("ChatRoomsId", UUID.randomUUID().toString());
        for (String key : CIRCLE_LISTS) {
            player.put(key, new ArrayList<>());
        }
        enrol(player, player, POSITION_LEADER);
        log(player, CHAT_CIRCLE_CREATE, player.id(), "");
        refreshFlags(player);
        request.store().save(player);
        return J.map("circle_info", circleInfo(player, player));
    }

    private Map<String, Object> join(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        PlayerContext holder = holderByKey(request.store(), request.body());
        if (J.truthy(player.raw("GuildId"))) {
            throw WireError.conflict("This account is already in a circle");
        }
        String pid = player.id();
        if (containsPlayer(holder.raw("circle_join_requests"), pid)) {
            return result(true);
        }
        if (findJoinRequest(request.store(), pid) != null) {
            throw WireError.conflict("A join request to another circle is pending");
        }
        int recruiting = J.intOr(holder.raw("GuildPolicyRecruiting"), 0);
        boolean invited = containsPlayer(holder.raw("circle_invitations"), pid);
        if (recruiting == RECRUITING_SOLICITATION_ONLY && !invited) {
            throw WireError.conflict("This circle only accepts invited players");
        }
        if (memberCount(holder) >= MAX_MEMBERS) {
            throw WireError.conflict("This circle is full");
        }
        if (recruiting == RECRUITING_AUTOMATIC || invited) {
            enrol(holder, player, POSITION_MEMBER);
            refreshFlags(holder);
            request.store().save(holder);
            request.store().save(player);
            return result(true);
        }
        List<Object> requests = new ArrayList<>(
                J.listOrEmpty(holder.raw("circle_join_requests")));
        requests.add(pendingRow(pid));
        holder.put("circle_join_requests", requests);
        refreshFlags(holder);
        request.store().save(holder);
        return result(true);
    }

    private Map<String, Object> out(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        PlayerContext holder = requireHolder(player, request.store());
        if (position(holder, player.id()) == POSITION_LEADER) {
            throw WireError.conflict("Transfer leadership or dissolve the circle first");
        }
        leave(holder, player, request.store(), player.id());
        return result(true);
    }

    private Map<String, Object> setSetting(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        PlayerContext holder = holderByKey(request.store(), request.body());
        Map<String, Object> setting = setting(request.body());
        requireOfficer(holder, player);
        boolean renamed = !String.valueOf(setting.get("GuildName"))
                .equals(orEmpty(holder.raw("GuildName")));
        holder.put("GuildName", setting.get("GuildName"));
        holder.put("GuildComment", setting.get("GuildComment"));
        holder.put("GuildPolicyAction", setting.get("GuildPolicyAction"));
        holder.put("GuildPolicyRecruiting", setting.get("GuildPolicyRecruiting"));
        holder.put("GuildIconPartsInfo", setting.get("GuildIconPartsInfo"));
        log(holder, CHAT_CIRCLE_SET_SETTING, player.id(), "");
        request.store().save(holder);
        if (renamed) {
            for (Map<String, Object> row : J.rowsOr(holder.raw("circle_members"))) {
                if (holder.id().equals(row.get("player_id"))) {
                    continue;
                }
                PlayerContext member = request.store().loadById(
                        String.valueOf(row.get("player_id")));
                if (member != null) {
                    member.put("GuildName", setting.get("GuildName"));
                    request.store().save(member);
                }
            }
        }
        return result(true);
    }

    private Map<String, Object> invite(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        id(request.body(), "player_id");
        PlayerContext holder = requireHolder(player, request.store());
        PlayerContext target = byId(request);
        requireOfficer(holder, player);
        if (target.id().equals(player.id()) || J.truthy(target.raw("GuildId"))) {
            throw WireError.conflict("That player is already in a circle");
        }
        if (containsPlayer(holder.raw("circle_invitations"), target.id())) {
            return result(true);
        }
        if (findInvitation(request.store(), target.id()) != null) {
            throw WireError.conflict("That player already has a pending invitation");
        }
        if (memberCount(holder) >= MAX_MEMBERS) {
            throw WireError.conflict("This circle is full");
        }
        List<Object> invitations = new ArrayList<>(
                J.listOrEmpty(holder.raw("circle_invitations")));
        invitations.add(pendingRow(target.id()));
        holder.put("circle_invitations", invitations);
        target.entity().isCircleInviteUser = true;
        request.store().save(target);
        request.store().save(holder);
        return result(true);
    }

    private Map<String, Object> cancelInvite(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body(), "d_guild_invitations_id");
        PlayerContext holder = requireHolder(player, request.store());
        requireOfficer(holder, player);
        Map<String, Object> row = findPending(holder, "circle_invitations", ident);
        if (row == null) {
            throw WireError.notFound("Local invitation not found");
        }
        dropPending(holder, "circle_invitations", null, ident);
        PlayerContext target = request.store().loadById(String.valueOf(row.get("player_id")));
        if (target != null) {
            target.entity().isCircleInviteUser = false;
            request.store().save(target);
        }
        request.store().save(holder);
        return result(true);
    }

    private Map<String, Object> answerInvite(WireRequest request, boolean accept) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body(), "d_guild_invitations_id");
        Pending found = findInvitation(request.store(), player.id());
        if (found == null || !ident.equals(J.str(found.row().get("d_guild_invitations_id")))) {
            throw WireError.notFound("Local invitation not found");
        }
        PlayerContext holder = found.holder();
        player.entity().isCircleInviteUser = false;
        if (!accept) {
            dropPending(holder, "circle_invitations", null, ident);
            request.store().save(holder);
            request.store().save(player);
            return result(true);
        }
        if (J.truthy(player.raw("GuildId"))) {
            throw WireError.conflict("This account is already in a circle");
        }
        if (memberCount(holder) >= MAX_MEMBERS) {
            throw WireError.conflict("This circle is full");
        }
        enrol(holder, player, POSITION_MEMBER);
        Pending other = findJoinRequest(request.store(), player.id());
        if (other != null && !other.holder().id().equals(holder.id())) {
            dropPending(other.holder(), "circle_join_requests", player.id(), null);
            refreshFlags(other.holder());
            request.store().save(other.holder());
        }
        request.store().save(holder);
        request.store().save(player);
        return result(true);
    }

    private Map<String, Object> answerJoin(WireRequest request, boolean accept) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body(), "d_guild_invitations_id");
        PlayerContext holder = requireHolder(player, request.store());
        requireOfficer(holder, player);
        Map<String, Object> row = findPending(holder, "circle_join_requests", ident);
        if (row == null) {
            throw WireError.notFound("Local join request not found");
        }
        dropPending(holder, "circle_join_requests", null, ident);
        refreshFlags(holder);
        if (!accept) {
            request.store().save(holder);
            return result(true);
        }
        PlayerContext target = request.store().loadById(String.valueOf(row.get("player_id")));
        if (target == null || J.truthy(target.raw("GuildId"))) {
            request.store().save(holder);
            throw WireError.conflict(
                    "That player is no longer available; the request was dropped");
        }
        if (memberCount(holder) >= MAX_MEMBERS) {
            throw WireError.conflict("This circle is full");
        }
        enrol(holder, target, POSITION_MEMBER);
        request.store().save(holder);
        request.store().save(target);
        return result(true);
    }

    private Map<String, Object> cancelJoin(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String ident = id(request.body(), "d_guild_invitations_id");
        Pending found = findJoinRequest(request.store(), player.id());
        if (found == null || !ident.equals(J.str(found.row().get("d_guild_invitations_id")))) {
            throw WireError.notFound("Local join request not found");
        }
        dropPending(found.holder(), "circle_join_requests", null, ident);
        refreshFlags(found.holder());
        request.store().save(found.holder());
        return result(true);
    }

    private Map<String, Object> setPosition(WireRequest request, Integer position) {
        PlayerContext player = request.requirePlayer();
        int wanted = position == null
                ? (int) WireServices.integer(request.body(), "position_type",
                        (Integer) null, POSITION_MEMBER, POSITION_SUB_LEADER)
                : position;
        String targetId = id(request.body(), "player_id");
        PlayerContext holder = requireHolder(player, request.store());
        requireLeader(holder, player);
        Map<String, Object> row = memberRow(holder, targetId);
        if (row == null) {
            throw WireError.notFound("That player is not a member of this circle");
        }
        if (targetId.equals(player.id())) {
            throw WireError.conflict("The leader cannot change their own position");
        }
        if (J.intOr(row.get("position_type"), 0) != wanted) {
            row.put("position_type", wanted);
            log(holder, CHAT_CIRCLE_SET_POSITION, targetId, "");
            request.store().save(holder);
        }
        return result(true);
    }

    private Map<String, Object> transferLeader(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String targetId = id(request.body(), "player_id");
        PlayerContext holder = requireHolder(player, request.store());
        requireLeader(holder, player);
        if (targetId.equals(player.id())) {
            throw WireError.conflict("The leader cannot transfer leadership to themselves");
        }
        if (memberRow(holder, targetId) == null) {
            throw WireError.notFound("That player is not a member of this circle");
        }
        PlayerContext target = request.store().loadById(targetId);
        if (target == null) {
            throw WireError.notFound("Local player not found");
        }
        for (Map<String, Object> row : J.rowsOr(holder.raw("circle_members"))) {
            if (targetId.equals(row.get("player_id"))) {
                row.put("position_type", POSITION_LEADER);
            } else if (player.id().equals(row.get("player_id"))) {
                row.put("position_type", POSITION_MEMBER);
            }
        }
        log(holder, CHAT_CIRCLE_TRANSFER_LEADER, targetId, "");

        for (String key : CIRCLE_FIELDS) {
            target.put(key, WireServices.deepCopyJson(holder.raw(key)));
        }
        for (String key : CIRCLE_LISTS) {
            target.put(key, WireServices.deepCopyJson(holder.raw(key)));
        }
        target.entity().existsCircleApprovalPendingFromUser =
                holder.entity().existsCircleApprovalPendingFromUser;

        List<String> previousName = new ArrayList<>();
        for (String key : MEMBER_FIELDS) {
            previousName.add(J.str(holder.raw(key)));
        }
        clearCircle(holder);
        for (int index = 0; index < MEMBER_FIELDS.size(); index++) {
            holder.put(MEMBER_FIELDS.get(index), previousName.get(index) == null
                    ? "" : previousName.get(index));
        }
        request.store().save(target);
        request.store().save(holder);
        return result(true);
    }

    private Map<String, Object> expel(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        String targetId = id(request.body(), "player_id");
        PlayerContext holder = requireHolder(player, request.store());
        requireOfficer(holder, player);
        Map<String, Object> row = memberRow(holder, targetId);
        if (row == null) {
            throw WireError.notFound("That player is not a member of this circle");
        }
        int targetPosition = J.intOr(row.get("position_type"), 0);
        if (targetId.equals(player.id()) || targetPosition == POSITION_LEADER) {
            throw WireError.conflict("The leader cannot be expelled");
        }
        if (targetPosition == POSITION_SUB_LEADER
                && position(holder, player.id()) != POSITION_LEADER) {
            throw WireError.conflict("Only the leader may expel a sub-leader");
        }
        PlayerContext target = request.store().loadById(targetId);
        if (target == null) {
            throw WireError.notFound("Local player not found");
        }
        leave(holder, target, request.store(), targetId);
        return result(true);
    }

    private Map<String, Object> dissolve(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        PlayerContext holder = requireHolder(player, request.store());
        requireLeader(holder, player);
        for (Map<String, Object> row : J.rowsOr(holder.raw("circle_members"))) {
            if (player.id().equals(row.get("player_id"))) {
                continue;
            }
            PlayerContext member = request.store().loadById(String.valueOf(row.get("player_id")));
            if (member != null) {
                clearMembership(member);
                member.entity().isCircleDissolutionUser = true;
                request.store().save(member);
            }
        }
        for (Map<String, Object> row : J.rowsOr(holder.raw("circle_invitations"))) {
            PlayerContext invitee = request.store().loadById(String.valueOf(row.get("player_id")));
            if (invitee != null) {
                invitee.entity().isCircleInviteUser = false;
                request.store().save(invitee);
            }
        }
        clearCircle(holder);
        request.store().save(holder);
        return result(true);
    }

    private Map<String, Object> chat(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> info = jsonObject(request.body(), "guild_chat_info");
        String room = id(info, "d_chat_rooms_id");
        String comment = text(info, "chat_comment", MAX_CHAT, true);
        PlayerContext holder = requireHolder(player, request.store());
        if (!room.equals(J.str(holder.raw("ChatRoomsId")))) {
            throw WireError.notFound("Local chat room not found");
        }
        log(holder, CHAT_MESSAGE, player.id(), comment);
        request.store().save(holder);
        return J.map("chat_comment", comment);
    }

    private Map<String, Object> itemRequest(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> info = jsonObject(request.body(), "request_item_info");
        String room = id(info, "d_chat_rooms_id");
        int itemId = (int) WireServices.integer(info, "m_items_id", (Long) null,
                1L, Long.MAX_VALUE);
        int num = (int) WireServices.integer(info, "item_num", (Long) null,
                1L, MAX_REQUEST_NUM);
        PlayerContext holder = requireHolder(player, request.store());
        if (!room.equals(J.str(holder.raw("ChatRoomsId")))) {
            throw WireError.notFound("Local chat room not found");
        }
        for (Map<String, Object> row : J.rowsOr(holder.raw("circle_chat_logs"))) {
            Map<String, Object> state = requestState(row);
            if (state != null && player.id().equals(row.get("player_id"))
                    && J.intOr(state.get("donated_num"), 0) < J.intOr(state.get("item_num"), 0)) {
                throw WireError.conflict("This account already has an open item request");
            }
        }
        log(holder, CHAT_ITEM_REQUEST, player.id(),
                writeJson(J.map("m_items_id", itemId, "item_num", num, "donated_num", 0)));
        request.store().save(holder);
        return result(true);
    }

    private Map<String, Object> donation(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> info = jsonObject(request.body(), "donation_item_info");
        String ident = id(info, "d_chat_datas_id");
        int num = (int) WireServices.integer(info, "item_num", (Long) null, 1L, Long.MAX_VALUE);
        PlayerContext holder = requireHolder(player, request.store());
        Map<String, Object> row = null;
        for (Map<String, Object> candidate : J.rowsOr(holder.raw("circle_chat_logs"))) {
            if (ident.equals(J.str(candidate.get("d_chat_datas_id")))) {
                row = candidate;
                break;
            }
        }
        Map<String, Object> state = row == null ? null : requestState(row);
        if (state == null) {
            throw WireError.notFound("Local item request not found");
        }
        if (player.id().equals(row.get("player_id"))) {
            throw WireError.conflict("Cannot donate to your own request");
        }
        int itemId = J.intOr(state.get("m_items_id"), 0);
        int remaining = J.intOr(state.get("item_num"), 0) - J.intOr(state.get("donated_num"), 0);
        if (remaining <= 0) {
            throw WireError.conflict("This item request is already fulfilled");
        }
        if (num > remaining) {
            throw WireError.badRequest("item_num exceeds the remaining request (" + remaining + ")");
        }
        String requesterId = J.str(row.get("player_id"));
        if (memberRow(holder, requesterId) == null) {
            throw WireError.conflict("The requester has left the circle");
        }
        PlayerContext donor = player.id().equals(holder.id()) ? holder
                : request.store().loadById(player.id());
        PlayerContext requester = requesterId.equals(holder.id()) ? holder
                : request.store().loadById(requesterId);
        if (donor == null || requester == null) {
            throw WireError.notFound("Local player not found");
        }
        Map<String, Object> template = itemTemplate(donor, itemId);
        if (template == null) {
            throw WireError.badRequest(
                    "Not enough items to donate (item " + itemId + ": have 0, need " + num + ")");
        }
        debitItem(donor, itemId, num);
        creditItem(requester, template, num);
        List<Object> results = new ArrayList<>(
                J.listOrEmpty(requester.raw("circle_item_request_results")));
        results.add(J.map("item_id", itemId, "num", num));
        requester.put("circle_item_request_results", results);
        state.put("donated_num", J.intOr(state.get("donated_num"), 0) + num);
        row.put("comment", writeJson(state));

        request.store().save(holder);
        if (donor != holder) {
            request.store().save(donor);
        }
        if (requester != holder && requester != donor) {
            request.store().save(requester);
        }
        return result(true);
    }

    // ------------------------------------------------------------------
    // 查找
    // ------------------------------------------------------------------

    static boolean isHolder(PlayerContext candidate) {
        return J.truthy(candidate.raw("GuildId"))
                && !J.rowsOr(candidate.raw("circle_members")).isEmpty();
    }

    /** 本地每个队长账号（携带社团记录的账号），按创建时间排序。 */
    private List<PlayerContext> holders(PlayerStore store) {
        List<PlayerContext> found = new ArrayList<>();
        for (Player summary : store.listPlayers()) {
            PlayerContext target = store.loadById(summary.playerId);
            if (target != null && isHolder(target)) {
                found.add(target);
            }
        }
        found.sort(Comparator.comparingLong(target -> target.entity().created));
        return found;
    }

    private PlayerContext holderBy(PlayerStore store, String key, Object value) {
        for (PlayerContext holder : holders(store)) {
            if (value == null ? holder.raw(key) == null : value.equals(holder.raw(key))) {
                return holder;
            }
        }
        return null;
    }

    /** 本账号所在社团的队长记录；不在社团里时为 null。 */
    private PlayerContext ownHolder(PlayerContext player, PlayerStore store) {
        Object guildId = player.raw("GuildId");
        if (!J.truthy(guildId)) {
            return null;
        }
        if (isHolder(player)) {
            return player;
        }
        return holderBy(store, "GuildId", guildId);
    }

    private PlayerContext requireHolder(PlayerContext player, PlayerStore store) {
        PlayerContext holder = ownHolder(player, store);
        if (holder == null) {
            throw WireError.notFound("This account is not in a local circle");
        }
        return holder;
    }

    private PlayerContext holderByKey(PlayerStore store, Map<String, Object> body) {
        PlayerContext holder = holderBy(store, "SearchGuildKey", id(body, "search_guild_key"));
        if (holder == null) {
            throw WireError.notFound("Local circle not found");
        }
        return holder;
    }

    private static Map<String, Object> memberRow(PlayerContext holder, Object pid) {
        for (Map<String, Object> row : J.rowsOr(holder.raw("circle_members"))) {
            if (pid == null ? row.get("player_id") == null
                    : String.valueOf(pid).equals(String.valueOf(row.get("player_id")))) {
                return row;
            }
        }
        return null;
    }

    private static Integer position(PlayerContext holder, String pid) {
        Map<String, Object> row = memberRow(holder, pid);
        return row == null ? null : J.intOr(row.get("position_type"), 0);
    }

    private static void requireOfficer(PlayerContext holder, PlayerContext player) {
        Integer position = position(holder, player.id());
        if (position == null
                || (position != POSITION_LEADER && position != POSITION_SUB_LEADER)) {
            throw WireError.conflict("Only the leader or a sub-leader may do this");
        }
    }

    private static void requireLeader(PlayerContext holder, PlayerContext player) {
        if (position(holder, player.id()) == null
                || position(holder, player.id()) != POSITION_LEADER) {
            throw WireError.conflict("Only the leader may do this");
        }
    }

    /** 一条待处理的邀请 / 加入申请：挂在哪个队长账号上，以及那一行本身。 */
    record Pending(PlayerContext holder, Map<String, Object> row) {
    }

    /** 发给 {@code pid} 的待处理邀请；没有时返回 null。 */
    private Pending findInvitation(PlayerStore store, String pid) {
        return findPending(store, "circle_invitations", pid);
    }

    /** {@code pid} 发出的待审批加入申请；没有时返回 null。 */
    private Pending findJoinRequest(PlayerStore store, String pid) {
        return findPending(store, "circle_join_requests", pid);
    }

    private Pending findPending(PlayerStore store, String key, String pid) {
        for (PlayerContext holder : holders(store)) {
            for (Map<String, Object> row : J.rowsOr(holder.raw(key))) {
                if (pid.equals(J.str(row.get("player_id")))) {
                    return new Pending(holder, row);
                }
            }
        }
        return null;
    }

    private static Map<String, Object> findPending(PlayerContext holder, String key, String ident) {
        for (Map<String, Object> row : J.rowsOr(holder.raw(key))) {
            if (ident.equals(J.str(row.get("d_guild_invitations_id")))) {
                return row;
            }
        }
        return null;
    }

    private static String newSearchKey(PlayerStore store) {
        List<PlayerContext> holders = new ArrayList<>();
        for (Player summary : store.listPlayers()) {
            PlayerContext candidate = store.loadById(summary.playerId);
            if (candidate != null && isHolder(candidate)) {
                holders.add(candidate);
            }
        }
        while (true) {
            StringBuilder key = new StringBuilder(SEARCH_KEY_LENGTH);
            for (int index = 0; index < SEARCH_KEY_LENGTH; index++) {
                key.append(SEARCH_KEY_ALPHABET.charAt(RANDOM.nextInt(SEARCH_KEY_ALPHABET.length())));
            }
            String candidate = key.toString();
            boolean taken = false;
            for (PlayerContext holder : holders) {
                if (candidate.equals(J.str(holder.raw("SearchGuildKey")))) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return candidate;
            }
        }
    }

    // ------------------------------------------------------------------
    // 视图
    // ------------------------------------------------------------------

    private static List<String> muted(PlayerContext viewer) {
        List<String> result = new ArrayList<>();
        for (Object pid : J.listOrEmpty(viewer.raw("muted_players"))) {
            result.add(String.valueOf(pid));
        }
        return result;
    }

    private static Map<String, Object> person(PlayerContext target, PlayerContext viewer) {
        return J.map(
                "player_id", target.id(),
                "player_name", text(target.entity().playerName),
                "player_level", (long) target.entity().playerLevel,
                "user_type", userType(target),
                "profile_card_parts_info", target.str("ProfileCardPartsInfo", ""),
                "profile_icon_parts_info", target.str("ProfileIconPartsInfo", ""),
                "last_login_date_time", lastLogin(target),
                "is_mute_player", muted(viewer).contains(target.id()));
    }

    private static List<Map<String, Object>> members(PlayerContext holder, PlayerStore store,
                                                     PlayerContext viewer) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : J.rowsOr(holder.raw("circle_members"))) {
            PlayerContext target = store.loadById(String.valueOf(row.get("player_id")));
            if (target == null) {
                continue;
            }
            Map<String, Object> person = person(target, viewer);
            person.put("position_type", J.intOr(row.get("position_type"), 0));
            person.put("is_voice_chat_ban", J.truthy(row.get("is_voice_chat_ban")));
            rows.add(person);
        }
        rows.sort((left, right) -> {
            int byPosition = Boolean.compare(
                    J.intOr(left.get("position_type"), 0) != POSITION_LEADER,
                    J.intOr(right.get("position_type"), 0) != POSITION_LEADER);
            if (byPosition != 0) {
                return byPosition;
            }
            int byLevel = Long.compare(J.longOr(right.get("player_level"), 0L),
                    J.longOr(left.get("player_level"), 0L));
            if (byLevel != 0) {
                return byLevel;
            }
            return J.str(left.get("player_id")).compareTo(J.str(right.get("player_id")));
        });
        return rows;
    }

    private static List<Map<String, Object>> inviteJoinRows(PlayerContext holder, String key,
                                                            PlayerStore store,
                                                            PlayerContext viewer) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : J.rowsOr(holder.raw(key))) {
            PlayerContext target = store.loadById(String.valueOf(row.get("player_id")));
            if (target == null) {
                continue;
            }
            Map<String, Object> person = person(target, viewer);
            person.put("d_guild_invitations_id",
                    J.str(row.get("d_guild_invitations_id")) == null
                            ? "" : J.str(row.get("d_guild_invitations_id")));
            person.put("create_time", row.get("create_time"));
            out.add(person);
        }
        return out;
    }

    /** 本地没有社团排行结算：赛事名 / 月份留空，分数为 0。 */
    private static Map<String, Object> ranking() {
        return J.map("name", "", "rank", 0, "month", 0L, "point", 0L,
                "member_ranking_list", new ArrayList<>());
    }

    private static List<String> pendingIds(PlayerContext holder, String pid) {
        String invite = "";
        String join = "";
        for (Map<String, Object> row : J.rowsOr(holder.raw("circle_invitations"))) {
            if (pid.equals(J.str(row.get("player_id")))) {
                invite = J.str(row.get("d_guild_invitations_id"));
                break;
            }
        }
        for (Map<String, Object> row : J.rowsOr(holder.raw("circle_join_requests"))) {
            if (pid.equals(J.str(row.get("player_id")))) {
                join = J.str(row.get("d_guild_invitations_id"));
                break;
            }
        }
        return List.of(invite == null ? "" : invite, join == null ? "" : join);
    }

    private static String leaderId(PlayerContext holder) {
        for (Map<String, Object> row : J.rowsOr(holder.raw("circle_members"))) {
            if (J.intOr(row.get("position_type"), 0) == POSITION_LEADER) {
                return J.str(row.get("player_id"));
            }
        }
        return holder.id();
    }

    private static Map<String, Object> circleInfo(PlayerContext holder, PlayerContext viewer) {
        List<String> pending = pendingIds(holder, viewer.id());
        String invite = pending.get(0);
        String join = pending.get(1);
        return J.map(
                "search_guild_key", orEmpty(holder.raw("SearchGuildKey")),
                "guild_id", orEmpty(holder.raw("GuildId")),
                "guild_name", orEmpty(holder.raw("GuildName")),
                "leader_player_id", leaderId(holder),
                "guild_rank", GUILD_RANK_E,
                "guild_policy_action", J.intOr(holder.raw("GuildPolicyAction"), 0),
                "guild_policy_recruiting", J.intOr(holder.raw("GuildPolicyRecruiting"), 0),
                "guild_comment", orEmpty(holder.raw("GuildComment")),
                "guild_member_num", (long) memberCount(holder),
                "guild_icon_parts_info", orEmpty(holder.raw("GuildIconPartsInfo")),
                "d_chat_rooms_id", orEmpty(holder.raw("ChatRoomsId")),
                "feslive_rank_message", "",
                "d_guild_invitations_id", join.isEmpty() ? invite : join,
                "circle_invitations_id", invite,
                "circle_ranking_info", ranking(),
                "is_join_guild_ranking", false);
    }

    private static Map<String, Object> emptyCircleInfo() {
        return J.map(
                "search_guild_key", "", "guild_id", "", "guild_name", "",
                "leader_player_id", "", "guild_rank", 0,
                "guild_policy_action", 0, "guild_policy_recruiting", 0,
                "guild_comment", "", "guild_member_num", 0L, "guild_icon_parts_info", "",
                "d_chat_rooms_id", "", "feslive_rank_message", "",
                "d_guild_invitations_id", "", "circle_invitations_id", "",
                "circle_ranking_info", ranking(), "is_join_guild_ranking", false);
    }

    private static Map<String, Object> invitationInfo(PlayerContext holder,
                                                      Map<String, Object> row) {
        return J.map(
                "d_guild_invitations_id", orEmpty(row.get("d_guild_invitations_id")),
                "search_guild_key", orEmpty(holder.raw("SearchGuildKey")),
                "guild_name", orEmpty(holder.raw("GuildName")),
                "guild_rank", GUILD_RANK_E,
                "guild_policy_action", J.intOr(holder.raw("GuildPolicyAction"), 0),
                "guild_policy_recruiting", J.intOr(holder.raw("GuildPolicyRecruiting"), 0),
                "guild_member_num", (long) memberCount(holder),
                "guild_icon_parts_info", orEmpty(holder.raw("GuildIconPartsInfo")));
    }

    // ------------------------------------------------------------------
    // 变更 helper
    // ------------------------------------------------------------------

    private static void log(PlayerContext holder, int chatType, String pid, String comment) {
        List<Object> logs = new ArrayList<>(J.listOrEmpty(holder.raw("circle_chat_logs")));
        long order = 1;
        for (Object item : logs) {
            Map<String, Object> row = J.nodeOr(item);
            if (row != null) {
                order = Math.max(order, J.longOr(row.get("order_id"), 0L) + 1);
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("d_chat_datas_id", UUID.randomUUID().toString());
        row.put("chat_type", chatType);
        row.put("player_id", pid);
        row.put("comment", comment);
        row.put("order_id", order);
        row.put("create_time", now());
        logs.add(row);
        logs.sort(Comparator.comparingLong(item -> J.longOr(J.nodeOrEmpty(item).get("order_id"), 0L)));
        List<Object> kept = logs.size() > MAX_CHAT_LOGS
                ? new ArrayList<>(logs.subList(logs.size() - MAX_CHAT_LOGS, logs.size()))
                : logs;
        holder.put("circle_chat_logs", kept);
    }

    /** 解析 {@code chat_type} 3 日志里的申请内容；不是申请或格式错误时返回 null。 */
    private Map<String, Object> requestState(Map<String, Object> row) {
        if (J.intOr(row.get("chat_type"), 0) != CHAT_ITEM_REQUEST) {
            return null;
        }
        Map<String, Object> state;
        try {
            state = mapper.readValue(orEmpty(row.get("comment")), Map.class);
        } catch (JsonProcessingException | RuntimeException e) {
            return null;
        }
        if (state == null) {
            return null;
        }
        return J.map("m_items_id", J.intOr(state.get("m_items_id"), 0),
                "item_num", J.intOr(state.get("item_num"), 0),
                "donated_num", J.intOr(state.get("donated_num"), 0));
    }

    private static List<Map<String, Object>> itemRows(PlayerContext player, int itemId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> group : J.rowsOr(player.raw("Items"))) {
            for (Map<String, Object> row : J.rowsOr(group.get("UserItemList"))) {
                if (J.intOr(row.get("ItemId"), 0) == itemId) {
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static void debitItem(PlayerContext player, int itemId, int num) {
        List<Map<String, Object>> rows = itemRows(player, itemId);
        int have = 0;
        for (Map<String, Object> row : rows) {
            have += Math.max(0, J.intOr(row.get("ItemNum"), 0));
        }
        if (have < num) {
            throw WireError.badRequest("Not enough items to donate (item " + itemId
                    + ": have " + have + ", need " + num + ")");
        }
        int left = num;
        for (Map<String, Object> row : rows) {
            int owned = Math.max(0, J.intOr(row.get("ItemNum"), 0));
            int debit = Math.min(left, owned);
            row.put("ItemNum", J.intOr(row.get("ItemNum"), 0) - debit);
            left -= debit;
        }
    }

    /** 把 {@code num} 个 {@code template} 描述的道具加进目标背包。 */
    @SuppressWarnings("unchecked")
    private static void creditItem(PlayerContext player, Map<String, Object> template, int num) {
        int itemId = J.intOr(template.get("ItemId"), 0);
        List<Map<String, Object>> owned = itemRows(player, itemId);
        if (!owned.isEmpty()) {
            owned.get(0).put("ItemNum", J.intOr(owned.get(0).get("ItemNum"), 0) + num);
            return;
        }
        Object category = template.get("ItemCategory");
        List<Map<String, Object>> groups = player.rows("Items");
        Map<String, Object> group = null;
        for (Map<String, Object> candidate : groups) {
            if (category == null ? candidate.get("ItemCategory") == null
                    : category.equals(candidate.get("ItemCategory"))) {
                group = candidate;
                break;
            }
        }
        if (group == null) {
            group = J.map("ItemCategory", category, "UserItemList", new ArrayList<>());
            groups.add(group);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        template.forEach((key, value) -> {
            if (!"ItemCategory".equals(key)) {
                row.put(key, value);
            }
        });
        row.put("UserItemId", UUID.randomUUID().toString());
        row.put("ItemNum", num);
        List<Map<String, Object>> items = group.get("UserItemList") instanceof List<?>
                ? (List<Map<String, Object>>) group.get("UserItemList")
                : new ArrayList<>();
        items.add(row);
        group.put("UserItemList", items);
    }

    private static Map<String, Object> itemTemplate(PlayerContext player, int itemId) {
        for (Map<String, Object> group : J.rowsOr(player.raw("Items"))) {
            for (Map<String, Object> row : J.rowsOr(group.get("UserItemList"))) {
                if (J.intOr(row.get("ItemId"), 0) == itemId) {
                    Map<String, Object> template = new LinkedHashMap<>(row);
                    template.put("ItemCategory", group.get("ItemCategory"));
                    return template;
                }
            }
        }
        return null;
    }

    private static Map<String, Object> pendingRow(String pid) {
        return J.map("d_guild_invitations_id", UUID.randomUUID().toString(),
                "player_id", pid, "create_time", now());
    }

    private static void dropPending(PlayerContext holder, String key, String pid, String ident) {
        List<Object> kept = new ArrayList<>();
        for (Object item : J.listOrEmpty(holder.raw(key))) {
            Map<String, Object> row = J.nodeOr(item);
            if (row == null) {
                continue;
            }
            boolean matchPid = pid != null && pid.equals(J.str(row.get("player_id")));
            boolean matchIdent = ident != null
                    && ident.equals(J.str(row.get("d_guild_invitations_id")));
            if (!matchPid && !matchIdent) {
                kept.add(row);
            }
        }
        holder.put(key, kept);
    }

    private static void refreshFlags(PlayerContext holder) {
        holder.entity().existsCircleApprovalPendingFromUser =
                !J.rowsOr(holder.raw("circle_join_requests")).isEmpty();
    }

    private static void clearMembership(PlayerContext target) {
        for (String key : MEMBER_FIELDS) {
            target.put(key, "");
        }
    }

    private static void clearCircle(PlayerContext target) {
        for (String key : CIRCLE_FIELDS) {
            target.put(key, key.startsWith("GuildPolicy") ? 0 : "");
        }
        for (String key : CIRCLE_LISTS) {
            target.put(key, new ArrayList<>());
        }
        target.entity().existsCircleApprovalPendingFromUser = false;
    }

    /** 把 {@code target} 纳入 {@code holder} 的社团（两者都是当前请求的工作副本）。 */
    private static void enrol(PlayerContext holder, PlayerContext target, int position) {
        List<Object> rows = new ArrayList<>(J.listOrEmpty(holder.raw("circle_members")));
        rows.add(J.map("player_id", target.id(), "position_type", position,
                "is_voice_chat_ban", false));
        holder.put("circle_members", rows);
        dropPending(holder, "circle_invitations", target.id(), null);
        dropPending(holder, "circle_join_requests", target.id(), null);
        for (String key : MEMBER_FIELDS) {
            target.put(key, holder.raw(key) == null ? "" : holder.raw(key));
        }
        target.entity().isCircleInviteUser = false;
        if (!target.id().equals(holder.id())) {
            log(holder, CHAT_CIRCLE_MEMBER_JOIN, target.id(), "");
        }
    }

    /** 把 {@code target} 移出社团并落库两个账号。 */
    private void leave(PlayerContext holder, PlayerContext target, PlayerStore store,
                       String chatActor) {
        List<Object> kept = new ArrayList<>();
        for (Object item : J.listOrEmpty(holder.raw("circle_members"))) {
            Map<String, Object> row = J.nodeOr(item);
            if (row != null && !target.id().equals(J.str(row.get("player_id")))) {
                kept.add(row);
            }
        }
        holder.put("circle_members", kept);
        clearMembership(target);
        log(holder, CHAT_CIRCLE_MEMBER_OUT, chatActor, "");
        store.save(holder);
        store.save(target);
    }

    private static Map<String, Object> result(boolean ok) {
        return J.map("result", ok);
    }

    private PlayerContext byId(WireRequest request) {
        PlayerContext target = request.store().loadById(id(request.body(), "player_id"));
        if (target == null) {
            throw WireError.notFound("Local player not found");
        }
        return target;
    }

    private static int memberCount(PlayerContext holder) {
        return J.rowsOr(holder.raw("circle_members")).size();
    }

    private static boolean containsPlayer(Object rows, String pid) {
        for (Map<String, Object> row : J.rowsOr(rows)) {
            if (pid.equals(J.str(row.get("player_id")))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 参数
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> searchConditions(Object raw) {
        if (raw instanceof String text) {
            if (text.isBlank()) {
                return new LinkedHashMap<>();
            }
            try {
                Map<String, Object> parsed = JSON.readValue(text, Map.class);
                return parsed == null ? new LinkedHashMap<>() : parsed;
            } catch (JsonProcessingException | RuntimeException e) {
                throw WireError.badRequest("search_conditions must be a JSON object string");
            }
        }
        if (raw == null) {
            return new LinkedHashMap<>();
        }
        throw WireError.badRequest("search_conditions must be a JSON object string");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Map<String, Object> setting(Map<String, Object> body) {
        Map<String, Object> setting = jsonObject(body, "guild_setting");
        return J.map(
                "GuildName", text(setting, "guild_name", MAX_NAME, true),
                "GuildComment", text(setting, "guild_comment", MAX_COMMENT, false),
                "GuildPolicyAction", enumValue(setting, "guild_policy_action", POLICY_ACTIONS),
                "GuildPolicyRecruiting",
                enumValue(setting, "guild_policy_recruiting", RECRUITING_POLICIES),
                "GuildIconPartsInfo",
                text(setting, "guild_icon_parts_info", 8192, false));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonObject(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw WireError.badRequest(key + " must be a JSON object string");
        }
        try {
            Map<String, Object> parsed = JSON.readValue(text, Map.class);
            if (parsed == null) {
                throw WireError.badRequest(key + " must be a JSON object string");
            }
            return parsed;
        } catch (JsonProcessingException | RuntimeException e) {
            throw WireError.badRequest(key + " must be a JSON object string");
        }
    }

    private static String text(Map<String, Object> body, String key, int maximum,
                               boolean required) {
        Object raw = body.get(key);
        if (raw == null && !required) {
            return "";
        }
        if (!(raw instanceof String text) || text.length() > maximum
                || (required && text.isBlank())) {
            throw WireError.badRequest(key + " must be a "
                    + (required ? "non-empty " : "") + "string of at most " + maximum
                    + " characters");
        }
        return text;
    }

    private static int enumValue(Map<String, Object> body, String key, List<Integer> allowed) {
        Object raw = body.get(key);
        if (!(raw instanceof Integer value) || !allowed.contains(value)) {
            List<String> options = new ArrayList<>();
            for (Integer item : allowed) {
                options.add(String.valueOf(item));
            }
            throw WireError.badRequest(key + " must be one of " + String.join(", ", options));
        }
        return value;
    }

    private static String id(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (!(raw instanceof String text) || text.isBlank() || text.length() > 128) {
            throw WireError.badRequest(
                    key + " must be a non-empty string of at most 128 characters");
        }
        return text;
    }

    // ------------------------------------------------------------------
    // 其他
    // ------------------------------------------------------------------

    private static int userType(PlayerContext target) {
        return target.entity().temporary ? 0 : 1;
    }

    private static String text(Object value) {
        String text = J.str(value);
        return text == null ? "" : text;
    }

    private static String orEmpty(Object value) {
        String text = J.str(value);
        return text == null ? "" : text;
    }

    private static String lastLogin(PlayerContext target) {
        long lastLogin = target.entity().lastLogin > 0
                ? target.entity().lastLogin : target.entity().created;
        return WireState.timestamp(lastLogin);
    }

    private static String now() {
        return MICRO_UTC.format(Instant.now().atOffset(ZoneOffset.UTC));
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化聊天申请内容", e);
        }
    }
}
