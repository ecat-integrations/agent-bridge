package com.ecat.integration.agentbridge.capability;

import com.ecat.integration.HttpServerIntegration.HttpServerIntegration;
import com.ecat.integration.HttpServerIntegration.RouteDescriptor;
import com.ecat.integration.HttpServerIntegration.RouteDescriptor.SafetyLevel;
import com.ecat.integration.agentbridge.mcp.McpServer;
import com.ecat.integration.agentbridge.mcp.McpSession;
import com.ecat.integration.agentbridge.mcp.JsonRpcNotification;
import com.ecat.integration.agentbridge.tool.McpTool;
import com.ecat.integration.agentbridge.tool.ToolGenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 能力组管理器，负责扫描、索引和管理 ECAT API 能力组。
 *
 * <p>核心职责：
 * <ul>
 *   <li>从 HttpServerIntegration 扫描所有已注册路由的 RouteDescriptor</li>
 *   <li>按 capabilityGroup 分组，使用 {@link ToolGenerator} 为每个路由生成 {@link McpTool}</li>
 *   <li>提供能力组搜索、加载和工具列表管理功能</li>
 *   <li>管理 6 个内置工具（始终可见，不属于任何能力组）</li>
 * </ul>
 *
 * <p>内置工具列表：
 * <ol>
 *   <li>{@code ecat_search_capabilities} — 搜索能力组</li>
 *   <li>{@code ecat_load_capabilities} — 加载能力组</li>
 *   <li>{@code query_async_result} — 查询异步操作结果</li>
 *   <li>{@code confirm_operation} — 确认高风险操作</li>
 *   <li>{@code query_audit_log} — 查询审计日志</li>
 * </ol>
 *
 * @author coffee
 */
public class CapabilityManager {

    /** ecat-core-api 服务器 IP */
    private final String serverIp;

    /** ecat-core-api 服务器端口 */
    private final int serverPort;

    /** HTTP 服务器资源池，用于获取路由描述符 */
    private final HttpServerIntegration httpServerPool;

    /**
     * 能力组工具索引。
     * key: capabilityGroup, value: 该组包含的 McpTool 列表
     */
    private final Map<String, List<McpTool>> capabilityGroupTools = new HashMap<String, List<McpTool>>();

    /**
     * 能力组元数据索引。
     * key: capabilityGroup, value: 摘要信息
     */
    private final Map<String, CapabilityGroupSummary> capabilityGroupMeta = new HashMap<String, CapabilityGroupSummary>();

    /** 内置工具列表（不可变） */
    private final List<McpTool> builtInTools;

    /**
     * 构造器。
     *
     * @param httpServerPool HTTP 服务器资源池
     * @param serverIp       ecat-core-api 服务器 IP
     * @param serverPort     ecat-core-api 服务器端口
     */
    public CapabilityManager(HttpServerIntegration httpServerPool,
                             String serverIp, int serverPort) {
        if (httpServerPool == null) {
            throw new IllegalArgumentException("httpServerPool must not be null");
        }
        if (serverIp == null || serverIp.isEmpty()) {
            throw new IllegalArgumentException("serverIp must not be null or empty");
        }
        this.httpServerPool = httpServerPool;
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        this.builtInTools = Collections.unmodifiableList(createBuiltInTools());
    }

    /**
     * 扫描所有 server 的所有 RouteDescriptor，按 capabilityGroup 分组，
     * 使用 {@link ToolGenerator} 为每个路由生成 {@link McpTool}。
     *
     * <p>扫描结果存储在内存索引中，供后续搜索和加载使用。
     */
    public void buildIndex() {
        Map<String, Map<String, Map<String, RouteDescriptor>>> allDescriptors =
                httpServerPool.getAllRouteDescriptors();

        for (Map.Entry<String, Map<String, Map<String, RouteDescriptor>>> serverEntry
                : allDescriptors.entrySet()) {
            Map<String, Map<String, RouteDescriptor>> urlMap = serverEntry.getValue();
            for (Map.Entry<String, Map<String, RouteDescriptor>> urlEntry : urlMap.entrySet()) {
                String url = urlEntry.getKey();
                Map<String, RouteDescriptor> methodMap = urlEntry.getValue();
                for (Map.Entry<String, RouteDescriptor> methodEntry : methodMap.entrySet()) {
                    String method = methodEntry.getKey();
                    RouteDescriptor descriptor = methodEntry.getValue();
                    if (descriptor == null || descriptor.getCapabilityGroup() == null) {
                        continue;
                    }
                    McpTool tool = ToolGenerator.generate(descriptor, url, method);
                    addToGroup(descriptor, tool);
                }
            }
        }
    }

