package com.ecat.integration.agentbridge.tool;

import com.alibaba.fastjson2.JSON;
import com.ecat.integration.agentbridge.subagent.ArgDescriptor;
import com.ecat.integration.agentbridge.subagent.ToolDescriptor;

import java.io.BufferedReader;
import java.io.UnsupportedEncodingException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具执行引擎，通过 HTTP 自调用 ecat-core-api 执行 MCP 工具。
 *
 * <p>执行流程：
 * <ol>
 *   <li>根据 {@link ToolDescriptor} 的 httpMethod/httpPath 构造 HTTP 请求</li>
 *   <li>设置内部认证 Token 和异步模式请求头</li>
 *   <li>发送请求到 localhost:9999</li>
 *   <li>解析响应并返回 {@link ToolResult}</li>
 * </ol>
 *
 * <p>重试策略：仅对 5xx 错误且幂等操作（GET/PUT）进行重试，最多 3 次。
 *
 * @author coffee
 */
public class ToolExecutor {

    /** 连接超时（毫秒） */
    private static final int CONNECT_TIMEOUT = 30_000;

    /** 读取超时（毫秒） */
    private static final int READ_TIMEOUT = 60_000;

    /** 最大重试次数 */
    private static final int MAX_RETRIES = 3;

    /** 启用异步模式的 Accept 头 */
    private static final String ACCEPT_ASYNC = "application/vnd.ecat.async+json";

    /** 内部 Token 提供者 */
    private final InternalTokenProvider tokenProvider;

    /**
     * 内部 Token 提供者接口。
     *
     * <p>用于获取 ecat 内部认证 Token，使 Agent Bridge 可以
     * 以内部服务身份调用 ecat-core-api 接口。
     */
    public interface InternalTokenProvider {

        /**
         * 获取内部认证 Token。
         *
         * @return Bearer Token 字符串
         */
        String getInternalToken();
    }

    /**
     * 构造器。
     *
     * @param tokenProvider 内部 Token 提供者，不能为 null
     */
    public ToolExecutor(InternalTokenProvider tokenProvider) {
        if (tokenProvider == null) {
            throw new IllegalArgumentException("tokenProvider must not be null");
        }
        this.tokenProvider = tokenProvider;
    }

    /**
     * 执行 {@link ToolDescriptor} 工具调用（BCP/SubAgent 路径）。
     *
     * <p>由 {@link ToolDescriptor} 的 httpMethod/httpPath/args 经 {@link #buildRequest}
     * 构造请求（路径参数替换 + GET query + body 分发），再经重试发送。
     *
     * @param tool    工具定义（含 HTTP 映射）
     * @param params  解析后的参数
     * @param baseUrl ecat-core-api 基础 URL（如 http://127.0.0.1:9999）
     * @return 执行结果
     */
    public ToolResult execute(ToolDescriptor tool, Map<String, Object> params, String baseUrl) {
        if (tool == null) {
            throw new IllegalArgumentException("tool must not be null");
        }
        Request req = buildRequest(tool, params, baseUrl);
        return executeWithRetry(req.getUrl(), req.getMethod(), req.getBody());
    }

    /**
     * 带重试的 HTTP 执行：仅对 5xx 且幂等操作（GET/PUT）重试，最多 {@value #MAX_RETRIES} 次。
     *
     * @param url    完整 URL
     * @param method HTTP 方法
     * @param body   已构造的请求体（GET/无参数时为 null）
     * @return 执行结果
     */
    private ToolResult executeWithRetry(String url, String method, String body) {
        boolean canRetry = "GET".equals(method) || "PUT".equals(method);
        Exception lastException = null;
        int attempts = canRetry ? MAX_RETRIES : 1;

        for (int i = 0; i < attempts; i++) {
            try {
                ToolResult result = sendHttpRequest(url, method, body);
                // 5xx 且可重试
                if (result.getStatusCode() >= 500 && canRetry && i < attempts - 1) {
                    lastException = new RuntimeException(
                            "HTTP " + result.getStatusCode() + ": " + result.getContent());
                    continue;
                }
                return result;
            } catch (Exception e) {
                lastException = e;
                if (!canRetry || i >= attempts - 1) {
                    break;
                }
            }
        }

        return ToolResult.error("Execution failed after " + attempts + " attempts: "
                + (lastException != null ? lastException.getMessage() : "unknown error"));
    }

    /**
     * 发送单次 HTTP 请求（请求体由调用方构造）。
     *
     * @param url    完整 URL
     * @param method HTTP 方法
     * @param body   已构造的请求体（GET/无参数时为 null）
     * @return 执行结果
     * @throws Exception HTTP 请求异常
     */
    private ToolResult sendHttpRequest(String url, String method, String body) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoInput(true);

