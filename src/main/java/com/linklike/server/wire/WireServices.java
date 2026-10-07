package com.linklike.server.wire;

import com.linklike.server.protocol.WireError;
import com.linklike.server.resource.ResourceCatalogLabels;
import com.linklike.server.service.PlayerContext;
import com.linklike.server.service.RefCatalogStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@code wire_services.py} 的移植（进行中）—— {@code /v1} 业务层里被其它
 * {@code wire_*} 模块共用的一层。
 *
 * <p>目前移植了两块：
 * <ul>
 *   <li>「按已下发资源目录过滤 + 与账号自己的状态合并」这一层：{@code catalog_labels} /
 *       {@code resource_labels} / {@code with_resources} / {@code served} / {@code overlay}
 *       与各资源标签常量（Python 里这些常量紧挨着这部分，所以一并放在这里）。调用方是目录型
 *       接口：先取导入的参考目录，再按客户端真正能渲染出来的资源过滤，最后叠上本账号的记录；
 *   <li>时间与取值 helper：{@code next_daily_reset}（04:00 JST 日界，日常券与节奏游戏
 *       自动游玩券共用）、{@code _rows}（账号状态列表的深拷贝）、{@code _integer}
 *       （严格整数 + 闭区间校验）。
 * </ul>
 *
 * <p>{@code wire_services.py} 其余部分（礼物盒、剧情、关卡、抽卡、任务、好友……）还没移植，
 * 对应路径仍由 {@link RouteSnapshot} 注册为显式 501；这个类会随着移植继续长大，不另起炉灶。
 */
@Component
public class WireServices {

    /** 表情每一条需要的 label（Python {@code wire_services.STICKER_ASSETS}）。 */
    public static final String STICKER_ASSETS = "image_sticker_{stickers_id}";

    /** 歌曲需要的 label：缩略图与谱面文件（Python {@code wire_services.MUSIC_ASSETS}）。 */
    public static final List<String> MUSIC_ASSETS = List.of(
            "image_music_thumbnail_{musics_id}", "musicscore_{musics_id}.csv");

    /**
     * 节奏游戏（School Idol Show）歌曲需要的 label（Python
     * {@code wire_services.RHYTHM_MUSIC_ASSETS}）。
     *
     * <p>与 {@link #MUSIC_ASSETS} 的差别只在字段名：节奏游戏目录里的歌曲 ID 字段是
     * {@code music_id}，收藏目录里是 {@code musics_id}。
     */
    public static final List<String> RHYTHM_MUSIC_ASSETS = List.of(
            "image_music_thumbnail_{music_id}", "musicscore_{music_id}.csv");

    /** 每日重置（04:00）用的时区，Python {@code wire_services._JST}。 */
    public static final ZoneOffset JST = ZoneOffset.ofHours(9);

    /** Python {@code next_daily_reset} 的输出格式：{@code 2026-10-08T04:00:00+09:00}。 */
    private static final DateTimeFormatter DAILY_RESET_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    private final RefCatalogStore catalogs;
    private final ResourceCatalogLabels resourceLabels;

    public WireServices(RefCatalogStore catalogs, ResourceCatalogLabels resourceLabels) {
        this.catalogs = catalogs;
        this.resourceLabels = resourceLabels;
    }

    // ------------------------------------------------------------------
    // 参考目录
    // ------------------------------------------------------------------

    /** 导入的参考目录（例如 {@code sticker_collection}）的全部行，对应 Python {@code _refs}。 */
    public List<Map<String, Object>> refs(String catalogKey) {
        return new ArrayList<>(catalogs.list(catalogKey));
    }

    /**
     * 服务端实际能渲染出来的参考目录行，对应 Python {@code served(...)}。
     *
     * <p>{@code templates} 是每条记录需要的 addressable label（用记录字段格式化），
     * 例如 {@code image_sticker_{stickers_id}}；本机没有解码资源目录时不过滤。
     */
    public List<Map<String, Object>> served(String catalogKey, List<String> templates) {
        return withResources(refs(catalogKey), resourceLabels.current(), templates);
    }

