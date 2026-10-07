package com.linklike.server.resource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklike.server.config.ServerProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 「服务端实际下发的资源目录里存在哪些 addressable label」—— 对应 Python 版
 * {@code wire_services.catalog_labels} / {@code resource_labels}。
 *
 * <p>客户端发现界面（道具、歌曲、服装……）引用了资源目录里不存在的资源时，加载场景会一直
 * 等下去，所以任何列出内容的接口都必须先按 label 过滤：目录里没有的条目一律不下发。
 *
 * <p>解码目录由 {@code verify_resource_catalog.py --output-dir} 产出，按
 * {@code <client_version>-<Rversion>/catalog_entries.json} 分版本存放；取不到时返回
 * {@code null}，表示「本机没有解码目录」，此时不过滤（与 Python 版一致）：
 *
 * <pre>
 *   for path in sorted(_CATALOG_DIR.glob('*-%s/catalog_entries.json' % version)):
 *       labels = {entry.get('label') for entry in json.loads(path.read_text())}
 * </pre>
 *
 * <p>注意多版本目录是<b>按路径名排序后最后一个胜出</b>（Python 的循环会整体覆盖），自制内容
 * 生成的 {@code <client>-<Rversion+1>} 目录因此能取代官方目录；某个文件解析失败也会把结果
 * 重置成 {@code null}（不过滤），与 Python 里 {@code except (OSError, ValueError)} 的写法一致。
 */
@Component
public class ResourceCatalogLabels {

    private static final Logger log = LoggerFactory.getLogger(ResourceCatalogLabels.class);

    private final ServerProperties config;
    private final ObjectMapper mapper;
    private final Map<String, Optional<Set<String>>> cache = new ConcurrentHashMap<>();

    public ResourceCatalogLabels(ServerProperties config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
    }

    /** 启动时把「过滤是否生效」明确说出来，不静默降级。 */
    @PostConstruct
    void logAvailability() {
        String version = simpleVersion(config.getResourceVersion());
        if (version == null) {
            log.warn("未配置 resource-version，无法定位解码资源目录；目录类接口不按资源过滤");
            return;
        }
        Set<String> labels = forVersion(config.getResourceVersion());
        if (labels == null) {
            log.warn("在 {} 下没找到 *-{}/catalog_entries.json（解码资源目录）。"
                            + "目录类接口会下发全部条目，客户端可能因为缺资源停在加载中。",
                    config.getResourceCatalogDir(), version);
        } else {
            log.info("资源目录 {}：{} 个 addressable label，目录类接口按此过滤",
                    version, labels.size());
        }
    }

    /** 当前下发资源版本对应的 label 集合；本机没有解码目录时返回 {@code null}。 */
    public Set<String> current() {
        return forVersion(config.getResourceVersion());
    }

    /**
     * 某个资源版本的 label 集合。
     *
     * @param resourceVersion {@code R2604100@BFrkfPQkJ9JM6RLSZCpOYoDTYA==} 形态的版本串
     * @return label 集合；版本为空或没有解码目录时返回 {@code null}
     */
    public Set<String> forVersion(String resourceVersion) {
        String version = simpleVersion(resourceVersion);
        if (version == null) {
            return null;
        }
        return cache.computeIfAbsent(version, this::load).orElse(null);
    }

    /** Python 的 {@code str(version or '').split('@')[0]}；结果为空时返回 null。 */
    private static String simpleVersion(String resourceVersion) {
        String value = resourceVersion == null ? "" : resourceVersion;
        int at = value.indexOf('@');
        String version = at < 0 ? value : value.substring(0, at);
        return version.isEmpty() ? null : version;
    }

    private Optional<Set<String>> load(String version) {
        List<Path> files = catalogFiles(version);
        Set<String> labels = null;
        for (Path file : files) {
            try (InputStream in = Files.newInputStream(file)) {
                labels = readLabels(file, in);
            } catch (IOException | RuntimeException e) {
                log.warn("解码资源目录 {} 读取失败，本次不按资源过滤: {}", file, e.getMessage());
                labels = null;
            }
        }
        return Optional.ofNullable(labels);
    }

    private Set<String> readLabels(Path file, InputStream in) throws IOException {
        List<Map<String, Object>> entries =
                mapper.readValue(in, new TypeReference<List<Map<String, Object>>>() {
                });
        Set<String> labels = new LinkedHashSet<>();
        for (Map<String, Object> entry : entries) {
            // Python 是 {entry.get('label') for entry in entries}，缺 label 会放进一个 None；
            // 模板里取的字段不会是 None，所以这里直接跳过，行为一致。
            Object label = entry.get("label");
            if (label != null) {
                labels.add(String.valueOf(label));
            }
        }
        return labels;
    }

    /** {@code <dir>/*-<version>/catalog_entries.json}，按路径排序（Python 的 sorted(glob(...))）。 */
    private List<Path> catalogFiles(String version) {
        List<Path> files = new ArrayList<>();
        Path root = Path.of(config.getResourceCatalogDir());
        if (!Files.isDirectory(root)) {
            return files;
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root, "*-" + version)) {
            for (Path dir : dirs) {
                Path file = dir.resolve("catalog_entries.json");
                if (Files.isRegularFile(file)) {
                    files.add(file);
                }
            }
        } catch (IOException e) {
            log.warn("枚举解码资源目录 {} 失败: {}", root, e.getMessage());
            return List.of();
        }
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }
}
