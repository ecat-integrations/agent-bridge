package com.ecat.integration.agentbridge.resource;

import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceRegistry;
import com.ecat.core.Device.DeviceStatus;
import com.ecat.core.EcatCore;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.integration.agentbridge.audit.AuditLogger;
import com.ecat.integration.agentbridge.audit.AuditRecord;
import com.ecat.integration.agentbridge.capability.CapabilityGroupSummary;
import com.ecat.integration.agentbridge.capability.CapabilityManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MCP Resources 实现。提供系统信息和文档资源的只读访问。
 *
 * <p>支持以下资源 URI：
 * <ul>
 *   <li>{@code ecat://system/info} — 系统信息（版本、运行时间、已加载集成数量）</li>
 *   <li>{@code ecat://devices/overview} — 设备概览（数量、状态统计）</li>
 *   <li>{@code ecat://capabilities/loaded} — 已加载能力组列表</li>
 *   <li>{@code ecat://audit/recent} — 最近审计记录</li>
 * </ul>
 *
 * <p>{@link CapabilityManager} 和 {@link AuditLogger} 通过 setter 注入以避免循环依赖。
 *
 * @author coffee
 */
public class ResourceManager {

    private static final Logger log = Logger.getLogger(ResourceManager.class.getName());

    /** 资源 URI 前缀 */
    private static final String URI_PREFIX = "ecat://";

    /** 系统信息资源 URI */
    private static final String URI_SYSTEM_INFO = "ecat://system/info";

    /** 设备概览资源 URI */
    private static final String URI_DEVICES_OVERVIEW = "ecat://devices/overview";

    /** 已加载能力组资源 URI */
    private static final String URI_CAPABILITIES_LOADED = "ecat://capabilities/loaded";

    /** 最近审计记录资源 URI */
    private static final String URI_AUDIT_RECENT = "ecat://audit/recent";

    /** EcatCore 实例 */
    private final EcatCore core;

    /** 能力组管理器（通过 setter 注入） */
    private CapabilityManager capabilityManager;

    /** 审计日志管理器（通过 setter 注入） */
    private AuditLogger auditLogger;

    /** 系统启动时间戳（epoch 毫秒） */
    private final long startTimeMs;

    /**
     * 构造器。
     *
     * @param core EcatCore 实例，不能为 null
     */
    public ResourceManager(EcatCore core) {
        if (core == null) {
            throw new IllegalArgumentException("core must not be null");
        }
        this.core = core;
        this.startTimeMs = System.currentTimeMillis();
    }

    /**
     * 注入 CapabilityManager（避免循环依赖）。
     *
     * @param cm 能力组管理器实例
     */
    public void setCapabilityManager(Object cm) {
        if (cm instanceof CapabilityManager) {
            this.capabilityManager = (CapabilityManager) cm;
        }
    }

    /**
     * 注入 AuditLogger（避免循环依赖）。
     *
     * @param al 审计日志管理器实例
     */
    public void setAuditLogger(Object al) {
        if (al instanceof AuditLogger) {
            this.auditLogger = (AuditLogger) al;
        }
    }

    /**
     * 返回 MCP resources/list 响应。
     *
     * <p>包含 4 个根资源的描述列表，每个包含 uri、name、description、mimeType。
     *
     * @return 资源描述列表（不可变）
     */
    public List<Map<String, Object>> listResources() {
        List<Map<String, Object>> resources = new ArrayList<Map<String, Object>>();
        resources.add(createResourceEntry(URI_SYSTEM_INFO,
                "系统信息", "ECAT 系统版本、运行时间、已加载集成数量", "application/json"));
        resources.add(createResourceEntry(URI_DEVICES_OVERVIEW,
                "设备概览", "已注册设备数量及状态统计", "application/json"));
        resources.add(createResourceEntry(URI_CAPABILITIES_LOADED,
                "已加载能力组", "当前已加载的 MCP 能力组列表", "application/json"));
        resources.add(createResourceEntry(URI_AUDIT_RECENT,
                "最近审计记录", "最近 20 条 Agent 操作审计日志", "application/json"));
        return Collections.unmodifiableList(resources);
    }

    /**
     * 读取指定 URI 的资源内容。
     *
     * <p>根据 URI 前缀分发到对应的处理方法。不支持的 URI 抛出 IllegalArgumentException。
     *
     * @param uri 资源 URI，不能为 null
     * @return 资源内容 Map
     * @throws IllegalArgumentException URI 不支持时抛出
     */
    public Map<String, Object> readResource(String uri) {
        if (uri == null || uri.isEmpty()) {
            throw new IllegalArgumentException("uri must not be null or empty");
        }
        if (URI_SYSTEM_INFO.equals(uri)) {
            return readSystemInfo();
        }
        if (URI_DEVICES_OVERVIEW.equals(uri)) {
            return readDevicesOverview();
        }
        if (URI_CAPABILITIES_LOADED.equals(uri)) {
            return readCapabilitiesLoaded();
        }
        if (URI_AUDIT_RECENT.equals(uri)) {
            return readAuditRecent();
        }
        throw new IllegalArgumentException("Unsupported resource URI: " + uri);
    }

    /**
     * 订阅资源更新，返回该资源关联的事件类型列表。
     *
     * <p>用于 MCP resources/subscribe 协议方法，告知 Agent 哪些事件类型
     * 会触发该资源的更新通知。
     *
     * @param uri 资源 URI，不能为 null
     * @return 关联的事件类型列表
     * @throws IllegalArgumentException URI 不支持时抛出
     */
    public List<String> subscribeResource(String uri) {
        if (uri == null || uri.isEmpty()) {
            throw new IllegalArgumentException("uri must not be null or empty");
        }
        if (URI_SYSTEM_INFO.equals(uri)) {
            List<String> events = new ArrayList<String>();
            events.add("integration_lifecycle");
            return events;
        }
        if (URI_DEVICES_OVERVIEW.equals(uri)) {
            List<String> events = new ArrayList<String>();
            events.add("device_data_update");
            events.add("config_entry_lifecycle");
            return events;
        }
        if (URI_CAPABILITIES_LOADED.equals(uri)) {
            List<String> events = new ArrayList<String>();
            events.add("integration_lifecycle");
            return events;
        }
        if (URI_AUDIT_RECENT.equals(uri)) {
            // 审计记录无实时事件触发
            return Collections.emptyList();
        }
        throw new IllegalArgumentException("Unsupported resource URI: " + uri);
    }

    // ===== 内部资源读取方法 =====

    /**
     * 读取系统信息。
     *
     * @return 包含 version、uptime、integrationCount、deviceCount 的 Map
     */
    private Map<String, Object> readSystemInfo() {
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("version", "2.0.0");
        info.put("uptime", System.currentTimeMillis() - startTimeMs);
        int integrationCount = 0;
        int deviceCount = 0;
        try {
            IntegrationRegistry reg = core.getIntegrationRegistry();
            if (reg != null && reg.getAllCoordinates() != null) {
                integrationCount = reg.getAllCoordinates().size();
            }
        } catch (Exception e) {
            log.log(Level.FINE, "Failed to get integration count", e);
        }
        try {
            DeviceRegistry devReg = core.getDeviceRegistry();
            if (devReg != null && devReg.getAllDevices() != null) {
                deviceCount = devReg.getAllDevices().size();
            }
        } catch (Exception e) {
            log.log(Level.FINE, "Failed to get device count", e);
        }
        info.put("integrationCount", integrationCount);
        info.put("deviceCount", deviceCount);
        return info;
    }

    /**
     * 读取设备概览。
     *
     * <p>统计各状态（NORMAL、OFFLINE、ALARM 等）的设备数量。
     *
     * @return 包含 total、statusCounts 的 Map
     */
    private Map<String, Object> readDevicesOverview() {
        Map<String, Object> overview = new LinkedHashMap<String, Object>();
        Map<String, Integer> statusCounts = new LinkedHashMap<String, Integer>();
        try {
            DeviceRegistry devReg = core.getDeviceRegistry();
            if (devReg != null) {
                List<DeviceBase> devices = devReg.getAllDevices();
                if (devices != null) {
                    overview.put("total", devices.size());
                    for (DeviceBase device : devices) {
                        DeviceStatus status = device.getDeviceStatus();
                        String statusName = status != null ? status.name() : "UNKNOWN";
                        Integer count = statusCounts.get(statusName);
                        statusCounts.put(statusName, count != null ? count + 1 : 1);
                    }
                } else {
                    overview.put("total", 0);
                }
            } else {
                overview.put("total", 0);
            }
        } catch (Exception e) {
            log.log(Level.FINE, "Failed to get devices overview", e);
            overview.put("total", 0);
        }
        overview.put("statusCounts", statusCounts);
        return overview;
    }

    /**
     * 读取已加载能力组列表。
     *
     * <p>使用多个通用关键词进行搜索以尽量覆盖所有能力组。
     * CapabilityManager 的 searchCapabilities 对空串返回空列表，
     * 因此使用常见能力组关键词进行搜索并去重。
     *
     * @return 包含 groups 列表的 Map
     */
    private Map<String, Object> readCapabilitiesLoaded() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        if (capabilityManager != null) {
            // searchCapabilities("") returns empty; use common keywords to discover groups
            // and deduplicate by groupId
            Map<String, CapabilityGroupSummary> seen = new HashMap<String, CapabilityGroupSummary>();
            String[] keywords = {"device", "config", "calibration", "media", "logic",
                    "station", "audit", "async", "status", "management"};
            for (String keyword : keywords) {
                for (CapabilityGroupSummary summary : capabilityManager.searchCapabilities(keyword)) {
                    if (!seen.containsKey(summary.getGroupId())) {
                        seen.put(summary.getGroupId(), summary);
                    }
                }
            }
            for (CapabilityGroupSummary summary : seen.values()) {
                Map<String, Object> group = new LinkedHashMap<String, Object>();
                group.put("groupId", summary.getGroupId());
                group.put("displayName", summary.getDisplayName());
                group.put("toolCount", summary.getToolCount());
                group.put("safetyLevel", summary.getSafetyLevel());
                groups.add(group);
            }
        }
        result.put("groups", groups);
        result.put("total", groups.size());
        return result;
    }

    /**
     * 读取最近审计记录（默认 20 条）。
     *
     * @return 包含 records 列表的 Map
     */
    private Map<String, Object> readAuditRecent() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> records = new ArrayList<Map<String, Object>>();
        if (auditLogger != null) {
            long now = System.currentTimeMillis();
            long from = now - 24 * 60 * 60 * 1000L; // 最近 24 小时
            List<AuditRecord> auditRecords = auditLogger.query(from, now, 20);
            if (auditRecords != null) {
                for (AuditRecord record : auditRecords) {
                    Map<String, Object> entry = new LinkedHashMap<String, Object>();
                    entry.put("timestamp", record.getTimestamp());
                    entry.put("agentId", record.getAgentId());
                    entry.put("toolName", record.getToolName());
                    entry.put("status", record.getStatus());
                    entry.put("sessionId", record.getSessionId());
                    records.add(entry);
                }
            }
        }
        result.put("records", records);
        result.put("total", records.size());
        return result;
    }

    /**
     * 创建单个资源描述条目。
     *
     * @param uri         资源 URI
     * @param name        资源名称
     * @param description 资源描述
     * @param mimeType    MIME 类型
     * @return 资源描述 Map
     */
    private static Map<String, Object> createResourceEntry(String uri, String name,
                                                            String description, String mimeType) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("uri", uri);
        entry.put("name", name);
        entry.put("description", description);
        entry.put("mimeType", mimeType);
        return entry;
    }
}
