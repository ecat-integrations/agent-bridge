package com.ecat.integration.agentbridge.subagent.impl;

import com.ecat.integration.agentbridge.subagent.ArgDescriptor;
import com.ecat.integration.agentbridge.subagent.CliParseResult;
import com.ecat.integration.agentbridge.subagent.CliParser;
import com.ecat.integration.agentbridge.subagent.ToolDescriptor;
import com.ecat.integration.agentbridge.tool.ToolExecutor;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link MediaSubAgent} 单元测试。验证 3 工具映射 media-api plugin 模式端点。
 *
 * @author coffee
 */
public class MediaSubAgentTest {

    @Test
    public void agentNameAndRequiredIntegration() {
        MediaSubAgent agent = new MediaSubAgent();
        assertEquals("media", agent.getAgentName());
        assertEquals("com.ecat:integration-media-api", agent.getRequiredIntegration());
    }

    @Test
    public void returnsThreeTools() {
        List<ToolDescriptor> tools = new MediaSubAgent().getTools();
        assertEquals(3, tools.size());
        assertEquals("snapshot", tools.get(0).getToolName());
        assertEquals("stream", tools.get(1).getToolName());
        assertEquals("stop-stream", tools.get(2).getToolName());
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
        for (ToolDescriptor t : new MediaSubAgent().getTools()) {
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
}
