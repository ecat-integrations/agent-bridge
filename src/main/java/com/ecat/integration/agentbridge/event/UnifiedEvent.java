package com.ecat.integration.agentbridge.event;

import com.ecat.core.Bus.AsyncExecutionInfo;
import com.ecat.core.Bus.ConfigEntryEvent;
import com.ecat.core.Bus.IntegrationLifecycleEvent;
import com.ecat.core.State.AttributeBase;
import com.alibaba.fastjson2.JSONObject;
import com.ecat.integration.EcatCoreApiIntegration.event.EventEnvelope;
import com.ecat.integration.EcatCoreApiIntegration.event.EventPayload;
import com.ecat.integration.EcatCoreApiIntegration.event.IntegrationLifecyclePayload;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 统一事件模型。将不同来源的 Bus 事件统一为 MCP Notification 格式。
 *
 * <p>每种 Bus 事件通过对应的静态工厂方法转换为 UnifiedEvent，
 * 再通过 {@link #toMcpParams()} 序列化为 MCP notification params 格式。
 *
 * <p>每个事件自动生成唯一 eventId（格式：{@code evt-<UUID前8位>}），
 * 外部无需也不应手动构造 eventId。
 *
 * <p>支持的事件类型：
 * <ul>
 *   <li>{@link #DEVICE_DATA_UPDATE} — 设备属性数据变更</li>
 *   <li>{@link #CONFIG_ENTRY_LIFECYCLE} — ConfigEntry 生命周期（增/删/改/启用/停用）</li>
 *   <li>{@link #ASYNC_EXECUTION_COMPLETED} — 异步操作执行完成</li>
 *   <li>{@link #INTEGRATION_LIFECYCLE} — 集成生命周期（加载/卸载/启用/停用）</li>
 * </ul>
 *
 * @author coffee
 */
public class UnifiedEvent {

    /** 设备属性数据变更事件 */
    public static final String DEVICE_DATA_UPDATE = "device_data_update";

    /** ConfigEntry 生命周期事件 */
    public static final String CONFIG_ENTRY_LIFECYCLE = "config_entry_lifecycle";

    /** 异步操作执行完成事件 */
    public static final String ASYNC_EXECUTION_COMPLETED = "async_execution_completed";

    /** 集成生命周期事件 */
    public static final String INTEGRATION_LIFECYCLE = "integration_lifecycle";

    /** 异步操作状态变更事件 */
    public static final String ASYNC_STATUS_CHANGED = "async_status_changed";

    /** 系统通知事件 */
    public static final String NOTIFICATION = "notification";

    /**
     * 生成唯一事件 ID。
     *
     * @return 格式为 "evt-&lt;UUID前8位&gt;"
     */
    private static String generateEventId() {
        return "evt-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 事件唯一标识（自动生成） */
    private final String eventId;

    /** 事件类型 */
    private final String eventType;

    /** 事件来源（如集成坐标、设备 ID 等） */
    private final String source;

    /** 事件数据（不可变） */
    private final Map<String, Object> data;

    /** 事件时间戳（epoch 毫秒） */
    private final long timestamp;

    /**
     * 全参构造器。eventId 自动生成，无需外部传入。
     *
     * @param eventType 事件类型
     * @param source    事件来源
     * @param data      事件数据，可为 null
     * @param timestamp 事件时间戳（epoch 毫秒）
     */
    public UnifiedEvent(String eventType, String source, Map<String, Object> data, long timestamp) {
        if (eventType == null || eventType.isEmpty()) {
            throw new IllegalArgumentException("eventType must not be null or empty");
        }
        this.eventId = generateEventId();
        this.eventType = eventType;
        this.source = source;
        this.data = data != null
                ? Collections.unmodifiableMap(new HashMap<String, Object>(data))
                : Collections.<String, Object>emptyMap();
        this.timestamp = timestamp;
    }

    /**
     * 获取事件唯一标识。
     *
     * @return 事件 ID
     */
    public String getEventId() {
        return eventId;
    }

    /**
     * 获取事件类型。
     *
     * @return 事件类型字符串
     */
    public String getEventType() {
        return eventType;
    }

    /**
     * 获取事件来源。
     *
     * @return 事件来源字符串
     */
    public String getSource() {
        return source;
    }

    /**
     * 获取事件数据（不可变）。
     *
     * @return 事件数据 Map
     */
    public Map<String, Object> getData() {
        return data;
    }

    /**
     * 获取事件时间戳。
     *
     * @return epoch 毫秒
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * 转为 MCP notification params 格式。
     *
     * <p>返回的 Map 包含：
     * <ul>
     *   <li>{@code eventType} — 事件类型</li>
     *   <li>{@code source} — 事件来源</li>
     *   <li>{@code data} — 事件数据</li>
     *   <li>{@code timestamp} — 时间戳</li>
     * </ul>
     *
     * @return MCP notification params
     */
    public Map<String, Object> toMcpParams() {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("eventId", eventId);
        params.put("eventType", eventType);
        params.put("source", source);
        params.put("data", data);
        params.put("timestamp", timestamp);
        return params;
    }

    /**
     * 从 AttributeBase 事件数据创建设备数据更新事件。
     *
     * <p>提取属性 ID、所属设备 ID、属性值等信息作为事件数据。
     *
     * @param eventData Bus 事件数据，预期为 AttributeBase 实例
     * @return UnifiedEvent 实例，如果 eventData 类型不匹配则返回 null
     */
    public static UnifiedEvent fromDeviceDataUpdate(Object eventData) {
        if (!(eventData instanceof AttributeBase)) {
            return null;
        }
        AttributeBase<?> attr = (AttributeBase<?>) eventData;
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("attributeId", attr.getAttributeID());
        if (attr.getDevice() != null) {
            data.put("deviceId", attr.getDevice().getId());
        }
        data.put("value", attr.getValue());
        data.put("displayValue", attr.getDisplayValue());
        String source = attr.getDevice() != null ? attr.getDevice().getId() : "unknown";
        return new UnifiedEvent(DEVICE_DATA_UPDATE, source, data, System.currentTimeMillis());
    }

    /**
     * 从 ConfigEntryEvent 创建 ConfigEntry 生命周期事件。
     *
     * @param eventData Bus 事件数据，预期为 ConfigEntryEvent 实例
     * @return UnifiedEvent 实例，如果 eventData 类型不匹配则返回 null
     */
    public static UnifiedEvent fromConfigEntryLifecycle(Object eventData) {
        if (!(eventData instanceof ConfigEntryEvent)) {
            return null;
        }
        ConfigEntryEvent evt = (ConfigEntryEvent) eventData;
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("entryId", evt.getEntryId());
        data.put("coordinate", evt.getCoordinate());
        data.put("action", evt.getAction().name());
        return new UnifiedEvent(CONFIG_ENTRY_LIFECYCLE, evt.getCoordinate(), data, System.currentTimeMillis());
    }

    /**
     * 从 AsyncExecutionInfo 创建异步操作完成事件。
     *
     * @param eventData Bus 事件数据，预期为 AsyncExecutionInfo 实例
     * @return UnifiedEvent 实例，如果 eventData 类型不匹配则返回 null
     */
    public static UnifiedEvent fromAsyncExecution(Object eventData) {
        if (!(eventData instanceof AsyncExecutionInfo)) {
            return null;
        }
        AsyncExecutionInfo info = (AsyncExecutionInfo) eventData;
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("executionId", info.getAsyncExecutionId());
        data.put("operationType", info.getOperationType());
        data.put("targetPath", info.getTargetPath());
        data.put("clientId", info.getClientId());
        data.put("status", info.getStatus().name());
        if (info.getErrorMessage() != null) {
            data.put("errorMessage", info.getErrorMessage());
        }
        data.put("createdAt", info.getCreatedAt().toEpochMilli());
        data.put("completedAt", info.getCompletedAt() != null ? info.getCompletedAt().toEpochMilli() : 0L);
        return new UnifiedEvent(ASYNC_EXECUTION_COMPLETED, info.getAsyncExecutionId(), data, System.currentTimeMillis());
    }

    /**
     * 从 IntegrationLifecycleEvent 创建集成生命周期事件。
     *
     * @param eventData Bus 事件数据，预期为 IntegrationLifecycleEvent 实例
     * @return UnifiedEvent 实例，如果 eventData 类型不匹配则返回 null
     */
    public static UnifiedEvent fromIntegrationLifecycle(Object eventData) {
        if (!(eventData instanceof IntegrationLifecycleEvent)) {
            return null;
        }
        IntegrationLifecycleEvent evt = (IntegrationLifecycleEvent) eventData;
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("coordinate", evt.getCoordinate());
        data.put("action", evt.getAction().name());
        data.put("effect", evt.getEffect().name());
        return new UnifiedEvent(INTEGRATION_LIFECYCLE, evt.getCoordinate(), data, System.currentTimeMillis());
    }

    /**
     * 从 EventController 的 EventEnvelope 创建 UnifiedEvent。
     *
     * <p>将 EventEnvelope.type 映射到 UnifiedEvent 常量，
     * 从 payload.toJson() 提取事件数据。
     * 支持的类型：
     * <ul>
     *   <li>async.completed → ASYNC_EXECUTION_COMPLETED</li>
     *   <li>async.status_changed → ASYNC_STATUS_CHANGED</li>
     *   <li>config.lifecycle → CONFIG_ENTRY_LIFECYCLE</li>
     *   <li>integration.lifecycle → INTEGRATION_LIFECYCLE</li>
     *   <li>notification → NOTIFICATION</li>
     * </ul>
     *
     * @param envelope EventController 推送的事件信封
     * @return UnifiedEvent 实例，如果类型未知则返回 null
     */
    public static UnifiedEvent fromEventEnvelope(EventEnvelope envelope) {
        if (envelope == null) {
            return null;
        }
        String type = envelope.getType();
        EventPayload payload = envelope.getPayload();
        JSONObject payloadJson = payload.toJson();

        String eventType;
        String source;
        if ("async.completed".equals(type)) {
            eventType = ASYNC_EXECUTION_COMPLETED;
            source = payloadJson.getString("asyncExecutionId");
        } else if ("async.status_changed".equals(type)) {
            eventType = ASYNC_STATUS_CHANGED;
            source = payloadJson.getString("asyncExecutionId");
        } else if ("config.lifecycle".equals(type)) {
            eventType = CONFIG_ENTRY_LIFECYCLE;
            source = payloadJson.getString("coordinate");
        } else if ("integration.lifecycle".equals(type)) {
            eventType = INTEGRATION_LIFECYCLE;
            source = payloadJson.getString("coordinate");
        } else if ("notification".equals(type)) {
            eventType = NOTIFICATION;
            source = "ecat-system";
        } else {
            return null;
        }

        Map<String, Object> data = new HashMap<String, Object>(payloadJson);
        return new UnifiedEvent(eventType, source, data, envelope.getTimestamp().toEpochMilli());
    }
}
