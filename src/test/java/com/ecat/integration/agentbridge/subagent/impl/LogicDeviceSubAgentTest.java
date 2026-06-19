package com.ecat.integration.agentbridge.subagent.impl;

import com.ecat.integration.agentbridge.subagent.ArgDescriptor;
import com.ecat.integration.agentbridge.subagent.ToolDescriptor;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link LogicDeviceSubAgent} 单元测试。验证 3 工具映射 LogicDeviceController 实际端点。
 *
 * @author coffee
 */
public class LogicDeviceSubAgentTest {

    @Test
    public void agentNameAndRequiredIntegration() {
        LogicDeviceSubAgent agent = new LogicDeviceSubAgent();
        assertEquals("logic-device", agent.getAgentName());
        assertEquals("com.ecat:integration-ecat-core-api", agent.getRequiredIntegration());
    }

    @Test
    public void returnsThreeTools() {
        List<ToolDescriptor> tools = new LogicDeviceSubAgent().getTools();
        assertEquals(3, tools.size());
        assertEquals("list", tools.get(0).getToolName());
        assertEquals("get-attributes", tools.get(1).getToolName());
        assertEquals("set-attribute", tools.get(2).getToolName());
    }

    @Test
    public void setAttributeMapsToAsyncPutEndpoint() {
        ToolDescriptor setAttr = findTool("set-attribute");
        assertEquals("PUT", setAttr.getHttpMethod());
        assertEquals("/core-api/logic-devices/{id}/attributes/{attrId}/value", setAttr.getHttpPath());
        assertTrue("set-attribute 应为异步", setAttr.isAsync());
        assertTrue("id 应为路径参数", findArg(setAttr, "id").isPathParam());
        assertTrue("attrId 应为路径参数", findArg(setAttr, "attrId").isPathParam());
        assertEquals("--attr-id", findArg(setAttr, "attrId").getFlag());
    }

    @Test
    public void getAttributesUsesAttributesPath() {
        ToolDescriptor getAttr = findTool("get-attributes");
        assertEquals("GET", getAttr.getHttpMethod());
        assertEquals("/core-api/logic-devices/{id}/attributes", getAttr.getHttpPath());
    }

    private ToolDescriptor findTool(String name) {
        for (ToolDescriptor t : new LogicDeviceSubAgent().getTools()) {
            if (name.equals(t.getToolName())) return t;
        }
        throw new AssertionError("tool not found: " + name);
    }

    private ArgDescriptor findArg(ToolDescriptor tool, String name) {
        for (ArgDescriptor a : tool.getArgs()) {
            if (name.equals(a.getName())) return a;
        }
        throw new AssertionError("arg not found: " + name);
    }
}
