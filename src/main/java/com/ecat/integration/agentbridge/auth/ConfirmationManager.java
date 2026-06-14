package com.ecat.integration.agentbridge.auth;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 确认请求管理器，用于高风险操作的人工确认流程。
 * <p>
 * 当 Agent 请求执行高风险操作（如设备配置变更、系统重启等）时，
 * 先创建一个确认请求，等待人工确认后才执行。
 * 确认请求支持超时自动过期。
 * <p>
 * 线程安全：内部使用 {@link ConcurrentHashMap} 和 {@link ScheduledExecutorService}。
 *
 * @author coffee
 */
public class ConfirmationManager {

    /** 默认确认请求超时时间：300 秒 */
    private static final long DEFAULT_TIMEOUT_SECONDS = 300L;

    /** 待处理的确认请求：confirmationId -> ConfirmationRequest */
    private final ConcurrentHashMap<String, ConfirmationRequest> pendingRequests =
            new ConcurrentHashMap<String, ConfirmationRequest>();

    /** 审计日志桥接（可能为 null，表示不记录审计日志） */
    private final AuditLoggerBridge auditLogger;

    /** 超时调度器 */
    private final ScheduledExecutorService scheduler;

    /** 确认请求超时时间（秒） */
    private final long timeoutSeconds;

    /**
     * 使用默认超时时间（300 秒）构造 ConfirmationManager。
     *
     * @param auditLogger 审计日志桥接，可以为 null
     */
    public ConfirmationManager(AuditLoggerBridge auditLogger) {
        this(auditLogger, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * 构造 ConfirmationManager。
     *
     * @param auditLogger     审计日志桥接，可以为 null
     * @param timeoutSeconds  确认请求超时时间（秒），必须大于 0
     */
    public ConfirmationManager(AuditLoggerBridge auditLogger, long timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be greater than 0, got: " + timeoutSeconds);
        }
        this.auditLogger = auditLogger;
        this.timeoutSeconds = timeoutSeconds;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
            private final java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "confirmation-timeout-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    /**
     * 创建确认请求。
     * <p>
     * 生成唯一的 confirmationId，设置创建时间和过期时间，
     * 并调度超时任务使其自动过期。
     *
     * @param toolName    工具名称，不能为 null
     * @param params      工具参数，不能为 null
     * @param requesterId 请求者 ID，不能为 null
     * @return 创建的 ConfirmationRequest
     */
    public ConfirmationRequest createConfirmationRequest(String toolName, Map<String, Object> params,
                                                          String requesterId) {
        if (toolName == null) {
            throw new IllegalArgumentException("toolName must not be null");
        }
        if (params == null) {
            throw new IllegalArgumentException("params must not be null");
        }
        if (requesterId == null) {
            throw new IllegalArgumentException("requesterId must not be null");
        }

        String confirmationId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long expiresAt = now + timeoutSeconds * 1000L;

        ConfirmationRequest request = new ConfirmationRequest(
                confirmationId, toolName, params, requesterId, now, expiresAt);

        pendingRequests.put(confirmationId, request);

        // 调度超时任务
        scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                ConfirmationRequest req = pendingRequests.get(confirmationId);
                if (req != null && req.getStatus() == ConfirmationStatus.PENDING) {
                    req.setStatus(ConfirmationStatus.EXPIRED);
                    if (auditLogger != null) {
                        auditLogger.logConfirmation(confirmationId, "expired", toolName, requesterId);
                    }
                    pendingRequests.remove(confirmationId);
                }
            }
        }, timeoutSeconds, TimeUnit.SECONDS);

        if (auditLogger != null) {
            auditLogger.logConfirmation(confirmationId, "created", toolName, requesterId);
        }

