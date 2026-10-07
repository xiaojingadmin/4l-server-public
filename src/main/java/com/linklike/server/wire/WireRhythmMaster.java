package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import com.linklike.server.resource.ClientDefaults;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.springframework.stereotype.Component;

/**
 * 节奏游戏（School Idol Show）主表读取层 —— 对应 Python 版 {@code wire_rhythm.py} 里以
 * {@code _master()} 开头的那一段。
 *
 * <p>数据来自打包的 5.1.0 客户端主表 {@code client_defaults/rhythm_live_master.json}
 * （Python 版读 {@code build/client_defaults/rhythm_live_master.json}），五张子表：
 * {@code scores}（每首歌的经验类型 / 奖励系列 / 各难度经验与物量 / 掉落组）、
 * {@code drop_groups}（掉落组，含生效窗口与权重）、{@code score_rewards} 与
 * {@code score_datas}（分数与连击档位奖励）、{@code levels}（熟练度等级表）。
 *
 * <p>文件缺失时整张主表当作空表（Python 版 {@code _MASTER_PATH.is_file()} 不成立时也是
 * 返回 {@code {}}）：{@code scores} 查不到歌就退回「零经验、零连击」的默认值，不会 500。
 *
 * <p>与 Python 版的一处差异：{@code _chart()} 会把 {@code custom_content.custom_scores()}
 * 里的自制谱面叠在打包主表上，Java 版还没有自制内容这一层，因此只返回打包主表里的行。
 * 没有自制内容时两者结果相同。
 */
@Component
public class WireRhythmMaster {

    /** 掉落判定基准（Python {@code _ODDS_BASE}）：{@code odds} 是万分比。 */
    public static final int ODDS_BASE = 10000;

    /** 主表里的时间列形态（Python {@code '%Y-%m-%d %H:%M:%S'}），按 JST 解释。 */
    private static final DateTimeFormatter MASTER_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ClientDefaults defaults;

    private volatile Map<String, Object> master;

    public WireRhythmMaster(ClientDefaults defaults) {
        this.defaults = defaults;
    }

    // ------------------------------------------------------------------
    // 主表读
    // ------------------------------------------------------------------

    /** 整张主表；文件缺失时为空表（对应 Python 的 {@code _master()}）。 */
    public Map<String, Object> master() {
        Map<String, Object> cached = master;
        if (cached == null) {
            cached = defaults.object("rhythm_live_master").orElseGet(LinkedHashMap::new);
            master = cached;
        }
        return cached;
    }

    /** 某一首歌的主表行；没有这首歌时返回 null（对应 Python {@code _chart}）。 */
    public Map<String, Object> chart(int musicId) {
        return row(table("scores"), musicId);
    }

    /**
     * 按难度取映射里的值，取不到返回 0 —— 对应 Python {@code _lookup}。
     *
     * <p>Python 先试 {@code key in mapping}（键是字符串的主表里整数键永远不命中），再试
     * {@code mapping.get(str(key), default)}，所以实际行为就是「按十进制字符串查」。
     * 调用点只用来取数值（{@code gain_exp}、{@code max_combo}、{@code drop_series}），
     * 所以非数值同样按「取不到」处理。
     */
    public static long lookup(Object mapping, Object key, long fallback) {
        if (!(mapping instanceof Map<?, ?> map) || map.isEmpty() || key == null) {
            return fallback;
        }
        Object value = map.get(tableKey(key));
        return value instanceof Number number ? number.longValue() : fallback;
    }

    /** 某个掉落组（对应 Python {@code _drop_rewards} 里的 {@code drop_groups} 取行）。 */
    public List<Map<String, Object>> dropGroups(long seriesId) {
        return rows(table("drop_groups"), seriesId);
    }

    /**
     * 某个奖励系列下的档位行，允许系列号缺席 —— 对应 Python
     * {@code (master['score_rewards'] or {}).get(str(series_id)) or []}。
     *
     * <p>{@code seriesId} 为 null（本地主表里没有这首歌）时按 Python 的 {@code str(None)} 取键，
     * 而不是退化成 {@code 0} 号系列。
     */
    public List<Map<String, Object>> scoreRewardsOf(Object seriesId) {
        return rows(table("score_rewards"), seriesId);
    }