    /**
     * 清空现有索引，重新扫描所有路由。
     */
    public void rebuildIndex() {
        capabilityGroupTools.clear();
        capabilityGroupMeta.clear();
        buildIndex();
    }

    /**
     * 按 tags/displayName/description 搜索能力组。
     *
     * <p>搜索规则（不区分大小写）：
     * <ul>
     *   <li>query 匹配 groupId</li>
     *   <li>query 匹配 displayName</li>
     *   <li>query 匹配 description</li>
     *   <li>query 匹配某个 tag</li>
     * </ul>
     *
     * @param query 搜索关键词
     * @return 匹配的能力组摘要列表
     */
    public List<CapabilityGroupSummary> searchCapabilities(String query) {
        if (query == null || query.isEmpty()) {
            return Collections.emptyList();
        }
        String lowerQuery = query.toLowerCase();
        List<CapabilityGroupSummary> results = new ArrayList<CapabilityGroupSummary>();
        for (CapabilityGroupSummary summary : capabilityGroupMeta.values()) {
            if (matchesQuery(summary, lowerQuery)) {
                results.add(summary);
            }
        }
        return results;
    }

    /**
     * 加载指定能力组到 session，并发送 tools/list_changed 通知。
     *
     * <p>加载后该 session 的 loadedCapabilityGroups 集合将包含此 groupId，
     * getActiveTools() 返回的工具列表将包含该组的所有工具。
     * 同时通过 McpServer 发送 JSON-RPC 通知，告知 AI Agent 工具列表已变更。
     *
     * @param groupId 能力组 ID
     * @param session MCP 会话
     * @param server  MCP 服务器（用于发送通知，可为 null）
     * @return 加载结果
     */
    public LoadResult loadCapabilities(String groupId, McpSession session, McpServer server) {
        if (groupId == null || groupId.isEmpty()) {
            return LoadResult.failure(groupId, "groupId must not be null or empty");
        }
        if (session == null) {
            return LoadResult.failure(groupId, "session must not be null");
        }
        List<McpTool> tools = capabilityGroupTools.get(groupId);
        if (tools == null || tools.isEmpty()) {
            return LoadResult.failure(groupId, "Capability group not found: " + groupId);
        }

        // 记录已加载的能力组到 session
        session.getLoadedCapabilityGroups().add(groupId);

        // 发送 tools/list_changed 通知
        if (server != null) {
            try {
                JsonRpcNotification notification = new JsonRpcNotification(
                        "notifications/tools/list_changed", null);
                server.sendNotification(session.getId(), notification);
            } catch (Exception e) {
                // 通知失败不影响加载结果，记录日志即可
                System.err.println("[CapabilityManager] Failed to send tools/list_changed "
                        + "notification for group " + groupId + ": " + e.getMessage());
            }
        }

        return LoadResult.success(groupId, tools.size());
    }

    /**
     * 获取 session 的完整工具列表：6 个内置工具 + 已加载能力组的工具。
     *
     * <p>从 session 的 loadedCapabilityGroups 集合查找对应的工具列表。
     *
     * @param session MCP 会话
     * @return 完整的工具列表
     */
    public List<McpTool> getActiveTools(McpSession session) {
        if (session == null) {
            return getBuiltInTools();
        }
        List<McpTool> allTools = new ArrayList<McpTool>(getBuiltInTools());
        for (String groupId : session.getLoadedCapabilityGroups()) {
            List<McpTool> groupTools = capabilityGroupTools.get(groupId);
            if (groupTools != null) {
                allTools.addAll(groupTools);
            }
        }
        return allTools;
    }

    /**
     * 获取 6 个内置工具（始终可见，不属于任何能力组）。
     *
     * @return 内置工具列表（不可变）
     */
    public List<McpTool> getBuiltInTools() {
        return builtInTools;
    }

    /**
     * 将工具添加到对应的能力组索引中。
     *
     * @param descriptor 路由描述符
     * @param tool       生成的工具
     */
    private void addToGroup(RouteDescriptor descriptor, McpTool tool) {
        String groupId = descriptor.getCapabilityGroup();
        List<McpTool> tools = capabilityGroupTools.get(groupId);
        if (tools == null) {
            tools = new ArrayList<McpTool>();
            capabilityGroupTools.put(groupId, tools);
        }
        tools.add(tool);

        // 更新或创建能力组摘要
        updateGroupMeta(groupId, descriptor, tools.size());
    }

