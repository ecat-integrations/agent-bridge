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
import static org.junit.Assert.fail;

/**
 * {@link ToolExecutor} 单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@link ToolExecutor#buildRequest}：路径参数 {xxx} 替换、GET query string 拼装、
 *       路径参数不泄漏到 body（doc 06 阶段1 DoD）。</li>
 *   <li>{@code extractAsyncId}：异步接受响应（HTTP 202）→ asyncExecutionId 提取，
 *       及契约违反（字段缺失/空/非法 JSON）时抛 {@link IllegalStateException}（BUG-1 回归）。</li>
 * </ul>
 *
 * @author coffee
 */
public class ToolExecutorTest {

    /** 可复用的内部 Token 提供者（测试桩，固定返回 "internal-tok"）。 */
    private static ToolExecutor.InternalTokenProvider stubToken() {
        return new ToolExecutor.InternalTokenProvider() {
            @Override
            public String getInternalToken() {
                return "internal-tok";
            }
        };
    }

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

    // ===== extractAsyncId：异步接受响应（HTTP 202）→ asyncExecutionId =====

    /**
     * BUG-1 主回归：core-api 异步接受响应契约字段为 asyncExecutionId，
     * 必须正确提取（而非旧实现误查的 executionId，亦非整段 JSON 当 id）。
     */
    @Test
    public void extractAsyncIdReadsAsyncExecutionIdField() {
        ToolExecutor exec = new ToolExecutor(stubToken());
        String resp = "{\"asyncExecutionId\":\"async-3\",\"status\":\"SUCCESS\","
                + "\"estimatedTimeout\":30,\"createdAt\":\"2026-06-14T23:54:02+08:00\"}";
        assertEquals("async-3", exec.extractAsyncId(resp));
    }

    /**
     * 契约违反：仅有旧实现误查的 executionId（错误字段），缺少 asyncExecutionId，
     * 必须抛异常——不得返回整段响应当 id（旧 BUG-1 的静默兜底）。
     */
    @Test
    public void extractAsyncIdThrowsWhenOnlyLegacyExecutionIdPresent() {
        ToolExecutor exec = new ToolExecutor(stubToken());
        String resp = "{\"executionId\":\"async-3\",\"status\":\"SUCCESS\"}";
        try {
            exec.extractAsyncId(resp);
            fail("缺少 asyncExecutionId 应抛 IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue("异常信息应点明契约字段 asyncExecutionId",
                    expected.getMessage().contains("asyncExecutionId"));
        }
    }

    /** 契约违反：空响应必须抛异常，不得返回占位 "unknown"。 */
    @Test
    public void extractAsyncIdThrowsOnEmptyResponse() {
        ToolExecutor exec = new ToolExecutor(stubToken());
        try {
            exec.extractAsyncId("");
            fail("空响应应抛 IllegalStateException");
        } catch (IllegalStateException expected) {
            // 期望明确报错，不静默返回
        }
    }

    /** 契约违反：null 响应必须抛异常。 */
    @Test
    public void extractAsyncIdThrowsOnNullResponse() {
        ToolExecutor exec = new ToolExecutor(stubToken());
        try {
            exec.extractAsyncId(null);
            fail("null 响应应抛 IllegalStateException");
        } catch (IllegalStateException expected) {
            // 期望明确报错
        }
    }

    /** 契约违反：非合法 JSON 必须抛异常，不得返回原文当 id。 */
    @Test
    public void extractAsyncIdThrowsOnInvalidJson() {
        ToolExecutor exec = new ToolExecutor(stubToken());
        try {
            exec.extractAsyncId("not-json");
            fail("非法 JSON 应抛 IllegalStateException");
        } catch (IllegalStateException expected) {
            // 期望明确报错
        }
    }

    /** 契约违反：asyncExecutionId 字段存在但值为空字符串，必须抛异常。 */
    @Test
    public void extractAsyncIdThrowsOnEmptyIdValue() {
        ToolExecutor exec = new ToolExecutor(stubToken());
        try {
            exec.extractAsyncId("{\"asyncExecutionId\":\"\"}");
            fail("空 asyncExecutionId 应抛 IllegalStateException");
        } catch (IllegalStateException expected) {
            // 期望明确报错
        }
    }
}
