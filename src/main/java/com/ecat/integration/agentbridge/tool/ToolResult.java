package com.ecat.integration.agentbridge.tool;

/**
 * 工具执行结果，封装 MCP tools/call 的返回数据。
 *
 * <p>三种结果类型：
 * <ul>
 *   <li>同步成功：content 包含响应数据，isError=false，asyncExecutionId=null</li>
 *   <li>异步接受：asyncExecutionId 包含任务 ID，HTTP 202 场景</li>
 *   <li>执行失败：isError=true，content 包含错误信息</li>
 * </ul>
 *
 * @author coffee
 */
public class ToolResult {

    /** 响应内容（成功时为业务数据，失败时为错误信息） */
    private final Object content;

    /** 是否为错误结果 */
    private final boolean isError;

    /** 异步执行 ID，同步操作时为 null */
    private final String asyncExecutionId;

    /** HTTP 状态码 */
    private final int statusCode;

    /**
     * 全参构造器。
     *
     * @param content           响应内容
     * @param isError           是否为错误结果
     * @param asyncExecutionId  异步执行 ID（可为 null）
     * @param statusCode        HTTP 状态码
     */
    public ToolResult(Object content, boolean isError, String asyncExecutionId, int statusCode) {
        this.content = content;
        this.isError = isError;
        this.asyncExecutionId = asyncExecutionId;
        this.statusCode = statusCode;
    }

    /**
     * 创建同步成功结果。
     *
     * @param content 响应数据
     * @return 成功的 ToolResult
     */
    public static ToolResult success(Object content) {
        return new ToolResult(content, false, null, 200);
    }

    /**
     * 创建错误结果。
     *
     * @param message 错误消息
     * @return 错误的 ToolResult
     */
    public static ToolResult error(String message) {
        return new ToolResult(message, true, null, 500);
    }

    /**
     * 创建异步接受结果（HTTP 202 Accepted）。
     *
     * @param asyncExecutionId 异步任务 ID
     * @param statusCode       HTTP 状态码（通常为 202）
     * @return 异步的 ToolResult
     */
    public static ToolResult async(String asyncExecutionId, int statusCode) {
        return new ToolResult(null, false, asyncExecutionId, statusCode);
    }

    /**
     * 获取响应内容。
     *
     * @return 响应内容，异步场景可能为 null
     */
    public Object getContent() {
        return content;
    }

    /**
     * 判断是否为错误结果。
     *
     * @return true 表示执行出错
     */
    public boolean isError() {
        return isError;
    }

    /**
     * 获取异步执行 ID。
     *
     * @return 异步任务 ID，同步操作返回 null
     */
    public String getAsyncExecutionId() {
        return asyncExecutionId;
    }

    /**
     * 获取 HTTP 状态码。
     *
     * @return HTTP 状态码
     */
    public int getStatusCode() {
        return statusCode;
    }
}
