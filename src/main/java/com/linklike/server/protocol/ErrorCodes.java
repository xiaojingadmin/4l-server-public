package com.linklike.server.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 官方 SchoolException 的扁平错误体。
 *
 * <p>{@code error_code} 沿用官方 "4位reason_6位location" 形状。401 使用抓包验证过的
 * 官方无账号码 {@code 00300_101709}；其余为本地占位符，只保证形状一致供客户端对话框显示。
 *
 * <p>客户端按 reason（前 5 位）决定弹窗后的动作：{@code 00400} 走「アカウント停止」并返回标题，
 * 未登记的 reason 也返回标题；{@code 00500} 只弹出 message 并留在当前画面。
 * 普通校验类错误因此统一挂在 {@code 00500} 下，location 段保留原 HTTP 状态便于排查。
 */
public final class ErrorCodes {

    private ErrorCodes() {
    }

    private static final Map<Integer, String> DEFAULTS = Map.of(
            400, "00500_10400",
            401, "00300_101709",
            403, "00403_10000",
            404, "00500_10404",
            405, "00500_10405",
            406, "00500_10406",
            409, "00500_10409",
            500, "00500_10000",
            501, "00500_10501");

    public static String defaultCode(int status) {
        return DEFAULTS.getOrDefault(status, "00000_00000");
    }

    public static Map<String, Object> payload(int status, String message, String errorCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error_code", errorCode != null ? errorCode : defaultCode(status));
        body.put("message", message == null ? "" : message);
        body.put("title", "エラー");
        return body;
    }
}
