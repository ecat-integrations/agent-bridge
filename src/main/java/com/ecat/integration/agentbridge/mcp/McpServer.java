package com.ecat.integration.agentbridge.mcp;

import com.ecat.integration.HttpServerIntegration.EasyHttpServer;
import com.ecat.integration.HttpServerIntegration.exchange.EasyHttpExchange;
import com.ecat.integration.HttpServerIntegration.exchange.EasyHttpHandler;
import com.ecat.integration.agentbridge.auth.AuthException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Streamable HTTP 传输实现。
 *
 * <p>实现 MCP 协议的 Streamable HTTP 传输规范，在单个 HTTP endpoint 上处理两种请求：
 * <ul>
 *   <li>POST — 接收 JSON-RPC 请求，返回 JSON-RPC 响应</li>
 *   <li>DELETE — 关闭指定会话</li>
 * </ul>
 *
 * <p>BCP 重构已移除 server→client 推送通道（原 SSE GET 长连接）：
 * 当前仅暴露 {@code tools} 通道，Agent 用 {@code getTools}/{@code useTools} 主动拉取能力（doc 05 §1.4）。
 *
 * @author coffee
 */
public class McpServer {

    private final EasyHttpServer httpServer;
    private final McpAuthenticator authenticator;
    private final String endpoint;
    private final McpRequestHandler requestHandler;

    /** 活跃会话：sessionId -> McpSession */
    private final ConcurrentHashMap<String, McpSession> sessions = new ConcurrentHashMap<String, McpSession>();

    /** 服务是否运行中 */
    private volatile boolean running = false;

    /**
     * 构造器
     *
     * @param httpServer     HTTP 服务器实例
     * @param authenticator  MCP 认证器（AgentAuthManager 实现此接口）
     * @param endpoint       MCP endpoint 路径，如 "/mcp"
     * @param requestHandler MCP 请求处理器
     */
    public McpServer(EasyHttpServer httpServer, McpAuthenticator authenticator,
                     String endpoint, McpRequestHandler requestHandler) {
        if (httpServer == null) {
            throw new IllegalArgumentException("httpServer must not be null");
        }
        if (authenticator == null) {
            throw new IllegalArgumentException("authenticator must not be null");
        }
        if (endpoint == null || endpoint.isEmpty()) {
            throw new IllegalArgumentException("endpoint must not be null or empty");
        }
        if (requestHandler == null) {
            throw new IllegalArgumentException("requestHandler must not be null");
        }
        this.httpServer = httpServer;
        this.authenticator = authenticator;
        this.endpoint = endpoint;
        this.requestHandler = requestHandler;
    }

    /**
     * 启动 MCP 服务器，注册 POST / DELETE 路由。
     *
     * <p>SSE GET 路由已在 BCP 重构移除（doc 05/06 砍 SSE 推送决策）：
     * agent-bridge 不需 server→client push channel，仅靠 POST/DELETE 即可。
     */
    public void start() {
        if (running) {
            return;
        }
        httpServer.registerUrl(endpoint, "POST", EasyHttpServer.blocking(new EasyHttpHandler() {
            @Override
            public void handle(EasyHttpExchange exchange) throws Exception {
                handlePost(exchange);
            }
        }));
        httpServer.registerUrl(endpoint, "DELETE", EasyHttpServer.blocking(new EasyHttpHandler() {
            @Override
            public void handle(EasyHttpExchange exchange) throws Exception {
                handleDelete(exchange);
            }
        }));
        running = true;
    }

    /**
     * 停止 MCP 服务器，注销路由并关闭所有会话。
     */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        httpServer.unregisterUrl(endpoint, "POST");
        httpServer.unregisterUrl(endpoint, "DELETE");

        // 关闭所有会话
        for (McpSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
    }

    // ===== HTTP 方法处理器 =====