    /**
     * 资源文件确实存在的目录行；本机没有解码资源目录（{@code labels == null}）时返回全部。
     *
     * <p>对应 Python {@code wire_services.with_resources}：行里缺少模板需要的字段时跳过该行，
     * 而不是下发一个渲染不出来的条目。
     */
    public static List<Map<String, Object>> withResources(List<Map<String, Object>> rows,
                                                          Set<String> labels,
                                                          List<String> templates) {
        if (labels == null) {
            return rows;
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            boolean available = true;
            for (String template : templates) {
                String label = formatLabel(template, row);
                if (label == null || !labels.contains(label)) {
                    available = false;
                    break;
                }
            }
            if (available) {
                kept.add(row);
            }
        }
        return kept;
    }

    /**
     * 目录行叠加账号自己的行，对应 Python {@code wire_services.overlay}。
     *
     * <p>同 Identifier 的账号行会覆盖目录行的 {@code fields}（{@code null} 表示覆盖全部字段）；
     * 目录里没有的账号行原样追加，所以没有导入目录的部署仍然会把账号自己的记录下发出去。
     * 缺 Identifier 的账号行（Python 里的 keyless）排在最后。
     */
    public static List<Map<String, Object>> overlay(List<Map<String, Object>> catalog,
                                                    List<Map<String, Object>> owned,
                                                    String key) {
        return overlay(catalog, owned, key, null);
    }

