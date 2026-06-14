package com.ecat.integration.agentbridge.tool;

import com.ecat.integration.HttpServerIntegration.RouteDescriptor;
import com.ecat.integration.HttpServerIntegration.RouteDescriptor.ParamDescriptor;
import com.ecat.integration.HttpServerIntegration.RouteDescriptor.SafetyLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 RouteDescriptor 生成 McpTool 的工具类。
 *
 * <p>负责将 ECAT API 路由的元数据（URL、HTTP 方法、参数描述、安全级别等）
 * 转换为符合 MCP 协议的工具定义，包括工具名称转换、JSON Schema 生成和安全注解映射。
 *
 * @author coffee
 */
public class ToolGenerator {

    /**
     * 将 URL 路径和 HTTP 方法转换为合法的 MCP 工具名称。
     *
     * <p>转换规则：
     * <ul>
     *   <li>路径参数 {xxx} 替换为 _xxx_</li>
     *   <li>/ 替换为 _</li>
     *   <li>前导 _ 去掉</li>
     *   <li>小写方法名前缀 + _ + 转换后的路径</li>
     * </ul>
     *
     * <p>示例：
     * <ul>
     *   <li>{@code /core-api/devices/{id}/attributes/{attrId}/value + PUT}
     *       → {@code put_core_api_devices__id__attributes__attrid__value}</li>
     *   <li>{@code /core-api/devices + GET} → {@code get_core_api_devices}</li>
     * </ul>
     *
     * @param url    URL 路径
     * @param method HTTP 方法
     * @return 合法的 MCP 工具名称（小写，下划线分隔）
     */
    public static String urlToToolName(String url, String method) {
        if (url == null || method == null) {
            throw new IllegalArgumentException("url and method must not be null");
        }
        // 路径参数 {xxx} → _xxx_
        String name = url.replaceAll("\\{([^}]+)\\}", "_$1_");
        // / → _
        name = name.replace("/", "_");
        // 去掉前导 _
        while (name.startsWith("_")) {
            name = name.substring(1);
        }
        // 小写方法名 + _ + 路径
        return method.toLowerCase() + "_" + name.toLowerCase();
    }

    /**
     * 从 RouteDescriptor 生成完整的 McpTool。
     *
     * <p>生成内容包括：
     * <ul>
     *   <li>name: 通过 {@link #urlToToolName} 转换</li>
     *   <li>description: summary + description 拼接</li>
     *   <li>inputSchema: 从 ParamDescriptor 列表生成 JSON Schema</li>
     *   <li>annotations: safetyLevel、readOnly、idempotent 等安全注解</li>
     * </ul>
     *
     * @param descriptor 路由描述符
     * @param url        原始 URL 路径
     * @param method     HTTP 方法
     * @return 生成的 McpTool 实例
     */
    public static McpTool generate(RouteDescriptor descriptor, String url, String method) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null");
        }
        if (url == null || method == null) {
            throw new IllegalArgumentException("url and method must not be null");
        }

        String name = urlToToolName(url, method);

        // 拼接 description
        String description = buildDescription(descriptor);

        // 从 ParamDescriptor 生成 JSON Schema
        Map<String, Object> inputSchema = buildInputSchema(descriptor);

        // 生成安全注解
        Map<String, Object> annotations = buildAnnotations(descriptor, method);

        return new McpTool(name, description, inputSchema, annotations, url, method);
    }

    /**
     * 拼接工具描述：summary + description。
     *
     * @param descriptor 路由描述符
     * @return 描述文本
     */
    private static String buildDescription(RouteDescriptor descriptor) {
        StringBuilder sb = new StringBuilder();
        if (descriptor.getSummary() != null && !descriptor.getSummary().isEmpty()) {
            sb.append(descriptor.getSummary());
        }
        if (descriptor.getDescription() != null && !descriptor.getDescription().isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(descriptor.getDescription());
        }
        return sb.toString();
    }

    /**
     * 从 ParamDescriptor 列表生成 JSON Schema。
     *
     * <p>输出格式：
     * <pre>{
     *   "type": "object",
     *   "properties": { "paramName": { "type": "string", "description": "..." } },
     *   "required": ["param1", "param2"]
     * }</pre>
     *
     * @param descriptor 路由描述符
     * @return JSON Schema Map
     */
    private static Map<String, Object> buildInputSchema(RouteDescriptor descriptor) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        List<String> required = new ArrayList<String>();

        List<ParamDescriptor> params = descriptor.getParams();
        if (params != null) {
            for (ParamDescriptor param : params) {
                Map<String, Object> propDef = new LinkedHashMap<String, Object>();
                propDef.put("type", mapParamType(param.getType()));
                if (param.getDescription() != null) {
                    propDef.put("description", param.getDescription());
                }
                if (param.getDefaultValue() != null) {
                    propDef.put("default", param.getDefaultValue());
                }
                properties.put(param.getName(), propDef);
                if (param.isRequired()) {
                    required.add(param.getName());
                }
            }
        }

        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    /**
     * 将 RouteDescriptor 中的参数类型映射到 JSON Schema 类型。
     *
     * @param type RouteDescriptor 中定义的参数类型
     * @return JSON Schema 类型字符串
     */
    private static String mapParamType(String type) {
        if (type == null) {
            return "string";
        }
        String lower = type.toLowerCase();
        if ("int".equals(lower) || "integer".equals(lower) || "long".equals(lower)) {
            return "integer";
        }
        if ("double".equals(lower) || "float".equals(lower) || "number".equals(lower)) {
            return "number";
        }
        if ("boolean".equals(lower) || "bool".equals(lower)) {
            return "boolean";
        }
        if ("array".equals(lower) || "list".equals(lower)) {
            return "array";
        }
        if ("object".equals(lower) || "map".equals(lower)) {
            return "object";
        }
        return "string";
    }

    /**
     * 生成工具注解，包含安全级别和操作特征。
     *
     * @param descriptor 路由描述符
     * @param method     HTTP 方法
     * @return 注解 Map
     */
    private static Map<String, Object> buildAnnotations(RouteDescriptor descriptor, String method) {
        Map<String, Object> annotations = new LinkedHashMap<String, Object>();

        // safetyLevel
        SafetyLevel level = descriptor.getSafetyLevel();
        if (level != null) {
            annotations.put("safetyLevel", level.name());
        } else {
            annotations.put("safetyLevel", SafetyLevel.SAFE.name());
        }

        // readOnly: GET/HEAD 为 true
        String upperMethod = method.toUpperCase();
        boolean readOnly = "GET".equals(upperMethod) || "HEAD".equals(upperMethod);
        annotations.put("readOnlyHint", readOnly);

        // idempotent: GET/PUT/DELETE 为 true
        boolean idempotent = "GET".equals(upperMethod) || "PUT".equals(upperMethod)
                || "DELETE".equals(upperMethod) || "HEAD".equals(upperMethod);
        annotations.put("idempotentHint", idempotent);

        // async
        annotations.put("async", descriptor.isAsync());

        return annotations;
    }
}
