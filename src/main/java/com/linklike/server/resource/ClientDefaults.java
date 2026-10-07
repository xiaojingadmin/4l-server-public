package com.linklike.server.resource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 打包进 jar 的客户端静态主表 —— 对应 Python 版 {@code wire_catalog.ROOT} 指向的
 * {@code build/client_defaults} 目录。
 *
 * <p>这个目录里有两类文件，来源不同：
 * <ul>
 *   <li>{@code *.json}（{@code card_levels}、{@code standard_quest_stages}、
 *       {@code stage_deck_slots}……）—— 随仓库分发，随 jar 走；
 *   <li>{@code base_res_content_*.json}（{@code getProfileInfo}、{@code getCardList}、
 *       {@code getCharacterInfo_<id>}）—— 由客户端抽取脚本产出。Python 版把它们写在
 *       {@code .gitignore} 里（{@code /build/client_defaults/base_res_content_*.json}），
 *       只有做过抽取的机器上才有。
 * </ul>
 *
 * <p>因此这里把「文件不存在」当成正常状态返回空值，而不是抛异常：调用方据此决定是
 * 优雅降级（返回空目录）还是明确失败（501），不会把缺文件变成 500。Python 版直接
 * {@code read_text()}，缺文件会抛 {@code FileNotFoundError}；两种做法在「不伪造数据」
 * 这一点上一致。
 */
@Component
public class ClientDefaults {

    private static final Logger log = LoggerFactory.getLogger(ClientDefaults.class);

    /** 客户端抽取产物的文件名前缀。 */
    public static final String EXTRACTED_PREFIX = "base_res_content_";

    private static final String CLASSPATH_ROOT = "client_defaults/";

    private final ObjectMapper mapper;
    private final PathMatchingResourcePatternResolver resolver;
    private final Map<String, Optional<Object>> cache = new java.util.concurrent.ConcurrentHashMap<>();

    public ClientDefaults(ObjectMapper mapper) {
        this.mapper = mapper;
        this.resolver = new PathMatchingResourcePatternResolver(getClass().getClassLoader());
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /** 读一个 JSON 文件（相对 {@code client_defaults/}），缺失时返回空。 */
    public Optional<Object> json(String name) {
        return cache.computeIfAbsent(name, key -> load(key + ".json"));
    }

    /**
     * 读一个 JSON 对象，缺失或不是对象时返回空。
     *
     * <p>对应 Python 版的 {@code json.loads(...)}，只是把「文件不在」变成空值。
     */
    public Optional<Map<String, Object>> object(String name) {
        Object value = json(name).orElse(null);
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return Optional.of(typed);
        }
        return Optional.empty();
    }

    /** 读一个 JSON 数组的行；缺失或不是数组时返回空列表。 */
    public List<Map<String, Object>> rows(String name) {
        Object value = json(name).orElse(null);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                rows.add(typed);
            }
        }
        return rows;
    }

    /** 文件是否存在（已解析过则直接看缓存）。 */
    public boolean present(String name) {
        return json(name).isPresent();
    }

    /**
     * 按前缀列出文件名（不含 {@code .json}，按名称排序）。
     *
     * <p>对应 Python 的 {@code ROOT.glob('base_res_content_getCharacterInfo_*.json')}。
     */
    public List<String> fileNames(String prefix) {
        Map<String, String> found = new TreeMap<>();
        for (String name : classpathNames()) {
            if (name.startsWith(prefix)) {
                found.put(name, name);
            }
        }
        return new ArrayList<>(found.values());
    }

    /** 是否做过客户端抽取（{@code base_res_content_*} 至少有一个）。 */
    public boolean hasExtractedFixtures() {
        return !fileNames(EXTRACTED_PREFIX).isEmpty();
    }

    /** 启动日志用：把「缺客户端抽取产物」这件事明确说出来，不静默。 */
    public void logAvailability() {
        List<String> extracted = fileNames(EXTRACTED_PREFIX);
        if (extracted.isEmpty()) {
            log.warn("client_defaults 里没有 {}*.json（客户端抽取产物）。"
                            + "依赖它们的功能（初始卡、资料模板、收藏角色信息）会按「本机无此数据」处理，"
                            + "相关接口返回 501 而不是编造内容。",
                    EXTRACTED_PREFIX);
        } else {
            log.info("client_defaults: {} 个客户端抽取文件可用", extracted.size());
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private Optional<Object> load(String fileName) {
        String location = "classpath*:" + CLASSPATH_ROOT + fileName;
        try {
            Resource[] resources = resolver.getResources(location);
            for (Resource resource : resources) {
                if (!resource.isReadable()) {
                    continue;
                }
                try (InputStream in = resource.getInputStream()) {
                    return Optional.ofNullable(mapper.readValue(in, new TypeReference<Object>() {
                    }));
                }
            }
        } catch (IOException e) {
            log.warn("读取 client_defaults/{} 失败: {}", fileName, e.getMessage());
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** 枚举 {@code client_defaults/} 下的文件名（去掉目录与 {@code .json} 后缀）。 */
    private List<String> classpathNames() {
        List<String> names = new ArrayList<>();
        try {
            Resource[] resources = resolver.getResources("classpath*:" + CLASSPATH_ROOT + "*.json");
            for (Resource resource : resources) {
                String fileName = resource.getFilename();
                if (fileName != null && fileName.endsWith(".json")) {
                    names.add(fileName.substring(0, fileName.length() - ".json".length()));
                }
            }
        } catch (IOException e) {
            log.warn("枚举 client_defaults 目录失败: {}", e.getMessage());
        }
        return names;
    }

}
