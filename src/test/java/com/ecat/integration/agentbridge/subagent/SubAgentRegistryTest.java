package com.ecat.integration.agentbridge.subagent;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link SubAgentRegistry} 单元测试。
 *
 * <p>验证候选过滤（按 requiredIntegration 命中 ACTIVE 集合）、增量 onActive/onStopped、
 * 能力索引拼接。
 *
 * @author coffee
 */
public class SubAgentRegistryTest {

    @Test
    public void buildIndexRegistersOnlyAgentsWhoseIntegrationIsActive() {
        AbstractSubAgent deviceAgent = stubAgent("device", "com.ecat:integration-ecat-core-api");
        AbstractSubAgent mediaAgent = stubAgent("media", "com.ecat:integration-media-api");
        SubAgentRegistry registry = new SubAgentRegistry(Arrays.asList(deviceAgent, mediaAgent));

        // 只有 core-api ACTIVE，media-api 未启用
        registry.buildIndex(new HashSet<String>(Arrays.asList("com.ecat:integration-ecat-core-api")));

        assertNotNull(registry.findAgent("device"));
        assertNull("未 ACTIVE 的集成对应 agent 不应注册", registry.findAgent("media"));
    }

    @Test
    public void onIntegrationActiveRegistersMatchingAgentDynamically() {
        AbstractSubAgent mediaAgent = stubAgent("media", "com.ecat:integration-media-api");
        SubAgentRegistry registry = new SubAgentRegistry(Arrays.asList(mediaAgent));
        registry.buildIndex(Collections.<String>emptySet());

        assertNull(registry.findAgent("media"));
        registry.onIntegrationActive("com.ecat:integration-media-api");
        assertNotNull("集成 ACTIVE 后应动态注册对应 agent", registry.findAgent("media"));
    }

    @Test
    public void onIntegrationStoppedRemovesMatchingAgent() {
        AbstractSubAgent deviceAgent = stubAgent("device", "com.ecat:integration-ecat-core-api");
        SubAgentRegistry registry = new SubAgentRegistry(Arrays.asList(deviceAgent));
        registry.buildIndex(new HashSet<String>(Arrays.asList("com.ecat:integration-ecat-core-api")));
        assertNotNull(registry.findAgent("device"));

        registry.onIntegrationStopped("com.ecat:integration-ecat-core-api");
        assertNull("集成 STOPPED 后应移除对应 agent", registry.findAgent("device"));
    }

    @Test
    public void buildCapabilityIndexListsToolNamesPerAgent() {
        AbstractSubAgent deviceAgent = stubAgentWithTools(
                "device", "com.ecat:integration-ecat-core-api", "list", "get", "set-attribute");
        SubAgentRegistry registry = new SubAgentRegistry(Arrays.asList(deviceAgent));
        registry.buildIndex(new HashSet<String>(Arrays.asList("com.ecat:integration-ecat-core-api")));

        Map<String, List<String>> index = registry.buildCapabilityIndex();
        assertTrue(index.containsKey("device"));
        assertEquals(Arrays.asList("list", "get", "set-attribute"), index.get("device"));
    }

    // ==================== 测试辅助 ====================

    /** 造一个无工具的最小 AbstractSubAgent */
    private AbstractSubAgent stubAgent(final String name, final String integration) {
        return new AbstractSubAgent(name, integration) {
            @Override
            public List<ToolDescriptor> getTools() {
                return Collections.emptyList();
            }
        };
    }

    /** 造一个带指定工具名的 AbstractSubAgent */
    private AbstractSubAgent stubAgentWithTools(final String name, final String integration,
                                                final String... toolNames) {
        return new AbstractSubAgent(name, integration) {
            @Override
            public List<ToolDescriptor> getTools() {
                java.util.List<ToolDescriptor> tools = new java.util.ArrayList<ToolDescriptor>();
                for (String tn : toolNames) {
                    tools.add(new ToolDescriptor.Builder(tn).build());
                }
                return tools;
            }
        };
    }
}
