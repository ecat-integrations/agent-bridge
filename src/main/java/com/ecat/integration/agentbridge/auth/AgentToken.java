package com.ecat.integration.agentbridge.auth;

/**
 * Agent 认证令牌，包含 token 字符串与有效期。
 * <p>
 * Token 存储时仅保留 SHA-256 哈希值，原始 token 不持久化（hash-only）。
 * 每次生成 token 时返回原始值给调用方，后续验证通过哈希比对完成。
 *
 * <p><b>永不过期</b>：持久化后的 token 跨重启持续有效，直至显式删除 ConfigEntry 或被 revoke
 * （{@link AgentAuthManager#revokeByTokenHash}）。{@code expiresAt} 统一取 {@link Long#MAX_VALUE}，
 * {@link #isExpired()} 恒为 false。这是 {@link AgentAuthManager#loadFromEntries} 能从 ConfigEntry
 * 恢复有效 token 的前提——配合 hash-only 落盘，原始 token 不需持久化即可在重启后继续通过验证。
 *
 * <p>注：原关联的 {@code AgentIdentity}（role/permissions 身份模型）已移除——
 * 权限在执行路径上从不被消费，保留身份载体仅是无效开销。详见 {@link AgentAuthManager} 类注释。
 *
 * @author coffee
 */
public class AgentToken {

    /**
     * Token 字符串。
     * <p>新生成的 token 不为 null；从 ConfigEntry 恢复时可为 null——
     * {@link AgentAuthManager#validateToken} 不读取本字段（仅按 {@code sha256(传入 token)} 作 key
     * 查 tokenStore），故恢复时无需原始 token 字符串。
     */
    private final String token;

    /**
     * Token 过期时间（epoch 毫秒）。
     * <p>永不过期语义下统一取 {@link Long#MAX_VALUE}（{@link AgentAuthManager#generateToken}
     * 与 {@link AgentAuthManager#loadFromEntries} 均传此值）。
     */
    private final long expiresAt;

    /**
     * 构造 AgentToken。
     *
     * @param token     原始 token 字符串，新生成的 token 不为 null；
     *                  从 ConfigEntry 恢复时可为 null（{@code validateToken} 不读取本字段，
     *                  仅按 hash 索引，hash-only 落盘无需原始 token）
     * @param expiresAt 过期时间（epoch 毫秒）；永不过期语义下统一取 {@link Long#MAX_VALUE}
     */
    public AgentToken(String token, long expiresAt) {
        this.token = token;
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
     * 检查 Token 是否已过期。
     *
     * @return 如果当前时间超过 expiresAt 返回 true，否则 false
     */
    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }
}
