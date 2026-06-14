package com.ecat.integration.agentbridge.mcp;

import com.ecat.integration.HttpServerIntegration.handler.SseConnection;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 会话管理。
 *
 * <p>每个 Agent 连接对应一个 McpSession 实例。维护 SSE 输出流、
 * 已加载的能力组等状态。
 *
 * <p>v1.2 变更：移除 subscribedEventTypes（截流聚合机制不需要事件订阅过滤）。
 *
 * <p>生命周期：handlePost 创建/获取 -> handleGet 注册 SSE 输出流 -> handleDelete 关闭。
 *
 * @author coffee
 */
public class McpSession {

    /** 会话唯一标识 */
    private final String id;

    /** Agent 标识（由认证层设置） */
    private volatile String agentId;

    /** MCP initialize 是否已完成 */
    private volatile boolean initialized;

    /** 会话是否活跃 */
    private volatile boolean active;

    /** SSE 连接（由 SseHandler GET 回调注册） */
    private volatile SseConnection sseConnection;

    /** 已加载的能力组名称集合 */
    private final Set<String> loadedCapabilityGroups;

    /** 已执行请求的上下文存储（requestId -> context） */
    private final ConcurrentHashMap<String, Object> executedRequests;

    /** 会话创建时间戳 */
    private final long createdAt;

    /**
     * 构造器 — 生成随机 UUID 作为会话 ID
     */
    public McpSession() {
        this(UUID.randomUUID().toString());
    }

    /**
     * 构造器 — 指定会话 ID
     *
     * @param id 会话 ID
     */
    public McpSession(String id) {
        this.id = id;
        this.agentId = null;
        this.initialized = false;
        this.active = true;
        this.sseConnection = null;
        this.loadedCapabilityGroups = Collections.synchronizedSet(new HashSet<String>());
        this.executedRequests = new ConcurrentHashMap<String, Object>();
        this.createdAt = System.currentTimeMillis();
    }

    /**
     * 向此会话的 SSE 连接写入数据。
     *
     * <p>使用 SseConnection.send() 发送，Undertow 自动处理 SSE 帧格式。
     * synchronized 保证多线程写入原子性。
     *
     * <p>如果会话没有 SSE 连接（sseConnection == null），静默返回不抛异常。
     * Agent 可能只做 POST 操作不建立 GET SSE 连接，这不应被视为错误。
     *
     * @param sseData SSE data 内容（不含 "data: " 前缀）
     * @throws IOException 会话不活跃或 SSE 连接已关闭时抛出
     */
    public synchronized void sendSseEvent(String sseData) throws IOException {
        if (!active) {
            throw new IOException("Session is not active: " + id);
        }
        SseConnection conn = sseConnection;
        if (conn == null) {
            // 没有 SSE 连接，静默跳过（Agent 可能只做 POST 操作）
            return;
        }
        if (!conn.isOpen()) {
            throw new IOException("SSE connection closed for session: " + id);
        }
        conn.send(sseData);
    }

    /**
     * 关闭会话，释放资源。
     *
     * <p>设置 active=false，清空 SSE 连接引用。不主动关闭 SseConnection，
     * 由 SseHandler 的 onClose 回调负责。
     */
    public void close() {
        active = false;
        sseConnection = null;
        loadedCapabilityGroups.clear();
        executedRequests.clear();
    }

    // ===== Getter / Setter =====

    /**
     * 获取会话 ID
     *
     * @return 会话唯一标识
     */
    public String getId() {
        return id;
    }

    /**
     * 获取 Agent 标识
     *
     * @return Agent 标识，未认证时为 null
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * 设置 Agent 标识
     *
     * @param agentId Agent 标识
     */
    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    /**
     * 判断 MCP initialize 是否已完成
     *
     * @return true 表示已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 设置 MCP initialize 完成状态
     *
     * @param initialized 是否已初始化
     */
    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }

    /**
     * 判断会话是否活跃
     *
     * @return true 表示活跃
     */
    public boolean isActive() {
        return active;
    }

    /**
     * 注册 SSE 连接（由 SseHandler GET 回调调用）
     *
     * @param sseConnection SSE 连接
     */
    public void setSseConnection(SseConnection sseConnection) {
        this.sseConnection = sseConnection;
    }

    /**
     * 获取已加载的能力组名称集合
     *
     * @return 能力组名称集合（线程安全）
     */
    public Set<String> getLoadedCapabilityGroups() {
        return loadedCapabilityGroups;
    }

    /**
     * 获取已执行请求的上下文存储
     *
     * @return 请求上下文 Map（线程安全）
     */
    public ConcurrentHashMap<String, Object> getExecutedRequests() {
        return executedRequests;
    }

    /**
     * 获取会话创建时间戳
     *
     * @return 创建时间戳（毫秒）
     */
    public long getCreatedAt() {
        return createdAt;
    }
}
