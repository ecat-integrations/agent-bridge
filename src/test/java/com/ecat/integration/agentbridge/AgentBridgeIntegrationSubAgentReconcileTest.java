package com.ecat.integration.agentbridge;

import com.ecat.core.EcatCore;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.integration.EcatCoreApiIntegration.Auth.AuthManager;
import com.ecat.integration.agentbridge.subagent.AbstractSubAgent;
import com.ecat.integration.agentbridge.subagent.SubAgentRegistry;
import com.ecat.integration.agentbridge.subagent.impl.DeviceSubAgent;
import com.ecat.integration.agentbridge.subagent.impl.EventSubAgent;
import com.ecat.integration.agentbridge.subagent.impl.LogicDeviceSubAgent;
import com.ecat.integration.agentbridge.subagent.impl.MediaSubAgent;
import com.ecat.integration.agentbridge.tool.MediaUrlSigner;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AgentBridgeIntegration#reconcileSubAgentsFromRegistry()} 单元测试（回归 Bug #3）。
 *
 * <p>回归保护：agent-bridge 启动时初始 {@code buildIndex} 仅注入 {@code com.ecat:integration-ecat-core-api}
 * （agent-bridge 启动时唯一确定已 ACTIVE 的依赖），自发现依赖 {@code INTEGRATION_LIFECYCLE} 事件，
 * 但已加载的依赖集成（如 media-api）在该订阅建立前可能已 ACTIVE → 错过事件 → media SubAgent 永不注册。
 *
 * <p>正确做法：在 {@code INTEGRATIONS_ALL_LOADED} 这一"所有集成加载完成"确定时点，从
 * {@link IntegrationRegistry#getAllCoordinates()} 全量对账 SubAgent 索引，
 * 确保所有已加载依赖集成的 SubAgent 被注册。
 *
 * @author coffee
 */
public class AgentBridgeIntegrationSubAgentReconcileTest {

    /** 通过反射设置字段（含父类继承字段，如 IntegrationBase.integrationRegistry）。测试专用。 */
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

    /** 与生产代码 onLoad 一致的 4 个候选 SubAgent。 */
    private static SubAgentRegistry newRegistryWithAllCandidates() {
        return new SubAgentRegistry(Arrays.<AbstractSubAgent>asList(
                new DeviceSubAgent(), new LogicDeviceSubAgent(),
                new MediaSubAgent(new MediaUrlSigner(mock(AuthManager.class))), new EventSubAgent()));
    }

    /**
     * 核心回归：当 IntegrationRegistry 已含 media-api 时，对账后 media SubAgent 必须被注册。
     *
     * <p>复现 startup race：初始 buildIndex 仅 core-api → media 未注册；对账后应补齐。
     */
    @Test
    public void reconcile_registersMediaWhenMediaApiPresent() throws Exception {
        SubAgentRegistry registry = newRegistryWithAllCandidates();
        // 复现 startup 初始状态：仅 core-api（agent-bridge 启动时唯一确定 ACTIVE 的依赖）
        registry.buildIndex(new HashSet<String>(Arrays.asList("com.ecat:integration-ecat-core-api")));
        assertNull("初始状态 media 不应注册（复现 startup race）", registry.findAgent("media"));

        IntegrationRegistry integrationRegistry = mock(IntegrationRegistry.class);
        when(integrationRegistry.getAllCoordinates()).thenReturn(new HashSet<String>(Arrays.asList(
                "com.ecat:integration-ecat-core-api",
                "com.ecat:integration-media-api",
                "com.ecat:integration-agent-bridge")));

        AgentBridgeIntegration integration = new AgentBridgeIntegration();
        setField(integration, "integrationRegistry", integrationRegistry);
        setField(integration, "subAgentRegistry", registry);

        integration.reconcileSubAgentsFromRegistry();

        assertNotNull("对账后 media SubAgent 应被注册", registry.findAgent("media"));
        assertNotNull("device 仍应注册", registry.findAgent("device"));
        assertNotNull("logic-device 仍应注册", registry.findAgent("logic-device"));
        assertNotNull("event 仍应注册", registry.findAgent("event"));
    }

    /**
     * 反向断言：media-api 未加载时，对账后 media SubAgent 不应注册（不虚假暴露能力）。
     */
    @Test
    public void reconcile_doesNotRegisterMediaWhenMediaApiAbsent() throws Exception {
        SubAgentRegistry registry = newRegistryWithAllCandidates();
        registry.buildIndex(new HashSet<String>(Arrays.asList("com.ecat:integration-ecat-core-api")));

        IntegrationRegistry integrationRegistry = mock(IntegrationRegistry.class);
        when(integrationRegistry.getAllCoordinates()).thenReturn(new HashSet<String>(Arrays.asList(
                "com.ecat:integration-ecat-core-api", // media-api 未加载
                "com.ecat:integration-agent-bridge")));

        AgentBridgeIntegration integration = new AgentBridgeIntegration();
        setField(integration, "integrationRegistry", integrationRegistry);
        setField(integration, "subAgentRegistry", registry);

        integration.reconcileSubAgentsFromRegistry();

        assertNull("media-api 未加载时 media SubAgent 不应注册", registry.findAgent("media"));
        assertNotNull("device 应注册（core-api 存在）", registry.findAgent("device"));
    }
}
