package com.ecat.integration.agentbridge.auth;

import com.ecat.core.ConfigEntry.ConfigEntry;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link AgentAuthManager} 单元测试。
 *
 * <p>核心覆盖点：{@link AgentAuthManager#loadFromEntries} 从 ConfigEntry 的 tokenHash 恢复
 * **有效**（永不过期）token 到 tokenStore，使原始 rawToken 跨重启后仍能通过 validateToken 认证。
 * 此路径此前零覆盖，且修复前会存入已过期占位（expiresAt=0）导致重启后原始 token 永远认证失败。
 *
 * <p>覆盖矩阵：validateToken（有效/无效/已撤销）+ loadFromEntries（恢复/跳过/空入参）+
 * sha256 确定性 + generateToken 永不过期。
 *
 * @author coffee
 */
public class AgentAuthManagerTest {

    /** 通过反射取私有字段（含父类）。测试专用，不污染生产代码。 */
    private static Object readField(Object target, String name) throws Exception {
        Class<?> cls = target.getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    // ==================== validateToken ====================

    /**
     * 有效 token 应返回对应 AgentToken。
     */
    @Test
    public void validateToken_validToken_returnsToken() {
        AgentAuthManager manager = new AgentAuthManager();
        AgentToken generated = manager.generateToken("agent-1", "admin");

        AgentToken validated = manager.validateToken(generated.getToken());

        assertNotNull("有效 token 应返回非 null AgentToken", validated);
        assertEquals(generated, validated);
    }

    /**
     * 未知的 token 字符串应返回 null。
     */
    @Test
    public void validateToken_invalidToken_returnsNull() {
        AgentAuthManager manager = new AgentAuthManager();

        AgentToken validated = manager.validateToken("ecat-agent-doesnotexist0000000000000000");

        assertNull("未知 token 应返回 null", validated);
    }

    /**
     * revokeByTokenHash 后，原 token 应立即失效。
     */
    @Test
    public void validateToken_revokedToken_returnsNull() {
        AgentAuthManager manager = new AgentAuthManager();
        AgentToken generated = manager.generateToken("agent-2", "agent");
        String tokenHash = AgentAuthManager.sha256(generated.getToken());
        assertNotNull("撤销前 token 有效", manager.validateToken(generated.getToken()));

        manager.revokeByTokenHash(tokenHash);

        assertNull("撤销后 token 应失效", manager.validateToken(generated.getToken()));
    }

    // ==================== loadFromEntries（跨重启持久化恢复） ====================

    /**
     * <b>核心回归断言（修复前必失败）</b>：含 tokenHash 的 ConfigEntry 经 loadFromEntries 恢复后，
     * 原始 rawToken 应能通过 validateToken 认证。
     *
     * <p>模拟服务重启：① 创建 token 并算出其 sha256（= 持久化到 entry 的 tokenHash）；
     * ② 新建一个空的 AgentAuthManager（模拟重启后内存 tokenStore 清空）；
     * ③ loadFromEntries([entry]) 恢复；④ 用原始 rawToken 调 validateToken 应命中。
     *
     * <p>修复前：loadFromEntries 存入 expiresAt=0 占位 → isExpired() 恒 true → validateToken
     * 返回 null（且会从 tokenStore 移除该条目）。修复后存入 Long.MAX_VALUE → 永不过期。
     */
    @Test
    public void loadFromEntries_restoresValidToken_validatesAfterRestore() throws Exception {
        // ① 先在一个 manager 上生成 token，模拟"重启前"的状态
        AgentAuthManager beforeRestart = new AgentAuthManager();
        AgentToken generated = beforeRestart.generateToken("persistent-agent", "admin");
        String rawToken = generated.getToken();
        String tokenHash = AgentAuthManager.sha256(rawToken);

        // 构造持久化 entry：data.tokenHash = sha256(rawToken)（与 ConfigFlow.stepFinalConfirm 落盘一致）
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("tokenHash", tokenHash);
        data.put("name", "persistent-agent");
        data.put("role", "admin");
        ConfigEntry entry = new ConfigEntry.Builder()
                .entryId("entry-persist")
                .coordinate("com.ecat:integration-agent-bridge")
                .uniqueId("com.ecat.integrations.agent-bridge.token.persistent-agent")
                .data(data)
                .enabled(true)
                .build();

        // ② 新建空 manager（模拟重启后）
        AgentAuthManager afterRestart = new AgentAuthManager();
        // 重启前原始 token 在新 manager 上不可认证
        assertNull("重启后恢复前，原始 token 不应可认证",
                afterRestart.validateToken(rawToken));

        // ③ loadFromEntries 恢复
        List<ConfigEntry> entries = new ArrayList<ConfigEntry>();
        entries.add(entry);
        afterRestart.loadFromEntries(entries);

        // ④ 核心断言：原始 rawToken 跨重启后仍可认证
        AgentToken restored = afterRestart.validateToken(rawToken);
        assertNotNull("loadFromEntries 后原始 rawToken 应可认证（hash-only 恢复）", restored);
        assertFalse("恢复的 token 不应过期（永不过期）", restored.isExpired());
    }

    /**
     * 缺少 tokenHash 字段的旧版/非 token entry 应被跳过，不抛异常、不影响 tokenStore。
     */
    @Test
    public void loadFromEntries_entryWithoutTokenHash_skipped() {
        AgentAuthManager manager = new AgentAuthManager();
        // 无 tokenHash 字段的 entry
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("name", "legacy-agent");
        ConfigEntry entry = new ConfigEntry.Builder()
                .entryId("entry-legacy")
                .coordinate("com.ecat:integration-agent-bridge")
                .uniqueId("com.ecat.integrations.agent-bridge.token.legacy-agent")
                .data(data)
                .enabled(true)
                .build();

        List<ConfigEntry> entries = new ArrayList<ConfigEntry>();
        entries.add(entry);

        // 不应抛异常
        manager.loadFromEntries(entries);

        // tokenStore 不应被污染：任意 token 仍不可认证
        assertNull("无 tokenHash 的 entry 不应向 tokenStore 注入有效 token",
                manager.validateToken("ecat-agent-anything0000000000000000000"));
    }

    /**
     * null 入参应为空操作，不抛异常。
     */
    @Test
    public void loadFromEntries_nullEntries_noOp() {
        AgentAuthManager manager = new AgentAuthManager();
        manager.loadFromEntries(null); // 不应抛异常
    }

    // ==================== sha256 确定性 ====================

    /**
     * 同一输入的 sha256 应确定性一致（loadFromEntries 与 validateToken 共用此函数作 key）。
     */
    @Test
    public void sha256_deterministic() {
        String input = "ecat-agent-deterministic-test-value";
        String hash1 = AgentAuthManager.sha256(input);
        String hash2 = AgentAuthManager.sha256(input);

        assertEquals("同一输入 sha256 应一致", hash1, hash2);
        // sha256 hex 为 64 个小写字符
        assertEquals(64, hash1.length());
        assertTrue(hash1.equals(hash1.toLowerCase()));
    }

    // ==================== generateToken 永不过期 ====================

    /**
     * generateToken 生成的 token 应永不过期（expiresAt = Long.MAX_VALUE）。
     */
    @Test
    public void generateToken_neverExpires() throws Exception {
        AgentAuthManager manager = new AgentAuthManager();
        AgentToken token = manager.generateToken("non-expiring", "admin");

        assertFalse("生成的 token 不应过期", token.isExpired());

        // 反射确认 expiresAt 字段确为 Long.MAX_VALUE（修复前为 now + 24h TTL）
        long expiresAt = (Long) readField(token, "expiresAt");
        assertEquals("expiresAt 应为 Long.MAX_VALUE（永不过期语义）",
                Long.MAX_VALUE, expiresAt);
    }
}
