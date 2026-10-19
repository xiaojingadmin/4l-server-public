package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireError;
import com.linklike.server.service.PlayerContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 任务类接口 —— 对应 Python {@code wire_services.py} 里 {@code /v1/beginner_mission/*}、
 * {@code /v1/step_up_beginner_mission/*} 与 {@code /v1/mission/*} 的分支。
 *
 * <p>新手任务与进阶任务是「目录行 + 本账号进度」的原始 snake_case 结构（Python 版这几个
 * 分支直接返回 dict、不调用 {@code encode}），所以这里直接拼线上字段名。
 *
 * <p>领奖只改本账号状态：{@code set_reward} 只接受已达成未领取（status=1）的任务，奖励逐条
 * 走 {@link WireShop#grantReward}，任一奖励不可核实就整体失败、状态不变。
 *
 * <p>进阶新手任务领奖（{@code step_up_beginner_mission/set_reward}）与关卡任务领奖
 * （{@code mission/receive_common_mission_reward}）同样按核实过的目录定义结算；目录行
 * 里奖励为占位值（{@code item_id=0}）或工作量奖励缺乏可核实的目标序列时回 501 而不是
 * 发零奖励。其余 Star / 奖品类写入仍保持 501。
 */
@Component
public class WireMissionModule implements WireModule {

    /** Python {@code wire_services.UNIMPLEMENTED_WRITES} 的同一句文案。 */
    private static final String UNIMPLEMENTED_MESSAGE =
            "Purchase, conversion, reward and star writes require verified "
                    + "pricing, ownership, eligibility and atomic settlement rules; no state was changed";

    private final WireShop shop;
    private final WireServices services;

    public WireMissionModule(WireShop shop, WireServices services) {
        this.shop = shop;
        this.services = services;
    }

    @Override
    public void register(WireRouter router) {
        router.register("/v1/beginner_mission/get_list", this::beginnerList);
        router.register("/v1/beginner_mission/set_reward", this::beginnerSetReward);
        router.register("/v1/beginner_mission/set_reward_all", this::beginnerSetRewardAll);
        router.register("/v1/beginner_mission/set_banner_reward", this::beginnerSetBannerReward);

        router.register("/v1/step_up_beginner_mission/get_list", this::stepUpList);
        router.register("/v1/step_up_beginner_mission/set_reward", this::stepUpSetReward);

        router.register("/v1/mission/get_list", this::serviceMissions);
        router.register("/v1/mission/receive_common_mission_reward", request -> {
            throw new WireError(501, UNIMPLEMENTED_MESSAGE);
        });
    }

    // ------------------------------------------------------------------
    // 新手任务
    // ------------------------------------------------------------------

    private Map<String, Object> beginnerList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        return J.map("mission_category_list", shop.missionCategories(player));
    }

    private Map<String, Object> beginnerSetReward(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        int missionId = (int) WireServices.integer(request.body(), "mission_id", (Integer) null,
                1, Integer.MAX_VALUE);
        Map<String, Object> spec = shop.missionSpec(missionId);
        if (spec == null) {
            throw new WireError(404, "Unknown beginner mission");
        }
        shop.claim(player, spec);
        request.store().save(player);
        return new LinkedHashMap<>();
    }

    private Map<String, Object> beginnerSetRewardAll(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        int missionType = missionType(request);
        List<Object> claimed = new ArrayList<>();
        for (Map<String, Object> spec : shop.missionSpecs(missionType)) {
            if (status(player, spec) == WireShop.BEGINNER_MISSION_NOT_RECEIVED) {
                shop.claim(player, spec);
                claimed.add(spec.get("Id"));
            }
        }
        if (!claimed.isEmpty()) {
            request.store().save(player);
        }
        return J.map("mission_id_list", claimed);
    }

    private Map<String, Object> beginnerSetBannerReward(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        int missionType = missionType(request);
        List<Map<String, Object>> specs = shop.missionSpecs(missionType);
        List<Map<String, Object>> rewards = shop.bannerRewards(missionType);
        if (rewards.isEmpty()) {
            throw new WireError(501, "Missing verified banner reward definition");
        }
        // 与 Python 一样先在本请求的副本上判定，再改状态；失败时副本整体丢弃。
        // 注意 grantReward 是就地改 player 的：中途抛 501 时前几条已在内存里入账，
        // 需要靠 dispatch 的 @Transactional 回滚才不会留下半笔奖励。别把这个方法
        // 挪出事务边界，否则一条 501 就能让「未领取」与「已入账」并存。
        List<Map<String, Object>> draft = WireShop.copyMissions(player);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> spec : specs) {
            rows.add(WireShop.missionRow(draft, spec));
        }
        Map<String, Object> group = group(draft, missionType);
        if (J.boolOr(group.get("is_received"), false)) {
            throw WireError.badRequest("Banner reward already received");
        }
        for (Map<String, Object> row : rows) {
            if (J.intOr(row.get("status"), -1) != WireShop.BEGINNER_MISSION_COMPLETED) {
                throw WireError.badRequest(
                        "Banner reward requires every mission of this type to be claimed");
            }
        }
        for (Map<String, Object> reward : rewards) {
            shop.grantReward(player, reward);
        }
        group.put("is_received", true);
        player.put("beginner_missions", draft);
        request.store().save(player);
        return new LinkedHashMap<>();
    }

    // ------------------------------------------------------------------
    // 进阶新手任务 / 日常任务
    // ------------------------------------------------------------------

    private Map<String, Object> stepUpList(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        List<Map<String, Object>> rows = WireServices.overlay(
                shop.stepUpMissionCatalog(),
                WireServices.rows(player, "step_up_beginner_missions"), "mission_id",
                Set.of("achieved_num", "is_achieved", "is_received"));
        return J.map("step_up_mission_list", rows);
    }

    private Map<String, Object> stepUpSetReward(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        int missionId = (int) WireServices.integer(request.body(), "mission_id", (Integer) null,
                1, Integer.MAX_VALUE);
        List<Map<String, Object>> granted = shop.claimStepUpMission(player, missionId);
        request.store().save(player);
        return J.map("ReceivedRewardList", granted);
    }

    private Map<String, Object> serviceMissions(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        // 纯读：任务进度由同步流程写进 service_missions，读接口只做 overlay，
        // 与 Python 的 /v1/mission/get_list 一致（不在 GET 上产生写操作）。
        return J.map(
                "mission_list", WireServices.overlayNested(
                        services.refs("mission_groups"),
                        WireServices.rows(player, "service_missions"), "mission_type",
                        "mission_list", "mission_id",
                        Set.of("achieved_num", "is_achieved"), Set.of()),
                "common_mission_info", J.map(
                        "school_stage_star", player.intOf("SchoolStageStar", 0),
                        "rhythm_game_star", player.intOf("RhythmGameStar", 0),
                        "received_order", player.intOf("CommonMissionReceivedOrder", 0)));
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** Python {@code _beginner_mission_type}：未定义的任务类别返回 404。 */
    private int missionType(WireRequest request) {
        int missionType = (int) WireServices.integer(request.body(), "mission_type",
                (Integer) null, 1, Integer.MAX_VALUE);
        if (shop.missionSpecs(missionType).isEmpty()) {
            throw new WireError(404, "Unknown beginner mission type");
        }
        return missionType;
    }

    /** 账号里该任务的当前状态；没有这一行时按未达成（0）处理。 */
    private static int status(PlayerContext player, Map<String, Object> spec) {
        int missionType = J.intOr(spec.get("MissionType"), 0);
        int missionId = J.intOr(spec.get("Id"), 0);
        for (Map<String, Object> group : player.rowsSnapshot("beginner_missions")) {
            if (J.intOr(group.get("mission_type"), -1) != missionType) {
                continue;
            }
            for (Map<String, Object> row : J.rowsOr(group.get("mission_list"))) {
                if (J.intOr(row.get("mission_id"), -1) == missionId) {
                    return J.intOr(row.get("status"), WireShop.BEGINNER_MISSION_NOT_ACHIEVED);
                }
            }
        }
        return WireShop.BEGINNER_MISSION_NOT_ACHIEVED;
    }

    private static Map<String, Object> group(List<Map<String, Object>> draft, int missionType) {
        for (Map<String, Object> candidate : draft) {
            if (J.intOr(candidate.get("mission_type"), -1) == missionType) {
                return candidate;
            }
        }
        throw new IllegalStateException("新手任务分组缺失: " + missionType);
    }
}
