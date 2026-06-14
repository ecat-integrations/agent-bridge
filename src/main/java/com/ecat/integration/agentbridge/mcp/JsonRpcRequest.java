package com.ecat.integration.agentbridge.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.Collections;
import java.util.Map;

/**
 * JSON-RPC 2.0 请求模型。
 *
 * <p>标准字段：jsonrpc（固定 "2.0"）、id（可为 null，通知无 id）、
 * method（String）、params（Object，可为 null）。
 *
 * @author coffee
 */
public class JsonRpcRequest {

    /** JSON-RPC 协议版本 */
    private final String jsonrpc;

    /** 请求 ID，通知消息时为 null */
    private final Object id;

    /** 方法名 */
    private final String method;

    /** 方法参数，可以为 null */
    private final Object params;

    /**
     * 全参构造器
     *
     * @param jsonrpc 协议版本
     * @param id      请求 ID
     * @param method  方法名
     * @param params  方法参数
     */
    public JsonRpcRequest(String jsonrpc, Object id, String method, Object params) {
        this.jsonrpc = jsonrpc;
        this.id = id;
        this.method = method;
        this.params = params;
    }

    /**
     * 从 JSON 字符串解析 JSON-RPC 请求。
     *
     * @param jsonBody JSON 格式的请求体
     * @return 解析后的 JsonRpcRequest
     * @throws McpException 解析失败或格式不合法时抛出
     */
    public static JsonRpcRequest parse(String jsonBody) throws McpException {
        if (jsonBody == null || jsonBody.trim().isEmpty()) {
            throw new McpException(JsonRpcResponse.PARSE_ERROR, "Request body is empty");
        }
        JSONObject obj;
        try {
            obj = JSON.parseObject(jsonBody);
        } catch (Exception e) {
            throw new McpException(JsonRpcResponse.PARSE_ERROR, "Invalid JSON: " + e.getMessage());
        }
        if (obj == null) {
            throw new McpException(JsonRpcResponse.PARSE_ERROR, "Request body is not a JSON object");
        }
        String jsonrpc = obj.getString("jsonrpc");
        if (!"2.0".equals(jsonrpc)) {
            throw new McpException(JsonRpcResponse.INVALID_REQUEST,
                    "Invalid jsonrpc version: " + jsonrpc);
        }
        String method = obj.getString("method");
        if (method == null || method.isEmpty()) {
            throw new McpException(JsonRpcResponse.INVALID_REQUEST, "method is required");
        }
        Object id = obj.get("id");
        Object params = obj.get("params");
        return new JsonRpcRequest(jsonrpc, id, method, params);
    }

    /**
     * 获取协议版本
     *
     * @return 协议版本字符串
     */
    public String getJsonrpc() {
        return jsonrpc;
    }

    /**
     * 获取请求 ID
     *
     * @return 请求 ID，通知消息时为 null
     */
    public Object getId() {
        return id;
    }

    /**
     * 获取方法名
     *
     * @return 方法名
     */
    public String getMethod() {
        return method;
    }

    /**
     * 获取原始参数对象
     *
     * @return 参数对象，可能为 null
     */
    public Object getParams() {
        return params;
    }

    /**
     * 安全地将 params 转换为 Map。
     *
     * <p>如果 params 为 null 返回空 Map；如果 params 不是 Map 类型则抛出 McpException。
     *
     * @return 参数 Map（不可修改）
     * @throws McpException params 类型不是 Map 时抛出
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getParamsAsMap() throws McpException {
        if (params == null) {
            return Collections.emptyMap();
        }
        if (params instanceof Map) {
            return Collections.unmodifiableMap((Map<String, Object>) params);
        }
        throw new McpException(JsonRpcResponse.INVALID_PARAMS,
                "params must be a JSON object, got: " + params.getClass().getSimpleName());
    }

    /**
     * 判断此请求是否为通知（无 id 字段）。
     *
     * @return true 表示通知，不需要响应
     */
    public boolean isNotification() {
        return id == null;
    }
}