    /**
     * 更新能力组摘要元数据。
     *
     * @param groupId    能力组 ID
     * @param descriptor 最新的路由描述符
     * @param toolCount  当前的工具数量
     */
    private void updateGroupMeta(String groupId, RouteDescriptor descriptor, int toolCount) {
        // 取能力组中最高的安全级别
        SafetyLevel existingLevel = getHighestSafetyLevel(groupId);
        SafetyLevel currentLevel = descriptor.getSafetyLevel();
        String highestLevel = currentLevel != null ? currentLevel.name() : SafetyLevel.SAFE.name();
        if (existingLevel != null) {
            highestLevel = higherSafetyLevel(existingLevel, currentLevel).name();
        }

        // 使用第一个路由的 summary 作为 displayName，tags 合并
        CapabilityGroupSummary existing = capabilityGroupMeta.get(groupId);
        String displayName = (existing != null && existing.getDisplayName() != null
                && !existing.getDisplayName().isEmpty())
                ? existing.getDisplayName()
                : (descriptor.getSummary() != null ? descriptor.getSummary() : groupId);
        String description = (existing != null && existing.getDescription() != null
                && !existing.getDescription().isEmpty())
                ? existing.getDescription()
                : (descriptor.getDescription() != null ? descriptor.getDescription() : "");

        // 合并 tags
        List<String> mergedTags = new ArrayList<String>();
        if (existing != null) {
            mergedTags.addAll(existing.getTags());
        }
        if (descriptor.getTags() != null) {
            for (String tag : descriptor.getTags()) {
                if (!mergedTags.contains(tag)) {
                    mergedTags.add(tag);
                }
            }
        }

        capabilityGroupMeta.put(groupId, new CapabilityGroupSummary(
                groupId, displayName, description, toolCount, highestLevel, mergedTags));
    }

