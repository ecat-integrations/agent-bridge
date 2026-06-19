package com.ecat.integration.agentbridge.mcp;

import com.ecat.integration.HttpServerIntegration.exchange.EasyHttpExchange;

/**
 * MCP 认证器接口，定义 HTTP 请求的认证契约。
 * <p>
 * 实现类负责从 HTTP 请求中提取认证凭据并验证，校验失败时抛出 {@code AuthException}。
 * 此接口由 auth 包的 {@code AgentAuthManager} 实现。
 *
 * @author coffee
 */
public interface McpAuthenticator {

    /**
     * 对 HTTP 请求进行认证（校验 Bearer token）。
     *
     * <p>校验通过即正常返回；失败时抛出 {@code AuthException}（401），
     * 由调用方在 handler 内捕获并写出对应 HTTP 状态码响应（Bug #1）。
     *
     * @param exchange HTTP 交换对象
     * @throws Exception 认证失败时抛出 AuthException 或其他异常
     */
    void authenticate(EasyHttpExchange exchange) throws Exception;
}