        return request;
    }

    /**
     * 确认通过。
     * <p>
     * 将请求状态从 PENDING 变更为 CONFIRMED，并从待处理列表中移除。
     *
     * @param confirmationId 确认请求 ID
     * @return 如果确认成功返回 true；如果请求不存在、已过期、已被确认或拒绝返回 false
     */
    public boolean confirm(String confirmationId) {
        if (confirmationId == null) {
            throw new IllegalArgumentException("confirmationId must not be null");
        }
        ConfirmationRequest request = pendingRequests.get(confirmationId);
        if (request == null) {
            return false;
        }
        if (request.getStatus() != ConfirmationStatus.PENDING) {
            return false;
        }
        request.setStatus(ConfirmationStatus.CONFIRMED);
        pendingRequests.remove(confirmationId);

        if (auditLogger != null) {
            auditLogger.logConfirmation(confirmationId, "confirmed", request.getToolName(), request.getRequesterId());
        }
        return true;
    }

    /**
     * 拒绝确认请求。
     * <p>
     * 将请求状态从 PENDING 变更为 REJECTED，并从待处理列表中移除。
     *
     * @param confirmationId 确认请求 ID
     * @return 如果拒绝成功返回 true；如果请求不存在、已过期、已被确认或拒绝返回 false
     */
    public boolean reject(String confirmationId) {
        if (confirmationId == null) {
            throw new IllegalArgumentException("confirmationId must not be null");
        }
        ConfirmationRequest request = pendingRequests.get(confirmationId);
        if (request == null) {
            return false;
        }
        if (request.getStatus() != ConfirmationStatus.PENDING) {
            return false;
        }
        request.setStatus(ConfirmationStatus.REJECTED);
        pendingRequests.remove(confirmationId);

        if (auditLogger != null) {
            auditLogger.logConfirmation(confirmationId, "rejected", request.getToolName(), request.getRequesterId());
        }
        return true;
    }

    /**
     * 查询确认请求。
     *
     * @param confirmationId 确认请求 ID
     * @return 对应的 ConfirmationRequest，如果不存在返回 null
     */
    public ConfirmationRequest getRequest(String confirmationId) {
        if (confirmationId == null) {
            return null;
        }
        return pendingRequests.get(confirmationId);
    }

    /**
     * 关闭管理器，释放调度器资源。
     */
    public void shutdown() {
        scheduler.shutdownNow();
    }

    // ===== 内部类 =====

    /**
     * 确认请求状态枚举。
     */
    public enum ConfirmationStatus {
        /** 等待确认 */
        PENDING,
        /** 已确认 */
        CONFIRMED,
        /** 已拒绝 */
        REJECTED,
        /** 已过期 */
        EXPIRED
    }

    /**
     * 确认请求实体，包含请求信息和状态。
     */
    public static class ConfirmationRequest {

        /** 确认请求唯一 ID */
        private final String confirmationId;

        /** 关联的工具名称 */
        private final String toolName;

        /** 工具参数 */
        private final Map<String, Object> params;

        /** 请求者 ID */
        private final String requesterId;

        /** 创建时间（epoch 毫秒） */
        private final long createdAt;

        /** 过期时间（epoch 毫秒） */
        private final long expiresAt;

        /** 当前状态 */
        private volatile ConfirmationStatus status;

        /**
         * 构造确认请求。
         *
         * @param confirmationId 唯一 ID
         * @param toolName       工具名称
         * @param params         工具参数
         * @param requesterId    请求者 ID
         * @param createdAt      创建时间（epoch 毫秒）
         * @param expiresAt      过期时间（epoch 毫秒）
         */
        ConfirmationRequest(String confirmationId, String toolName, Map<String, Object> params,
                            String requesterId, long createdAt, long expiresAt) {
            this.confirmationId = confirmationId;
            this.toolName = toolName;
            this.params = params;
            this.requesterId = requesterId;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.status = ConfirmationStatus.PENDING;
        }

        /**
         * 获取确认请求 ID。
         *
         * @return confirmationId
         */
        public String getConfirmationId() {
            return confirmationId;
        }

        /**
         * 获取关联的工具名称。
         *
         * @return toolName
         */
        public String getToolName() {
            return toolName;
        }

        /**
         * 获取工具参数。
         *
         * @return 参数 Map
         */
        public Map<String, Object> getParams() {
            return params;
        }

        /**
         * 获取请求者 ID。
         *
         * @return requesterId
         */
        public String getRequesterId() {
            return requesterId;
        }

        /**
         * 获取创建时间。
         *
         * @return 创建时间（epoch 毫秒）
         */
        public long getCreatedAt() {
            return createdAt;
        }

        /**
         * 获取过期时间。
         *
         * @return 过期时间（epoch 毫秒）
         */
        public long getExpiresAt() {
            return expiresAt;
        }

        /**
         * 获取当前状态。
         *
         * @return ConfirmationStatus
         */
        public ConfirmationStatus getStatus() {
            return status;
        }

        /**
         * 设置状态（内部使用）。
         *
         * @param status 新状态
         */
        void setStatus(ConfirmationStatus status) {
            this.status = status;
        }
    }
}
