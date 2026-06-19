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
        ToolResult result = dispatcher.useTools("nonexistent list", null);

        assertTrue(result.isError());
        String msg = String.valueOf(result.getContent());
        assertTrue("应提示未知 agent，实际: " + msg, msg.contains("nonexistent"));
        assertTrue("应列出可用 agent，实际: " + msg, msg.contains("device"));
        assertTrue("应含 getTools 恢复指引，实际: " + msg, msg.contains("getTools"));
    }

    @Test
    public void useToolsUnknownToolReturnsErrorWithToolList() {
        ToolDispatcher dispatcher = newDispatcher(stubAgent("device", toolList("list", "get")));
        ToolResult result = dispatcher.useTools("device nonsuch", null);

        assertTrue(result.isError());
        String msg = String.valueOf(result.getContent());
        assertTrue("应提示未知 tool，实际: " + msg, msg.contains("nonsuch"));
        assertTrue("应列出可用工具，实际: " + msg, msg.contains("list"));
        assertTrue(msg.contains("get"));
        assertTrue("应含 getTools 恢复指引，实际: " + msg, msg.contains("getTools"));
    }

    @Test
    public void useToolsExecutesAndWrapsResult() {
        ToolResult execResult = ToolResult.success("OK");
        ToolDispatcher dispatcher = newDispatcher(
                stubAgentWithExecute("device", toolList("list"), execResult));
        ToolResult result = dispatcher.useTools("device list", null);

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

    // ==================== 工具描述（MCP tools/list 的 description） ====================

    /**
     * getTools 的 description 即能力菜单（BCP 元工具模式）：应包含每个领域 agent 及其全部工具名，
     * 格式 {@code agent: [tool1, tool2]}。
     */
    @Test
    public void buildGetToolsDescriptionContainsAllAgentsAndTools() {
        ToolDispatcher dispatcher = newDispatcher(
                stubAgent("device", toolList("list", "get")),
                stubAgent("media", toolList("snapshot")));
        String desc = dispatcher.buildGetToolsDescription();

        assertTrue("应含领域菜单标题，实际: " + desc, desc.contains("领域 Agents"));
        assertTrue("应列出 device 及其工具，实际: " + desc, desc.contains("device: [list, get]"));
        assertTrue("应列出 media 及其工具，实际: " + desc, desc.contains("media: [snapshot]"));
        assertTrue("应含工作流指令，实际: " + desc, desc.contains("getTools(\"<agent>\")"));
        // 参数示例应取首个 agent（device），与菜单一致，不硬编码未启用领域
        assertTrue("参数示例应取首个 agent device，实际: " + desc, desc.contains("如 \"device\""));
    }

    /**
     * 无任何领域 agent 注册（无集成 ACTIVE）时，description 应显式报状态占位，不臆造兜底、不留空。
     */
    @Test
    public void buildGetToolsDescriptionEmptyIndexShowsPlaceholder() {
        SubAgentRegistry registry = new SubAgentRegistry(
                Arrays.asList(stubAgent("device", toolList("list"))));
        registry.buildIndex(new HashSet<String>()); // 空 ACTIVE 集合 → 无 agent 注册 → 能力索引为空
        ToolDispatcher dispatcher = new ToolDispatcher(registry, new CliParser(),
                mock(ToolExecutor.class), BASE_URL);
        String desc = dispatcher.buildGetToolsDescription();

        assertTrue("空索引应显示占位说明，实际: " + desc, desc.contains("（当前无可用领域 Agent）"));
        assertFalse("空索引不应列出 agent，实际: " + desc, desc.contains("device:"));
        assertTrue("空索引参数示例应用通用占位 <agent>，实际: " + desc, desc.contains("如 \"<agent>\""));
    }

    /**
     * useTools 的 description 应含工作流、用法与具体示例（让 agent 无需试探即可构造 CLI）。
     */
    @Test
    public void buildUseToolsDescriptionContainsWorkflowAndExamples() {
        ToolDispatcher dispatcher = newDispatcher(stubAgent("device", toolList("list")));
        String desc = dispatcher.buildUseToolsDescription();

        assertTrue("应含示例 device list，实际: " + desc, desc.contains("device list"));
        assertTrue("应含示例 media snapshot，实际: " + desc, desc.contains("media snapshot"));
        assertTrue("应含示例 event query-async-result，实际: " + desc, desc.contains("event query-async-result"));
        assertTrue("应提示先调 getTools，实际: " + desc, desc.contains("getTools(\"<agent>\")"));
    }

    /**
     * useTools 的 description 应说明 set-attribute 异步返回 taskId 的约定（异步闭环关键提示）。
     */
    @Test
    public void buildUseToolsDescriptionMentionsAsyncTaskId() {
        ToolDispatcher dispatcher = newDispatcher(stubAgent("device", toolList("list")));
        String desc = dispatcher.buildUseToolsDescription();

        assertTrue("应提及 taskId，实际: " + desc, desc.contains("taskId"));
        assertTrue("应提及 asyncExecutionId 字段名，实际: " + desc, desc.contains("asyncExecutionId"));
        assertTrue("应提及 query-async-result 查询方式，实际: " + desc, desc.contains("query-async-result"));
    }

    /**
     * getTools inputSchema 的 tool 参数提示（{@code buildGetToolsParamHint}）应含当前首个 agent，
     * 而非硬编码——避免引用未启用的领域（与 description body 同理）。
     */
    @Test
    public void buildGetToolsParamHintReflectsFirstAgent() {
        // device 先注册 → 首个 agent = device
        ToolDispatcher dispatcher = newDispatcher(
                stubAgent("device", toolList("list")),
                stubAgent("media", toolList("snapshot")));
        String hint = dispatcher.buildGetToolsParamHint();
        assertTrue("hint 应含首个 agent device，实际: " + hint, hint.contains("\"device\""));
        assertFalse("hint 不应引用可能未启用的 media，实际: " + hint, hint.contains("\"media\""));
        assertTrue("hint 应说明省略语义，实际: " + hint, hint.contains("省略返回全部"));

        // 空索引：通用提示，不含任何具体 agent（严格模式，不臆造领域）
        SubAgentRegistry emptyRegistry = new SubAgentRegistry(
                Arrays.asList(stubAgent("device", toolList("list"))));
        emptyRegistry.buildIndex(new HashSet<String>()); // 空 ACTIVE → 无 agent 注册
        ToolDispatcher emptyDispatcher = new ToolDispatcher(emptyRegistry, new CliParser(),
                mock(ToolExecutor.class), BASE_URL);
        String emptyHint = emptyDispatcher.buildGetToolsParamHint();
        assertFalse("空索引 hint 不应含具体 agent 名，实际: " + emptyHint, emptyHint.contains("\""));
        assertTrue("空索引 hint 应说明语义，实际: " + emptyHint, emptyHint.contains("省略返回全部"));
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
                                      ToolExecutor executor, String baseUrl,
                                      String requestHostPort) {
                return execResult;
            }
        };
    }

    /**
     * host 传递回归（Plan A）：{@code useTools(cli, requestHostPort)} 应把 requestHostPort
     * 透传到 {@code agent.execute(...)}，供进程内工具（media get-download-url）拼装下载 URL。
     */
    @Test
    public void useToolsPassesRequestHostPortToExecute() {
        final String[] capturedHost = new String[1];
        AbstractSubAgent agent = new AbstractSubAgent("device", COORD) {
            @Override
            public List<ToolDescriptor> getTools() {
                return toolList("list");
            }

            @Override
            public ToolResult execute(ToolDescriptor tool, java.util.Map<String, Object> params,
                                      ToolExecutor executor, String baseUrl,
                                      String requestHostPort) {
                capturedHost[0] = requestHostPort;
                return ToolResult.success("OK");
            }
        };
        ToolDispatcher dispatcher = newDispatcher(agent);

        // 带 host：透传到 execute
        dispatcher.useTools("device list", "core.example.com:8080");
        assertEquals("带 host 调用应透传 requestHostPort", "core.example.com:8080", capturedHost[0]);
    }

    private List<ToolDescriptor> toolList(String... names) {
        List<ToolDescriptor> tools = new ArrayList<ToolDescriptor>();
        for (String n : names) {
            tools.add(new ToolDescriptor.Builder(n).build());
        }
        return tools;
    }
}
