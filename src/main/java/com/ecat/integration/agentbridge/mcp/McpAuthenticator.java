package com.ecat.integration.agentbridge.mcp;

import com.ecat.integration.HttpServerIntegration.exchange.EasyHttpExchange;

/**
 * MCP 认证器接口，定义 HTTP 请求的认证契约。
 * <p>
 * 实现类负责从 HTTP 请求中提取认证凭据并验证，返回认证后的身份对象。
 * 此接口由 auth 包的 AgentAuthManager 实现。
 *
 * @author coffee
 */
public interface McpAuthenticator {

    /**
     * 对 HTTP 请求进行认证。
     *
     * @param exchange HTTP 交换对象
     * @return 认证后的身份对象（如 AgentIdentity），认证失败时抛出异常
     * @throws Exception 认证失败时抛出 AuthException 或其他异常
     */
    Object authenticate(EasyHttpExchange exchange) throws Exception;

    /**
     * 通过 Token 字符串认证（用于 SSE 场景）。
     * SSE 使用 SseConnection 而非 EasyHttpExchange，通过此方法完成认证。
     *
     * <p>默认实现抛出 UnsupportedOperationException，SSE 场景需要实现类覆写此方法。
     *
     * @param token 认证 token 字符串
     * @return 认证后的身份对象
     * @throws Exception 认证失败或不支持时抛出
     */
    default Object authenticateByToken(String token) throws Exception {
        throw new UnsupportedOperationException("authenticateByToken not supported");
    }
}
