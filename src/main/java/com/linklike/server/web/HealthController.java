package com.linklike.server.web;

import com.linklike.server.config.ServerProperties;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查 —— 对应 Python 版 {@code server.Handler._handle} 里 {@code GET /health} 的分支。
 *
 * <p>字段与 Python 版完全一致，便于把现有的探活脚本直接指过来。
 * {@code gacha} 字段标明本地抽卡结算的规则版本。
 */
@RestController
public class HealthController {

    private final ServerProperties config;

    public HealthController(ServerProperties config) {
        this.config = config;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("version", config.getClientVersion());
        body.put("time", Instant.now().getEpochSecond());
        body.put("mode", "local");
        body.put("pid", ProcessHandle.current().pid());
        body.put("gacha", "local-settlement-v1");
        return body;
    }
}
