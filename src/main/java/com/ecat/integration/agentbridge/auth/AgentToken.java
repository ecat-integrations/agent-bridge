package com.ecat.integration.agentbridge.auth;

/**
 * Agent 认证令牌，包含 token 字符串、关联的身份信息和有效期。
 * <p>
 * Token 存储时仅保留 SHA-256 哈希值，原始 token 不持久化。
 * 每次生成 token 时返回原始值给调用方，后续验证通过哈希比对完成。
 *
 * @author coffee
 */
public class AgentToken {

    /** Token 字符串（仅存在于内存，不持久化） */
    private final String token;

    /** 关联的 Agent 身份信息 */
    private final AgentIdentity identity;

    /** Token 创建时间（epoch 毫秒） */
    private final long createdAt;

    /** Token 过期时间（epoch 毫秒） */
    private final long expiresAt;

    /**
     * 构造 AgentToken。
     *
     * @param token     原始 token 字符串，新生成的 token 不为 null，
     *                  从 ConfigEntry 恢复时可为 null（仅保留身份信息）
     * @param identity  关联的身份信息，不能为 null
     * @param createdAt 创建时间（epoch 毫秒）
     * @param expiresAt 过期时间（epoch 毫秒）
     */
    public AgentToken(String token, AgentIdentity identity, long createdAt, long expiresAt) {
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        this.token = token;
        this.identity = identity;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /**
     * 获取原始 token 字符串。
     *
     * @return token 字符串
     */
    public String getToken() {
        return token;
    }

    /**
     * 获取关联的 Agent 身份信息。
     *
     * @return AgentIdentity
     */
    public AgentIdentity getIdentity() {
        return identity;
    }

    /**
     * 获取 Token 创建时间。
     *
     * @return 创建时间（epoch 毫秒）
     */
    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * 获取 Token 过期时间。
     *
     * @return 过期时间（epoch 毫秒）
     */
    public long getExpiresAt() {
        return expiresAt;
    }

    /**
     * 检查 Token 是否已过期。
     *
     * @return 如果当前时间超过 expiresAt 返回 true，否则返回 false
     */
    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }
}
