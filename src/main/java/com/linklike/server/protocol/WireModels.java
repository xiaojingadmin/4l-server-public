package com.linklike.server.protocol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 5.1.0 客户端的协议模型表，来自客户端提取的 {@code wire_models.json}。
 *
 * <p>结构为 {@code model -> (属性名 -> {wire, type})}。只有真正的嵌套模型会出现在表里；
 * 枚举（{@code UserType}、{@code ItemType} …）和标量（{@code int}、{@code DateTime} …）
 * 按原值透传，与 Python 版 {@code wire_api.encode} 的行为一致。
 */
@Component
public class WireModels {

    /** 一个属性的线上字段名与类型。 */
    public record Field(String wire, String type) {

        public boolean isList() {
            return type.startsWith("List<") && type.endsWith(">");
        }

        /** {@code List<X>} 的元素类型；非列表时返回 null。 */
        public String elementType() {
            return isList() ? type.substring(5, type.length() - 1) : null;
        }

        /** 去掉 {@code Nullable<...>} 包装后的类型。 */
        public String unwrapped() {
            if (type.startsWith("Nullable<") && type.endsWith(">")) {
                return type.substring(9, type.length() - 1);
            }
            return type;
        }
    }

    private final Map<String, Map<String, Field>> models;

    public WireModels(ObjectMapper mapper) {
        this.models = load(mapper);
    }

    private static Map<String, Map<String, Field>> load(ObjectMapper mapper) {
        ClassPathResource resource = new ClassPathResource("wire_models.json");
        try (InputStream in = resource.getInputStream()) {
            Map<String, Map<String, Map<String, String>>> raw =
                    mapper.readValue(in, new TypeReference<>() {});
            Map<String, Map<String, Field>> parsed = new LinkedHashMap<>();
            raw.forEach((model, fields) -> {
                Map<String, Field> converted = new LinkedHashMap<>();
                fields.forEach((prop, spec) -> {
                    Object wire = spec.get("wire");
                    Object type = spec.get("type");
                    if (wire != null && type != null) {
                        converted.put(prop, new Field(String.valueOf(wire), String.valueOf(type)));
                    }
                });
                parsed.put(model, converted);
            });
            return Map.copyOf(parsed);
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取 wire_models.json", e);
        }
    }

    public boolean hasModel(String model) {
        return models.containsKey(model);
    }

    public Field field(String model, String prop) {
        Map<String, Field> fields = models.get(model);
        return fields == null ? null : fields.get(prop);
    }

    public Map<String, Field> fields(String model) {
        return models.getOrDefault(model, Map.of());
    }

    /**
     * 把「线上字段拼写（snake_case）」的记录转成「模型属性拼写（PascalCase）」，递归处理
     * 嵌套模型与模型列表 —— 对应 Python 版 {@code wire_initial.props}。
     *
     * <p>用途是把客户端抓来的 fixture（例如打包的 {@code getCardList}）转成内部存储形态，
     * 这样它才能再被 {@link WireCodec#encode} 编码下发。已经是属性名的键原样保留；
     * 两边都不认识的键丢弃（与 Python 版一致，不把未知字段带进账号状态）。
     */
    public Map<String, Object> toProperties(String model, Map<String, Object> row) {
        Map<String, Field> declared = fields(model);
        Map<String, String> byWire = new LinkedHashMap<>();
        declared.forEach((prop, field) -> byWire.put(field.wire(), prop));

        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey();
            String prop;
            String type;
            if (declared.containsKey(key)) {
                prop = key;
                type = declared.get(key).type();
            } else if (byWire.containsKey(key)) {
                prop = byWire.get(key);
                type = declared.get(prop).type();
            } else {
                continue;
            }
            out.put(prop, convertToProperties(type, entry.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object convertToProperties(String type, Object value) {
        String base = type;
        boolean list = false;
        if (type.startsWith("List<") && type.endsWith(">")) {
            base = type.substring(5, type.length() - 1);
            list = true;
        } else if (type.startsWith("Nullable<") && type.endsWith(">")) {
            base = type.substring(9, type.length() - 1);
        }
        if (!hasModel(base)) {
            return value;
        }
        if (list && value instanceof List<?> items) {
            List<Object> converted = new ArrayList<>(items.size());
            for (Object item : items) {
                converted.add(item instanceof Map<?, ?> map
                        ? toProperties(base, (Map<String, Object>) map)
                        : item);
            }
            return converted;
        }
        if (!list && value instanceof Map<?, ?> map) {
            return toProperties(base, (Map<String, Object>) map);
        }
        return value;
    }

    public Set<String> modelNames() {
        return models.keySet();
    }

    public int size() {
        return models.size();
    }
}
