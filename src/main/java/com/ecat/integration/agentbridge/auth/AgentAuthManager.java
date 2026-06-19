package com.ecat.integration.agentbridge.auth;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.integration.HttpServerIntegration.exchange.EasyHttpExchange;
import com.ecat.integration.agentbridge.mcp.McpAuthenticator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
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
 *   <li>回退到 query parameter {@code ?token=xxx}（无 header 客户端场景）</li>
 *   <li>对 token 进行 SHA-256 哈希后与存储的哈希比对</li>
 *   <li>检查 token 是否过期</li>
 * </ol>
 *
 * <p>注：原 role/permissions 身份模型（AgentIdentity）已移除——权限在执行路径上从不被消费，
 * 即任何有效 token 都可调用全部工具。如需分级授权，应在 ToolDispatcher 落地真正的权限校验后再恢复身份载体。
 *
 * @author coffee
 */
public class AgentAuthManager implements McpAuthenticator {

    /** Token 前缀 */
    private static final String TOKEN_PREFIX = "ecat-agent-";

    /** Token 随机部分长度（hex 字符数，32 字符 = 16 字节） */
    private static final int TOKEN_RANDOM_LENGTH = 32;

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
     * <p>永不过期：token 创建后持续有效，直至显式删除对应 ConfigEntry 或被
     * {@link #revokeByTokenHash} 撤销。配合 ConfigFlow 持久化的 tokenHash，token 可跨重启续存。
     *
     * @param agentName Agent 名称（仅作生成日志/标识用途，不嵌入 token）
     * @param role      Agent 角色（仅作元数据记录，当前不参与授权决策）
     * @return 生成的 AgentToken（包含原始 token 字符串）
     */
    public AgentToken generateToken(String agentName, String role) {
        if (agentName == null) {
            throw new IllegalArgumentException("agentName must not be null");
        }
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }

        // 生成随机 hex 字符串
        byte[] randomBytes = new byte[TOKEN_RANDOM_LENGTH / 2];
        SECURE_RANDOM.nextBytes(randomBytes);
        StringBuilder hexBuilder = new StringBuilder(TOKEN_RANDOM_LENGTH);
        for (byte b : randomBytes) {
            hexBuilder.append(String.format("%02x", b));
        }
        String token = TOKEN_PREFIX + hexBuilder.toString();

        // 永不过期：expiresAt = Long.MAX_VALUE，配合 hash-only 持久化实现跨重启续存
        AgentToken agentToken = new AgentToken(token, Long.MAX_VALUE);

        // 存储 token hash -> AgentToken
        String tokenHash = sha256(token);
        tokenStore.put(tokenHash, agentToken);

        return agentToken;
    }

    /**
     * 验证 token，返回对应的 AgentToken。
     * <p>
     * 对传入的 token 计算 SHA-256 哈希，在 tokenStore 中查找并检查是否过期。
     *
     * @param token 原始 token 字符串
     * @return 对应的 AgentToken，如果 token 无效或已过期返回 null
     */
    public AgentToken validateToken(String token) {
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
        return agentToken;
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
     * 从 ConfigEntry 列表恢复持久化的 token 到 tokenStore。
     * <p>
     * 用于服务重启后恢复 token 有效性：把每个 ConfigEntry data 中的 "tokenHash" 字段作为 key，
     * 存入一个永不过期（{@link AgentToken} token 字段为 null，{@link AgentAuthManager#validateToken}
     * 不读取该字段，仅按 {@code sha256(传入 token)} 作 key 索引）的 AgentToken。
     * <p>
     * <b>hash-only 恢复原理</b>：原始 rawToken 不持久化（安全考虑，不可逆），但
     * {@link #validateToken} 本就按 {@code sha256(传入 token)} 查 tokenStore——而 entry 中的
     * tokenHash 正是创建时 {@code sha256(rawToken)} 的值。故重启后调用方用同一个原始 token 调
     * {@code validateToken}，计算出的 hash 命中此处恢复的 key，即可认证通过。
     * <p>
     * {@code putIfAbsent}：避免覆盖已在内存中（本次启动 generateToken 生成）的同 hash token。
     * data 中无 "tokenHash" 字段（旧版 entry 或非 token entry）的条目被跳过，不抛异常。
     *
     * @param entries ConfigEntry 列表，可为 null
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

            // 恢复有效（永不过期）的 AgentToken。token 字段传 null——validateToken 不读取它，
            // 仅按 sha256(传入 token) 作 key 索引，而该 key 正是此处 put 的 tokenHash。
            AgentToken restoredToken = new AgentToken(null, Long.MAX_VALUE);
            tokenStore.putIfAbsent(tokenHash, restoredToken);
        }
    }

    /**
     * 对 HTTP 请求进行认证。
     * <p>
     * 认证流程：
     * <ol>
     *   <li>从 Authorization header 获取 Bearer token</li>
     *   <li>回退到 query parameter {@code ?token=xxx}（无 header 客户端场景）</li>
     *   <li>验证 token 有效性和过期时间</li>
     * </ol>
     *
     * <p>校验通过即正常返回；失败时抛出 {@link AuthException}（401），
     * 由 {@code McpServer} 在 handler 内捕获并写出 HTTP 错误响应（Bug #1）。
     *
     * @param exchange HTTP 交换对象
     * @throws AuthException 认证失败时抛出（401）
     * @throws Exception     其他异常
     */
    @Override
    public void authenticate(EasyHttpExchange exchange) throws Exception {
        // 1. 尝试从 Authorization header 获取 token
        String token = null;
        List<String> authHeaders = exchange.getRequestHeaders().get("Authorization");
        if (authHeaders != null && !authHeaders.isEmpty()) {
            String authHeader = authHeaders.get(0);
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                token = authHeader.substring(7).trim();
            }
        }

        // 2. 回退到 query parameter（无 header 客户端场景）
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
        AgentToken agentToken = validateToken(token);
        if (agentToken == null) {
            throw new AuthException(401, "Invalid or expired token");
        }
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
