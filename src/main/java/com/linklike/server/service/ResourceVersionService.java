package com.linklike.server.service;

import com.linklike.server.config.ServerProperties;
import com.linklike.server.resource.ResourceManifest;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 下发给客户端的资源版本 —— 对应 Python 版 {@code server.py} 的
 * {@code _current_resource_version()}。
 *
 * <p>客户端从 {@code x-res-version} 响应头读取资源版本，并据此判断是否需要重新同步资源
 * 目录。版本串必须通过 {@link ResourceManifest#parse} 校验：Python 版在加载配置时就校验，
 * 并且在下发前再校验一次，这里保持同样的做法 —— <b>启动即失败</b>，绝不回退到猜测的版本。
 *
 * <p>自制内容被激活时，Python 版会把版本替换成「由官方版本派生」的版本，让客户端重新
 * 同步带自制条目的目录；对应逻辑在自定义内容服务里，这里保留同一入口。
 */
@Service
public class ResourceVersionService {

    private static final Logger log = LoggerFactory.getLogger(ResourceVersionService.class);

    private final ServerProperties config;

    public ResourceVersionService(ServerProperties config) {
        this.config = config;
    }

    @PostConstruct
    void validateAtStartup() {
        String version = config.getResourceVersion();
        if (version == null || version.isEmpty()) {
            log.warn("未配置 linklike.resource-version，/v1 成功响应将不下发 x-res-version 头");
            return;
        }
        ResourceManifest manifest = ResourceManifest.parse(version);
        log.info("资源版本 {} -> 目录 {}（{} 字节）",
                manifest.simpleVersion(), manifest.catalogPath(), manifest.size());
    }

    /**
     * 当前下发的资源版本签名；未配置时返回空串。
     *
     * <p>做成方法而非常量，是为了给自制内容的动态替换留出位置。
     */
    public String currentSignature() {
        String version = config.getResourceVersion();
        if (version == null || version.isEmpty()) {
            return "";
        }
        // 下发前再校验一次：配置可能在运行期被改动。
        return ResourceManifest.parse(version).signature();
    }

    /** 当前版本对应的目录真名，资源代理用它拼 {@code /raw/..} 路径。 */
    public String currentCatalogPath() {
        String version = config.getResourceVersion();
        if (version == null || version.isEmpty()) {
            return "";
        }
        return ResourceManifest.parse(version).catalogPath();
    }
}
