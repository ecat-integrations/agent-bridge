package com.ecat.integration.agentbridge.subagent.impl;

import com.ecat.integration.agentbridge.subagent.ArgDescriptor;
import com.ecat.integration.agentbridge.subagent.ToolDescriptor;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link EventSubAgent} 单元测试。验证 query-async-result 映射 EventController 端点。
 *
 * @author coffee
 */
public class EventSubAgentTest {

    @Test
    public void agentNameAndRequiredIntegration() {
        EventSubAgent agent = new EventSubAgent();
        assertEquals("event", agent.getAgentName());
        assertEquals("com.ecat:integration-ecat-core-api", agent.getRequiredIntegration());
    }

    @Test
    public void returnsSingleQueryAsyncResultTool() {
        List<ToolDescriptor> tools = new EventSubAgent().getTools();
        assertEquals(1, tools.size());
        assertEquals("query-async-result", tools.get(0).getToolName());
    }

    @Test
    public void queryAsyncResultMapsToAsyncEndpoint() {
        ToolDescriptor tool = new EventSubAgent().getTools().get(0);
        assertEquals("GET", tool.getHttpMethod());
        assertEquals("/core-api/events/async/{id}", tool.getHttpPath());
        ArgDescriptor idArg = tool.getArgs().get(0);
        assertEquals("id", idArg.getName());
        assertTrue("id 应为路径参数", idArg.isPathParam());
        assertTrue("id 应必填", idArg.isRequired());
    }
}
