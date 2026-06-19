package com.ecat.integration.agentbridge.mcp;

import java.util.UUID;

/**
 * MCP 会话标识。
 *
 * <p>每个 Agent 连接对应一个 McpSession 实例，持有会话唯一标识 + 当前请求的 Host。
 * 会话生命周期：handlePost 创建/获取 -> handleDelete 关闭。
 *
 * <p>BCP 重构后移除了原 v1 会话累积状态（SSE 连接、agentId、
 * loadedCapabilityGroups、executedRequests、initialized/active 标记、createdAt）——
 * 这些字段在两工具架构下均无消费者（write-only）。当前保留：
 * <ul>
 *   <li>{@code id}：{@code Mcp-Session-Id} header 与 {@link McpServer} 会话 Map 的键</li>
 *   <li>{@code requestHostPort}：当前请求 Host header 的 host:port，供进程内工具
 *       （如 media get-download-url）拼装对 agent 可达的下载 URL。非 write-only——
 *       有明确消费者，故保留（区别于被移除的 v1 累积状态）。每次 handlePost 刷新。</li>
 * </ul>
 *
 * @author coffee
 */
public class McpSession {

    /** 会话唯一标识 */
    private final String id;

    /** 当前请求 Host header 的 host:port（每次 handlePost 刷新），供进程内工具拼装下载 URL */
    private String requestHostPort;

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
    }

    /**
     * 获取会话 ID
     *
     * @return 会话唯一标识
     */
    public String getId() {
        return id;
    }

    /**
     * 设置当前请求 Host header 的 host:port（由 McpServer.handlePost 从请求头提取后写入）。
     *
     * @param requestHostPort host:port，无 Host 头时为 null（进程内工具自行回退 baseUrl）
     */
    public void setRequestHostPort(String requestHostPort) {
        this.requestHostPort = requestHostPort;
    }

    /**
     * 获取当前请求 Host header 的 host:port，供进程内工具拼装下载 URL。
     *
     * @return host:port，无 Host 头时为 null
     */
    public String getRequestHostPort() {
        return requestHostPort;
    }

    /**
     * 关闭会话。
     *
     * <p>当前会话无可释放资源（移除 SSE 连接与累积状态后），保留方法供
     * {@link McpServer} 生命周期统一调用，便于后续按需扩展清理逻辑。
     */
    public void close() {
        // no per-session resources to release
    }
}
