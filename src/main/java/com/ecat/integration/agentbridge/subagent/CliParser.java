package com.ecat.integration.agentbridge.subagent;

import com.ecat.integration.agentbridge.mcp.McpException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI 字符串解析器。
 *
 * <p>将 CLI 命令串解析为 {@link CliParseResult}：首 token 为 agent、次 token 为 tool，
 * 剩余按 {@code --flag value} / {@code --flag=value} 解析为命名参数；无 flag 的裸值
 * 按 {@link ArgDescriptor#isPositional()} 声明顺序回填。
 *
 * <p>解析后按 {@link ArgDescriptor.Type} 强转参数类型，并校验必填参数。
 *
 * <p>严格模式：缺失必填参数、非法数值等解析失败抛 {@link McpException}(INVALID_PARAMS)，
 * 不写兜底默认值。
 *
 * @author coffee
 */
public class CliParser {

    /** JSON-RPC Invalid params 错误码 */
    private static final int INVALID_PARAMS = -32602;

    /**
     * 解析 CLI 字符串为 {@link CliParseResult}。
     *
     * @param cli            CLI 命令串，如 "device set-attribute --id D1"
     * @param argDescriptors 目标工具的参数定义（用于 positional 回填与类型强转）
     * @return 解析结果
     * @throws McpException 缺失必填参数或非法数值（INVALID_PARAMS）
     */
    public CliParseResult parse(String cli, List<ArgDescriptor> argDescriptors) throws McpException {
        List<String> tokens = tokenize(cli);
        String agent = tokens.get(0);
        String tool = tokens.get(1);
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        List<String> positionalTokens = new ArrayList<String>();

        // 建立 flag → name 映射（kebab-case flag → camelCase name，匹配 httpPath 占位符）
        Map<String, String> flagToName = new HashMap<String, String>();
        for (ArgDescriptor desc : argDescriptors) {
            if (desc.getFlag() != null) {
                flagToName.put(desc.getFlag(), desc.getName());
            }
        }

        int i = 2;
        while (i < tokens.size()) {
            String tok = tokens.get(i);
            if (tok.startsWith("--")) {
                String body = tok.substring(2);
                int eq = body.indexOf('=');
                String flagKey;
                String value;
                if (eq >= 0) {
                    // --flag=value 形式
                    flagKey = "--" + body.substring(0, eq);
                    value = body.substring(eq + 1);
                    i++;
                } else {
                    // --flag value 形式
                    flagKey = "--" + body;
                    value = (i + 1 < tokens.size()) ? tokens.get(i + 1) : "";
                    i += 2;
                }
                // 映射 flag → name；未声明 flag 时用 flag body（去 --）作 key
                String name = flagToName.containsKey(flagKey)
                        ? flagToName.get(flagKey) : flagKey.substring(2);
                params.put(name, value);
            } else {
                // positional token，按声明顺序回填
                positionalTokens.add(tok);
                i++;
            }
        }

        backfillPositional(positionalTokens, argDescriptors, params);
        coerceTypes(argDescriptors, params);
        validateRequired(argDescriptors, params);

        return new CliParseResult(agent, tool, params);
    }

    /**
     * 按 argDescriptors 中 positional=true 的声明顺序，将 positional token 回填到 params。
     */
    private void backfillPositional(List<String> positionalTokens,
                                    List<ArgDescriptor> argDescriptors,
                                    Map<String, Object> params) {
        int idx = 0;
        for (ArgDescriptor desc : argDescriptors) {
            if (desc.isPositional() && idx < positionalTokens.size()) {
                params.put(desc.getName(), positionalTokens.get(idx));
                idx++;
            }
        }
    }

    /**
     * 按 {@link ArgDescriptor.Type} 强转 params 中对应的 String 值。
     *
     * @throws McpException 数值强转失败（INVALID_PARAMS）
     */
    private void coerceTypes(List<ArgDescriptor> argDescriptors,
                             Map<String, Object> params) throws McpException {
        for (ArgDescriptor desc : argDescriptors) {
            Object raw = params.get(desc.getName());
            if (!(raw instanceof String)) {
                continue;
            }
            String s = (String) raw;
            try {
                params.put(desc.getName(), coerce(s, desc.getType()));
            } catch (NumberFormatException e) {
                throw new McpException(INVALID_PARAMS,
                        "参数 [" + desc.getName() + "] 值 [" + s + "] 无法转为 " + desc.getType(), e);
            }
        }
    }

    /**
     * 将字符串强转为指定类型。
     *
     * @throws NumberFormatException 数值格式非法
     * @throws IllegalStateException 未知类型（不可达，enum 固定 4 值）
     */
    private Object coerce(String s, ArgDescriptor.Type type) {
        switch (type) {
            case INTEGER:
                return Integer.valueOf(s);
            case NUMBER:
                return Double.valueOf(s);
            case BOOLEAN:
                return Boolean.valueOf(s);
            case STRING:
                return s;
            default:
                throw new IllegalStateException("Unsupported arg type: " + type);
        }
    }

    /**
     * 校验必填参数均已提供。
     *
     * @throws McpException 缺失必填参数（INVALID_PARAMS）
     */
    private void validateRequired(List<ArgDescriptor> argDescriptors,
                                  Map<String, Object> params) throws McpException {
        for (ArgDescriptor desc : argDescriptors) {
            if (desc.isRequired() && !params.containsKey(desc.getName())) {
                throw new McpException(INVALID_PARAMS, "缺少必填参数: " + desc.getName());
            }
        }
    }

    /**
     * 提取 CLI 首 2 token（agent 名、tool 名），不做参数解析。
     *
     * <p>用于路由阶段先确定目标 SubAgent 与工具，再用其参数定义完整解析。
     *
     * @param cli CLI 命令串
     * @return 长度 2 的数组 [agentName, toolName]
     * @throws McpException token 少于 2（命令格式错误，INVALID_PARAMS）
     */
    public String[] extractHead(String cli) throws McpException {
        List<String> tokens = tokenize(cli);
        if (tokens.size() < 2) {
            throw new McpException(INVALID_PARAMS,
                    "命令格式错误，期望: <agent> <tool> [参数...]");
        }
        return new String[]{tokens.get(0), tokens.get(1)};
    }

    /**
     * 分词：按空白切分，但双引号内的空白视为值的一部分，引号本身去除。
     *
     * <p>例如 {@code --note "hello world"} 分为 {@code [--note, hello world]}。
     */
    private List<String> tokenize(String cli) {
        List<String> tokens = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        boolean hasContent = false;
        for (int i = 0; i < cli.length(); i++) {
            char c = cli.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
                hasContent = true;
            } else if (Character.isWhitespace(c) && !inQuote) {
                if (hasContent) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                    hasContent = false;
                }
            } else {
                cur.append(c);
                hasContent = true;
            }
        }
        if (hasContent) {
            tokens.add(cur.toString());
        }
        return tokens;
    }
}
