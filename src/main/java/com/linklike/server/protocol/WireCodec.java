package com.linklike.server.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 内部数据（PascalCase 属性名）到线上字段（snake_case wire 名）的转换。
 *
 * <p>与 Python 版 {@code wire_api.encode} 等价：只输出模型声明过的属性，忽略其余键；
 * 嵌套模型递归转换，未声明的属性直接丢弃。
 */
@Component
public class WireCodec {

    private final WireModels models;

    public WireCodec(WireModels models) {
        this.models = models;
    }

    /**
     * 按模型定义把内部数据编码成线上下发结构。
     *
     * @param model 模型名，例如 {@code UserLoginResponse}
     * @param data  内部数据，键为模型属性名
     * @return 键为 snake_case 线上字段名的有序 Map
     */
    public Map<String, Object> encode(String model, Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, WireModels.Field> declared = models.fields(model);
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            WireModels.Field field = declared.get(entry.getKey());
            if (field == null) {
                continue;
            }
            result.put(field.wire(), convert(field.type(), entry.getValue()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object convert(String type, Object value) {
        if (value == null) {
            return null;
        }
        if (models.hasModel(type)) {
            return value instanceof Map<?, ?> map ? encode(type, (Map<String, Object>) map) : value;
        }
        if (type.startsWith("List<") && type.endsWith(">")) {
            String element = type.substring(5, type.length() - 1);
            if (models.hasModel(element) && value instanceof List<?> list) {
                List<Object> encoded = new ArrayList<>(list.size());
                for (Object item : list) {
                    encoded.add(item instanceof Map<?, ?> map
                            ? encode(element, (Map<String, Object>) map)
                            : item);
                }
                return encoded;
            }
        }
        return value;
    }
}
