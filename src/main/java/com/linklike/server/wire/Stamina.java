package com.linklike.server.wire;

import com.linklike.server.service.PlayerContext;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 体力（スタミナ）钱包与协议用时间字符串 —— 对应 Python 版 {@code stamina.py}。
 *
 * <p>客户端从 {@code stamina_recovery_time}（体力回满的时刻）推算显示值，每
 * {@code RecoverSecond=360} 秒回 1 点；{@code stamina_now} 是上次写入时的值。
 * 官方抓包：{@code 0/220} 且回满时刻在很久以前会被读成满值；一次 10 点的 SHOW live
 * 之后回 {@code 210/220} 且回满时刻 = 现在 + 60 分钟。
 *
 * <p>没有 {@code StaminaMax} 的账号（初始发放之前建的账号）视为「不记账」：读出来是 0，
 * 扣减是空操作，这样旧流程不会被卡住。
 *
 * <p>SIsCa 回复（{@code RecoveryStaminaRequest.use_recovery_type=1}）按
 * {@link #JEWEL_SETTINGS} 计价，每日次数记在 {@code JewelStRecoveryCount}，
 * 到达 {@code JewelStRecoveryResetTime}（04:00 JST 日界）后归零。
 */
public final class Stamina {

    private Stamina() {
    }

    /** 每点体力的恢复秒数（客户端 {@code PlayPointPanel} 的 {@code RecoverSecond}）。 */
    public static final long RECOVER_SECONDS = 360;

    /** Python 的 {@code '%Y-%m-%dT%H:%M:%S.%f'}：固定 6 位小数。 */
    private static final DateTimeFormatter PARSE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");

    /**
     * {@code StaminaUseJewelSetting} 的行：一次回复给 {@code EffectValue} 点，花
     * {@code Price} SIsCa，每天最多 {@code JewelStRecoveryCountLimit} 次。
     *
     * <p>官方定价不在客户端包内，这里是服务端下发的默认值，与 Python 版一致。
     */
    public static final List<Map<String, Object>> JEWEL_SETTINGS = List.of(
            jewelSetting(10, 50, 100));

    public static final int JEWEL_DAILY_LIMIT = JEWEL_SETTINGS.stream()
            .mapToInt(row -> (int) row.get("JewelStRecoveryCountLimit"))
            .max()
            .orElse(0);

    private static Map<String, Object> jewelSetting(int limit, int price, int effect) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("JewelStRecoveryCountLimit", limit);
        row.put("Price", price);
        row.put("EffectValue", effect);
        return Map.copyOf(row);
    }

    /** 取当前时刻；{@code now} 为 null 时用系统时间（测试注入用）。 */
    public static Instant now(Instant now) {
        return now != null ? now : Instant.now();
    }

    // ------------------------------------------------------------------
    // 时间字符串
    // ------------------------------------------------------------------

    /**
     * 解析客户端下发的 ISO-8601 {@code ...Z} 时间戳（小数位最多 9 位）。
     *
     * <p>只接受 UTC 偏移为 0 的写法（{@code Z} 或 {@code +00:00}），与 Python 版
     * {@code strptime} 的行为一致；解析失败返回 null，不猜。
     */
    public static Instant parseTime(Object text) {
        if (!(text instanceof String value) || value.isEmpty()) {
            return null;
        }
        String raw = value.trim();
        if (raw.endsWith("Z")) {
            raw = raw.substring(0, raw.length() - 1);
        } else if (raw.endsWith("+00:00")) {
            raw = raw.substring(0, raw.length() - 6);
        }
        int dot = raw.indexOf('.');
        if (dot >= 0) {
            String fraction = raw.substring(dot + 1) + "000000";
            raw = raw.substring(0, dot) + "." + fraction.substring(0, 6);
        } else {
            raw += ".000000";
        }
        try {
            return LocalDateTime.parse(raw, PARSE_FORMAT).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 与 Python {@code format_time} 相同：6 位微秒 + 补足到 9 位纳秒的 {@code 000} + {@code Z}。 */
    public static String formatTime(Instant moment) {
        LocalDateTime utc = moment.atOffset(ZoneOffset.UTC).toLocalDateTime();
        long micros = utc.getNano() / 1000L;
        return String.format("%04d-%02d-%02dT%02d:%02d:%02d.%06d000Z",
                utc.getYear(), utc.getMonthValue(), utc.getDayOfMonth(),
                utc.getHour(), utc.getMinute(), utc.getSecond(), micros);
    }

    // ------------------------------------------------------------------
    // 钱包
    // ------------------------------------------------------------------

    public static boolean tracked(PlayerContext player) {
        return player.entity().staminaMax > 0;
    }

    /** 当前可用点数：按 {@link #RECOVER_SECONDS} 一点一点恢复。 */
    public static int current(PlayerContext player, Instant now) {
        int maximum = player.entity().staminaMax;
        if (maximum <= 0) {
            return 0;
        }
        int stored = Math.max(0, player.entity().staminaNow);
        Instant fullAt = parseTime(player.entity().staminaRecoveryTime);
        if (fullAt == null) {
            return Math.min(stored, maximum);
        }
        Instant moment = now(now);
        // Python 用 (full_at - moment).total_seconds() 取浮点秒再 math.ceil(remaining/360)；
        // 这里用整数微秒做同一件事，避免浮点误差。
        long remainingMicros = ChronoUnit.MICROS.between(moment, fullAt);
        if (remainingMicros <= 0) {
            return Math.max(stored, maximum);
        }
        int value = (int) (maximum - ceilDiv(remainingMicros, RECOVER_SECONDS * 1_000_000L));
        return Math.max(Math.min(stored, maximum), Math.min(maximum, Math.max(value, 0)));
    }

    public static int current(PlayerContext player) {
        return current(player, null);
    }

    /**
     * 扣减 {@code cost} 点体力（就地写入）；不够时返回 false 且不改动。
     *
     * <p>对应 Python 版 {@code stamina.consume}。
     */
    public static boolean consume(PlayerContext player, int cost, Instant now) {
        if (cost <= 0 || !tracked(player)) {
            return true;
        }
        Instant moment = now(now);
        int have = current(player, moment);
        if (have < cost) {
            return false;
        }
        int maximum = player.entity().staminaMax;
        Instant fullAt = parseTime(player.entity().staminaRecoveryTime);
        if (fullAt == null || !fullAt.isAfter(moment) || have >= maximum) {
            fullAt = moment;
        }
        int left = have - cost;
        player.entity().staminaNow = left;
        if (left >= maximum) {
            player.entity().staminaRecoveryTime = formatTime(moment);
        } else {
            player.entity().staminaRecoveryTime = formatTime(fullAt.plus(
                    (long) (Math.min(have, maximum) - left) * RECOVER_SECONDS, ChronoUnit.SECONDS));
        }
        return true;
    }

    /**
     * 增加 {@code points} 点体力（就地写入）。钱包可以超过 {@code StaminaMax}，
     * 溢出部分不参与恢复。返回新的点数。对应 Python 版 {@code stamina.restore}。
     */
    public static int restore(PlayerContext player, int points, Instant now) {
        if (!tracked(player)) {
            return 0;
        }
        Instant moment = now(now);
        int maximum = player.entity().staminaMax;
        int value = current(player, moment) + Math.max(points, 0);
        player.entity().staminaNow = value;
        if (value >= maximum) {
            player.entity().staminaRecoveryTime = formatTime(moment);
        } else {
            player.entity().staminaRecoveryTime = formatTime(moment.plus(
                    (long) (maximum - value) * RECOVER_SECONDS, ChronoUnit.SECONDS));
        }
        return value;
    }

    public static int restore(PlayerContext player, int points) {
        return restore(player, points, null);
    }

    /** 今天已用掉的 SIsCa 回复次数；存的重置时刻已过则算 0。 */
    public static int jewelRecoveryCount(PlayerContext player, Instant now) {
        Instant resetAt = parseTime(player.str("JewelStRecoveryResetTime"));
        if (resetAt == null || !resetAt.isAfter(now(now))) {
            return 0;
        }
        return Math.max(0, player.intOf("JewelStRecoveryCount", 0));
    }

    public static List<Map<String, Object>> jewelSettings() {
        return new ArrayList<>(JEWEL_SETTINGS);
    }

    /**
     * {@code count} 次回复的总价与总点数；超出每日上限时返回 null。
     *
     * <p>返回 {@code [price, points]}，对应 Python 的元组。
     */
    public static int[] jewelPrice(int countBefore, int count) {
        int price = 0;
        int points = 0;
        for (int index = countBefore; index < countBefore + count; index++) {
            Map<String, Object> row = jewelRow(index);
            if (row == null) {
                return null;
            }
            price += (int) row.get("Price");
            points += (int) row.get("EffectValue");
        }
        return new int[] {price, points};
    }

    private static Map<String, Object> jewelRow(int index) {
        for (Map<String, Object> row : JEWEL_SETTINGS) {
            if (index < (int) row.get("JewelStRecoveryCountLimit")) {
                return row;
            }
        }
        return null;
    }

    /** 下线字段 {@code user_stamina}，带恢复后的当前值。 */
    public static Map<String, Object> snapshot(PlayerContext player, Instant now) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stamina_now", current(player, now));
        row.put("stamina_max", player.entity().staminaMax);
        row.put("stamina_recovery_time",
                player.entity().staminaRecoveryTime == null || player.entity().staminaRecoveryTime.isEmpty()
                        ? null : player.entity().staminaRecoveryTime);
        return row;
    }

    private static long ceilDiv(long value, long divisor) {
        return Math.floorDiv(value + divisor - 1, divisor);
    }
}
