package com.ecat.integration.agentbridge;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.ConfigEntry.ConfigEntryRegistry;
import com.ecat.core.EcatCore;
import com.ecat.integration.agentbridge.auth.AgentAuthManager;
import com.ecat.integration.agentbridge.auth.AgentToken;
import com.ecat.integration.agentbridge.config.AgentBridgeConfigFlow;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentBridgeIntegration#issueManagedToken} 与
 * {@link AgentBridgeIntegration#revokeManagedToken} 单元测试。
 *
 * <p>托管 token（MANAGED source）由集成代码（如 llm-agent）以编程方式调用 issueManagedToken 获取，
 * 区别于 ConfigFlow 向导创建的 USER source token。本测试验证：
 * <ol>
 *   <li>签发：返回含原始 token 的 AgentToken，并在 registry 中创建 owner=调用方、source=MANAGED 的 entry，
 *       tokenHash 落盘以便重启后恢复</li>
 *   <li>撤销：按 tokenHash 失效 token 并删除对应 entry</li>
 *   <li>未 onLoad 完成时调用签发抛 IllegalStateException（防止半初始化状态写出无主 entry）</li>
 * </ol>
 *
 * <p>反射注入 core（父类 IntegrationBase）与 authManager（本类）字段——onLoad 完整链路依赖
 * integration-httpserver，单测无法走完，故直接注入。复用 RemoveEntryTest 的 setField 模式。
 *
 * @author coffee
 */
public class AgentBridgeManagedTokenTest {

    private AgentBridgeIntegration integration;
    private EcatCore core;
    private ConfigEntryRegistry registry;
    private AgentAuthManager authManager;

    /** 通过反射设置字段（含父类继承字段，如 IntegrationBase.core）。测试专用，不污染生产代码。 */
    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> cls = target.getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** 通过反射读取字段（含父类继承字段）。与 setField 对称，用于验证 ownerLocks 结构。 */
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

    @Before
    public void setUp() throws Exception {
        integration = new AgentBridgeIntegration();
        core = mock(EcatCore.class);
        registry = mock(ConfigEntryRegistry.class);
        authManager = new AgentAuthManager();
        when(core.getEntryRegistry()).thenReturn(registry);
        // 反射注入 core（父类 IntegrationBase）与 authManager（本类）——onLoad 未走完整链路
        setField(integration, "core", core);
        setField(integration, "authManager", authManager);
    }

    /**
     * 签发托管 token：返回原始 token 且 registry 中创建 owner/source=MANAGED 的 entry，tokenHash 落盘。
     */
    @Test
    public void issueManagedToken_returnsRawTokenAndCreatesOwnedEntry() {
        String owner = "com.ecat:integration-llm-agent";
        AgentToken token = integration.issueManagedToken(owner, "hermes");
        assertNotNull("签发后应返回非 null token", token);
        assertTrue("原始 token 应有 ecat-agent- 前缀",
                token.getToken().startsWith("ecat-agent-"));
        assertNotNull("签发后 token 应立即可认证",
                authManager.validateToken(token.getToken()));

        ArgumentCaptor<ConfigEntry> captor = ArgumentCaptor.forClass(ConfigEntry.class);
        verify(registry).createEntry(captor.capture());
        ConfigEntry entry = captor.getValue();
        assertEquals("entry 归属 agent-bridge 集成",
                "com.ecat:integration-agent-bridge", entry.getCoordinate());
        assertEquals("owner 字段记录调用方集成",
                owner, entry.getData().get("owner"));
        assertEquals("source=MANAGED 标识托管来源（区别于向导创建的 USER token）",
                TokenEntrySource.MANAGED.name(), entry.getData().get("source"));
        assertEquals("name 字段同步 owner 便于运维识别",
                owner, entry.getData().get("name"));
        assertNotNull("tokenHash 必须落盘（重启后据此恢复有效 token）",
                entry.getData().get("tokenHash"));
        assertNotNull("uniqueId 必须设置", entry.getUniqueId());
        assertTrue("uniqueId 应以 token 前缀 + managed- 开头（区别于向导 token）",
                entry.getUniqueId().startsWith(AgentBridgeConfigFlow.UNIQUE_ID_PREFIX + "managed-"));
    }

    /**
     * T1:upsert MISS——本集成无该 owner 的 managed entry 时,走 createEntry 分支,
     * uniqueId 确定性派生自 owner(= 前缀 + owner,无 hash 后缀)。
     *
     * <p>托管 token 唯一性由 {@code issueManagedToken} 收口(一 owner 一 entry):
     * uniqueId 不再拼 tokenHash,改为确定性 f(owner),使 registry 的 uniqueId 约束成为二道物理防线
     * (即便 scan 漏判,两次同 owner create 也会撞 DuplicateUniqueIdException)。
     */
    @Test
    public void issueManagedToken_noExistingEntry_createsWithDeterministicUniqueId() {
        String owner = "com.ecat:integration-llm-agent";
        // scan:本集成无任何 managed entry(空列表)
        when(registry.listByCoordinate("com.ecat:integration-agent-bridge"))
                .thenReturn(Collections.<ConfigEntry>emptyList());

        AgentToken token = integration.issueManagedToken(owner, "hermes");

        assertNotNull("签发后 token 应立即可认证", authManager.validateToken(token.getToken()));

        ArgumentCaptor<ConfigEntry> captor = ArgumentCaptor.forClass(ConfigEntry.class);
        verify(registry).createEntry(captor.capture());
        ConfigEntry entry = captor.getValue();
        assertEquals("upsert MISS 走 create,uniqueId 确定性派生自 owner(无 hash 后缀)",
                AgentBridgeConfigFlow.UNIQUE_ID_PREFIX + "managed-" + owner,
                entry.getUniqueId());
        assertEquals("owner 字段记录调用方集成", owner, entry.getData().get("owner"));
        assertEquals("source=MANAGED 标识托管来源",
                TokenEntrySource.MANAGED.name(), entry.getData().get("source"));
        // MISS 分支不得 updateEntry
        verify(registry, never()).updateEntry(anyString(), any(ConfigEntry.class));
    }

    /**
     * T2:upsert HIT——同 owner 已有 managed entry(旧 hash-后缀格式)时,走 updateEntry 轮换:
     * 旧 token 立即撤销、新 token 即时有效、entry 数仍为 1(不 createEntry)。
     *
     * <p>预置一条旧格式 entry(uniqueId 含 hash 后缀,模拟磁盘残留)+ 其 tokenHash 已在 authManager,
     * 验证 owner-scan 能命中旧格式 entry 并轮换(这是 owner-scan 而非 getByUniqueId 的核心价值)。
     */
    @Test
    public void issueManagedToken_existingManagedEntryForOwner_updateEntryAndRotatesToken() {
        String owner = "com.ecat:integration-llm-agent";
        // 预置:authManager 生成一条旧 token,模拟已存在的 managed entry 的凭证
        AgentToken oldToken = authManager.generateToken(owner, "hermes");
        String oldRaw = oldToken.getToken();
        String oldHash = AgentAuthManager.sha256(oldRaw);
        assertNotNull("预置旧 token 应可认证", authManager.validateToken(oldRaw));

        // 旧格式 entry(uniqueId 含 hash 后缀,模拟磁盘残留)
        ConfigEntry existing = mock(ConfigEntry.class);
        when(existing.getEntryId()).thenReturn("e1");
        when(existing.getUniqueId()).thenReturn(
                AgentBridgeConfigFlow.UNIQUE_ID_PREFIX + "managed-" + owner + "-" + oldHash.substring(0, 8));
        Map<String, Object> existingData = new HashMap<>();
        existingData.put("owner", owner);
        existingData.put("source", TokenEntrySource.MANAGED.name());
        existingData.put("tokenHash", oldHash);
        when(existing.getData()).thenReturn(existingData);
        when(registry.listByCoordinate("com.ecat:integration-agent-bridge"))
                .thenReturn(Collections.singletonList(existing));

        AgentToken newToken = integration.issueManagedToken(owner, "hermes");
        String newRaw = newToken.getToken();

        assertNotNull("新 token 应立即可认证", authManager.validateToken(newRaw));
        assertNull("旧 token 应已被撤销(revokeByTokenHash)", authManager.validateToken(oldRaw));

        verify(registry, never()).createEntry(any(ConfigEntry.class));
        ArgumentCaptor<ConfigEntry> captor = ArgumentCaptor.forClass(ConfigEntry.class);
        verify(registry).updateEntry(eq("e1"), captor.capture());
        ConfigEntry updated = captor.getValue();
        assertEquals("轮换写入新 tokenHash", AgentAuthManager.sha256(newRaw),
                updated.getData().get("tokenHash"));
        assertEquals("owner 保留", owner, updated.getData().get("owner"));
        assertEquals("source 保留 MANAGED", TokenEntrySource.MANAGED.name(),
                updated.getData().get("source"));
    }

    /**
     * T3:upsert HIT 时传给 updateEntry 的 ConfigEntry 必须显式 enabled=true。
     *
     * <p>守 {@code ConfigEntry.withUpdate} 的 enabled 无条件覆盖陷阱:withUpdate 做 {@code .enabled(newData.enabled)},
     * 若调用方漏 setEnabled(true),primitive boolean 默认 false 会把 live entry 改成 disabled。
     * (测试用 mock registry 不走 withUpdate,故直接断言传入参数的 enabled=true——这正是让真实 withUpdate 安全的契约。)
     */
    @Test
    public void issueManagedToken_updateEntry_passesEnabledTrue() {
        String owner = "com.ecat:integration-llm-agent";
        AgentToken oldToken = authManager.generateToken(owner, "hermes");
        String oldHash = AgentAuthManager.sha256(oldToken.getToken());
        ConfigEntry existing = mock(ConfigEntry.class);
        when(existing.getEntryId()).thenReturn("e1");
        Map<String, Object> existingData = new HashMap<>();
        existingData.put("owner", owner);
        existingData.put("source", TokenEntrySource.MANAGED.name());
        existingData.put("tokenHash", oldHash);
        when(existing.getData()).thenReturn(existingData);
        when(registry.listByCoordinate("com.ecat:integration-agent-bridge"))
                .thenReturn(Collections.singletonList(existing));

        integration.issueManagedToken(owner, "hermes");

        ArgumentCaptor<ConfigEntry> captor = ArgumentCaptor.forClass(ConfigEntry.class);
        verify(registry).updateEntry(eq("e1"), captor.capture());
        assertTrue("upsert 传给 updateEntry 的 entry 必须 enabled=true "
                        + "(withUpdate 无条件覆盖 enabled,漏设会把 live entry 改 disabled)",
                captor.getValue().isEnabled());
    }

    /**
     * T4:per-owner 锁结构——ownerLocks 按 owner 分键,不同 owner 落不同锁、同 owner 复用。
     *
     * <p>序列化语义由 JVM {@code synchronized} 保证(语言级,不测时序);本测试用确定性结构断言守:
     * ownerLocks 字段存在 + 不同 owner 用不同锁对象(防退化为全局单锁)。避开 mvnd 并行下
     * timing 类并发断言的 flaky(Iron Law:latch/结构 > sleep/时序)。
     */
    @Test
    public void issueManagedToken_ownerLocks_perOwnerDistinct() throws Exception {
        String ownerA = "com.ecat:integration-owner-a";
        String ownerB = "com.ecat:integration-owner-b";
        when(registry.listByCoordinate("com.ecat:integration-agent-bridge"))
                .thenReturn(Collections.<ConfigEntry>emptyList());

        integration.issueManagedToken(ownerA, "hermes");
        integration.issueManagedToken(ownerB, "hermes");
        integration.issueManagedToken(ownerA, "hermes"); // 同 owner 再调,应复用同一锁

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Object> locks =
                (ConcurrentHashMap<String, Object>) readField(integration, "ownerLocks");
        assertNotNull("ownerLocks 字段应存在且非 null", locks);
        assertEquals("应有 ownerA、ownerB 两个锁(同 owner 复用,不新增)", 2, locks.size());
        Object lockA = locks.get(ownerA);
        Object lockB = locks.get(ownerB);
        assertNotNull("ownerA 锁存在", lockA);
        assertNotNull("ownerB 锁存在", lockB);
        assertNotSame("不同 owner 必须用不同锁对象(per-owner 隔离,不得退化为全局单锁)", lockA, lockB);
    }

    /**
     * 撤销托管 token：按 hash 失效内存 token 并删除对应 ConfigEntry。
     */
    @Test
    public void revokeManagedToken_invalidatesAndRemovesEntry() {
        String owner = "com.ecat:integration-llm-agent";
        AgentToken token = integration.issueManagedToken(owner, "hermes");
        String rawToken = token.getToken();
        String tokenHash = AgentAuthManager.sha256(rawToken);

        ConfigEntry entry = mock(ConfigEntry.class);
        when(entry.getEntryId()).thenReturn("entry-id-1");
        when(entry.getUniqueId()).thenReturn("any");
        when(entry.getData()).thenReturn(Collections.<String, Object>singletonMap("tokenHash", tokenHash));
        when(registry.listByCoordinate("com.ecat:integration-agent-bridge"))
                .thenReturn(Collections.singletonList(entry));

        integration.revokeManagedToken(tokenHash);

        assertNull("撤销后 token 应立即失效", authManager.validateToken(rawToken));
        verify(registry).removeEntry("entry-id-1");
    }

    /**
     * 未 onLoad 完成（core/authManager 未注入）时签发托管 token 抛 IllegalStateException，
     * 防止半初始化状态写出无主 entry。
     */
    @Test(expected = IllegalStateException.class)
    public void issueManagedToken_beforeLoad_throws() {
        new AgentBridgeIntegration().issueManagedToken("x", "y");
    }

    /**
     * 撤销时 hash 在 registry 中找不到匹配 entry：原 token 仍有效（revokeByTokenHash 对不存在 hash 是 no-op），
     * removeEntry 未被调用，仅记录 warn 日志。覆盖 revokeManagedToken 的 not-found 路径。
     */
    @Test
    public void revokeManagedToken_notFound_keepsTokenValidAndNoRemove() {
        String owner = "com.ecat:integration-llm-agent";
        AgentToken token = integration.issueManagedToken(owner, "hermes");
        String rawToken = token.getToken();

        // registry 中无任何 agent-bridge entry：listByCoordinate 返回空列表（找不到匹配 hash）
        when(registry.listByCoordinate("com.ecat:integration-agent-bridge"))
                .thenReturn(Collections.<ConfigEntry>emptyList());

        // 用一个不存在的 hash 撤销（64 个 0，Java 8 兼容写法）
        String nonExistentHash = String.format("%064x", 0L);
        integration.revokeManagedToken(nonExistentHash);

        // 原 token 仍有效（revokeByTokenHash 用不存在的 hash 是 no-op）
        assertNotNull("不存在的 hash 不应影响现有 token", authManager.validateToken(rawToken));
        // removeEntry 未被调用（没找到 entry）
        verify(registry, never()).removeEntry(anyString());
    }
}
