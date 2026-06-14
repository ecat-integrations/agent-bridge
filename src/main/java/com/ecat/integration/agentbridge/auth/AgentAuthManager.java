package com.ecat.integration.agentbridge.auth;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.integration.HttpServerIntegration.exchange.EasyHttpExchange;
import com.ecat.integration.agentbridge.mcp.McpAuthenticator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 认证管理器，负责 token 生成、验证和 HTTP 请求认证。
 * <p>
 * 实现 {@link McpAuthenticator} 接口，作为 MCP 服务端的认证入口。
 * Token 以 SHA-256 哈希形式存储在内存和 ConfigEntry 中，原始 token 仅在生成时返回。
 * <p>
 * 认证流程：
 * <ol>
 *   <li>从 Authorization header 提取 Bearer token</li>
 *   <li>回退到 query parameter {@code ?token=xxx}（SSE 场景）</li>
 *   <li>对 token 进行 SHA-256 哈希后与存储的哈希比对</li>
 *   <li>检查 token 是否过期</li>
 * </ol>
 *
 * @author coffee
 */
public class AgentAuthManager implements McpAuthenticator {

    /** Token 前缀 */
    private static final String TOKEN_PREFIX = "ecat-agent-";

    /** Token 随机部分长度（hex 字符数，32 字符 = 16 字节） */
    private static final int TOKEN_RANDOM_LENGTH = 32;

    /** Token 默认有效期：24 小时（毫秒） */
    private static final long DEFAULT_TOKEN_TTL_MS = 24 * 60 * 60 * 1000L;

    /** SecureRandom 实例，用于生成 token 的随机部分 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Token 存储：token hash -> AgentToken */
    private final ConcurrentHashMap<String, AgentToken> tokenStore = new ConcurrentHashMap<String, AgentToken>();

    /** ecat-core-api 内部 session token */
    private volatile String internalToken;

    /**
     * 生成新的 Agent token。
     * <p>
     * Token 格式为 {@code ecat-agent-<32位随机hex>}，生成后以 SHA-256 哈希形式存入 tokenStore。
     *
     * @param agentName   Agent 名称
     * @param role        Agent 角色
     * @param permissions 权限集合
     * @return 生成的 AgentToken（包含原始 token 字符串）
     */
    public AgentToken generateToken(String agentName, String role, Set<String> permissions) {
        if (agentName == null) {
            throw new IllegalArgumentException("agentName must not be null");
        }
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        if (permissions == null) {
            throw new IllegalArgumentException("permissions must not be null");
        }

        // 生成随机 hex 字符串
        byte[] randomBytes = new byte[TOKEN_RANDOM_LENGTH / 2];
        SECURE_RANDOM.nextBytes(randomBytes);
        StringBuilder hexBuilder = new StringBuilder(TOKEN_RANDOM_LENGTH);
        for (byte b : randomBytes) {
            hexBuilder.append(String.format("%02x", b));
        }
        String token = TOKEN_PREFIX + hexBuilder.toString();

        long now = System.currentTimeMillis();
        String agentId = "agent-" + hexBuilder.toString();
        AgentIdentity identity = new AgentIdentity(agentId, agentName, role, permissions);
        AgentToken agentToken = new AgentToken(token, identity, now, now + DEFAULT_TOKEN_TTL_MS);

        // 存储 token hash -> AgentToken
        String tokenHash = sha256(token);
        tokenStore.put(tokenHash, agentToken);

        return agentToken;
    }

