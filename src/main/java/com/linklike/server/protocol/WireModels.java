package com.linklike.server.protocol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
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

    public Set<String> modelNames() {
        return models.keySet();
    }

    public int size() {
        return models.size();
    }
}
