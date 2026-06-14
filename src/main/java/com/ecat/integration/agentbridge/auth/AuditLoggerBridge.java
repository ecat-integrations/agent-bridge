package com.ecat.integration.agentbridge.auth;

/**
 * 审计日志桥接接口，供 ConfirmationManager 记录确认操作日志。
 * <p>
 * 此接口定义在 auth 包中，避免 auth 包对 audit 包的直接依赖。
 * 实际实现由 Agent 3D 的 AuditLogger 通过适配器模式提供。
 *
 * @author coffee
 */
public interface AuditLoggerBridge {

    /**
     * 记录确认操作日志。
     *
     * @param confirmationId 确认请求 ID
     * @param action         操作类型（如 confirmed、rejected、expired、created）
     * @param toolName       关联的工具名称
     * @param requesterId    请求者 ID
     */
    void logConfirmation(String confirmationId, String action, String toolName, String requesterId);
}