    /**
     * 验证 token，返回对应的 AgentIdentity。
     * <p>
     * 对传入的 token 计算 SHA-256 哈希，在 tokenStore 中查找并检查是否过期。
     *
     * @param token 原始 token 字符串
     * @return 对应的 AgentIdentity，如果 token 无效或已过期返回 null
     */
    public AgentIdentity validateToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        String tokenHash = sha256(token);
        AgentToken agentToken = tokenStore.get(tokenHash);
        if (agentToken == null) {
            return null;
        }
        if (agentToken.isExpired()) {
            tokenStore.remove(tokenHash);
            return null;
        }
        return agentToken.getIdentity();
    }

    /**
     * 通过 token hash 撤销 token，使其立即失效。
     *
     * <p>用于删除 token 类型 ConfigEntry 时从内存 tokenStore 移除对应条目，
     * 防止被删除 entry 对应的 token 在过期前仍可认证（安全要求：删除即失效）。
     *
     * <p>{@link ConcurrentHashMap#remove} 语义：hash 不存在时为幂等无操作；
     * 传入 null 时抛 NPE（调用方已保证非 null）。
     *
     * @param tokenHash 要撤销的 token 的 SHA-256 hash
     */
    public void revokeByTokenHash(String tokenHash) {
        tokenStore.remove(tokenHash);
    }

    /**
     * 设置 ecat-core-api 的内部 session token。
     * <p>
     * 此 token 用于 Agent Bridge 向 ecat-core-api 发起请求时的身份认证。
     *
     * @param sessionToken ecat-core-api 的 session token
     */
    public void setInternalToken(String sessionToken) {
        this.internalToken = sessionToken;
    }

    /**
     * 获取 ecat-core-api 的内部 session token。
     *
     * @return session token，如果未设置返回 null
     */
    public String getInternalToken() {
        return this.internalToken;
    }

    /**
     * 从 ConfigEntry 列表加载已保存的 token 哈希。
     * <p>
     * 用于服务重启后恢复已有的 token 认证能力。
     * 每个 ConfigEntry 的 data 中应包含 "tokenHash" 字段。
     * <p>
     * 注意：从 ConfigEntry 恢复的 token 无法获取原始 token 字符串，
     * 仅恢复身份信息用于内部引用，不会使旧的原始 token 重新有效。
     * 调用方需要在重启后重新生成 token。
     *
     * @param entries ConfigEntry 列表
     */
    public void loadFromEntries(List<ConfigEntry> entries) {
        if (entries == null) {
            return;
        }
        for (ConfigEntry entry : entries) {
            Map<String, Object> data = entry.getData();
            if (data == null) {
                continue;
            }
            Object tokenHashObj = data.get("tokenHash");
            if (!(tokenHashObj instanceof String)) {
                continue;
            }
            String tokenHash = (String) tokenHashObj;

            // 从 entry 中恢复身份信息
            String agentId = entry.getUniqueId();
            String name = (String) data.get("name");
            String role = (String) data.get("role");

            Object permsObj = data.get("permissions");
            Set<String> permissions = new HashSet<String>();
            if (permsObj instanceof List) {
                for (Object perm : (List<?>) permsObj) {
                    if (perm != null) {
                        permissions.add(perm.toString());
                    }
                }
            }

            if (agentId == null || name == null || role == null) {
                continue;
            }

            AgentIdentity identity = new AgentIdentity(agentId, name, role, permissions);
            // 恢复的 token 设置为已过期（原始 token 已不可用）
            long createdAt = 0L;
            Object createdAtObj = data.get("createdAt");
            if (createdAtObj instanceof Number) {
                createdAt = ((Number) createdAtObj).longValue();
            }
            // 创建已过期的 AgentToken 作为占位符，保持身份信息可查
            AgentToken restoredToken = new AgentToken(null, identity, createdAt, 0L);
            tokenStore.putIfAbsent(tokenHash, restoredToken);
        }
    }

    /**
     * 通过 Token 字符串认证（用于 SSE 场景）。
     * SSE 使用 SseConnection 而非 EasyHttpExchange，通过此方法完成认证。
     *
     * @param token 认证 token 字符串
     * @return 认证后的 AgentIdentity
     * @throws AuthException token 无效或已过期时抛出（401）
     */
    @Override
    public Object authenticateByToken(String token) throws Exception {
        if (token == null || token.isEmpty()) {
            throw new AuthException(401, "No token provided");
        }
        AgentIdentity identity = validateToken(token);
        if (identity == null) {
            throw new AuthException(401, "Invalid or expired token");
        }
        return identity;
    }

    /**
     * 对 HTTP 请求进行认证。
     * <p>
     * 认证流程：
     * <ol>
     *   <li>从 Authorization header 获取 Bearer token</li>
     *   <li>回退到 query parameter {@code ?token=xxx}（SSE 场景不能设置 header）</li>
     *   <li>验证 token 有效性和过期时间</li>
     * </ol>
     *
     * @param exchange HTTP 交换对象
     * @return 认证后的 {@link AgentIdentity}
     * @throws AuthException 认证失败时抛出（401）
     * @throws Exception     其他异常
     */
    @Override
    public Object authenticate(EasyHttpExchange exchange) throws Exception {
        // 1. 尝试从 Authorization header 获取 token
        String token = null;
        List<String> authHeaders = exchange.getRequestHeaders().get("Authorization");
        if (authHeaders != null && !authHeaders.isEmpty()) {
            String authHeader = authHeaders.get(0);
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                token = authHeader.substring(7).trim();
            }
        }

        // 2. 回退到 query parameter（SSE 场景不能设置 header）
        if (token == null || token.isEmpty()) {
            String query = exchange.getRequestURI().getQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    if (param.startsWith("token=")) {
                        token = param.substring(6);
                        break;
                    }
                }
            }
        }

        // 3. 验证 token
        if (token == null || token.isEmpty()) {
            throw new AuthException(401, "No token provided");
        }
        AgentIdentity identity = validateToken(token);
        if (identity == null) {
            throw new AuthException(401, "Invalid or expired token");
        }
        return identity;
    }

    /**
     * 计算 SHA-256 哈希值，返回 hex 字符串。
     *
     * <p>这是一段纯函数工具（单向哈希），无安全敏感性——哈希值本身就是 ConfigEntry 中持久化的内容。
     * 对外公开以便集成层（如 removeEntry 撤销 token）和测试构造与 tokenStore 一致的 hash。
     *
     * @param input 输入字符串
     * @return SHA-256 hex 字符串（小写）
     */
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to exist in all Java implementations
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
