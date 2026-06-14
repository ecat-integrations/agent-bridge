package com.ecat.integration.agentbridge.tool;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 工具定义，描述一个可供 AI Agent 调用的工具。
 *
 * <p>每个 McpTool 对应一个 ECAT API 路由或内置操作。包含名称、描述、
 * JSON Schema 格式的输入参数定义，以及安全级别等注解信息。
 *
 * <p>{@link #toMcpFormat()} 生成符合 MCP tools/list 响应标准的对象格式，
 * 仅包含 name、description、inputSchema、annotations 四个字段，
 * 不暴露内部使用的 sourceUrl 和 sourceMethod。
 *
 * @author coffee
 */
public class McpTool {

    /** 工具名称，全局唯一标识符 */
    private final String name;

    /** 工具描述，供 AI Agent 理解工具用途 */
    private final String description;

    /**
     * 输入参数 JSON Schema。
     * 格式：type=object, properties={...}, required=[...]
     */
    private final Map<String, Object> inputSchema;

    /**
     * 工具注解，包含 safetyLevel、readOnlyHint、idempotentHint 等。
     */
    private final Map<String, Object> annotations;

    /**
     * 原始 URL 路径（内部使用，不暴露给 MCP 客户端）。
     * 仅用于 ToolExecutor 反向构造 HTTP 请求。
     */
    private final String sourceUrl;

    /**
     * 原始 HTTP 方法（内部使用，不暴露给 MCP 客户端）。
     * 仅用于 ToolExecutor 反向构造 HTTP 请求。
     */
    private final String sourceMethod;

    /**
     * 全参构造器。
     *
     * @param name         工具名称
     * @param description  工具描述
     * @param inputSchema  输入参数 JSON Schema
     * @param annotations  工具注解
     * @param sourceUrl    原始 URL 路径（可为 null，内置工具无对应路由）
     * @param sourceMethod 原始 HTTP 方法（可为 null，内置工具无对应路由）
     */
    public McpTool(String name, String description,
                   Map<String, Object> inputSchema,
                   Map<String, Object> annotations,
                   String sourceUrl, String sourceMethod) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name must not be null or empty");
        }
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema != null
                ? Collections.unmodifiableMap(new HashMap<String, Object>(inputSchema))
                : Collections.<String, Object>emptyMap();
        this.annotations = annotations != null
                ? Collections.unmodifiableMap(new HashMap<String, Object>(annotations))
                : Collections.<String, Object>emptyMap();
        this.sourceUrl = sourceUrl;
        this.sourceMethod = sourceMethod;
    }

    /**
     * 获取工具名称。
     *
     * @return 工具名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取工具描述。
     *
     * @return 工具描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 获取输入参数 JSON Schema（不可变）。
     *
     * @return JSON Schema Map
     */
    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }

    /**
     * 获取工具注解（不可变）。
     *
     * @return 注解 Map
     */
    public Map<String, Object> getAnnotations() {
        return annotations;
    }

    /**
     * 获取原始 URL 路径。
     *
     * @return URL 路径，内置工具返回 null
     */
    public String getSourceUrl() {
        return sourceUrl;
    }

    /**
     * 获取原始 HTTP 方法。
     *
     * @return HTTP 方法（GET/POST/PUT/DELETE），内置工具返回 null
     */
    public String getSourceMethod() {
        return sourceMethod;
    }

    /**
     * 转为 MCP tools/list 响应中的 tool 对象格式。
     *
     * <p>输出格式：
     * <pre>{
     *   "name": "tool_name",
     *   "description": "tool description",
     *   "inputSchema": { "type": "object", "properties": {...}, "required": [...] },
     *   "annotations": { "safetyLevel": "SAFE", "readOnlyHint": true, ... }
     * }</pre>
     *
     * <p>注意：sourceUrl 和 sourceMethod 不包含在输出中。
     *
     * @return MCP 协议格式的工具描述 Map
     */
    public Map<String, Object> toMcpFormat() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("name", name);
        map.put("description", description);
        map.put("inputSchema", inputSchema);
        map.put("annotations", annotations);
        return map;
    }
}
