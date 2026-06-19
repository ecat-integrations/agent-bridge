package com.ecat.integration.agentbridge.config;

import com.ecat.core.ConfigFlow.ConfigFlowResult;
import com.ecat.integration.agentbridge.auth.AgentAuthManager;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * AgentBridgeConfigFlow unit tests.
 *
 * <p>Uses a simple TokenManagerCallback stub for testing.
 *
 * @author coffee
 */
public class AgentBridgeConfigFlowTest {

    /** Test token returned by stub */
    private static final String TEST_TOKEN = "ecat-agent-testtoken123";

    private AgentBridgeConfigFlow flow;
    private String lastGeneratedTokenName;
    private String lastGeneratedTokenRole;

    @Before
    public void setUp() {
        lastGeneratedTokenName = null;
        lastGeneratedTokenRole = null;

        AgentBridgeConfigFlow.TokenManagerCallback callback =
                new AgentBridgeConfigFlow.TokenManagerCallback() {
                    @Override
                    public String generateToken(String name, String role) {
                        lastGeneratedTokenName = name;
                        lastGeneratedTokenRole = role;
                        return TEST_TOKEN;
                    }

                    @Override
                    public String hashToken(String rawToken) {
                        return AgentAuthManager.sha256(rawToken);
                    }
                };

        flow = new AgentBridgeConfigFlow(callback);
    }

    // ==================== 步骤注册验证 ====================

    @Test
    public void hasUserStep_returnsTrue() {
        assertTrue(flow.hasUserStep());
    }

    @Test
    public void hasReconfigureStep_returnsFalse() {
        assertFalse(flow.hasReconfigureStep());
    }

    // ==================== token_setup 步骤 ====================

    @Test
    public void stepTokenSetup_noInput_showsForm() {
        ConfigFlowResult result = flow.executeUserStep(new HashMap<String, Object>());
        assertEquals(ConfigFlowResult.ResultType.SHOW_FORM, result.getType());
        assertEquals("token_setup", result.getStepId());
    }

    @Test
    public void stepTokenSetup_nullInput_showsForm() {
        ConfigFlowResult result = flow.executeUserStep(null);
        assertEquals(ConfigFlowResult.ResultType.SHOW_FORM, result.getType());
    }

    @Test
    public void stepTokenSetup_validInput_generatesToken() {
        Map<String, Object> input = new HashMap<String, Object>();
        input.put("name", "test-agent");
        input.put("role", "agent");

        ConfigFlowResult result = flow.executeUserStep(input);

        // Should advance to final_confirm step
        assertEquals(ConfigFlowResult.ResultType.SHOW_FORM, result.getType());
        assertEquals("final_confirm", result.getStepId());
        assertEquals("test-agent", lastGeneratedTokenName);
        assertEquals("agent", lastGeneratedTokenRole);
    }

    @Test
    public void stepTokenSetup_storesEntryData() {
        Map<String, Object> input = new HashMap<String, Object>();
        input.put("name", "my-agent");
        input.put("role", "admin");

        flow.executeUserStep(input);

        Map<String, Object> entryData = flow.getContext().getEntryData();
        assertEquals("my-agent", entryData.get("name"));
        assertEquals("admin", entryData.get("role"));
        assertEquals(TEST_TOKEN, entryData.get("_rawToken"));
        assertNotNull(entryData.get("generatedAt"));
    }

    @Test
    public void stepTokenSetup_setsUniqueId() {
        Map<String, Object> input = new HashMap<String, Object>();
        input.put("name", "my-agent");
        input.put("role", "agent");

        flow.executeUserStep(input);

        assertEquals("com.ecat.integrations.agent-bridge.token.my-agent",
                flow.getContext().getEntryUniqueId());
    }

    @Test
    public void stepTokenSetup_setsTitle() {
        Map<String, Object> input = new HashMap<String, Object>();
        input.put("name", "my-agent");
        input.put("role", "agent");

        flow.executeUserStep(input);

        assertEquals("Agent Token: my-agent", flow.getContext().getEntryTitle());
    }

    // ==================== 验证测试 ====================

    @Test
    public void stepTokenSetup_emptyName_showsError() {
        Map<String, Object> input = new HashMap<String, Object>();
        input.put("name", "");
        input.put("role", "agent");

        ConfigFlowResult result = flow.executeUserStep(input);
        assertEquals("token_setup", result.getStepId());
        // errors should contain "name"
        assertNotNull(result.getErrors());
        assertTrue(result.getErrors().containsKey("name"));
    }

    @Test
    public void stepTokenSetup_shortName_showsError() {
        Map<String, Object> input = new HashMap<String, Object>();
        input.put("name", "ab");
        input.put("role", "agent");

        ConfigFlowResult result = flow.executeUserStep(input);
        assertTrue(result.getErrors().containsKey("name"));
    }

    @Test
    public void stepTokenSetup_longName_showsError() {
        Map<String, Object> input = new HashMap<String, Object>();
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 65; i++) {
            longName.append("x");
        }
        input.put("name", longName.toString());
        input.put("role", "agent");

