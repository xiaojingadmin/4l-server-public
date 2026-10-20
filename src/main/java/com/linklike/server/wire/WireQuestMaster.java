package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import com.linklike.server.resource.ClientDefaults;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Quest Live 用的客户端静态表 —— 对应 Python {@code wire_services.py} 里
 * {@code _stage_table} / {@code _stage_reward_list} / {@code _music_level} 那一段。
 *
 * <p>数据来自随仓库分发的 {@code client_defaults/*.json}（Python 读的是
 * {@code build/client_defaults/} 下的同一批抽取产物）：
 * <ul>
 *   <li>{@code quest_stage_rewards}（itemsources.tsv 反查）：每个关卡的发奖行；
 *   <li>{@code quest_stage_scores}：三档通关星数门槛；
 *   <li>{@code quest_stage_style_points}：{@code GainStylePoint}；
 *   <li>{@code quest_sections}：section skill 列表；
 *   <li>{@code rhythm_live_master}：歌曲经验类型与熟练度等级表；
 *   <li>{@code dream_live_master}：Dream Live 系列的解放步骤。
 * </ul>
 *
 * <p>文件缺失时整张表按空表处理，与 Python 的 {@code path.is_file()} 分支一致 ——
 * 不在本地编造阈值、星数或奖励。
 */
@Component
public class WireQuestMaster {

    /** {@code items.tsv 3004000 スタイルPt. LimitNum}；钱包上限。 */
    public static final long STYLE_POINT_LIMIT = 999_999_999L;

    private final ClientDefaults defaults;

    private final Map<String, Map<String, Object>> tables = new LinkedHashMap<>();
    private volatile Map<String, Object> dreamMaster;

    public WireQuestMaster(ClientDefaults defaults) {
        this.defaults = defaults;
    }

    /** 一张以行为键的静态表（{@code stage_id -> ...}）。缺失时为空表。 */
    public Map<String, Object> table(String name) {
        Map<String, Object> cached;
        synchronized (tables) {
            cached = tables.get(name);
            if (cached == null) {
                cached = defaults.object(name).orElseGet(LinkedHashMap::new);
                tables.put(name, cached);
            }
        }
        return cached;
    }

    /** {@code quest_stage_rewards[stage_id]} 的副本；没有这一关返回空表。 */
    public List<Map<String, Object>> stageRewards(long stageId) {
        return copy(J.rowsOr(table("quest_stage_rewards").get(String.valueOf(stageId))));
    }

    /** {@code quest_stage_scores[stage_id]}：三档星数门槛；未打包时返回 null。 */
    public List<Object> stageScores(long stageId) {
        Object value = table("quest_stage_scores").get(String.valueOf(stageId));
        return value instanceof List<?> list ? new ArrayList<>(list) : null;
    }

    /** {@code GainStylePoint}（未打包的关卡是 0）。 */
    public long stageStylePoint(long stageId) {
        return J.longOr(table("quest_stage_style_points").get(String.valueOf(stageId)), 0L);
    }

    /** {@code questsections.tsv}：关卡的 section skill 行。 */
    public List<Map<String, Object>> sections(long stageId) {
        return copy(J.rowsOr(table("quest_sections").get(String.valueOf(stageId))));
    }

    /**
     * {@code dream_live_master.json}（按 series_id 索引）。
     *
     * <p>返回深拷贝：调用方（{@code dreamMembers} 等）会在 spec 上就地改写，
     * 直接下发缓存会把改动写回主表。对应 Python 每次读取都重新解析 JSON。
     */
    public Map<String, Object> dreamMaster() {
        Map<String, Object> cached = dreamMaster;
        if (cached == null) {
            cached = defaults.object("dream_live_master").orElseGet(LinkedHashMap::new);
            dreamMaster = cached;
        }
        return WireServices.copyRow(cached);
    }

    /**
     * 一首歌在给定经验下的熟练度等级 —— 对应 Python {@code _music_level}。
     *
     * <p>{@code 0} = 未習得，{@code 1} 从第一点经验开始，之后按
     * {@code musiclevels.tsv} 的 {@code CumulativeExperience} 台阶走。
     */
    public int musicLevel(long musicId, long experience) {
        if (experience <= 0) {
            return 0;
        }
        Map<String, Object> master = table("rhythm_live_master");
        Map<String, Object> chart = J.nodeOrEmpty(
                J.nodeOrEmpty(master.get("scores")).get(String.valueOf(musicId)));
        List<Map<String, Object>> levels = J.rowsOr(
                J.nodeOrEmpty(master.get("levels")).get(String.valueOf(chart.get("experience_type"))));
        int level = 1;
        for (Map<String, Object> row : levels) {
            if (J.longOr(row.get("cumulative"), 0L) <= experience) {
                level = J.intOr(row.get("level"), level);
            }
        }
        return level;
    }

    private static List<Map<String, Object>> copy(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            out.add(WireServices.copyRow(row));
        }
        return out;
    }
}
