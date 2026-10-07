package com.linklike.server.wire;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklike.server.protocol.J;
import com.linklike.server.service.PlayerContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * {@code /v1/home/get_login_bonus} 的登录奖励板 —— 对应 Python 版
 * {@code wire_login_bonus.py}。
 *
 * <p>周期定义（id、名称、每日奖励、时间窗）取自 5.1.0 上游为新注册账号抓到的响应
 * （{@code login_bonus_periods.json}），这里没有编造任何内容。唯一的本地状态是每账号的
 * 「今天已盖章」标记：每个生效周期的一条 {@code datas} 在每天第一次拉取时变成
 * {@code is_get} / {@code is_now_get}（JST 自然日，与周期的 JST 零点窗口一致）。
 * 奖励只做展示，不发放到背包。
 *
 * <p>返回空列表不是选项：5.1.0 客户端的
 * {@code LoginBonusSceneController.DispLoginBoard} 遇到 0 个奖励板会调用
 * {@code SceneChanger.ChangeScene(Home)}，而这时登录奖励的转场还在跑，这次调用会被
 * 静默丢弃，留下一个全白的场景。
 */
@Component
public class WireLoginBonus {

    /** JST = UTC+9。 */
    static final ZoneId JST = ZoneId.ofOffset("UTC", ZoneOffset.ofHours(9));

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final Map<String, String> REWARD_KEYS = Map.of(
            "name", "Name", "amount", "Amount", "reward_type", "RewardType", "reward_id", "RewardId");

    private static final Map<String, String> PERIOD_KEYS = periodKeys();

