package com.ecat.integration.agentbridge.subagent;

import java.util.Collections;
import java.util.Map;

/**
 * {@link CliParser} 解析输出值对象。
 *
 * <p>包含解析出的 agent 名、tool 名，以及参数键值表（命名参数 + positional 回填后的统一视图）。
 *
 * @author coffee
 */
public class CliParseResult {

    private final String agent;
    private final String tool;
    private final Map<String, Object> params;

    /**
     * 构造解析结果。
     *
     * @param agent  SubAgent 名（CLI 首 token）
     * @param tool   工具名（CLI 次 token）
     * @param params 参数键值表
     */
    public CliParseResult(String agent, String tool, Map<String, Object> params) {
        this.agent = agent;
        this.tool = tool;
        this.params = params;
    }

    /** 获取 SubAgent 名 */
    public String getAgent() {
        return agent;
    }

    /** 获取工具名 */
    public String getTool() {
        return tool;
    }

    /**
     * 获取指定参数值（已按 ArgDescriptor 类型强转）。
     *
     * @param name 参数名
     * @return 参数值，不存在时返回 null
     */
    public Object getParam(String name) {
        return params == null ? null : params.get(name);
    }

    /** 获取全部参数键值表（不可变视图） */
    public Map<String, Object> getParams() {
        return params == null ? Collections.<String, Object>emptyMap() : params;
    }
}