    /**
     * 处理 POST 请求 — 接收 JSON-RPC 请求。
     *
     * <p>流程：
     * <ol>
     *   <li>Token 认证</li>
     *   <li>读取请求体</li>
     *   <li>解析 JsonRpcRequest</li>
     *   <li>获取或创建 McpSession（从 Mcp-Session-Id header 提取）</li>
     *   <li>调用 requestHandler.handle(request, session)</li>
     *   <li>设置 Mcp-Session-Id 响应头，返回 JSON 响应</li>
     * </ol>
     */
    void handlePost(EasyHttpExchange exchange) throws Exception {
        // 1. 认证 — 必须在此捕获 AuthException 并写出 HTTP 错误响应。
        //    blocking() 通过 dispatch() 异步在工作线程执行 handler，handleRequest 的 try/catch
        //    在 IO 线程、dispatch 前返回，无法捕获工作线程抛出的异常；一旦逃逸则无 HTTP 响应，
        //    客户端会 ReadTimeout 挂起（Bug #1）。
        try {
            authenticator.authenticate(exchange);
        } catch (AuthException e) {
            sendAuthErrorResponse(exchange, e);
            return;
        }

        // 2. 读取请求体
        String jsonBody = readRequestBody(exchange);

        // 3. 解析 JSON-RPC 请求
        JsonRpcRequest request;
        try {
            request = JsonRpcRequest.parse(jsonBody);
        } catch (McpException e) {
            sendJsonResponse(exchange, 200, e.toResponse(null).toJson());
            return;
        }

        // 4. 获取或创建会话
        String sessionId = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
        McpSession session;
        if (sessionId != null && !sessionId.isEmpty()) {
            session = sessions.get(sessionId);
            if (session == null) {
                sendJsonResponse(exchange, 200, JsonRpcResponse.error(
                        request.getId(), JsonRpcResponse.SERVER_NOT_INITIALIZED,
                        "Session not found: " + sessionId).toJson());
                return;
            }
        } else {
            // 新会话
            session = new McpSession();
            sessions.put(session.getId(), session);
            sessionId = session.getId();
        }

        // 刷新当前请求 Host（每次 POST 都设，保证会话复用时也取最新 host），
        // 供进程内工具（media get-download-url）拼装对 agent 可达的下载 URL。无 Host 头时为 null。
        session.setRequestHostPort(extractHostPort(exchange));

        // 5. 调用请求处理器
        JsonRpcResponse response;
        try {
            response = requestHandler.handle(request, session);
        } catch (McpException e) {
            response = e.toResponse(request.getId());
        } catch (Exception e) {
            // 兜底：将未预期的异常转为 Internal Error 响应，防止请求挂起
            response = JsonRpcResponse.error(request.getId(),
                    JsonRpcResponse.INTERNAL_ERROR, "Internal error: " + e.getMessage());
        }

        // 6. 返回响应
        exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);
        if (response != null) {
            sendJsonResponse(exchange, 200, response.toJson());
        } else {
            // 通知消息不需要响应体
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(202, -1L);
            exchange.close();
        }
    }

    /**
     * 处理 DELETE 请求 — 关闭指定会话。
     */
    void handleDelete(EasyHttpExchange exchange) throws Exception {
        // 认证 — 同 handlePost，必须捕获 AuthException 写出 HTTP 错误响应（Bug #1）
        try {
            authenticator.authenticate(exchange);
        } catch (AuthException e) {
            sendAuthErrorResponse(exchange, e);
            return;
        }

        // 提取 sessionId
        String sessionId = extractSessionId(exchange);
        if (sessionId == null || sessionId.isEmpty()) {
            sendJsonResponse(exchange, 400, JsonRpcResponse.error(
                    null, JsonRpcResponse.INVALID_REQUEST,
                    "Mcp-Session-Id header or token query param is required").toJson());
            return;
        }

        McpSession removed = sessions.remove(sessionId);
        if (removed != null) {
            removed.close();
        }

        // 200 OK，无响应体
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, -1L);
        exchange.close();
    }

    // ===== 内部辅助方法 =====

    /**
     * 读取请求体为 UTF-8 字符串
     */
    private String readRequestBody(EasyHttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        byte[] buffer = new byte[4096];
        StringBuilder sb = new StringBuilder();
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
            sb.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * 从 Mcp-Session-Id header 或 ?token= query param 提取会话 ID。
     *
     * <p>MCP Streamable HTTP 规范：GET 和 DELETE 通过 Mcp-Session-Id header
     * 标识会话，也可以通过 ?token= query param 传递（浏览器/无 header 客户端场景）。
     */
    private String extractSessionId(EasyHttpExchange exchange) {
        // 优先从 header 获取
        String sessionId = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
        if (sessionId != null && !sessionId.isEmpty()) {
            return sessionId;
        }
        // 从 query param 获取
        String query = exchange.getRequestURI().getQuery();
        if (query != null && !query.isEmpty()) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2 && "token".equals(kv[0])) {
                    return kv[1];
                }
            }
        }
        return null;
    }

    /**
     * 提取 MCP 请求 Host header 的 host:port，供进程内工具拼装对 agent 可达的下载 URL。
     *
     * <p>Host header 形如 {@code 127.0.0.1:9999} / {@code core.example.com:8080}，直接取整段作为
     * host:port（HTTP/1.1 Host 头本就是 host[:port]）。无 Host 头返回 null（进程内工具回退 baseUrl）。
     */
    private String extractHostPort(EasyHttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        return (host == null || host.isEmpty()) ? null : host;
    }

    /**
     * 发送认证错误响应。
     *
     * <p>在 handler 内部捕获 {@link AuthException} 时调用，使用异常携带的 HTTP 状态码（401/403）
     * 写出 JSON 错误响应，确保客户端收到明确错误而非因无响应挂起（Bug #1 修复）。
     *
     * <p>message 来自 AuthException 构造（AgentAuthManager 中受控字符串，无 JSON 特殊字符），直接嵌入。
     *
     * @param exchange HTTP 交换对象
     * @param e        认证异常
     */
    private void sendAuthErrorResponse(EasyHttpExchange exchange, AuthException e) throws IOException {
        int code = e.getStatusCode();
        String json = "{\"code\":" + code + ",\"error\":\"" + e.getMessage() + "\"}";
        sendJsonResponse(exchange, code, json);
    }

    /**
     * 发送 JSON 响应
     */
    private void sendJsonResponse(EasyHttpExchange exchange, int statusCode, String jsonBody) throws IOException {
        byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
        exchange.close();
    }
}
