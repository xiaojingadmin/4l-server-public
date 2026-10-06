package com.linklike.server.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态 JSON 结构的读写工具，对应 Python 版里直接操作 {@code dict} / {@code list} 的写法。
 *
 * <p>没有强类型模型的地方（949 个协议模型无法逐个建类）统一用
 * {@code Map<String,Object>} / {@code List<Object>} 承载，取值走这里的容错 helper，
 * 语义与 Python 的 {@code _id} / {@code _integer} / {@code _rows} 一致。
 */
public final class J {

    private J() {
    }

    /** {@code map("Result", true, "ItemList", list())} 形式的字面量构造。 */
    public static Map<String, Object> map(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("map() 需要偶数个参数（键值成对）");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    public static Map<String, Object> emptyMap() {
        return new LinkedHashMap<>();
    }

    @SafeVarargs
    public static <T> List<T> list(T... items) {
        List<T> list = new ArrayList<>(items.length);
        list.addAll(Arrays.asList(items));
        return list;
    }

    public static List<Object> emptyList() {
        return new ArrayList<>();
    }

    // ---- 容错取值 ----

    public static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 空串与 null 都返回 null，用于「必填字符串」判断。 */
    public static String nonEmpty(Object value) {
        if (value instanceof String s && !s.isEmpty()) {
            return s;
        }
        return null;
    }

    public static Integer integer(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isEmpty()) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static Long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isEmpty()) {
            try {
                return Long.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static int intOr(Object value, int fallback) {
        Integer parsed = integer(value);
        return parsed == null ? fallback : parsed;
    }

    public static long longOr(Object value, long fallback) {
        Long parsed = asLong(value);
        return parsed == null ? fallback : parsed;
    }

    public static boolean boolOr(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return "true".equalsIgnoreCase(s);
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> nodeOr(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    /** 取对象节点，缺失时返回空 Map（Python 里常见 `body.get(...) or {}`）。 */
    public static Map<String, Object> nodeOrEmpty(Object value) {
        Map<String, Object> node = nodeOr(value);
        return node == null ? emptyMap() : node;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> listOr(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : null;
    }

    /** 取列表节点，缺失时返回空 List。 */
    public static List<Object> listOrEmpty(Object value) {
        List<Object> list = listOr(value);
        return list == null ? emptyList() : list;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> rowsOr(Object value) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : listOrEmpty(value)) {
            if (item instanceof Map<?, ?> map) {
                rows.add((Map<String, Object>) map);
            }
        }
        return rows;
    }
}
