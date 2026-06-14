package com.ecat.integration.agentbridge.mcp;

/**
 * MCP 请求处理器接口。
 *
 * <p>McpServer 在收到 JSON-RPC 请求后，通过此接口将请求分派给上层处理。
 * 上层实现负责方法路由、能力调度、工具执行等业务逻辑。
 *
 * @author coffee
 */
public interface McpRequestHandler {

    /**
     * 处理 JSON-RPC 请求。
     *
     * <p>实现类根据 request 中的 method 字段分派到具体的处理逻辑，
     * 返回 JSON-RPC 响应。如果请求是通知（无 id），实现类可以返回 null。
     *
     * @param request JSON-RPC 请求
     * @param session MCP 会话
     * @return JSON-RPC 响应，通知请求可返回 null
     * @throws McpException 处理过程中发生协议级错误时抛出
     */
    JsonRpcResponse handle(JsonRpcRequest request, McpSession session) throws McpException;
}