    private static Map<String, String> periodKeys() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("login_bonus_id", "LoginBonusId");
        keys.put("name", "Name");
        keys.put("file_name", "FileName");
        keys.put("start_time", "StartTime");
        keys.put("end_time", "EndTime");
        keys.put("show_order", "ShowOrder");
        return Map.copyOf(keys);
    }

    /** 两个奖励板列表的周期模板，对应 Python 的模块级 {@code PERIODS}。 */
    private final Map<String, List<Map<String, Object>>> periods;

    public WireLoginBonus(ObjectMapper mapper) {
        this.periods = load(mapper);
    }

    private static Map<String, List<Map<String, Object>>> load(ObjectMapper mapper) {
        ClassPathResource resource = new ClassPathResource("login_bonus_periods.json");
        Map<String, Object> seed;
        try (InputStream in = resource.getInputStream()) {
            seed = mapper.readValue(in, new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取 login_bonus_periods.json", e);
        }
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        result.put("LoginBonuses", templates(seed.get("login_bonus_list")));
        result.put("EventLoginBonuses", templates(seed.get("event_login_bonus_list")));
        return result;
    }

    private static List<Map<String, Object>> templates(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> period : J.rowsOr(value)) {
            out.add(periodTemplate(period));
        }
        return out;
    }

    private static Map<String, Object> periodTemplate(Map<String, Object> period) {
        Map<String, Object> out = new LinkedHashMap<>();
        PERIOD_KEYS.forEach((wire, prop) -> out.put(prop, period.get(wire)));
        List<Map<String, Object>> datas = new ArrayList<>();
        for (Map<String, Object> data : J.rowsOr(period.get("datas"))) {
            List<Map<String, Object>> rewards = new ArrayList<>();
            for (Map<String, Object> reward : J.rowsOr(data.get("rewards"))) {
                Map<String, Object> row = new LinkedHashMap<>();
                REWARD_KEYS.forEach((wire, prop) -> row.put(prop, reward.get(wire)));
                rewards.add(row);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("Count", data.get("count"));
            row.put("FileName", data.get("file_name"));
            row.put("IsGet", false);
            row.put("IsNowGet", false);
            row.put("Rewards", rewards);
            datas.add(row);
        }
        out.put("Datas", datas);
        return out;
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    /** JST 自然日键，对应 Python 的 {@code day_key}。 */
    public static String dayKey(Instant now) {
        return DAY_FORMAT.format(now.atZone(JST));
    }

    /** 解析周期时间窗里的 ISO-8601 时间戳；解析失败返回 null。 */
    public static Instant parse(Object value) {
        if (!(value instanceof String text) || text.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (RuntimeException ignored) {
            // 继续尝试带偏移量的写法。
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (RuntimeException ignored) {
            // 既没有 Z 也没有偏移量时按 UTC 解释（Python 的 fromisoformat 也是这个行为）。
        }
        try {
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** 周期是否处在生效时间窗内。 */
    public static boolean isActive(Map<String, Object> period, Instant now) {
        Instant start = parse(period.get("StartTime"));
        Instant end = parse(period.get("EndTime"));
        if (start == null || end == null) {
            return false;
        }
        return !now.isBefore(start) && !now.isAfter(end);
    }

    private static Map<String, Object> nextStamp(Map<String, Object> period) {
        for (Map<String, Object> data : J.rowsOr(period.get("Datas"))) {
            if (!J.boolOr(data.get("IsGet"), false)) {
                return data;
            }
        }
        return null;
    }

    /** 今天第一次拉取时是否至少会盖一个章。 */
    public boolean hasPending(PlayerContext player, Instant now) {
        if (dayKey(now).equals(player.str("LoginBonusStampDay"))) {
            return false;
        }
        for (Map.Entry<String, List<Map<String, Object>>> entry : periods.entrySet()) {
            Map<String, Map<String, Object>> stored = storedPeriods(player, entry.getKey());
            for (Map<String, Object> template : entry.getValue()) {
                Map<String, Object> period = stored.getOrDefault(
                        String.valueOf(template.get("LoginBonusId")), template);
                if (isActive(period, now) && nextStamp(period) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 补齐缺失的周期并盖上今天的章。账号有变化时返回 true（调用方负责落库）。
     *
     * <p>对应 Python 版 {@code wire_login_bonus.sync}。
     */
    public boolean sync(PlayerContext player, Instant now) {
        boolean changed = false;
        for (Map.Entry<String, List<Map<String, Object>>> entry : periods.entrySet()) {
            List<Map<String, Object>> rows = player.rows(entry.getKey());
            Map<String, Boolean> known = new LinkedHashMap<>();
            for (Map<String, Object> period : rows) {
                known.put(String.valueOf(period.get("LoginBonusId")), Boolean.TRUE);
            }
            for (Map<String, Object> template : entry.getValue()) {
                if (!known.containsKey(String.valueOf(template.get("LoginBonusId")))) {
                    rows.add(copy(template));
                    changed = true;
                }
            }
        }
        String today = dayKey(now);
        if (today.equals(player.str("LoginBonusStampDay"))) {
            return changed;
        }
        for (String key : periods.keySet()) {
            for (Map<String, Object> period : player.rows(key)) {
                for (Map<String, Object> data : J.rowsOr(period.get("Datas"))) {
                    data.put("IsNowGet", false);
                }
                if (isActive(period, now)) {
                    Map<String, Object> data = nextStamp(period);
                    if (data != null) {
                        data.put("IsGet", true);
                        data.put("IsNowGet", true);
                    }
                }
            }
        }
        player.put("LoginBonusStampDay", today);
        return true;
    }

    /** {@code HomeGetLoginBonusResponse} 的响应体。 */
    public Map<String, Object> response(PlayerContext player) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("LoginBonusList", player.rows("LoginBonuses"));
        out.put("EventLoginBonusList", player.rows("EventLoginBonuses"));
        return out;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static Map<String, Map<String, Object>> storedPeriods(PlayerContext player, String key) {
        Map<String, Map<String, Object>> stored = new LinkedHashMap<>();
        for (Map<String, Object> period : player.rows(key)) {
            stored.put(String.valueOf(period.get("LoginBonusId")), period);
        }
        return stored;
    }

    /** 深拷贝（Python 的 {@code copy.deepcopy}），避免模板被账号状态改脏。 */
    private static Map<String, Object> copy(Map<String, Object> value) {
        Map<String, Object> out = new LinkedHashMap<>();
        value.forEach((key, item) -> out.put(key, deepCopy(item)));
        return out;
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, item) -> out.put(String.valueOf(key), deepCopy(item)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(deepCopy(item));
            }
            return out;
        }
        return value;
    }
}
