package com.ecat.integration.agentbridge;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.ConfigEntry.ConfigEntryRegistry;
import com.ecat.core.EcatCore;
import com.ecat.integration.agentbridge.auth.AgentAuthManager;
import com.ecat.integration.agentbridge.auth.AgentToken;
import com.ecat.integration.agentbridge.config.AgentBridgeConfigFlow;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentBridgeIntegration#removeEntry(String)} 单元测试。
 *
 * <p>回归保护：removeEntry 由 {@code ConfigEntryRegistry.notifyIntegrationRemove()} 回调触发
 * （调用链：ConfigEntryRegistry.removeEntry → notifyIntegrationRemove → integration.removeEntry），
 * 绝不能再调用 {@code super.removeEntry()}，否则会重新进入
 * {@code ConfigEntryRegistry.removeEntry} 造成无限递归 StackOverflowError，
 * 表现为 {@code DELETE /core-api/config-flow/entries/{id}} 挂起无响应。
 *
 * <p>正确做法参考 {@code IntegrationDeviceBase.removeEntry}：只清理集成自身资源（撤销 token），
 * Registry 自身负责从缓存移除 entry 和持久化删除。
 *
 * @author coffee
 */
public class AgentBridgeIntegrationRemoveEntryTest {

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

    /**
     * 回归核心断言：integration.removeEntry 不能回调 registry.removeEntry。
     *
     * <p>当前实现调用了 super.removeEntry(entryId) → IntegrationBase.removeEntry →
     * registry.removeEntry(entryId)，本测试在修复前应当失败。
     */
    @Test
    public void removeEntry_doesNotReenterRegistry() throws Exception {
        ConfigEntryRegistry registry = mock(ConfigEntryRegistry.class);
        EcatCore core = mock(EcatCore.class);
        when(core.getEntryRegistry()).thenReturn(registry);

        ConfigEntry entry = tokenEntry("entry-1", "test-agent", "dummy-hash");
        when(registry.getByEntryId("entry-1")).thenReturn(entry);

        AgentBridgeIntegration integration = new AgentBridgeIntegration();
        setField(integration, "core", core);
        setField(integration, "authManager", new AgentAuthManager());

        // 模拟 Registry 回调进入 integration.removeEntry
        integration.removeEntry("entry-1");

        // 回归断言：绝不能回调 registry.removeEntry（否则无限递归）
        verify(registry, never()).removeEntry(anyString());
    }

    /**
     * 删除 token 类型 entry 时，应从内存 tokenStore 撤销对应 token，使其立即失效。
     */
    @Test
    public void removeEntry_revokesTokenForAgentTokenEntry() throws Exception {
        ConfigEntryRegistry registry = mock(ConfigEntryRegistry.class);
        EcatCore core = mock(EcatCore.class);
        when(core.getEntryRegistry()).thenReturn(registry);

        AgentAuthManager authManager = new AgentAuthManager();
        AgentToken token = authManager.generateToken("test-agent", "admin");
        String tokenHash = AgentAuthManager.sha256(token.getToken());
        assertNotNull("token 初始应有效", authManager.validateToken(token.getToken()));

        ConfigEntry entry = tokenEntry("entry-1", "test-agent", tokenHash);
        when(registry.getByEntryId("entry-1")).thenReturn(entry);

        AgentBridgeIntegration integration = new AgentBridgeIntegration();
        setField(integration, "core", core);
        setField(integration, "authManager", authManager);

        integration.removeEntry("entry-1");

        assertNull("删除 entry 后 token 应被撤销（立即失效）", authManager.validateToken(token.getToken()));
    }

    /**
     * 删除非 token 类型 entry（uniqueId 不以 token 前缀开头）时，不应影响 tokenStore。
     */
    @Test
    public void removeEntry_skipsNonTokenEntry() throws Exception {
        ConfigEntryRegistry registry = mock(ConfigEntryRegistry.class);
        EcatCore core = mock(EcatCore.class);
        when(core.getEntryRegistry()).thenReturn(registry);

        AgentAuthManager authManager = new AgentAuthManager();
        AgentToken token = authManager.generateToken("test-agent", "admin");

        Map<String, Object> data = new HashMap<>();
        data.put("tokenHash", AgentAuthManager.sha256(token.getToken()));
        ConfigEntry nonTokenEntry = new ConfigEntry.Builder()
                .entryId("entry-other")
                .coordinate("com.ecat:integration-agent-bridge")
                .uniqueId("com.ecat.integrations.agent-bridge.something-else") // 非 token 前缀
                .data(data)
                .enabled(true)
                .build();
        when(registry.getByEntryId("entry-other")).thenReturn(nonTokenEntry);

        AgentBridgeIntegration integration = new AgentBridgeIntegration();
        setField(integration, "core", core);
        setField(integration, "authManager", authManager);

        integration.removeEntry("entry-other");

        assertNotNull("非 token entry 删除不应撤销 token", authManager.validateToken(token.getToken()));
    }

    private ConfigEntry tokenEntry(String entryId, String name, String tokenHash) {
        Map<String, Object> data = new HashMap<>();
        data.put("tokenHash", tokenHash);
        data.put("name", name);
        data.put("role", "admin");
        return new ConfigEntry.Builder()
                .entryId(entryId)
                .coordinate("com.ecat:integration-agent-bridge")
                .uniqueId(AgentBridgeConfigFlow.UNIQUE_ID_PREFIX + name)
                .data(data)
                .enabled(true)
                .build();
    }
}
