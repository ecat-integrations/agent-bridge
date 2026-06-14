package com.ecat.integration.agentbridge.tool;

import com.ecat.integration.agentbridge.subagent.ArgDescriptor;
import com.ecat.integration.agentbridge.subagent.ToolDescriptor;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ToolExecutor#buildRequest} 单元测试。
 *
 * <p>验证路径参数 {xxx} 替换、GET query string 拼装、路径参数不泄漏到 body（doc 06 阶段1 DoD）。
 *
 * @author coffee
 */
public class ToolExecutorTest {

    @Test
    public void replacesPathParamsAndOmitsThemFromBody() {
        ToolDescriptor tool = new ToolDescriptor.Builder("set-attribute")
                .httpMethod("PUT")
                .httpPath("/devices/{deviceId}/attributes/{attrId}/value")
                .args(Arrays.asList(
                        new ArgDescriptor.Builder("deviceId").pathParam().build(),
                        new ArgDescriptor.Builder("attrId").pathParam().build(),
                        new ArgDescriptor.Builder("value").flag("--value").build()))
                .build();
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("deviceId", "D1");
        params.put("attrId", "temp");
        params.put("value", "25");

        ToolExecutor.Request req = ToolExecutor.buildRequest(tool, params, "http://127.0.0.1:9999");

        assertEquals("http://127.0.0.1:9999/devices/D1/attributes/temp/value", req.getUrl());
        assertEquals("PUT", req.getMethod());
        assertNotNull("PUT 有参数应有 body", req.getBody());
        assertTrue("body 应含 value", req.getBody().contains("\"value\":\"25\""));
        assertFalse("路径参数 deviceId 不应进 body", req.getBody().contains("deviceId"));
        assertFalse("路径参数 attrId 不应进 body", req.getBody().contains("attrId"));
    }

    @Test
    public void appendsGetQueryForRemainingParams() {
        ToolDescriptor tool = new ToolDescriptor.Builder("list")
                .httpMethod("GET")
                .httpPath("/devices")
                .args(Arrays.asList(
                        new ArgDescriptor.Builder("status").flag("--status").build()))
                .build();
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("status", "online");

        ToolExecutor.Request req = ToolExecutor.buildRequest(tool, params, "http://127.0.0.1:9999");

        assertEquals("http://127.0.0.1:9999/devices?status=online", req.getUrl());
        assertEquals("GET", req.getMethod());
        assertNull("GET 无 body", req.getBody());
    }
}
