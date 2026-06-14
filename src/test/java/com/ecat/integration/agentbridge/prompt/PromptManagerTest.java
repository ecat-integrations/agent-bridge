package com.ecat.integration.agentbridge.prompt;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * PromptManager unit tests.
 *
 * @author coffee
 */
public class PromptManagerTest {

    private PromptManager manager;

    @Before
    public void setUp() {
        manager = new PromptManager();
    }

    // ==================== listPrompts ====================

    @Test
    public void listPrompts_returnsThreeBuiltInTemplates() {
        List<Map<String, Object>> prompts = manager.listPrompts();
        assertEquals(3, prompts.size());
    }

    @Test
    public void listPrompts_containsSystemOverview() {
        Map<String, Object> overview = findPromptByName("ecat_system_overview");
        assertNotNull("ecat_system_overview should exist", overview);
        assertEquals("ecat_system_overview", overview.get("name"));
        assertNotNull(overview.get("description"));
        // 无参数模板
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> args = (List<Map<String, Object>>) overview.get("arguments");
        assertTrue(args.isEmpty());
    }

    @Test
    public void listPrompts_containsDeviceGuide() {
        Map<String, Object> guide = findPromptByName("ecat_device_guide");
        assertNotNull("ecat_device_guide should exist", guide);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> args = (List<Map<String, Object>>) guide.get("arguments");
        assertEquals(1, args.size());
        assertEquals("device_type", args.get(0).get("name"));
        assertTrue((Boolean) args.get(0).get("required"));
    }

    @Test
    public void listPrompts_containsSafetyGuide() {
        Map<String, Object> safety = findPromptByName("ecat_safety_guide");
        assertNotNull("ecat_safety_guide should exist", safety);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> args = (List<Map<String, Object>>) safety.get("arguments");
        assertTrue(args.isEmpty());
    }

    // ==================== getPrompt ====================

    @Test
    public void getPrompt_systemOverview_noArgs() {
        Map<String, Object> result = manager.getPrompt("ecat_system_overview", null);
        assertNotNull(result.get("description"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).get("role"));

        @SuppressWarnings("unchecked")
        Map<String, Object> content = (Map<String, Object>) messages.get(0).get("content");
        assertEquals("text", content.get("type"));
        String text = (String) content.get("text");
        assertTrue(text.contains("ECAT"));
        assertTrue(text.contains("MCP"));
    }

    @Test
    public void getPrompt_deviceGuide_withArgs() {
        Map<String, String> args = new HashMap<String, String>();
        args.put("device_type", "modbus_rtu");
        Map<String, Object> result = manager.getPrompt("ecat_device_guide", args);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        @SuppressWarnings("unchecked")
        Map<String, Object> content = (Map<String, Object>) messages.get(0).get("content");
        String text = (String) content.get("text");
        assertTrue(text.contains("modbus_rtu"));
        assertFalse(text.contains("{device_type}"));
    }

    @Test
    public void getPrompt_safetyGuide_noArgs() {
        Map<String, Object> result = manager.getPrompt("ecat_safety_guide", null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        @SuppressWarnings("unchecked")
        Map<String, Object> content = (Map<String, Object>) messages.get(0).get("content");
        String text = (String) content.get("text");
        assertTrue(text.contains("SAFE"));
        assertTrue(text.contains("DANGEROUS"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void getPrompt_nullName_throws() {
        manager.getPrompt(null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void getPrompt_unknownName_throws() {
        manager.getPrompt("nonexistent", null);
    }

    // ==================== registerTemplate ====================

    @Test
    public void registerTemplate_customTemplate_listedInPrompts() {
        PromptTemplate custom = new PromptTemplate("custom_prompt", "自定义提示词",
                "Hello {name}", new ArrayList<PromptTemplate.Argument>());
        manager.registerTemplate(custom);
        List<Map<String, Object>> prompts = manager.listPrompts();
        assertEquals(4, prompts.size());
        assertNotNull(findPromptByName("custom_prompt"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void registerTemplate_duplicateName_throws() {
        PromptTemplate dup = new PromptTemplate("ecat_system_overview", "重复",
                "dup", new ArrayList<PromptTemplate.Argument>());
        manager.registerTemplate(dup);
    }

    @Test(expected = IllegalArgumentException.class)
    public void registerTemplate_null_throws() {
        manager.registerTemplate(null);
    }

    // ==================== 辅助方法 ====================

    private Map<String, Object> findPromptByName(String name) {
        for (Map<String, Object> prompt : manager.listPrompts()) {
            if (name.equals(prompt.get("name"))) {
                return prompt;
            }
        }
        return null;
    }
}
