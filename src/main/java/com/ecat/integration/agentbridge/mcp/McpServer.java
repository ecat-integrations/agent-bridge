package com.ecat.integration.agentbridge.mcp;

import com.ecat.integration.HttpServerIntegration.EasyHttpServer;
import com.ecat.integration.HttpServerIntegration.exchange.EasyHttpExchange;
import com.ecat.integration.HttpServerIntegration.exchange.EasyHttpHandler;
import com.ecat.integration.HttpServerIntegration.handler.SseConnection;
import com.ecat.integration.HttpServerIntegration.handler.SseHandler;
import com.ecat.integration.agentbridge.auth.AuthException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Streamable HTTP 传输实现。
 *
 * <p>实现 MCP 协议的 Streamable HTTP 传输规范，在单个 HTTP endpoint 上处理三种请求：
 * <ul>
 *   <li>POST — 接收 JSON-RPC 请求，返回 JSON-RPC 响应</li>
 *   <li>GET  — 建立 SSE 长连接，服务端推送通知</li>
 *   <li>DELETE — 关闭指定会话</li>
 * </ul>
 *
 * <p>参照 LogController SSE 模式实现 GET 长连接和 keepalive 心跳。
 *
 * @author coffee
 */
public class McpServer {

    /** SSE keepalive 间隔（毫秒） */
    private static final long SSE_KEEPALIVE_INTERVAL_MS = 30000L;

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
     * @param authenticator  MCP 认证器（Agent 3B 的 AgentAuthManager 实现此接口）
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
     * 启动 MCP 服务器，注册 POST / GET / DELETE 路由。
     * GET 路由使用原生 SseHandler，Undertow 自动管理 SSE 连接和 keepalive。
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
        // SSE GET 路由已移除（doc 05/06 砍 SSE 推送决策）：原 GET SseHandler 与 POST 同 path，
        // 砍 SSE 后 agent-bridge 不需 server→client push channel。POST/DELETE 正常注册。
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

    /**
     * 向所有活跃会话推送通知。
     *
     * @param notification JSON-RPC 通知
     */
    public void sendNotification(JsonRpcNotification notification) {
        String sseData = notification.toSseData();
        List<String> closedSessions = new ArrayList<String>();
        for (Map.Entry<String, McpSession> entry : sessions.entrySet()) {
            McpSession session = entry.getValue();
            if (session.isActive()) {
                try {
                    session.sendSseEvent(sseData);
                } catch (IOException e) {
                    // SSE 流已关闭，标记清理
                    closedSessions.add(entry.getKey());
                }
            } else {
                closedSessions.add(entry.getKey());
            }
        }
        // 清理已关闭的会话
        for (String sessionId : closedSessions) {
            McpSession removed = sessions.remove(sessionId);
            if (removed != null) {
                removed.close();
            }
        }
    }

    /**
     * 向指定会话推送通知。
     *
     * @param sessionId    目标会话 ID
     * @param notification JSON-RPC 通知
     * @throws IOException 会话不存在或 SSE 流已关闭时抛出
     */
    public void sendNotification(String sessionId, JsonRpcNotification notification) throws IOException {
        McpSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IOException("Session not found: " + sessionId);
        }
        session.sendSseEvent(notification.toSseData());
    }

    /**
     * 获取活跃会话数量
     *
     * @return 活跃会话数
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }

    /**
     * 获取指定会话
     *
     * @param sessionId 会话 ID
     * @return 会话对象，不存在时返回 null
     */
    public McpSession getSession(String sessionId) {
        return sessions.get(sessionId);
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
     * SSE 连接建立回调。由 Undertow ServerSentEventHandler 在新连接建立时调用。
     *
     * <p>替代原 handleGet 的 Thread.sleep + OutputStream 模式。
     * Undertow 自动管理 keepalive（setKeepAliveTime），无需手动发送心跳。
     *
     * @param conn        SSE 连接（线程安全，可从任意线程调用 send）
     * @param lastEventId 客户端 Last-Event-ID 头（断线重连支持）
     */
    private void onSseConnect(SseConnection conn, String lastEventId) {
        // 认证：从 SseConnection 提取 token
        String token = extractTokenFromSseConnection(conn);
        try {
            authenticator.authenticateByToken(token);
        } catch (Exception e) {
            try { conn.close(); } catch (IOException ignored) {}
            return;
        }

        // 提取 sessionId
        String sessionId = extractSessionIdFromSseConnection(conn);
        if (sessionId == null || sessionId.isEmpty()) {
            try { conn.close(); } catch (IOException ignored) {}
            return;
        }

        McpSession session = sessions.get(sessionId);
        if (session == null) {
            try { conn.close(); } catch (IOException ignored) {}
            return;
        }

        // 设置 keepalive — Undertow 自动发送 ":" 注释帧
        conn.setKeepAliveTime(SSE_KEEPALIVE_INTERVAL_MS);

        // 注册 SSE 连接到会话
        session.setSseConnection(conn);

        // 注册关闭回调：清理会话中的 SSE 连接引用
        conn.onClose(() -> session.setSseConnection(null));
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
     * 标识会话，也可以通过 ?token= query param 传递（SSE 场景浏览器无法设置 header）。
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
     * 从 SseConnection 提取会话 ID（用于 SSE 场景）。
     * 逻辑与 extractSessionId(EasyHttpExchange) 一致，数据来源为 SseConnection。
     */
    private String extractSessionIdFromSseConnection(SseConnection conn) {
        // 1. 从 Mcp-Session-Id header 获取
        String sessionId = conn.getRequestHeaders().getFirst("Mcp-Session-Id");
        if (sessionId != null && !sessionId.isEmpty()) {
            return sessionId;
        }
        // 2. 从 ?token= query param 获取
        Deque<String> tokens = conn.getQueryParameters().get("token");
        if (tokens != null && !tokens.isEmpty()) {
            return tokens.getFirst();
        }
        return null;
    }

    /**
     * 从 SseConnection 提取认证 token（用于 SSE 场景）。
     * 优先从 Authorization header 获取，回退到 ?token= query param。
     */
    private String extractTokenFromSseConnection(SseConnection conn) {
        // 1. 从 Authorization header 获取
        String authHeader = conn.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }
        // 2. 从 ?token= query param 获取
        Deque<String> tokens = conn.getQueryParameters().get("token");
        if (tokens != null && !tokens.isEmpty()) {
            return tokens.getFirst();
        }
        return null;
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
