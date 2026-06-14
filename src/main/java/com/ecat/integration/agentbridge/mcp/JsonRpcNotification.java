package com.ecat.integration.agentbridge.mcp;

import com.alibaba.fastjson2.JSON;

import java.util.LinkedHashMap;

/**
 * JSON-RPC 通知模型。
 *
 * <p>通知没有 id 字段，不需要响应。用于服务端向 Agent 推送事件。
 * 通过 SSE 连接发送时，使用 {@link #toSseData()} 生成 SSE data 行内容。
 *
 * @author coffee
 */
public class JsonRpcNotification {

    /** JSON-RPC 协议版本 */
    private final String jsonrpc;

    /** 通知方法名 */
    private final String method;

    /** 通知参数 */
    private final Object params;

    /**
     * 构造器
     *
     * @param method 通知方法名
     * @param params 通知参数，可为 null
     */
    public JsonRpcNotification(String method, Object params) {
        this.jsonrpc = "2.0";
        this.method = method;
        this.params = params;
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
     * 获取通知方法名
     *
     * @return 方法名
     */
    public String getMethod() {
        return method;
    }

    /**
     * 获取通知参数
     *
     * @return 参数对象，可能为 null
     */
    public Object getParams() {
        return params;
    }

    /**
     * 序列化为 SSE data 行内容。
     *
     * <p>返回不带 "data: " 前缀的 JSON 字符串，供 SSE 写入时使用。
     *
     * @return JSON 字符串
     */
    public String toSseData() {
        return toJson();
    }

    /**
     * 序列化为完整 JSON 字符串。
     *
     * @return JSON 格式字符串
     */
    public String toJson() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("jsonrpc", jsonrpc);
        map.put("method", method);
        if (params != null) {
            map.put("params", params);
        }
        return JSON.toJSONString(map);
    }
}
