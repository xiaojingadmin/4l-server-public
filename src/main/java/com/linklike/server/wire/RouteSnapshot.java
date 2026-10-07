package com.linklike.server.wire;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Python 参考服务端支持的 {@code /v1} 路径快照，来自 {@code wire_api.SUPPORTED}
 * （{@code wire_routes_5_1_0.json}）。
 *
 * <p>用途是让「Java 版还没移植的接口」与「Python 版根本不认识的路径」在客户端看来一致
 * （都是 501），同时保留 {@code route_implemented} 语义：
 *
 * <ul>
 *   <li>路径在快照里 -> 注册一个直接抛 501 的 handler（对应 Python 里
 *       {@code raise WireError(501, 'Local endpoint not implemented')} 的分支）；
 *   <li>路径不在快照里 -> 不注册，走到分发层的「未实现」501。
 * </ul>
 *
 * <p>验收标准很直接：{@link #paths()} 与 Python 的 {@code SUPPORTED} 逐条相等。
 * 每次移植完一个 {@code wire_*} 模块，这个文件就该少一批待办路径。
 */
@Component
public class RouteSnapshot {

    private final Set<String> paths;

    public RouteSnapshot(ObjectMapper mapper) {
        this.paths = load(mapper);
    }

    private static Set<String> load(ObjectMapper mapper) {
        ClassPathResource resource = new ClassPathResource("wire_routes_5_1_0.json");
        try (InputStream in = resource.getInputStream()) {
            Document document = mapper.readValue(in, new TypeReference<Document>() {
            });
            Set<String> paths = new LinkedHashSet<>();
            for (String path : document.paths()) {
                if (path != null && !path.isBlank()) {
                    paths.add(WireRouter.normalize(path));
                }
            }
            return Set.copyOf(paths);
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取 wire_routes_5_1_0.json", e);
        }
    }

    /** 5.1.0 的已支持路径（规范化后）。 */
    public Set<String> paths() {
        return paths;
    }

    public boolean contains(String path) {
        return paths.contains(WireRouter.normalize(path));
    }

    public int size() {
        return paths.size();
    }

    /** 快照文件的形态，与 Python 导出脚本一致。 */
    record Document(String protocol, String source, int count, List<String> paths) {
    }
}