            // 设置认证头
            String token = tokenProvider.getInternalToken();
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }

            // 启用异步模式
            conn.setRequestProperty("Accept", ACCEPT_ASYNC);

            // 写入请求体（POST/PUT/DELETE 且 body 非空时）
            if (body != null && !body.isEmpty()) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(bodyBytes);
                    os.flush();
                } finally {
                    os.close();
                }
            }

            int statusCode = conn.getResponseCode();
            String response = readResponse(conn);

            // HTTP 202 Accepted — 异步操作
            if (statusCode == 202) {
                // 尝试从响应中提取 asyncExecutionId
                String asyncId = extractAsyncId(response);
                return ToolResult.async(asyncId, statusCode);
            }

            // 2xx 成功
            if (statusCode >= 200 && statusCode < 300) {
                Object content = parseResponseContent(response);
                return new ToolResult(content, false, null, statusCode);
            }

            // 4xx/5xx 错误
            return new ToolResult(response, true, null, statusCode);

        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 读取 HTTP 响应体。
     *
     * @param conn HTTP 连接
     * @return 响应体字符串
     * @throws Exception 读取异常
     */
    private String readResponse(HttpURLConnection conn) throws Exception {
        InputStream is = null;
        try {
            is = conn.getInputStream();
        } catch (Exception e) {
            is = conn.getErrorStream();
        }
        if (is == null) {
            return "";
        }
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } finally {
            is.close();
        }
    }

    /**
     * 从异步接受响应（HTTP 202）中提取 asyncExecutionId。
     *
     * <p>core-api 异步响应契约（{@code ControllerUtils.buildAsyncAcceptedResponse}）：
     * <pre>{"asyncExecutionId":"async-3","status":"...","estimatedTimeout":30,"createdAt":"..."}</pre>
     * 字段名为 {@code asyncExecutionId}（非 {@code executionId}）。
     *
     * <p>严格模式（CLAUDE.md）：字段缺失/响应非法属契约违反，抛 {@link IllegalStateException}
     * 明确报错，<b>不</b>静默返回整段响应——否则会生成畸形 taskId，污染下游
     * {@code event query-async-result} 链路（BUG-1）。
     *
     * <p>包级可见以支持单元测试（{@code ToolExecutorTest#extractAsyncIdReadsAsyncExecutionIdField} 等）。
     *
     * @param response HTTP 202 响应体
     * @return 异步任务 ID（如 "async-3"）
     * @throws IllegalStateException 响应为空 / 非合法 JSON / 缺少 asyncExecutionId / 值为空
     */
    String extractAsyncId(String response) {
        if (response == null || response.isEmpty()) {
            throw new IllegalStateException("异步接受响应为空，无法提取 asyncExecutionId");
        }
        com.alibaba.fastjson2.JSONObject json;
        try {
            json = JSON.parseObject(response);
        } catch (Exception e) {
            throw new IllegalStateException("异步接受响应非合法 JSON: " + response, e);
        }
        if (json == null || !json.containsKey("asyncExecutionId")) {
            throw new IllegalStateException(
                    "异步接受响应缺少 asyncExecutionId 字段（契约违反）: " + response);
        }
        String id = json.getString("asyncExecutionId");
        if (id == null || id.isEmpty()) {
            throw new IllegalStateException("异步接受响应 asyncExecutionId 为空: " + response);
        }
        return id;
    }

    /**
     * 尝试将响应内容解析为结构化对象。
     *
     * @param response HTTP 响应体字符串
     * @return 解析后的对象（JSONObject/JSONArray），解析失败时返回原始字符串
     */
    private Object parseResponseContent(String response) {
        if (response == null || response.isEmpty()) {
            return "";
        }
        try {
            return JSON.parse(response);
        } catch (Exception e) {
            return response;
        }
    }

    /**
     * 根据 {@link ToolDescriptor} 与参数构造 HTTP 请求（骨架，待 TDD 实现）。
     *
     * <p>路径参数（args 中 pathParam=true）替换 httpPath 的 {xxx} 占位符并从待发送参数移除；
     * GET 的剩余参数拼为 query string；POST/PUT/DELETE 的剩余参数序列化为 JSON body。
     *
     * @param tool    工具定义
     * @param params  解析后的参数
     * @param baseUrl ecat-core-api 基础 URL
     * @return 构造出的 HTTP 请求
     */
    public static Request buildRequest(ToolDescriptor tool, Map<String, Object> params, String baseUrl) {
        // 路径参数替换：pathParam 标记的参数替换 httpPath 的 {xxx}，并从待发送参数移除
        Map<String, Object> remaining = new LinkedHashMap<String, Object>(params);
        String path = tool.getHttpPath();
        for (ArgDescriptor arg : tool.getArgs()) {
            if (arg.isPathParam() && remaining.containsKey(arg.getName())) {
                path = path.replace("{" + arg.getName() + "}",
                        String.valueOf(remaining.remove(arg.getName())));
            }
        }

        String method = tool.getHttpMethod().toUpperCase();
        String url = baseUrl + path;
        String body = null;

        if ("GET".equals(method)) {
            // GET：剩余参数拼 query string（URL 编码）
            if (!remaining.isEmpty()) {
                url = url + "?" + buildQueryString(remaining);
            }
        } else {
            // POST/PUT/DELETE：剩余参数序列化为 JSON body
            if (!remaining.isEmpty()) {
                body = JSON.toJSONString(remaining);
            }
        }
        return new Request(url, method, body);
    }

    /**
     * 将参数表编码为 URL query string（key=value 用 & 连接，UTF-8 编码）。
     */
    private static String buildQueryString(Map<String, Object> params) {
        StringBuilder qs = new StringBuilder();
        try {
            for (Map.Entry<String, Object> e : params.entrySet()) {
                if (qs.length() > 0) {
                    qs.append("&");
                }
                qs.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                  .append("=")
                  .append(URLEncoder.encode(String.valueOf(e.getValue()), "UTF-8"));
            }
        } catch (UnsupportedEncodingException e) {
            // UTF-8 是 Java 规范保证支持的编码，不可达
            throw new IllegalStateException("UTF-8 not supported", e);
        }
        return qs.toString();
    }

    /**
     * 构造出的 HTTP 请求值对象（url / method / body）。
     */
    public static class Request {
        private final String url;
        private final String method;
        private final String body;

        public Request(String url, String method, String body) {
            this.url = url;
            this.method = method;
            this.body = body;
        }

        public String getUrl() { return url; }
        public String getMethod() { return method; }
        public String getBody() { return body; }
    }
}
