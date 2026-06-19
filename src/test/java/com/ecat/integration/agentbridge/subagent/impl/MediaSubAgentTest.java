package com.ecat.integration.agentbridge.subagent.impl;

import com.ecat.integration.EcatCoreApiIntegration.Auth.AuthManager;
import com.ecat.integration.agentbridge.mcp.McpException;
import com.ecat.integration.agentbridge.subagent.ArgDescriptor;
import com.ecat.integration.agentbridge.subagent.CliParseResult;
import com.ecat.integration.agentbridge.subagent.CliParser;
import com.ecat.integration.agentbridge.subagent.ToolDescriptor;
import com.ecat.integration.agentbridge.tool.MediaUrlSigner;
import com.ecat.integration.agentbridge.tool.ToolExecutor;
import com.ecat.integration.agentbridge.tool.ToolResult;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MediaSubAgent} 单元测试。验证 6 个 HTTP 工具映射 media-api plugin 模式端点
 * （snapshot / stream / stop-stream / stream-info / record / record-info）
 * + 1 个进程内工具 get-download-url（Plan A 下载机制）。
 *
 * @author coffee
 */
public class MediaSubAgentTest {

    private static final String BASE_URL = "http://127.0.0.1:9999";
    private static final String URI =
            "ecat-media://com.ecat:integration-media-test-client/snapshots/test.jpg";
    private static final String SIGNED_PATH = "/core-api/media/stream?uri=x&exp=1&sig=y";

    /** 构造一个 MediaSubAgent，其 MediaUrlSigner 背靠 stub AuthManager（signMediaUrl→SIGNED_PATH）。 */
    private MediaSubAgent newAgent() {
        AuthManager am = mock(AuthManager.class);
        when(am.signMediaUrl(URI)).thenReturn(SIGNED_PATH);
        return new MediaSubAgent(new MediaUrlSigner(am));
    }

