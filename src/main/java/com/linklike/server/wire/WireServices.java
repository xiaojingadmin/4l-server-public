package com.linklike.server.wire;

import com.linklike.server.resource.ResourceCatalogLabels;
import com.linklike.server.service.RefCatalogStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@code wire_services.py} 的移植（进行中）—— {@code /v1} 业务层里被其它
 * {@code wire_*} 模块共用的一层。
 *
 * <p>目前只移植了「按已下发资源目录过滤 + 与账号自己的状态合并」这一层，即
 * {@code catalog_labels} / {@code resource_labels} / {@code with_resources} /
 * {@code served} / {@code overlay} 与各资源标签常量（Python 里这些常量紧挨着这部分，
 * 所以一并放在这里）。这一层的调用方是目录型接口：先取导入的参考目录，再按客户端真正
 * 能渲染出来的资源过滤，最后叠上本账号的记录。
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
