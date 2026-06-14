package com.ecat.integration.agentbridge.subagent;

import com.ecat.integration.agentbridge.mcp.McpException;
import com.ecat.integration.agentbridge.tool.ToolExecutor;
import com.ecat.integration.agentbridge.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * SubAgent 抽象基类。
 *
 * <p>每个 SubAgent 是一个领域适配器：声明 {@link #getRequiredIntegration()}（依赖的集成 coordinate），
 * 暴露该领域下的 CLI 工具集合 {@link #getTools()}。SubAgentRegistry 据此构建能力索引。
 *
 * <p>关键约束（doc 05 §3.2）：{@code agentName} 与 {@code requiredIntegration} 必须有值，
 * 构造时强制校验。
 *
 * <p>工具执行（{@code execute}）在 Phase 3 实现具体业务 SubAgent 时落地（默认走 ToolExecutor HTTP，
 * 进程内调用工具如 {@code system confirm} 自行 override）。
 *
 * @author coffee
 */
public abstract class AbstractSubAgent {

    private final String agentName;
    private final String requiredIntegration;

    /**
     * @param agentName           SubAgent 名（CLI 首 token，如 "device"），不能为 null/空
     * @param requiredIntegration 依赖的集成 coordinate（如 "com.ecat:integration-ecat-core-api"），不能为 null/空
     */
    public AbstractSubAgent(String agentName, String requiredIntegration) {
        if (agentName == null || agentName.isEmpty()) {
            throw new IllegalArgumentException("agentName must not be null/empty");
        }
        if (requiredIntegration == null || requiredIntegration.isEmpty()) {
            throw new IllegalArgumentException("requiredIntegration must not be null/empty");
        }
        this.agentName = agentName;
        this.requiredIntegration = requiredIntegration;
    }

    /** 获取 SubAgent 名（CLI 首 token） */
    public String getAgentName() { return agentName; }

    /** 获取依赖的集成 coordinate */
    public String getRequiredIntegration() { return requiredIntegration; }

    /**
     * 获取该 SubAgent 暴露的全部 CLI 工具定义。
     *
     * @return 工具描述符列表
     */
    public abstract List<ToolDescriptor> getTools();

    /**
     * 执行工具调用。
     *
     * <p>默认实现委托 {@link ToolExecutor} 发 HTTP 请求（业务 SubAgent 直接复用）。
     * 进程内调用工具（如 {@code system confirm}）override 此方法，不走 HTTP。
     *
     * @param tool         工具定义
     * @param params       解析后的参数
     * @param toolExecutor HTTP 执行器（默认实现使用）
     * @param baseUrl      ecat-core-api 基础 URL
     * @return 执行结果
     * @throws McpException 进程内调用工具的执行错误
     */
    public ToolResult execute(ToolDescriptor tool, Map<String, Object> params,
                              ToolExecutor toolExecutor, String baseUrl) throws McpException {
        return toolExecutor.execute(tool, params, baseUrl);
    }

    /**
     * HTTP baseUrl 覆盖。
     *
     * <p>默认 null 表示用 dispatcher 的默认 baseUrl（core-api 9999）。
     * 访问独立端口集成的 SubAgent（如 MediaSubAgent 访问 media-api 9931）覆盖返回对应 baseUrl。
     *
     * @return baseUrl 覆盖值，null 表示用默认
     */
    public String getBaseUrlOverride() {
        return null;
    }
}