    @Test
    public void constructor_nullSigner_throws() {
        try {
            new MediaSubAgent(null);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void agentNameAndRequiredIntegration() {
        MediaSubAgent agent = newAgent();
        assertEquals("media", agent.getAgentName());
        assertEquals("com.ecat:integration-media-api", agent.getRequiredIntegration());
    }

    @Test
    public void returnsSevenTools() {
        List<ToolDescriptor> tools = newAgent().getTools();
        assertEquals(7, tools.size());
        assertEquals("snapshot", tools.get(0).getToolName());
        assertEquals("stream", tools.get(1).getToolName());
        assertEquals("stop-stream", tools.get(2).getToolName());
        assertEquals("stream-info", tools.get(3).getToolName());
        assertEquals("record", tools.get(4).getToolName());
        assertEquals("record-info", tools.get(5).getToolName());
        assertEquals("get-download-url", tools.get(6).getToolName());
    }

    @Test
    public void snapshotUsesPluginPathOnCoreApi() {
        ToolDescriptor snapshot = findTool("snapshot");
        assertEquals("POST", snapshot.getHttpMethod());
        // plugin 模式挂 core-api 9999（非 standalone 9931），basePath /plugins/...
        assertEquals("/plugins/com.ecat/integration-media-api/media/snapshot", snapshot.getHttpPath());
    }

    @Test
    public void streamEndpoints() {
        assertEquals("/plugins/com.ecat/integration-media-api/media/stream/start",
                findTool("stream").getHttpPath());
        assertEquals("/plugins/com.ecat/integration-media-api/media/stream/stop",
                findTool("stop-stream").getHttpPath());
    }

    private ToolDescriptor findTool(String name) {
        for (ToolDescriptor t : newAgent().getTools()) {
            if (name.equals(t.getToolName())) return t;
        }
        throw new AssertionError("tool not found: " + name);
    }

    /**
     * 回归 Bug #5：snapshot 参数名必须为 {@code cameraDeviceId}。
     *
     * <p>media-api {@code MediaController} 从 JSON body 读取 {@code cameraDeviceId}
     * （MediaController.java:440），而非 {@code deviceId}。{@link CliParser} 用 arg name 作
     * body key，故 arg name 必须与 media-api 契约一致，否则 body 发送 {@code {deviceId:...}}
     * 被 media-api 拒绝 "cameraDeviceId 为必填参数"。CLI flag {@code --device-id} 不变。
     */
    @Test
    public void snapshotArgNameMatchesMediaApiContract() {
        ArgDescriptor arg = findTool("snapshot").getArgs().get(0);
        assertEquals("CLI flag 应保持 --device-id（用户友好）", "--device-id", arg.getFlag());
        assertEquals("arg name（body key）必须 == media-api 契约 cameraDeviceId",
                "cameraDeviceId", arg.getName());
    }

    /**
     * 回归 Bug #5（端到端参数链）：{@code media snapshot --device-id cam-01} 经 CliParser 解析后，
     * body key 必须是 {@code cameraDeviceId}（media-api 契约），而非 {@code deviceId}。
     */
    @Test
    public void snapshotParsesDeviceIdFlagToCameraDeviceIdBodyKey() throws Exception {
        ToolDescriptor snapshot = findTool("snapshot");
        CliParser parser = new CliParser();
        CliParseResult result = parser.parse("media snapshot --device-id cam-01", snapshot.getArgs());
        Map<String, Object> params = result.getParams();
        assertTrue("CliParser 应产出 body key cameraDeviceId，实际 keys=" + params.keySet(),
                params.containsKey("cameraDeviceId"));
        assertEquals("cam-01", params.get("cameraDeviceId"));

        // 进一步验证 buildRequest 产出的 POST body 含 cameraDeviceId key
        ToolExecutor.Request req = ToolExecutor.buildRequest(snapshot, params, "http://127.0.0.1:9999");
        assertTrue("POST body 必须含 cameraDeviceId key，实际 body=" + req.getBody(),
                req.getBody() != null && req.getBody().contains("cameraDeviceId"));
    }

    /**
     * 回归 Bug #5：stream / stop-stream 同样使用 cameraDeviceId body key。
     */
    @Test
    public void streamAndStopStreamArgNamesMatchMediaApiContract() {
        assertEquals("cameraDeviceId", findTool("stream").getArgs().get(0).getName());
        assertEquals("cameraDeviceId", findTool("stop-stream").getArgs().get(0).getName());
    }

    // ───────────────────────── 新增 3 工具（stream-info / record / record-info）─────────────────────────

    /**
     * stream-info：GET 方法，路径 /media/stream/info，cameraDeviceId 必填。
     * 非pathParam 参数经 ToolExecutor 自动拼为 query string（非 body）。
     */
    @Test
    public void streamInfoToolContract() {
        ToolDescriptor t = findTool("stream-info");
        assertEquals("GET", t.getHttpMethod());
        assertEquals("/plugins/com.ecat/integration-media-api/media/stream/info", t.getHttpPath());
        ArgDescriptor arg = t.getArgs().get(0);
        assertEquals("cameraDeviceId", arg.getName());
        assertEquals("--device-id", arg.getFlag());
        assertTrue("cameraDeviceId 必填", arg.isRequired());
    }

    /**
     * stream-info 经 CliParser + buildRequest 后，参数应落在 query string 而非 body（GET 语义）。
     */
    @Test
    public void streamInfoParsesToQueryString() throws Exception {
        ToolDescriptor t = findTool("stream-info");
        CliParser parser = new CliParser();
        CliParseResult result = parser.parse("media stream-info --device-id cam-01", t.getArgs());
        ToolExecutor.Request req = ToolExecutor.buildRequest(t, result.getParams(), "http://127.0.0.1:9999");
        // GET 工具：cameraDeviceId 应出现在 URL query string，body 应为空
        assertTrue("GET URL 应含 ?cameraDeviceId=，实际 url=" + req.getUrl(),
                req.getUrl().contains("cameraDeviceId=cam-01"));
        assertNull("GET 工具不应有 body，实际 body=" + req.getBody(), req.getBody());
    }

    /**
     * record：POST /media/recording/start。
     * - cameraDeviceId 必填（body key 对齐契约）
     * - durationSeconds 必填 + INTEGER 类型（严格模式，源头防 agent 忘传致无限录制）
     * - preEventSeconds 可选 + INTEGER
     */
    @Test
    public void recordToolContract() {
        ToolDescriptor t = findTool("record");
        assertEquals("POST", t.getHttpMethod());
        assertEquals("/plugins/com.ecat/integration-media-api/media/recording/start", t.getHttpPath());

        Map<String, ArgDescriptor> byName = new HashMap<>();
        for (ArgDescriptor a : t.getArgs()) {
            byName.put(a.getName(), a);
        }
        assertTrue("必须含 cameraDeviceId", byName.containsKey("cameraDeviceId"));
        assertTrue("必须含 durationSeconds", byName.containsKey("durationSeconds"));
        assertTrue("必须含 preEventSeconds", byName.containsKey("preEventSeconds"));
        assertTrue("必须含 filename", byName.containsKey("filename"));

        // durationSeconds：必填 + INTEGER（严格模式）
        ArgDescriptor dur = byName.get("durationSeconds");
        assertTrue("durationSeconds 必填", dur.isRequired());
        assertEquals("durationSeconds 必须为 INTEGER（防 agent 传字符串导致底层非预期）",
                ArgDescriptor.Type.INTEGER, dur.getType());

        // preEventSeconds：可选（默认 0）+ INTEGER
        ArgDescriptor pre = byName.get("preEventSeconds");
        assertFalse("preEventSeconds 应可选（默认 0 纯定时录制）", pre.isRequired());
        assertEquals(ArgDescriptor.Type.INTEGER, pre.getType());

        // cameraDeviceId：必填
        assertTrue("cameraDeviceId 必填", byName.get("cameraDeviceId").isRequired());
        // filename：可选
        assertFalse("filename 应可选", byName.get("filename").isRequired());
    }

    /**
     * record 端到端参数链：CLI flag → JSON body key（对齐 media-api 契约）。
     * {@code media record --device-id cam-01 --duration 10} → body 含 cameraDeviceId + durationSeconds。
     */
    @Test
    public void recordParsesFlagsToBodyKeys() throws Exception {
        ToolDescriptor t = findTool("record");
        CliParser parser = new CliParser();
        CliParseResult result = parser.parse(
                "media record --device-id cam-01 --duration 10", t.getArgs());
        Map<String, Object> params = result.getParams();
        assertEquals("cam-01", params.get("cameraDeviceId"));
        // INTEGER 类型经 CliParser 应被强转为 Integer（非字符串 "10"）
        assertEquals(Integer.valueOf(10), params.get("durationSeconds"));

        ToolExecutor.Request req = ToolExecutor.buildRequest(t, params, "http://127.0.0.1:9999");
        assertTrue("POST body 必须含 cameraDeviceId，实际 body=" + req.getBody(),
                req.getBody() != null && req.getBody().contains("cameraDeviceId"));
        assertTrue("POST body 必须含 durationSeconds，实际 body=" + req.getBody(),
                req.getBody() != null && req.getBody().contains("durationSeconds"));
    }

    /**
     * record 缺 durationSeconds（必填）时，CliParser 严格模式应抛 McpException(INVALID_PARAMS)，
     * 不静默兜底（CLAUDE.md 严格模式）。
     */
    @Test(expected = McpException.class)
    public void recordRequiresDurationSeconds() throws Exception {
        ToolDescriptor t = findTool("record");
        CliParser parser = new CliParser();
        // 只给 device-id，缺必填 duration → 解析应抛异常
        parser.parse("media record --device-id cam-01", t.getArgs());
    }

    /**
     * record-info：GET /media/recording/info，sessionId 必填（query string）。
     */
    @Test
    public void recordInfoToolContract() {
        ToolDescriptor t = findTool("record-info");
        assertEquals("GET", t.getHttpMethod());
        assertEquals("/plugins/com.ecat/integration-media-api/media/recording/info", t.getHttpPath());
        ArgDescriptor arg = t.getArgs().get(0);
        assertEquals("sessionId", arg.getName());
        assertEquals("--id", arg.getFlag());
        assertTrue("sessionId 必填", arg.isRequired());
    }

    /**
     * record-info 端到端：{@code media record-info --id rec-abc} → query string ?sessionId=rec-abc，无 body。
     */
    @Test
    public void recordInfoParsesIdFlagToSessionIdQuery() throws Exception {
        ToolDescriptor t = findTool("record-info");
        CliParser parser = new CliParser();
        CliParseResult result = parser.parse("media record-info --id rec-abc", t.getArgs());
        Map<String, Object> params = result.getParams();
        // CLI flag --id → arg name sessionId（对齐 MediaController handleRecordingInfo query key）
        assertEquals("rec-abc", params.get("sessionId"));

        ToolExecutor.Request req = ToolExecutor.buildRequest(t, params, "http://127.0.0.1:9999");
        assertTrue("GET URL 应含 ?sessionId=rec-abc，实际 url=" + req.getUrl(),
                req.getUrl().contains("sessionId=rec-abc"));
        assertNull("GET 工具不应有 body，实际 body=" + req.getBody(), req.getBody());
    }

    // ───────────────────────── 进程内工具 get-download-url（Plan A 下载机制）─────────────────────────

    /**
     * get-download-url schema：进程内工具，无 HTTP 映射（httpMethod/httpPath 为 null），
     * uri 必填，flag --uri。
     */
    @Test
    public void getDownloadUrlToolContract() {
        ToolDescriptor t = findTool("get-download-url");
        assertNull("进程内工具不应设 httpMethod，实际=" + t.getHttpMethod(), t.getHttpMethod());
        assertNull("进程内工具不应设 httpPath，实际=" + t.getHttpPath(), t.getHttpPath());
        assertEquals(1, t.getArgs().size());
        ArgDescriptor arg = t.getArgs().get(0);
        assertEquals("uri", arg.getName());
        assertEquals("--uri", arg.getFlag());
        assertTrue("uri 必填", arg.isRequired());
    }

    /**
     * get-download-url 进程内执行（核心 Plan A）：输入 URI，走 execute override（不经 HTTP），
     * 用请求 Host 拼 host:port、baseUrl 取 scheme，返回完整无 token 下载 URL。
     * requestHostPort=null 时回退 baseUrl 的 host:port。
     */
    @Test
    public void getDownloadUrlExecute_buildsFullDownloadUrl() throws Exception {
        MediaSubAgent agent = newAgent();
        ToolDescriptor tool = findTool("get-download-url");
        Map<String, Object> params = new HashMap<>();
        params.put("uri", URI);

        // requestHostPort 缺失 → 回退 baseUrl host:port
        ToolResult r = agent.execute(tool, params, null, BASE_URL, null);
        assertFalse("不应是错误结果", r.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> content = (Map<String, Object>) r.getContent();
        assertEquals(URI, content.get("uri"));
        assertEquals("http://127.0.0.1:9999" + SIGNED_PATH, content.get("downloadUrl"));
        assertEquals(300, content.get("expiresInSeconds"));
    }

    /**
     * get-download-url 进程内执行：requestHostPort 优先于 baseUrl 的 host:port（支持远程 agent）。
     */
    @Test
    public void getDownloadUrlExecute_requestHostOverridesBaseUrl() throws Exception {
        MediaSubAgent agent = newAgent();
        ToolDescriptor tool = findTool("get-download-url");
        Map<String, Object> params = new HashMap<>();
        params.put("uri", URI);

        ToolResult r = agent.execute(tool, params, null, BASE_URL, "core.example.com:8080");
        assertFalse(r.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> content = (Map<String, Object>) r.getContent();
        assertEquals("http://core.example.com:8080" + SIGNED_PATH, content.get("downloadUrl"));
    }
}
