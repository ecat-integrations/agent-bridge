package com.ecat.integration.agentbridge.mcp;

import com.alibaba.fastjson2.JSON;

import java.util.LinkedHashMap;

/**
 * JSON-RPC 2.0 响应模型。
 *
 * <p>标准字段：jsonrpc（固定 "2.0"）、id、result（成功时）、error（失败时）。
 * result 和 error 互斥 — 有 result 无 error，有 error 无 result。
 *
 * @author coffee
 */
public class JsonRpcResponse {

    /* ===== 标准 JSON-RPC 错误码 ===== */

    /** JSON 解析错误 */
    public static final int PARSE_ERROR = -32700;

    /** 无效请求 */
    public static final int INVALID_REQUEST = -32600;

    /** 方法未找到 */
    public static final int METHOD_NOT_FOUND = -32601;

    /** 无效参数 */
    public static final int INVALID_PARAMS = -32602;

    /** 内部错误 */
    public static final int INTERNAL_ERROR = -32603;

    /** 服务器尚未初始化（MCP 扩展） */
    public static final int SERVER_NOT_INITIALIZED = -32002;

    /** JSON-RPC 协议版本 */
    private final String jsonrpc;

    /** 请求 ID（与请求中的 id 对应） */
    private final Object id;

    /** 成功结果（与 error 互斥） */
    private final Object result;

    /** 错误对象（与 result 互斥） */
    private final ErrorObject error;

    /**
     * 私有构造器（通过工厂方法创建实例）
     */
    private JsonRpcResponse(String jsonrpc, Object id, Object result, ErrorObject error) {
        this.jsonrpc = jsonrpc;
        this.id = id;
        this.result = result;
        this.error = error;
    }

    /**
     * 创建成功响应。
     *
     * @param id     请求 ID
     * @param result 结果数据
     * @return 成功响应
     */
    public static JsonRpcResponse success(Object id, Object result) {
        return new JsonRpcResponse("2.0", id, result, null);
    }

    /**
     * 创建错误响应。
     *
     * @param id      请求 ID
     * @param code    错误码
     * @param message 错误消息
     * @return 错误响应
     */
    public static JsonRpcResponse error(Object id, int code, String message) {
        return new JsonRpcResponse("2.0", id, null, new ErrorObject(code, message, null));
    }

    /**
     * 创建带附加数据的错误响应。
     *
     * @param id      请求 ID
     * @param code    错误码
     * @param message 错误消息
     * @param data    附加数据
     * @return 错误响应
     */
    public static JsonRpcResponse error(Object id, int code, String message, Object data) {
        return new JsonRpcResponse("2.0", id, null, new ErrorObject(code, message, data));
    }

    /**
     * 判断是否为成功响应。
     *
     * @return true 表示成功
     */
    public boolean isSuccess() {
        return error == null;
    }

    /**
     * 序列化为 JSON 字符串。
     *
     * @return JSON 格式字符串
     */
    public String toJson() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("jsonrpc", jsonrpc);
        map.put("id", id);
        if (error != null) {
            map.put("error", error);
        } else {
            map.put("result", result);
        }
        return JSON.toJSONString(map);
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
     * @return 请求 ID
     */
    public Object getId() {
        return id;
    }

    /**
     * 获取成功结果
     *
     * @return 结果数据，失败时为 null
     */
    public Object getResult() {
        return result;
    }

    /**
     * 获取错误对象
     *
     * @return 错误对象，成功时为 null
     */
    public ErrorObject getError() {
        return error;
    }

    /**
     * JSON-RPC 错误对象。
     *
     * <p>标准字段：code（int）、message（String）、data（Object，可选）。
     */
    public static class ErrorObject {

        /** 错误码 */
        private final int code;

        /** 错误消息 */
        private final String message;

        /** 附加数据（可选） */
        private final Object data;

        /**
         * 构造器
         *
         * @param code    错误码
         * @param message 错误消息
         * @param data    附加数据，可为 null
         */
        public ErrorObject(int code, String message, Object data) {
            this.code = code;
            this.message = message;
            this.data = data;
        }

        /**
         * 获取错误码
         *
         * @return 错误码
         */
        public int getCode() {
            return code;
        }

        /**
         * 获取错误消息
         *
         * @return 错误消息
         */
        public String getMessage() {
            return message;
        }

        /**
         * 获取附加数据
         *
         * @return 附加数据，可能为 null
         */
        public Object getData() {
            return data;
        }
    }
}
