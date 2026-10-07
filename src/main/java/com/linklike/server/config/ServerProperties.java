package com.linklike.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 对应 Python 版 private_server/config.json 的运行时配置。 */
@ConfigurationProperties(prefix = "linklike")
public class ServerProperties {

    /** "off" 表示不校验 X-Api-Key；否则请求必须匹配。 */
    private String apiKey = "off";

    /** true 时强制所有 /v1 接口（登录、条款、连携除外）必须带有效 Bearer。 */
    private boolean requireBearer = false;

    private String clientVersion = "5.1.0";

    /** 下发给客户端的资源版本；自制内容激活时会被替换。 */
    private String resourceVersion = "";

    /**
     * 已解码资源目录的根目录（{@code verify_resource_catalog.py --output-dir} 的产物），
     * 里面按 {@code <client_version>-<Rversion>/catalog_entries.json} 分版本存放。
     *
     * <p>Python 版写死在仓库的 {@code build/catalog}；Java 版做成可配置，默认值与它一致
     * （相对进程工作目录）。目录不存在时按「没有解码目录」处理，不按标签过滤。
     */
    private String resourceCatalogDir = "build/catalog";

    private boolean logRequests = true;

    private String logDir = "build/server-profiles/5.1.0/logs";

    private OfficialApi officialApi = new OfficialApi();

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean isRequireBearer() {
        return requireBearer;
    }

    public void setRequireBearer(boolean requireBearer) {
        this.requireBearer = requireBearer;
    }

    public String getClientVersion() {
        return clientVersion;
    }

    public void setClientVersion(String clientVersion) {
        this.clientVersion = clientVersion;
    }

    public String getResourceVersion() {
        return resourceVersion;
    }

    public void setResourceVersion(String resourceVersion) {
        this.resourceVersion = resourceVersion;
    }

    public String getResourceCatalogDir() {
        return resourceCatalogDir;
    }

    public void setResourceCatalogDir(String resourceCatalogDir) {
        this.resourceCatalogDir = resourceCatalogDir;
    }

    public boolean isLogRequests() {
        return logRequests;
    }

    public void setLogRequests(boolean logRequests) {
        this.logRequests = logRequests;
    }

    public String getLogDir() {
        return logDir;
    }

    public void setLogDir(String logDir) {
        this.logDir = logDir;
    }

    public OfficialApi getOfficialApi() {
        return officialApi;
    }

    public void setOfficialApi(OfficialApi officialApi) {
        this.officialApi = officialApi;
    }

    /** 本地没有可核实来源的数据（表情 / 会员贴图 / 社团赛事名）的官方回源设置。 */
    public static class OfficialApi {
        private boolean enabled = false;
        private String playerId = "";
        private String deviceId = "";
        private String proxy = "";
        private long cacheSeconds = 600;
        private long retrySeconds = 60;
        private long timeoutSeconds = 20;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPlayerId() {
            return playerId;
        }

        public void setPlayerId(String playerId) {
            this.playerId = playerId;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(String deviceId) {
            this.deviceId = deviceId;
        }

        public String getProxy() {
            return proxy;
        }

        public void setProxy(String proxy) {
            this.proxy = proxy;
        }

        public long getCacheSeconds() {
            return cacheSeconds;
        }

        public void setCacheSeconds(long cacheSeconds) {
            this.cacheSeconds = cacheSeconds;
        }

        public long getRetrySeconds() {
            return retrySeconds;
        }

        public void setRetrySeconds(long retrySeconds) {
            this.retrySeconds = retrySeconds;
        }

        public long getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(long timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