    /** 档位对应的奖励明细（{@code score_datas}），按 {@code datas} 里的 ID 取。 */
    public Map<String, Object> scoreData(Object dataId) {
        return table("score_datas").get(tableKey(dataId)) instanceof Map<?, ?> map ? cast(map) : null;
    }

    /** 经验类型对应的熟练度等级表（{@code levels}）。 */
    public List<Map<String, Object>> levels(long experienceType) {
        return rows(table("levels"), experienceType);
    }

    // ------------------------------------------------------------------
    // 主表驱动的算法
    // ------------------------------------------------------------------

    /**
     * 主表时间列 -> 时刻，按 JST 解释 —— 对应 Python {@code _parse_master_time}。
     *
     * <p>解析失败返回 null；Python 会抛 {@code ValueError} 变成 500，调用方
     * {@link #inWindow} 把 null 当作「这一端不设限」，与「主表里没写这个字段」同义。
     */
    public static Instant parseMasterTime(Object text) {
        if (!(text instanceof String value) || value.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, MASTER_TIME).toInstant(ZoneOffset.ofHours(9));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 掉落组当前是否在生效窗口内 —— 对应 Python {@code _in_window}。 */
    public static boolean inWindow(Map<String, Object> group, Instant now) {
        Instant start = parseMasterTime(group.get("start"));
        if (start != null && now.isBefore(start)) {
            return false;
        }
        Instant end = parseMasterTime(group.get("end"));
        return end == null || !now.isAfter(end);
    }

    /**
     * 按权重抽一条 —— 对应 Python {@code _pick}。
     *
     * <p>权重合计小于 1 时返回 null（Python 的 {@code total < 1}）；遍历完之后兜底返回最后
     * 一条，对应 Python 的 {@code return choices[-1]}。
     */
    public static Map<String, Object> pick(List<Map<String, Object>> choices, Random rng) {
        long total = 0;
        for (Map<String, Object> item : choices) {
            total += J.longOr(item.get("odds"), 0);
        }
        if (total < 1) {
            return null;
        }
        long n = rng.nextLong(total);
        for (Map<String, Object> item : choices) {
            n -= J.longOr(item.get("odds"), 0);
            if (n < 0) {
                return item;
            }
        }
        return choices.get(choices.size() - 1);
    }

    /**
     * 一次结算的掉落奖励 —— 对应 Python {@code _drop_rewards}。
     *
     * <p>每个掉落组独立掷一次，先按 {@code odds}（万分比）判定是否掉落，再按权重挑一条明细。
     * {@code afk} 行只在自动游玩时被排除 —— 官方「手动游玩失败」（{@code remain_mental=0}）
     * 仍然给金币。
     *
     * <p>随机数发生器从外面传进来：Python 用平台随机的 {@code random.Random()}，判定结果
     * 本来就不逐值可比；能逐值比对的是「固定 odds 下的取舍」。{@code enableAuto} 对应
     * Python 的 {@code enable_auto}，{@code now} 对应 {@code datetime.now(_JST)}。
     */
    public List<Map<String, Object>> dropRewards(long seriesId, boolean enableAuto,
                                                 Instant now, Random rng) {
        List<Map<String, Object>> rewards = new ArrayList<>();
        for (Map<String, Object> group : dropGroups(seriesId)) {
            if (!inWindow(group, now)) {
                continue;
            }
            List<Map<String, Object>> choices = J.rowsOr(group.get("choices"));
            List<Map<String, Object>> eligible = new ArrayList<>(choices.size());
            for (Map<String, Object> item : choices) {
                if (enableAuto && J.truthy(item.get("afk"))) {
                    continue;
                }
                eligible.add(item);
            }
            if (eligible.isEmpty()) {
                continue;
            }
            if (rng.nextInt(ODDS_BASE) >= J.intOr(group.get("odds"), 0)) {
                continue;
            }
            Map<String, Object> picked = pick(eligible, rng);
            if (picked == null) {
                continue;
            }
            rewards.add(J.map(
                    "RewardType", picked.get("reward_type"),
                    "RewardItemId", picked.get("reward_item_id"),
                    "RewardNum", picked.get("reward_num")));
        }
        return rewards;
    }

    /**
     * 累计经验对应的熟练度等级 —— 对应 Python {@code _mastery_level}。
     *
     * <p>等级表按 {@code cumulative} 升序，取最后一个「累计经验不超过 exp」的等级；经验为
     * 0 或负数时是 0 级（不是 1 级），等级表为空时是最低的 1 级。
     */
    public long masteryLevel(long experienceType, long exp) {
        if (exp <= 0) {
            return 0;
        }
        long level = 1;
        for (Map<String, Object> row : levels(experienceType)) {
            if (J.longOr(row.get("cumulative"), 0) <= exp) {
                level = J.longOr(row.get("level"), level);
            }
        }
        return level;
    }

    /**
     * 数值命中的档位数（第 1 档是 1，不达标是 0）—— 对应 Python {@code _rank}。
     *
     * <p>取最后一个「阈值不超过 value」的下标，所以档位不要求阈值升序以外的性质；
     * 阈值表为空时是 0。
     */
    public static int rank(long value, List<Object> thresholds) {
        int status = 0;
        int index = 0;
        for (Object threshold : thresholds) {
            index++;
            if (value >= J.longOr(threshold, 0)) {
                status = index;
            }
        }
        return status;
    }

    /**
     * 分数 / 连击档位新跨过的奖励 —— 对应 Python {@code _score_rewards}。
     *
     * <p>{@code rewardType} 是档位类型（0 表示分数档，其余等于难度），只挑该类型的行；第
     * {@code index + 1} 档落在 {@code (before, after]} 区间里才发，避免重复领取。
     */
    public List<Map<String, Object>> scoreRewards(long seriesId, int rewardType,
                                                  int before, int after) {
        List<Map<String, Object>> rewards = new ArrayList<>();
        for (Map<String, Object> row : scoreRewardsOf(seriesId)) {
            if (J.intOr(row.get("type"), -1) != rewardType) {
                continue;
            }
            List<Object> datas = J.listOrEmpty(row.get("datas"));
            for (int index = 0; index < datas.size(); index++) {
                int rank = index + 1;
                if (!(before < rank && rank <= after)) {
                    continue;
                }
                Map<String, Object> data = scoreData(datas.get(index));
                if (data == null) {
                    continue;
                }
                rewards.add(J.map(
                        "RewardType", data.get("reward_type"),
                        "RewardItemId", data.get("reward_item_id"),
                        "RewardNum", data.get("reward_num")));
            }
        }
        return rewards;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 主表里的一级子表（{@code scores}、{@code drop_groups}……）。 */
    private Map<String, Object> table(String name) {
        return J.nodeOrEmpty(master().get(name));
    }

    private static Map<String, Object> row(Map<String, Object> table, Object key) {
        return table.get(tableKey(key)) instanceof Map<?, ?> map ? cast(map) : null;
    }

    private static List<Map<String, Object>> rows(Map<String, Object> table, Object key) {
        return J.rowsOr(table.get(tableKey(key)));
    }

    /**
     * 主表键的形态 —— 对应 Python 的 {@code str(key)}。
     *
     * <p>主表里的键全是字符串：JSON 对象的键，{@code scores} 按歌曲 ID、{@code levels} 按经验
     * 类型。因此查表一律要把数值键转成 Python {@code str()} 的写法。{@code None} 也必须转成
     * {@code "None"} 而不是 {@code "null"}，否则「本地没有这首歌」会意外落到 0 号系列上。
     *
     * <p>方法名不叫 {@code key}，是为了不和 {@code lookup} 的参数重名。
     */
    private static String tableKey(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Boolean flag) {
            return flag ? "True" : "False";
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
