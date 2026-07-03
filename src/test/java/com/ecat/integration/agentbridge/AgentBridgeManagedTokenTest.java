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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.anyString;
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
