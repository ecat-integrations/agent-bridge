package com.ecat.integration.agentbridge.prompt;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * PromptTemplate unit tests.
 *
 * @author coffee
 */
public class PromptTemplateTest {

    // ==================== Argument 内部类测试 ====================

    @Test(expected = IllegalArgumentException.class)
    public void argument_nullName_throws() {
        new PromptTemplate.Argument(null, "desc", true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void argument_nullDescription_throws() {
        new PromptTemplate.Argument("name", null, true);
    }

    @Test
    public void argument_getters() {
        PromptTemplate.Argument arg = new PromptTemplate.Argument("device_type", "设备类型", true);
        assertEquals("device_type", arg.getName());
        assertEquals("设备类型", arg.getDescription());
        assertTrue(arg.isRequired());
    }

    @Test
    public void argument_optional_isNotRequired() {
        PromptTemplate.Argument arg = new PromptTemplate.Argument("opt", "可选参数", false);
        assertFalse(arg.isRequired());
    }

    // ==================== 构造器验证 ====================

    @Test(expected = IllegalArgumentException.class)
    public void constructor_nullName_throws() {
        List<PromptTemplate.Argument> args = Collections.emptyList();
        new PromptTemplate(null, "desc", "template", args);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_nullDescription_throws() {
        List<PromptTemplate.Argument> args = Collections.emptyList();
        new PromptTemplate("name", null, "template", args);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_nullTemplate_throws() {
        List<PromptTemplate.Argument> args = Collections.emptyList();
        new PromptTemplate("name", "desc", null, args);
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_nullArguments_throws() {
        new PromptTemplate("name", "desc", "template", null);
    }

    @Test
    public void constructor_validParameters_gettersWork() {
        List<PromptTemplate.Argument> args = new ArrayList<PromptTemplate.Argument>();
        args.add(new PromptTemplate.Argument("arg1", "第一个参数", true));
        PromptTemplate tmpl = new PromptTemplate("test", "测试模板", "hello {arg1}", args);

        assertEquals("test", tmpl.getName());
        assertEquals("测试模板", tmpl.getDescription());
        assertEquals("hello {arg1}", tmpl.getTemplate());
        assertEquals(1, tmpl.getArguments().size());
        assertEquals("arg1", tmpl.getArguments().get(0).getName());
    }

    @Test
    public void constructor_emptyArguments_immutable() {
        PromptTemplate tmpl = new PromptTemplate("n", "d", "t", Collections.<PromptTemplate.Argument>emptyList());
        assertEquals(0, tmpl.getArguments().size());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void getArguments_isImmutable() {
        PromptTemplate tmpl = new PromptTemplate("n", "d", "t", Collections.<PromptTemplate.Argument>emptyList());
        tmpl.getArguments().add(new PromptTemplate.Argument("x", "y", true));
    }

    // ==================== apply() 测试 ====================

    @Test
    public void apply_nullArgs_returnsTemplate() {
        PromptTemplate tmpl = new PromptTemplate("n", "d", "hello world", Collections.<PromptTemplate.Argument>emptyList());
        assertEquals("hello world", tmpl.apply(null));
    }

    @Test
    public void apply_emptyArgs_returnsTemplate() {
        PromptTemplate tmpl = new PromptTemplate("n", "d", "hello world", Collections.<PromptTemplate.Argument>emptyList());
        assertEquals("hello world", tmpl.apply(new HashMap<String, String>()));
    }

    @Test
    public void apply_singleArg_replacesPlaceholder() {
        PromptTemplate tmpl = new PromptTemplate("n", "d", "device type: {device_type}", Collections.<PromptTemplate.Argument>emptyList());
        Map<String, String> args = new HashMap<String, String>();
        args.put("device_type", "modbus_rtu");
        assertEquals("device type: modbus_rtu", tmpl.apply(args));
    }

    @Test
    public void apply_multipleArgs_replacesAllPlaceholders() {
        PromptTemplate tmpl = new PromptTemplate("n", "d", "{a} and {b}", Collections.<PromptTemplate.Argument>emptyList());
        Map<String, String> args = new HashMap<String, String>();
        args.put("a", "alpha");
        args.put("b", "beta");
        assertEquals("alpha and beta", tmpl.apply(args));
    }

    @Test
    public void apply_missingArg_placeholderKept() {
        PromptTemplate tmpl = new PromptTemplate("n", "d", "hello {missing}", Collections.<PromptTemplate.Argument>emptyList());
        Map<String, String> args = new HashMap<String, String>();
        args.put("other", "value");
        assertEquals("hello {missing}", tmpl.apply(args));
    }

    @Test
    public void apply_noPlaceholders_returnsTemplate() {
        PromptTemplate tmpl = new PromptTemplate("n", "d", "no placeholders here", Collections.<PromptTemplate.Argument>emptyList());
        Map<String, String> args = new HashMap<String, String>();
        args.put("unused", "value");
        assertEquals("no placeholders here", tmpl.apply(args));
    }
}
