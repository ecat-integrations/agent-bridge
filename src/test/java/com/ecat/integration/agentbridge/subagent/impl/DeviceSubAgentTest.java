package com.ecat.integration.agentbridge.subagent.impl;

import com.ecat.integration.agentbridge.subagent.ArgDescriptor;
import com.ecat.integration.agentbridge.subagent.ToolDescriptor;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link DeviceSubAgent} 单元测试。
 *
 * <p>验证 5 个工具的 CLI 元数据与 HTTP 映射与 core-api DeviceController 实际端点一致
 * （端点已核实：DeviceController BASE_PATH=/core-api/devices）。
 *
 * @author coffee
 */
public class DeviceSubAgentTest {

    @Test
    public void agentNameAndRequiredIntegration() {
        DeviceSubAgent agent = new DeviceSubAgent();
        assertEquals("device", agent.getAgentName());
        assertEquals("com.ecat:integration-ecat-core-api", agent.getRequiredIntegration());
    }

    @Test
    public void returnsFiveToolsWithCorrectNames() {
        List<ToolDescriptor> tools = new DeviceSubAgent().getTools();
        assertEquals(5, tools.size());
        assertEquals("list", tools.get(0).getToolName());
        assertEquals("get", tools.get(1).getToolName());
        assertEquals("get-attributes", tools.get(2).getToolName());
        assertEquals("get-attribute-schemas", tools.get(3).getToolName());
        assertEquals("set-attribute", tools.get(4).getToolName());
    }

    @Test
    public void listToolMapsToListDevicesEndpoint() {
        ToolDescriptor list = findTool("list");
        assertEquals("GET", list.getHttpMethod());
        assertEquals("/core-api/devices", list.getHttpPath());
    }

    @Test
    public void getToolUsesDeviceIdPathParam() {
        ToolDescriptor get = findTool("get");
        assertEquals("GET", get.getHttpMethod());
        assertEquals("/core-api/devices/{id}", get.getHttpPath());
        assertTrue("id 应为路径参数", findArg(get, "id").isPathParam());
        assertTrue("id 应必填", findArg(get, "id").isRequired());
    }

    @Test
    public void getAttributeSchemasUsesSingularAttributePath() {
        ToolDescriptor schemas = findTool("get-attribute-schemas");
        assertEquals("GET", schemas.getHttpMethod());
        // 实际端点是单数 attribute（DeviceController 核实，区别于 get-attributes 的复数）
        assertEquals("/core-api/devices/{id}/attribute/schemas", schemas.getHttpPath());
    }

    @Test
    public void setAttributeToolMapsToAsyncPutEndpoint() {
        ToolDescriptor setAttr = findTool("set-attribute");
        assertEquals("PUT", setAttr.getHttpMethod());
        assertEquals("/core-api/devices/{id}/attributes/{attrId}/value", setAttr.getHttpPath());
        assertTrue("set-attribute 应为异步", setAttr.isAsync());

        ArgDescriptor idArg = findArg(setAttr, "id");
        assertTrue("id 应为路径参数", idArg.isPathParam());
        ArgDescriptor attrIdArg = findArg(setAttr, "attrId");
        assertTrue("attrId 应为路径参数", attrIdArg.isPathParam());
        assertEquals("attrId 的 CLI flag 应为 --attr-id（kebab-case）", "--attr-id", attrIdArg.getFlag());
    }

    // ==================== 辅助 ====================

    private ToolDescriptor findTool(String name) {
        for (ToolDescriptor t : new DeviceSubAgent().getTools()) {
            if (name.equals(t.getToolName())) {
                return t;
            }
        }
        throw new AssertionError("tool not found: " + name);
    }

    private ArgDescriptor findArg(ToolDescriptor tool, String name) {
        for (ArgDescriptor a : tool.getArgs()) {
            if (name.equals(a.getName())) {
                return a;
            }
        }
        throw new AssertionError("arg not found: " + name);
    }
}