    /**
     * 获取能力组中当前的最高安全级别。
     *
     * @param groupId 能力组 ID
     * @return 安全级别，不存在时返回 null
     */
    private SafetyLevel getHighestSafetyLevel(String groupId) {
        CapabilityGroupSummary meta = capabilityGroupMeta.get(groupId);
        if (meta == null || meta.getSafetyLevel() == null) {
            return null;
        }
        try {
            return SafetyLevel.valueOf(meta.getSafetyLevel());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 比较两个安全级别，返回更高的那个。
     * HIGH_RISK > MODERATE > SAFE
     *
     * @param a 安全级别 A（可为 null，默认 SAFE）
     * @param b 安全级别 B（可为 null，默认 SAFE）
     * @return 较高的安全级别
     */
    private SafetyLevel higherSafetyLevel(SafetyLevel a, SafetyLevel b) {
        if (a == null) { a = SafetyLevel.SAFE; }
        if (b == null) { b = SafetyLevel.SAFE; }
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /**
     * 判断能力组摘要是否匹配搜索关键词。
     *
     * @param summary    能力组摘要
     * @param lowerQuery 小写的搜索关键词
     * @return 是否匹配
     */
    private boolean matchesQuery(CapabilityGroupSummary summary, String lowerQuery) {
        if (summary.getGroupId() != null
                && summary.getGroupId().toLowerCase().contains(lowerQuery)) {
            return true;
        }
        if (summary.getDisplayName() != null
                && summary.getDisplayName().toLowerCase().contains(lowerQuery)) {
            return true;
        }
        if (summary.getDescription() != null
                && summary.getDescription().toLowerCase().contains(lowerQuery)) {
            return true;
        }
        for (String tag : summary.getTags()) {
            if (tag != null && tag.toLowerCase().contains(lowerQuery)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 创建 6 个内置工具定义。
     *
     * <p>内置工具始终可见，不属于任何能力组，无对应的 HTTP 路由
     * （sourceUrl 和 sourceMethod 均为 null）。
     *
     * @return 内置工具列表
     */
    private static List<McpTool> createBuiltInTools() {
        List<McpTool> tools = new ArrayList<McpTool>();

        // 1. ecat_search_capabilities
        tools.add(new McpTool(
                "ecat_search_capabilities",
                "搜索 ECAT 能力组\n根据关键词搜索可用的能力组，返回匹配的能力组摘要列表",
                createSearchSchema(),
                createBuiltInAnnotations(SafetyLevel.SAFE, true, true),
                null, null
        ));

        // 2. ecat_load_capabilities
        tools.add(new McpTool(
                "ecat_load_capabilities",
                "加载能力组\n将指定能力组的工具加载到当前会话，加载后 AI Agent 可以使用这些工具",
                createLoadSchema(),
                createBuiltInAnnotations(SafetyLevel.SAFE, false, false),
                null, null
        ));

        // 3. query_async_result
        tools.add(new McpTool(
                "query_async_result",
                "查询异步操作结果\n根据异步任务 ID 查询操作的执行状态和结果",
                createAsyncResultSchema(),
                createBuiltInAnnotations(SafetyLevel.SAFE, true, true),
                null, null
        ));

        // 5. confirm_operation
        tools.add(new McpTool(
                "confirm_operation",
                "确认高风险操作\n对高风险操作进行人工确认，需提供操作确认码",
                createConfirmSchema(),
                createBuiltInAnnotations(SafetyLevel.HIGH_RISK, false, false),
                null, null
        ));

        // 6. query_audit_log
        tools.add(new McpTool(
                "query_audit_log",
                "查询审计日志\n查询 Agent 操作的审计日志记录",
                createAuditLogSchema(),
                createBuiltInAnnotations(SafetyLevel.SAFE, true, true),
                null, null
        ));

        return tools;
    }

    /**
     * 创建 ecat_search_capabilities 的输入 Schema。
     */
    private static Map<String, Object> createSearchSchema() {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        Map<String, Object> queryProp = new LinkedHashMap<String, Object>();
        queryProp.put("type", "string");
        queryProp.put("description", "搜索关键词");
        properties.put("query", queryProp);
        schema.put("properties", properties);
        List<String> required = new ArrayList<String>();
        required.add("query");
        schema.put("required", required);
        return schema;
    }

    /**
     * 创建 ecat_load_capabilities 的输入 Schema。
     */
    private static Map<String, Object> createLoadSchema() {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        Map<String, Object> groupIdProp = new LinkedHashMap<String, Object>();
        groupIdProp.put("type", "string");
        groupIdProp.put("description", "能力组 ID");
        properties.put("groupId", groupIdProp);
        schema.put("properties", properties);
        List<String> required = new ArrayList<String>();
        required.add("groupId");
        schema.put("required", required);
        return schema;
    }

    /**
     * 创建 query_async_result 的输入 Schema。
     */
    private static Map<String, Object> createAsyncResultSchema() {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<String, Object>();

        Map<String, Object> executionIdProp = new LinkedHashMap<String, Object>();
        executionIdProp.put("type", "string");
        executionIdProp.put("description", "异步任务 ID");
        properties.put("executionId", executionIdProp);

        schema.put("properties", properties);
        List<String> required = new ArrayList<String>();
        required.add("executionId");
        schema.put("required", required);
        return schema;
    }

    /**
     * 创建 confirm_operation 的输入 Schema。
     */
    private static Map<String, Object> createConfirmSchema() {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<String, Object>();

        Map<String, Object> confirmCodeProp = new LinkedHashMap<String, Object>();
        confirmCodeProp.put("type", "string");
        confirmCodeProp.put("description", "操作确认码");
        properties.put("confirmCode", confirmCodeProp);

        Map<String, Object> operationIdProp = new LinkedHashMap<String, Object>();
        operationIdProp.put("type", "string");
        operationIdProp.put("description", "待确认的操作 ID");
        properties.put("operationId", operationIdProp);

        schema.put("properties", properties);
        List<String> required = new ArrayList<String>();
        required.add("confirmCode");
        required.add("operationId");
        schema.put("required", required);
        return schema;
    }

    /**
     * 创建 query_audit_log 的输入 Schema。
     */
    private static Map<String, Object> createAuditLogSchema() {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<String, Object>();

        Map<String, Object> startTimeProp = new LinkedHashMap<String, Object>();
        startTimeProp.put("type", "string");
        startTimeProp.put("description", "起始时间（ISO 8601 格式）");
        properties.put("startTime", startTimeProp);

        Map<String, Object> endTimeProp = new LinkedHashMap<String, Object>();
        endTimeProp.put("type", "string");
        endTimeProp.put("description", "结束时间（ISO 8601 格式）");
        properties.put("endTime", endTimeProp);

        Map<String, Object> agentIdProp = new LinkedHashMap<String, Object>();
        agentIdProp.put("type", "string");
        agentIdProp.put("description", "Agent ID 过滤（可选）");
        properties.put("agentId", agentIdProp);

        Map<String, Object> limitProp = new LinkedHashMap<String, Object>();
        limitProp.put("type", "integer");
        limitProp.put("description", "返回记录数上限，默认 50");
        properties.put("limit", limitProp);

        schema.put("properties", properties);
        // 无必填字段
        return schema;
    }

    /**
     * 创建内置工具的注解。
     *
     * @param safetyLevel 安全级别
     * @param readOnly    是否只读
     * @param idempotent  是否幂等
     * @return 注解 Map
     */
    private static Map<String, Object> createBuiltInAnnotations(
            SafetyLevel safetyLevel, boolean readOnly, boolean idempotent) {
        Map<String, Object> annotations = new LinkedHashMap<String, Object>();
        annotations.put("safetyLevel", safetyLevel.name());
        annotations.put("readOnlyHint", readOnly);
        annotations.put("idempotentHint", idempotent);
        annotations.put("async", false);
        return annotations;
    }
}
