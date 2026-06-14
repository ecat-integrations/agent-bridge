package com.ecat.integration.agentbridge.subagent;

import com.ecat.integration.agentbridge.tool.ToolExecutor;
import com.ecat.integration.agentbridge.tool.ToolResult;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link ToolDispatcher} 单元测试。
 *
 * <p>验证 useTools 路由 + BCP 错误处理（未知 agent/tool 含可用列表提示）、
 * getTools CLI-first 返回（全部/指定/未知）。
 *
 * @author coffee
 */
public class ToolDispatcherTest {

    private static final String COORD = "com.ecat:integration-ecat-core-api";
    private static final String BASE_URL = "http://127.0.0.1:9999";

    @Test
    public void useToolsUnknownAgentReturnsErrorWithAvailableList() {
        ToolDispatcher dispatcher = newDispatcher(stubAgent("device", toolList("list")));
        ToolResult result = dispatcher.useTools("nonexistent list");

        assertTrue(result.isError());
        String msg = String.valueOf(result.getContent());
        assertTrue("应提示未知 agent，实际: " + msg, msg.contains("nonexistent"));
        assertTrue("应列出可用 agent，实际: " + msg, msg.contains("device"));
    }

    @Test
    public void useToolsUnknownToolReturnsErrorWithToolList() {
        ToolDispatcher dispatcher = newDispatcher(stubAgent("device", toolList("list", "get")));
        ToolResult result = dispatcher.useTools("device nonsuch");

        assertTrue(result.isError());
        String msg = String.valueOf(result.getContent());
        assertTrue("应提示未知 tool，实际: " + msg, msg.contains("nonsuch"));
        assertTrue("应列出可用工具，实际: " + msg, msg.contains("list"));
        assertTrue(msg.contains("get"));
    }

    @Test
    public void useToolsExecutesAndWrapsResult() {
        ToolResult execResult = ToolResult.success("OK");
        ToolDispatcher dispatcher = newDispatcher(
                stubAgentWithExecute("device", toolList("list"), execResult));
        ToolResult result = dispatcher.useTools("device list");

        assertFalse(result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) result.getContent();
        assertEquals("device", resp.get("agent"));
        assertEquals("list", resp.get("tool"));
        assertEquals(Boolean.TRUE, resp.get("success"));
    }

    @Test
    public void getToolsAllActiveAgents() {
        ToolDispatcher dispatcher = newDispatcher(stubAgent("device", toolList("list", "get")));
        ToolResult result = dispatcher.getTools(null);

        assertFalse(result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) result.getContent();
        assertEquals(Boolean.TRUE, resp.get("success"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) data.get("tools");
        assertEquals(2, tools.size());
    }

    @Test
    public void getToolsSingleAgent() {
        ToolDispatcher dispatcher = newDispatcher(stubAgent("device", toolList("list", "get")));
        ToolResult result = dispatcher.getTools("device");

        assertFalse(result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) result.getContent();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) data.get("tools");
        assertEquals(2, tools.size());
        assertEquals("device", tools.get(0).get("agent"));
        assertEquals("list", tools.get(0).get("tool"));
    }

    @Test
    public void getToolsUnknownAgentReturnsErrorWithAvailableList() {
        ToolDispatcher dispatcher = newDispatcher(stubAgent("device", toolList("list")));
        ToolResult result = dispatcher.getTools("nonexistent");

        assertTrue(result.isError());
        String msg = String.valueOf(result.getContent());
        assertTrue(msg.contains("nonexistent"));
        assertTrue(msg.contains("device"));
    }

    // ==================== 测试辅助 ====================

    private ToolDispatcher newDispatcher(AbstractSubAgent... agents) {
        SubAgentRegistry registry = new SubAgentRegistry(Arrays.asList(agents));
        registry.buildIndex(new HashSet<String>(Arrays.asList(COORD)));
        return new ToolDispatcher(registry, new CliParser(), mock(ToolExecutor.class), BASE_URL);
    }

    private AbstractSubAgent stubAgent(final String name, final List<ToolDescriptor> tools) {
        return new AbstractSubAgent(name, COORD) {
            @Override
            public List<ToolDescriptor> getTools() {
                return tools;
            }
        };
    }

    private AbstractSubAgent stubAgentWithExecute(final String name,
                                                  final List<ToolDescriptor> tools,
                                                  final ToolResult execResult) {
        return new AbstractSubAgent(name, COORD) {
            @Override
            public List<ToolDescriptor> getTools() {
                return tools;
            }

            @Override
            public ToolResult execute(ToolDescriptor tool, java.util.Map<String, Object> params,
                                      ToolExecutor executor, String baseUrl) {
                return execResult;
            }
        };
    }

    private List<ToolDescriptor> toolList(String... names) {
        List<ToolDescriptor> tools = new ArrayList<ToolDescriptor>();
        for (String n : names) {
            tools.add(new ToolDescriptor.Builder(n).build());
        }
        return tools;
    }
}
