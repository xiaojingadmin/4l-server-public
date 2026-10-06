package com.linklike.server.wire;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklike.server.config.ServerProperties;
import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireError;
import com.linklike.server.service.PlayerContext;
import com.linklike.server.service.PlayerStore;
import java.util.List;
import java.util.Map;

/**
 * 一次游戏请求的输入：路径、请求体、请求头、已认证玩家与配置。
 *
 * <p>取参 helper 复刻 Python 版里「先判类型再取值」的写法，把校验失败统一变成
 * {@link WireError}（400），避免每个 handler 重复写类型检查。
 */
public record WireRequest(
        String path,
        Map<String, Object> body,
        Map<String, String> headers,
        PlayerContext player,
        PlayerStore store,
        ObjectMapper mapper,
        ServerProperties config) {

    // ------------------------------------------------------------------
    // 请求头
    // ------------------------------------------------------------------

    public String header(String name) {
        return headers.get(name.toLowerCase());
    }

    public String idempotencyKey() {
        String key = header("x-idempotency-key");
        return key == null || key.isBlank() ? null : key;
    }

    // ------------------------------------------------------------------
    // 参数
    // ------------------------------------------------------------------

    public Object arg(String key) {
        return body.get(key);
    }

    public boolean has(String key) {
        return body.containsKey(key) && body.get(key) != null;
    }

    public String str(String key) {
        return J.str(body.get(key));
    }

    /** 必填非空字符串。 */
    public String requireStr(String key) {
        String value = J.nonEmpty(body.get(key));
        if (value == null) {
            throw WireError.badRequest(key + " 必须是非空字符串");
        }
        return value;
    }

    /** 必填字符串，长度上限校验。 */
    public String requireStr(String key, int maxLength) {
        String value = requireStr(key);
        if (value.length() > maxLength) {
            throw WireError.badRequest(key + " 长度不能超过 " + maxLength);
        }
        return value;
    }

    public int intArg(String key, int fallback) {
        return J.intOr(body.get(key), fallback);
    }

    public long longArg(String key, long fallback) {
        return J.longOr(body.get(key), fallback);
    }

    public boolean boolArg(String key, boolean fallback) {
        return J.boolOr(body.get(key), fallback);
    }

    /** 必填整数。 */
    public int requireInt(String key) {
        Integer value = J.integer(body.get(key));
        if (value == null) {
            throw WireError.badRequest(key + " 必须是整数");
        }
        return value;
    }

    public int requireInt(String key, int min, int max) {
        int value = requireInt(key);
        if (value < min || value > max) {
            throw WireError.badRequest(key + " 必须在 " + min + " 到 " + max + " 之间");
        }
        return value;
    }

    public Map<String, Object> node(String key) {
        return J.nodeOr(body.get(key));
    }

    public Map<String, Object> nodeOrEmpty(String key) {
        return J.nodeOrEmpty(body.get(key));
    }

    public List<Object> listArg(String key) {
        return J.listOr(body.get(key));
    }

    public List<Map<String, Object>> rowsArg(String key) {
        return J.rowsOr(body.get(key));
    }

    // ------------------------------------------------------------------
    // 玩家
    // ------------------------------------------------------------------

    /** 已认证玩家；未认证时抛 401。 */
    public PlayerContext requirePlayer() {
        if (player == null) {
            throw new WireError(401, "Valid local session required");
        }
        if (player.entity().banned) {
            String reason = player.entity().banReason;
            throw WireError.forbidden("このアカウントは利用停止中です。"
                    + (reason == null || reason.isEmpty() ? "" : " (" + reason + ")"));
        }
        return player;
    }
}
