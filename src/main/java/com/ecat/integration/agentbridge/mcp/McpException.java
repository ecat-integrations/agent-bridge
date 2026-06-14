package com.ecat.integration.agentbridge.mcp;

/**
 * MCP 协议层异常。
 *
 * <p>携带 JSON-RPC 错误码和错误消息，用于在 MCP 处理链路中传递协议级错误。
 * 错误码遵循 JSON-RPC 2.0 规范和 MCP 扩展定义。
 *
 * @author coffee
 */
public class McpException extends Exception {

    /** JSON-RPC 错误码 */
    private final int errorCode;

    /** 错误消息 */
    private final String errorMessage;

    /**
     * 构造器
     *
     * @param errorCode    JSON-RPC 错误码
     * @param errorMessage 错误消息
     */
    public McpException(int errorCode, String errorMessage) {
        super(errorMessage);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    /**
     * 构造器（带原因异常）
     *
     * @param errorCode    JSON-RPC 错误码
     * @param errorMessage 错误消息
     * @param cause        原因异常
     */
    public McpException(int errorCode, String errorMessage, Throwable cause) {
        super(errorMessage, cause);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    /**
     * 获取 JSON-RPC 错误码
     *
     * @return 错误码
     */
    public int getErrorCode() {
        return errorCode;
    }

    /**
     * 获取错误消息
     *
     * @return 错误消息
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * 转换为 JSON-RPC 错误响应。
     *
     * @param id 请求 ID，可为 null
     * @return 对应的错误响应
     */
    public JsonRpcResponse toResponse(Object id) {
        return JsonRpcResponse.error(id, errorCode, errorMessage);
    }
}
