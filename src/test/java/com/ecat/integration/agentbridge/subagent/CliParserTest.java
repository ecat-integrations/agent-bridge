package com.ecat.integration.agentbridge.subagent;

import com.ecat.integration.agentbridge.mcp.McpException;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * {@link CliParser} 单元测试。
 *
 * <p>验证 CLI 字符串解析：agent/tool 提取、命名 flag、引号包裹的含空格值、
 * positional 回填、类型强转、缺失必填参数与非法值抛 McpException。
 *
 * @author coffee
 */
public class CliParserTest {

    @Test
    public void parsesAgentToolAndNamedFlags() throws McpException {
        CliParser parser = new CliParser();
        CliParseResult result = parser.parse(
                "device set-attribute --id D1 --attr temp --value 25",
                Collections.<ArgDescriptor>emptyList());

        assertEquals("device", result.getAgent());
        assertEquals("set-attribute", result.getTool());
        assertEquals("D1", result.getParam("id"));
        assertEquals("temp", result.getParam("attr"));
        assertEquals("25", result.getParam("value"));
    }

    @Test
    public void parsesQuotedValueWithSpaces() throws McpException {
        CliParser parser = new CliParser();
        CliParseResult result = parser.parse(
                "device set-attribute --unit \"°C\" --note \"hello world\"",
                Collections.<ArgDescriptor>emptyList());

        assertEquals("°C", result.getParam("unit"));
        assertEquals("hello world", result.getParam("note"));
    }

    @Test
    public void parsesFlagEqualsValueForm() throws McpException {
        CliParser parser = new CliParser();
        CliParseResult result = parser.parse(
                "device get --id=D1",
                Collections.<ArgDescriptor>emptyList());

        assertEquals("D1", result.getParam("id"));
    }

    @Test
    public void backfillsPositionalArgsByDeclarationOrder() throws McpException {
        CliParser parser = new CliParser();
        List<ArgDescriptor> args = Arrays.asList(
                new ArgDescriptor.Builder("deviceId").positional().build(),
                new ArgDescriptor.Builder("attrId").positional().build());
        // D1 → deviceId（第 1 个 positional），temperature → attrId（第 2 个 positional）
        CliParseResult result = parser.parse("device get D1 temperature", args);

        assertEquals("D1", result.getParam("deviceId"));
        assertEquals("temperature", result.getParam("attrId"));
    }

    @Test
    public void coercesByArgType() throws McpException {
        CliParser parser = new CliParser();
        List<ArgDescriptor> args = Arrays.asList(
                new ArgDescriptor.Builder("count").flag("--count").type(ArgDescriptor.Type.INTEGER).build(),
                new ArgDescriptor.Builder("enabled").flag("--enabled").type(ArgDescriptor.Type.BOOLEAN).build(),
                new ArgDescriptor.Builder("rate").flag("--rate").type(ArgDescriptor.Type.NUMBER).build());
        CliParseResult result = parser.parse("device x --count 5 --enabled true --rate 3.14", args);

        assertEquals(Integer.valueOf(5), result.getParam("count"));
        assertEquals(Boolean.TRUE, result.getParam("enabled"));
        assertEquals(Double.valueOf(3.14), result.getParam("rate"));
    }

    @Test(expected = McpException.class)
    public void throwsOnMissingRequiredArg() throws McpException {
        CliParser parser = new CliParser();
        List<ArgDescriptor> args = Arrays.asList(
                new ArgDescriptor.Builder("id").flag("--id").required().build());
        // 缺 --id，应抛 McpException(INVALID_PARAMS)
        parser.parse("device get", args);
    }

    @Test(expected = McpException.class)
    public void throwsOnInvalidIntegerValue() throws McpException {
        CliParser parser = new CliParser();
        List<ArgDescriptor> args = Arrays.asList(
                new ArgDescriptor.Builder("count").flag("--count").type(ArgDescriptor.Type.INTEGER).build());
        // abc 无法转为 INTEGER，应抛 McpException(INVALID_PARAMS)
        parser.parse("device x --count abc", args);
    }

    @Test
    public void extractHeadReturnsAgentAndTool() throws McpException {
        String[] head = new CliParser().extractHead("device set-attribute --id D1");
        assertArrayEquals(new String[]{"device", "set-attribute"}, head);
    }

    @Test(expected = McpException.class)
    public void extractHeadThrowsOnTooFewTokens() throws McpException {
        // 仅 1 个 token，缺 tool 名
        new CliParser().extractHead("device");
    }

    @Test
    public void mapsKebabCaseFlagToCamelCaseName() throws McpException {
        // CLI flag 用 kebab-case（--attr-id），ArgDescriptor.name 用 camelCase（attrId，匹配 httpPath {attrId}）
        CliParser parser = new CliParser();
        List<ArgDescriptor> args = Arrays.asList(
                new ArgDescriptor.Builder("attrId").flag("--attr-id").build());
        CliParseResult result = parser.parse("device set --attr-id temperature", args);

        assertEquals("temperature", result.getParam("attrId"));
        assertNull("不应保留 kebab-case key", result.getParam("attr-id"));
    }
}
