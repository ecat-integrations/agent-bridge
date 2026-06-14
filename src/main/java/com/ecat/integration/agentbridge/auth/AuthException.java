package com.ecat.integration.agentbridge.auth;

/**
 * 认证异常，用于表示 Agent 认证过程中的错误。
 * <p>
 * 携带 HTTP 状态码，便于 HTTP 层返回正确的响应状态。
 * 常见状态码：
 * <ul>
 *   <li>401 — 未提供 token 或 token 无效/已过期</li>
 *   <li>403 — 权限不足</li>
 * </ul>
 *
 * @author coffee
 */
public class AuthException extends RuntimeException {

    /** HTTP 状态码 */
    private final int statusCode;

    /**
     * 构造认证异常。
     *
     * @param statusCode HTTP 状态码（如 401、403）
     * @param message    错误描述信息
     */
    public AuthException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    /**
     * 获取 HTTP 状态码。
     *
     * @return 状态码
     */
    public int getStatusCode() {
        return statusCode;
    }
}
