package com.ecat.integration.agentbridge.subagent;

import com.ecat.integration.agentbridge.mcp.McpException;
import com.ecat.integration.agentbridge.tool.ToolExecutor;
import com.ecat.integration.agentbridge.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * useTools/getTools 路由调度器。
 *
 * <p>封装 BCP 两工具的核心逻辑，与 MCP 协议层解耦，便于单测。
 * <ul>
 *   <li>{@link #getTools}：返回 active SubAgent 的工具定义（CLI-first 格式，doc 05 §7.1）</li>
 *   <li>{@link #useTools}：CLI → 路由 SubAgent/工具 → 执行，BCP 错误统一 isError + 友好提示</li>
 * </ul>
 *
 * <p>BCP 错误原则（doc 05 §1.4.1.1）：未知 agent/tool、解析失败、执行失败一律返回
 * {@code isError:true} + 含可用选项的友好文本，让 agent 看到错误并决策；仅协议/认证层错误才抛异常。
 *
 * @author coffee
 */
public class ToolDispatcher {

    private final SubAgentRegistry registry;
    private final CliParser cliParser;
    private final ToolExecutor toolExecutor;
    private final String baseUrl;

    public ToolDispatcher(SubAgentRegistry registry, CliParser cliParser,
                          ToolExecutor toolExecutor, String baseUrl) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        if (cliParser == null) {
            throw new IllegalArgumentException("cliParser must not be null");
        }
        if (toolExecutor == null) {
            throw new IllegalArgumentException("toolExecutor must not be null");
        }
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new IllegalArgumentException("baseUrl must not be null/empty");
        }
        this.registry = registry;
        this.cliParser = cliParser;
        this.toolExecutor = toolExecutor;
        this.baseUrl = baseUrl;
    }

    /**
     * getTools：返回 active SubAgent 的工具定义（CLI-first）。
     *
     * @param agentName SubAgent 名，null/空表示返回全部 active agent 的工具
     * @return 工具列表结果（{success, data:{tools:[...]}}）或未知 agent 错误
     */
    public ToolResult getTools(String agentName) {
        if (agentName == null || agentName.isEmpty()) {
            List<Map<String, Object>> allTools = new ArrayList<Map<String, Object>>();
            for (AbstractSubAgent agent : activeAgentsInOrder()) {
                for (ToolDescriptor t : agent.getTools()) {
                    allTools.add(toolToCliMap(agent.getAgentName(), t));
                }
            }
            return toolsResult(allTools);
        }

        AbstractSubAgent agent = registry.findAgent(agentName);
        if (agent == null) {
            return ToolResult.error("未知 SubAgent '" + agentName + "'，可用: " + availableAgents());
        }
        List<Map<String, Object>> tools = new ArrayList<Map<String, Object>>();
        for (ToolDescriptor t : agent.getTools()) {
            tools.add(toolToCliMap(agentName, t));
        }
        return toolsResult(tools);
    }

    /**
     * useTools：解析 CLI → 路由 SubAgent/工具 → 执行。
     *
     * @param cli             CLI 命令串
     * @param requestHostPort MCP 请求 Host header 的 host:port，透传给进程内工具（如
     *                        media get-download-url）拼装对 agent 可达的下载 URL；可为 null
     *                        （HTTP 工具忽略，进程内工具回退 baseUrl host:port）
     * @return 执行结果（{agent, tool, success, result/taskId}）或 BCP 错误
     */
    public ToolResult useTools(String cli, String requestHostPort) {
        // 1. 提取 agent/tool 名（路由）
        String[] head;
        try {
            head = cliParser.extractHead(cli);
        } catch (McpException e) {
            return ToolResult.error(e.getErrorMessage());
        }
        String agentName = head[0];
        String toolName = head[1];

        // 2. 路由 agent
        AbstractSubAgent agent = registry.findAgent(agentName);
        if (agent == null) {
            return ToolResult.error("未知 SubAgent '" + agentName + "'，可用: " + availableAgents()
                    + "（用 getTools 查看工具参数）");
        }

        // 3. 路由 tool
        ToolDescriptor tool = findTool(agent, toolName);
        if (tool == null) {
            return ToolResult.error("SubAgent '" + agentName + "' 无 '" + toolName + "' 工具，可用: "
                    + joinNames(toolNamesOf(agent))
                    + "（用 getTools(\"" + agentName + "\") 查看该领域工具的参数）");
        }

        // 4. 用工具参数定义完整解析
        CliParseResult parseResult;
        try {
            parseResult = cliParser.parse(cli, tool.getArgs());
        } catch (McpException e) {
            return ToolResult.error("命令解析失败: " + e.getErrorMessage());
        }

        // 5. 执行（默认 HTTP；进程内工具由 agent override）
        try {
            String agentBaseUrl = agent.getBaseUrlOverride() != null
                    ? agent.getBaseUrlOverride() : baseUrl;
            ToolResult execResult = agent.execute(tool, parseResult.getParams(), toolExecutor,
                    agentBaseUrl, requestHostPort);
            if (execResult.isError()) {
                return ToolResult.error("工具 '" + agentName + " " + toolName + "' 执行失败: "
                        + execResult.getContent());
            }
            return ToolResult.success(buildUseToolsResponse(agentName, toolName, execResult));
        } catch (McpException e) {
            return ToolResult.error("工具 '" + agentName + " " + toolName + "' 执行失败: "
                    + e.getErrorMessage());
        }
    }

    // ==================== 响应构造 ====================

    /**
     * 构造 useTools 成功响应（doc 05 §7.2）：{agent, tool, success, result, [taskId, async]}。
     */
    private Map<String, Object> buildUseToolsResponse(String agentName, String toolName,
                                                      ToolResult execResult) {
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("agent", agentName);
        resp.put("tool", toolName);
        resp.put("success", Boolean.TRUE);
        if (execResult.getAsyncExecutionId() != null) {
            resp.put("taskId", execResult.getAsyncExecutionId());
            resp.put("async", Boolean.TRUE);
        }
        resp.put("result", execResult.getContent());
        return resp;
    }

    /**
     * 构造 getTools 成功响应（doc 05 §7.1）：{success, data:{tools:[...]}}。
     */
    private ToolResult toolsResult(List<Map<String, Object>> tools) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("tools", tools);
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("success", Boolean.TRUE);
        resp.put("data", data);
        return ToolResult.success(resp);
    }

    /**
     * 将 ToolDescriptor 转为 CLI-first 格式 Map（doc 05 §7.1）。
     */
    private Map<String, Object> toolToCliMap(String agentName, ToolDescriptor t) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("agent", agentName);
        m.put("tool", t.getToolName());
        m.put("description", t.getDescription());
        m.put("command", agentName + " " + t.getToolName());
        m.put("usage", t.getUsage() != null ? t.getUsage() : agentName + " " + t.getToolName());
        m.put("arguments", argsToList(t.getArgs()));
        m.put("examples", t.getExamples());
        return m;
    }

    private List<Map<String, Object>> argsToList(List<ArgDescriptor> args) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (ArgDescriptor a : args) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", a.getName());
            m.put("flag", a.getFlag());
            m.put("type", a.getType().name().toLowerCase());
            m.put("required", a.isRequired());
            m.put("positional", a.isPositional());
            m.put("description", a.getDescription());
            list.add(m);
        }
        return list;
    }

    // ==================== 辅助 ====================

    private ToolDescriptor findTool(AbstractSubAgent agent, String toolName) {
        for (ToolDescriptor t : agent.getTools()) {
            if (toolName.equals(t.getToolName())) {
                return t;
            }
        }
        return null;
    }

    /** 当前已注册 agent 名（按注册顺序），用于错误提示 */
    private String availableAgents() {
        return joinNames(new ArrayList<String>(registry.buildCapabilityIndex().keySet()));
    }

    private List<String> toolNamesOf(AbstractSubAgent agent) {
        List<String> names = new ArrayList<String>();
        for (ToolDescriptor t : agent.getTools()) {
            names.add(t.getToolName());
        }
        return names;
    }

    private String joinNames(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(names.get(i));
        }
        return sb.toString();
    }

    /** 按 buildCapabilityIndex 顺序获取已注册 agent */
    private List<AbstractSubAgent> activeAgentsInOrder() {
        List<AbstractSubAgent> list = new ArrayList<AbstractSubAgent>();
        for (String name : registry.buildCapabilityIndex().keySet()) {
            AbstractSubAgent a = registry.findAgent(name);
            if (a != null) {
                list.add(a);
            }
        }
        return list;
    }

    /**
     * 当前已注册 agent 的能力索引（agentName → toolName 列表），供 tools/list 的 getTools description。
     */
    public Map<String, List<String>> capabilityIndex() {
        return registry.buildCapabilityIndex();
    }

    // ==================== 工具描述（MCP tools/list） ====================

    /**
     * 构造 getTools 工具的 description（供 MCP tools/list 展示）。
     *
     * <p>遵循 BCP/Nexus 元工具模式（doc 03 §Part 3）：description 本身即能力菜单——
     * 模型在 tools/list 阶段、不调用任何工具即可读到「领域 Agents: agent: [tools,...]」清单。
     * 菜单由 {@link #capabilityIndex()} 动态生成，仅含当前已启用集成的领域（media 等领域需对应集成 ACTIVE 才出现）。
     *
     * <p>结构：功能定义 + 工作流指令（发现→执行两步）+ 动态领域菜单 + tool 参数契约。
     *
     * @return getTools 的 description 文本
     */
    public String buildGetToolsDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append("取工具的详细用法、参数定义与示例。\n\n");
        sb.append("工作流（发现 → 执行，两步）：\n");
        sb.append("  1) 本 description 的「领域 Agents」即能力清单——无需调用即可看到\n");
        sb.append("  2) getTools(\"<agent>\") 取该领域每个工具的参数/示例\n");
        sb.append("  3) useTools(\"<agent> <tool> [--flag value ...]\") 执行\n");
        sb.append("（无参 getTools 返回全部领域工具的完整定义，数据量较大，优先用上面第 2 步的 targeted 调用）\n\n");
        sb.append("领域 Agents（仅当前已启用集成，未启用的领域不出现）:\n");
        Map<String, List<String>> index = capabilityIndex();
        if (index.isEmpty()) {
            // 真实状态反映：无任何领域 agent 注册（无集成 ACTIVE），非臆造兜底
            sb.append("（当前无可用领域 Agent）\n");
        } else {
            for (Map.Entry<String, List<String>> e : index.entrySet()) {
                sb.append(e.getKey()).append(": [").append(joinNames(e.getValue())).append("]\n");
            }
        }
        // 示例与上方菜单一致：取当前能力索引首个 agent，不引用未启用的领域——
        // 否则 agent 照示例调用 getTools("media") 会撞"未知 agent"错误（菜单已说明 media 未启用）。
        // 空索引用通用占位 <agent>，不臆造任何领域（严格模式）。
        String exampleAgent = index.isEmpty() ? "<agent>" : index.keySet().iterator().next();
        sb.append("\n参数 tool：领域 agent 名（如 \"").append(exampleAgent)
                .append("\"）；省略则返回全部领域工具。");
        return sb.toString();
    }

    /**
     * 构造 getTools 工具 inputSchema 中 tool 参数的提示（供 MCP tools/list 展示）。
     *
     * <p>示例 agent 取自当前能力索引首个 agent，与 {@link #buildGetToolsDescription} 的菜单一致——
     * 避免引用未启用的领域（与 description body 同理）。空索引时给出不含具体 agent 的通用提示。
     *
     * @return tool 参数提示文本
     */
    public String buildGetToolsParamHint() {
        Map<String, List<String>> index = capabilityIndex();
        if (index.isEmpty()) {
            return "领域 agent 名；省略返回全部领域工具";
        }
        String firstAgent = index.keySet().iterator().next();
        return "领域 agent 名（如 \"" + firstAgent + "\"），省略返回全部领域工具";
    }

    /**
     * 构造 useTools 工具的 description（供 MCP tools/list 展示）。
     *
     * <p>静态文案（不依赖运行时数据）：功能定义 + 用法/示例 + 异步 taskId 约定 + 错误提示说明。
     * 点破与 getTools 共用 {@code tool} 参数、但语义不同（getTools 传 agent 名，useTools 传 CLI 串）。
     *
     * @return useTools 的 description 文本
     */
    public String buildUseToolsDescription() {
        return "执行工具：把一条 CLI 命令传给 tool 参数，自动路由到领域 agent、解析参数、调用 ECAT。\n\n"
                + "用法：tool = \"<agent> <tool> [--flag value ...]\"\n"
                + "示例：\n"
                + "  device list\n"
                + "  device set-attribute --device-id <id> --attribute-id <id> --value <v>\n"
                + "  media snapshot --device-id cam-01\n"
                + "  event query-async-result --id <taskId>\n\n"
                + "执行前先用 getTools(\"<agent>\") 查该工具的参数定义与示例。\n"
                + "set-attribute 是异步操作，返回 taskId（asyncExecutionId）；用 event query-async-result --id <taskId> 查结果。\n"
                + "未知 agent/tool 返回错误并列出当前可用项。";
    }
}