        ConfigFlowResult result = flow.executeUserStep(input);
        assertTrue(result.getErrors().containsKey("name"));
    }

    @Test
    public void stepTokenSetup_emptyRole_showsError() {
        Map<String, Object> input = new HashMap<String, Object>();
        input.put("name", "test-agent");
        input.put("role", "");

        ConfigFlowResult result = flow.executeUserStep(input);
        assertTrue(result.getErrors().containsKey("role"));
    }

    @Test
    public void stepTokenSetup_nameBoundary_minLength3_ok() {
        Map<String, Object> input = new HashMap<String, Object>();
        input.put("name", "abc");
        input.put("role", "agent");

        ConfigFlowResult result = flow.executeUserStep(input);
        assertEquals("final_confirm", result.getStepId());
    }

    @Test
    public void stepTokenSetup_nameBoundary_maxLength64_ok() {
        Map<String, Object> input = new HashMap<String, Object>();
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            name.append("x");
        }
        input.put("name", name.toString());
        input.put("role", "agent");

        ConfigFlowResult result = flow.executeUserStep(input);
        assertEquals("final_confirm", result.getStepId());
    }

    // ==================== final_confirm 步骤 ====================

    @Test
    public void stepFinalConfirm_withInput_createsEntry() {
        // First step — token_setup
        Map<String, Object> setupInput = new HashMap<String, Object>();
        setupInput.put("name", "test-agent");
        setupInput.put("role", "agent");
        flow.executeUserStep(setupInput);

        // Second step — final_confirm with confirm input
        Map<String, Object> confirmInput = new HashMap<String, Object>();
        confirmInput.put("confirm", "true");

        ConfigFlowResult result = flow.handleStep("final_confirm", confirmInput);
        assertEquals(ConfigFlowResult.ResultType.CREATE_ENTRY, result.getType());
        // rawToken should be in the result entryData but not in the persistent data
        assertEquals(TEST_TOKEN, result.getEntryData().get("rawToken"));
        // _rawToken should have been removed from entry data
        assertNull(result.getEntryData().get("_rawToken"));
        // tokenHash（SHA-256，hash-only）应被持久化到 entry data，供重启后 loadFromEntries 恢复
        assertEquals(AgentAuthManager.sha256(TEST_TOKEN), result.getEntryData().get("tokenHash"));
    }

    /**
     * final_confirm 应把 tokenHash（SHA-256）写入持久化 entryData，使重启后
     * AgentAuthManager.loadFromEntries 能据此恢复有效 token（hash-only 持久化修复的核心断言）。
     */
    @Test
    public void stepFinalConfirm_persistsTokenHash() {
        Map<String, Object> setupInput = new HashMap<String, Object>();
        setupInput.put("name", "persist-agent");
        setupInput.put("role", "admin");
        flow.executeUserStep(setupInput);

        Map<String, Object> confirmInput = new HashMap<String, Object>();
        confirmInput.put("confirmed", "true");

        flow.handleStep("final_confirm", confirmInput);

        Map<String, Object> entryData = flow.getContext().getEntryData();
        // tokenHash 必须是 TEST_TOKEN 的 SHA-256（与生产 generateToken 同一 sha256 函数）
        Object tokenHash = entryData.get("tokenHash");
        assertNotNull("tokenHash must be persisted for cross-restart restore", tokenHash);
        assertEquals(AgentAuthManager.sha256(TEST_TOKEN), tokenHash);
        // 原始 token 不得落盘（hash-only）
        assertNull("rawToken must not be persisted (hash-only)", entryData.get("_rawToken"));
    }

    @Test
    public void stepFinalConfirm_noInput_showsFormAgain() {
        // First step — token_setup
        Map<String, Object> setupInput = new HashMap<String, Object>();
        setupInput.put("name", "test-agent");
        setupInput.put("role", "agent");
        flow.executeUserStep(setupInput);

        // Second step — final_confirm without input (step navigation back)
        ConfigFlowResult result = flow.handleStep("final_confirm", new HashMap<String, Object>());
        assertEquals(ConfigFlowResult.ResultType.SHOW_FORM, result.getType());
        assertEquals("final_confirm", result.getStepId());
    }

    // ==================== TokenManagerCallback 接口测试 ====================

    @Test
    public void tokenManagerCallback_interface_works() {
        AgentBridgeConfigFlow.TokenManagerCallback cb =
                new AgentBridgeConfigFlow.TokenManagerCallback() {
                    @Override
                    public String generateToken(String name, String role) {
                        return "generated-" + name + "-" + role;
                    }

                    @Override
                    public String hashToken(String rawToken) {
                        return AgentAuthManager.sha256(rawToken);
                    }
                };
        assertEquals("generated-myAgent-admin", cb.generateToken("myAgent", "admin"));
    }

    // ==================== 无参构造器测试 ====================

    @Test(expected = IllegalStateException.class)
    public void noArgConstructor_withoutStaticSetup_throws() {
        // Reset static to null to verify error
        AgentBridgeConfigFlow.setTokenManager(null);
        new AgentBridgeConfigFlow();
    }
}
