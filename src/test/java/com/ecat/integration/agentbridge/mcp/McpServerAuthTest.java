package com.ecat.integration.agentbridge.mcp;

import com.ecat.integration.HttpServerIntegration.EasyHttpServer;
import com.ecat.integration.HttpServerIntegration.exchange.EasyHttpExchange;
import com.ecat.integration.HttpServerIntegration.exchange.EasyHeaders;
import com.ecat.integration.agentbridge.auth.AuthException;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link McpServer} 认证异常处理测试（回归 Bug #1）。
 *
 * <p>回归保护：当 {@link McpAuthenticator#authenticate} 抛出 {@link AuthException}（如无 token 或
 * token 失效）时，handlePost / handleDelete <b>必须</b>在 handler 内捕获并写出对应 HTTP 状态码响应，
 * 绝不能让异常逃逸到 {@code EasyHttpServer.blocking()} 包装的工作线程。
 *
 * <p>根因：{@code EasyHttpServer.blocking()} 通过 {@code exchange.dispatch()} 在工作线程<b>异步</b>执行
 * handler，而 {@code handleRequest} 的 try/catch 运行在 IO 线程、在 dispatch 前就已返回，
 * 无法捕获工作线程抛出的异常。异常一旦逃逸，HTTP 响应永远不会被写出，
 * 客户端（MCP Agent）会一直等待直到 ReadTimeout（约 15s）挂起。
 *
 * <p>本测试通过同步执行 dispatch 模拟工作线程行为，验证异常被捕获且写出 401 响应。
 *
 * @author coffee
 */
public class McpServerAuthTest {

    private EasyHttpExchange mockExchange;
    private ByteArrayOutputStream responseBody;

    @Before
    public void setUp() throws Exception {
        mockExchange = mock(EasyHttpExchange.class);

        // 模拟 dispatch 同步执行（模拟工作线程 dispatch，使 blocking() 包装的 handler 在本线程内联运行）
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(mockExchange).dispatch(any(Runnable.class));
        doNothing().when(mockExchange).startBlocking();

        responseBody = new ByteArrayOutputStream();
        when(mockExchange.getResponseBody()).thenReturn(responseBody);
        when(mockExchange.getResponseHeaders()).thenReturn(new EasyHeaders());
        when(mockExchange.getRequestURI()).thenReturn(URI.create("/mcp"));
        when(mockExchange.getRequestHeaders()).thenReturn(new EasyHeaders());
    }

    /**
     * POST /mcp 无 token 时，authenticate 抛 AuthException(401)，
     * handlePost 应捕获并写出 401 响应，而非让异常逃逸导致客户端挂起。
     */
    @Test
    public void handlePost_authException_writes401NotHang() throws Exception {
        McpAuthenticator authenticator = mock(McpAuthenticator.class);
        when(authenticator.authenticate(any())).thenThrow(new AuthException(401, "No token provided"));

        McpRequestHandler handler = mock(McpRequestHandler.class);
        EasyHttpServer server = mock(EasyHttpServer.class);
        McpServer mcpServer = new McpServer(server, authenticator, "/mcp", handler);

        try {
            mcpServer.handlePost(mockExchange);
        } catch (AuthException e) {
            fail("handlePost should catch AuthException and write 401, but it propagated: " + e.getMessage());
        }

        // 断言：写出了 401 响应（而非挂起无响应）
        verify(mockExchange).sendResponseHeaders(eq(401), anyLong());
        String body = responseBody.toString("UTF-8");
        assertTrue("响应体应包含错误信息", body.contains("No token provided"));
    }

    /**
     * DELETE /mcp 无 token 时，authenticate 抛 AuthException(401)，
     * handleDelete 应捕获并写出 401 响应，而非让异常逃逸导致客户端挂起。
     */
    @Test
    public void handleDelete_authException_writes401NotHang() throws Exception {
        McpAuthenticator authenticator = mock(McpAuthenticator.class);
        when(authenticator.authenticate(any())).thenThrow(new AuthException(401, "No token provided"));

        McpRequestHandler handler = mock(McpRequestHandler.class);
        EasyHttpServer server = mock(EasyHttpServer.class);
        McpServer mcpServer = new McpServer(server, authenticator, "/mcp", handler);

        try {
            mcpServer.handleDelete(mockExchange);
        } catch (AuthException e) {
            fail("handleDelete should catch AuthException and write 401, but it propagated: " + e.getMessage());
        }

        verify(mockExchange).sendResponseHeaders(eq(401), anyLong());
        String body = responseBody.toString("UTF-8");
        assertTrue("响应体应包含错误信息", body.contains("No token provided"));
    }

    /**
     * token 无效时抛 AuthException(401)，应使用异常携带的状态码（401）写出响应。
     * 验证状态码透传正确（而非硬编码）。
     */
    @Test
    public void handlePost_usesStatusCodeFromAuthException() throws Exception {
        McpAuthenticator authenticator = mock(McpAuthenticator.class);
        when(authenticator.authenticate(any())).thenThrow(new AuthException(401, "Invalid or expired token"));

        McpRequestHandler handler = mock(McpRequestHandler.class);
        EasyHttpServer server = mock(EasyHttpServer.class);
        McpServer mcpServer = new McpServer(server, authenticator, "/mcp", handler);

        try {
            mcpServer.handlePost(mockExchange);
        } catch (AuthException e) {
            fail("handlePost should catch AuthException, but it propagated: " + e.getMessage());
        }

        verify(mockExchange).sendResponseHeaders(eq(401), anyLong());
    }
}