    public static List<Map<String, Object>> overlay(List<Map<String, Object>> catalog,
                                                    List<Map<String, Object>> owned,
                                                    String key,
                                                    Set<String> fields) {
        Map<String, Map<String, Object>> own = new LinkedHashMap<>();
        List<Map<String, Object>> keyless = new ArrayList<>();
        for (Map<String, Object> row : owned) {
            Object ident = row.get(key);
            if (ident == null) {
                keyless.add(row);
            } else {
                own.put(String.valueOf(ident), row);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>(catalog.size() + owned.size());
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> row : catalog) {
            String ident = String.valueOf(row.get(key));
            seen.add(ident);
            Map<String, Object> merged = deepCopy(row);
            Map<String, Object> mine = own.get(ident);
            // Python 的 `if mine:` 是 dict 真值判断，空 dict 不参与覆盖。
            if (mine != null && !mine.isEmpty()) {
                for (Map.Entry<String, Object> entry : mine.entrySet()) {
                    if (fields == null || fields.contains(entry.getKey())) {
                        merged.put(entry.getKey(), deepCopyValue(entry.getValue()));
                    }
                }
            }
            result.add(merged);
        }
        for (Map.Entry<String, Map<String, Object>> entry : own.entrySet()) {
            if (!seen.contains(entry.getKey())) {
                result.add(deepCopy(entry.getValue()));
            }
        }
        for (Map<String, Object> row : keyless) {
            result.add(deepCopy(row));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 时间与取值
    // ------------------------------------------------------------------

    /**
     * 下一个 04:00 JST，ISO-8601 带 {@code +09:00} 偏移 —— 对应 Python
     * {@code wire_services.next_daily_reset}。
     *
     * <p>日常券与节奏游戏自动游玩券共用这条上游重置边界（抓包
     * {@code 2026-09-10T04:00:00+09:00}）。客户端把两者都当不可空的 {@code DateTime} 解析，
     * 空串是反序列化错误而不是「未设置」，所以这里永远返回一个具体时刻。
     *
     * @param now 判定时刻；null 表示当前时间
     */
    public static String nextDailyReset(Instant now) {
        OffsetDateTime moment = (now == null ? Instant.now() : now).atOffset(JST);
        OffsetDateTime candidate = moment.withHour(4).withMinute(0).withSecond(0).withNano(0);
        if (!candidate.isAfter(moment)) {
            candidate = candidate.plusDays(1);
        }
        return DAILY_RESET_FORMAT.format(candidate);
    }

    public static String nextDailyReset() {
        return nextDailyReset(null);
    }

    /**
     * 账号某个状态键下的行，深拷贝一份再交给调用方改 —— 对应 Python
     * {@code wire_services._rows}。
     *
     * <p>Python 的 {@code _rows} 是 {@code deepcopy(player.get(key, []) or [])}：改副本、
     * 落库、再整体写回。Java 侧同样返回副本，避免调用方在计算过程中改脏当前请求里的账号状态。
     *
     * <p>与 Python 的一处差异：{@code _rows} 对「值不是列表但为真」的键（例如误把
     * {@code 'oops'} 存进该键）会原样深拷贝那个值，Java 的返回类型固定是行列表，这种键一律
     * 返回空表。调用点的键要么是列表要么缺失，这条差异在正常流程里看不到。
     */
    public static List<Map<String, Object>> rows(PlayerContext player, String key) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> row : player.rowsSnapshot(key)) {
            copy.add(deepCopy(row));
        }
        return copy;
    }

    /**
     * 严格整数 + 闭区间校验 —— 对应 Python {@code wire_services._integer}。
     *
     * <p>Python 用 {@code type(value) is not int} 判断，所以布尔、浮点、字符串都会落进
     * 「不是整数」这一支（{@code True} 虽然能参与比较，但类型不是 {@code int}）。这里同样
     * 只接受整型 JSON 数字（{@code Integer} / {@code Long}），{@code 3.0}、{@code "3"}、
     * {@code true} 一律按校验失败处理。
     *
     * <p>Python 的判定是 {@code type(value) is not int or not minimum <= value <= maximum}，
     * 短路求值意味着「类型不对」永远先命中；缺字段又没有默认值（Python 的
     * {@code default=None}）时走的是同一句 400 文案，不会像 {@code minimum <= None} 那样
     * 抛 {@code TypeError}。
     *
     * <p>上界用 {@code long}：Python 侧多处传 {@code maximum=2 ** 63 - 1}（{@code score}、
     * {@code play_time_ms}、{@code timeline_unixtime}），{@code int} 表达不了。
     *
     * @param fallback 缺字段时用的值；null 表示「必填」
     */
    public static long integer(Map<String, Object> body, String key, Long fallback,
                               long minimum, long maximum) {
        Object value = body.containsKey(key) ? body.get(key) : fallback;
        Long parsed = strictLong(value);
        if (parsed == null || parsed < minimum || parsed > maximum) {
            throw WireError.badRequest(String.format(
                    "%s must be an integer from %d to %d", key, minimum, maximum));
        }
        return parsed;
    }

    /** {@code int} 取值域的便捷重载；除上界外与上面的方法等价。 */
    public static int integer(Map<String, Object> body, String key, Integer fallback,
                              int minimum, int maximum) {
        return (int) integer(body, key, fallback == null ? null : fallback.longValue(),
                minimum, (long) maximum);
    }

    /** Python 的 {@code type(value) is int}：只认整型数字，布尔不算。 */
    private static Long strictLong(Object value) {
        if (value instanceof Integer number) {
            return number.longValue();
        }
        if (value instanceof Long number) {
            return number;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * Python {@code template.format(**row)}：把 {@code {字段名}} 换成行里的值，缺字段时返回
     * null（Python 会抛 {@code KeyError}，调用方 {@code with_resources} 据此跳过该行）。
     *
     * <p>只支持本项目模板用到的形态：具名字段、{@code {{}}} 转义。带格式说明（{@code {x:>3}}）
     * 或位置参数（{@code {0}}）的模板在这里一律当作「取不到值」，README 里记录了这一点。
     */
    static String formatLabel(String template, Map<String, Object> row) {
        StringBuilder out = new StringBuilder(template.length() + 16);
        int index = 0;
        while (index < template.length()) {
            char current = template.charAt(index);
            if (current == '{') {
                if (index + 1 < template.length() && template.charAt(index + 1) == '{') {
                    out.append('{');
                    index += 2;
                    continue;
                }
                int end = template.indexOf('}', index + 1);
                if (end < 0) {
                    return null;
                }
                String name = template.substring(index + 1, end);
                if (name.isEmpty() || name.indexOf(':') >= 0 || name.indexOf('!') >= 0
                        || !row.containsKey(name)) {
                    return null;
                }
                out.append(pythonText(row.get(name)));
                index = end + 1;
            } else if (current == '}') {
                if (index + 1 < template.length() && template.charAt(index + 1) == '}') {
                    out.append('}');
                    index += 2;
                    continue;
                }
                return null;
            } else {
                out.append(current);
                index++;
            }
        }
        return out.toString();
    }

    /** Python {@code str()} 在标签里的形态（None / True / False 与 Python 拼写一致）。 */
    private static String pythonText(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Boolean flag) {
            return flag ? "True" : "False";
        }
        return String.valueOf(value);
    }

    private static Map<String, Object> deepCopy(Map<String, Object> value) {
        Map<String, Object> out = new LinkedHashMap<>(value.size());
        value.forEach((key, item) -> out.put(key, deepCopyValue(item)));
        return out;
    }

    private static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>(map.size());
            map.forEach((key, item) -> out.put(String.valueOf(key), deepCopyValue(item)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(deepCopyValue(item));
            }
            return out;
        }
        return value;
    }
}
