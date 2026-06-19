package com.ecat.integration.agentbridge.subagent.impl;

import com.ecat.integration.agentbridge.subagent.AbstractSubAgent;
import com.ecat.integration.agentbridge.subagent.ArgDescriptor;
import com.ecat.integration.agentbridge.subagent.ToolDescriptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 事件/异步结果领域 SubAgent（MVP：仅 query-async-result）。
 *
 * <p>砍 SSE 推送后（doc 05 §1.4），事件能力仅保留异步操作结果查询。
 * DeviceSubAgent/LogicDeviceSubAgent 的 set-attribute 是异步操作（返回 executionId），
 * agent 用本工具查询执行结果（status/errorMessage/completedAt）。
 *
 * <p>端点已核实（EventController）：
 * <ul>
 *   <li>GET /core-api/events/async/{id} — query-async-result（core-api 现成端点，零改动）</li>
 * </ul>
 *
 * @author coffee
 */
public class EventSubAgent extends AbstractSubAgent {

    public EventSubAgent() {
        super("event", "com.ecat:integration-ecat-core-api");
    }

    @Override
    public List<ToolDescriptor> getTools() {
        return Collections.singletonList(buildQueryAsyncResultTool());
    }

    /** event query-async-result — GET /core-api/events/async/{id} */
    private ToolDescriptor buildQueryAsyncResultTool() {
        return new ToolDescriptor.Builder("query-async-result")
                .description("查询异步操作执行结果。设备/逻辑设备属性写入（set-attribute）等操作异步执行，"
                        + "返回 executionId（如 async-1）后，用本工具查询其最终状态（RUNNING/SUCCESS/FAILED/TIMEOUT）"
                        + "、错误信息和完成时间。")
                .usage("event query-async-result --id <executionId>")
                .args(Arrays.asList(
                        new ArgDescriptor.Builder("id").flag("--id").pathParam().required()
                                .description("异步操作 ID，由 set-attribute 等异步工具返回").build()))
                .examples(Arrays.asList("event query-async-result --id async-1"))
                .httpMethod("GET").httpPath("/core-api/events/async/{id}")
                .build();
    }
}
