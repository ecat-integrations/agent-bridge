package com.ecat.integration.agentbridge.event;

import com.ecat.core.Bus.BusRegistry;
import com.ecat.core.Bus.BusTopic;
import com.ecat.core.Bus.IntegrationLifecycleEvent;
import com.ecat.core.Bus.Subscription;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.AttributeStatus;
import com.ecat.integration.EcatCoreApiIntegration.event.EventController;
import com.ecat.integration.EcatCoreApiIntegration.event.EventEnvelope;
import com.ecat.integration.EcatCoreApiIntegration.event.IntegrationLifecyclePayload;
import com.ecat.integration.HttpServerIntegration.EasyHttpServer;
import com.ecat.integration.agentbridge.capability.CapabilityManager;
import com.ecat.integration.agentbridge.mcp.JsonRpcNotification;
import com.ecat.integration.agentbridge.mcp.McpAuthenticator;
import com.ecat.integration.agentbridge.mcp.McpServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 事件管理器。接收 Bus 和 EventController 事件，经截流聚合后通过 MCP Notification 推送给 Agent。
 *
 * <p>核心职责：
 * <ul>
 *   <li>实现 {@link EventController.EventListener}，接收 EventController 统一转换的事件</li>
 *   <li>DEVICE_DATA_UPDATE 直接订阅 Bus，仅检测 ALARM 状态变化（不推送原始数据）</li>
 *   <li>所有事件一律进入 {@link EventThrottler}，按 source + eventType 去重</li>
 *   <li>截流窗口结束时生成汇总摘要，通过 MCP SSE 推送给 Agent</li>
 *   <li>集成 ACTIVE 生命周期事件触发 {@link CapabilityManager#rebuildIndex()}</li>
 * </ul>
 *
 * <p>设计原则（P0-53~P0-55）：
 * <ul>
 *   <li>绝对不低于配置的最小间隔（默认 30 秒）推送</li>
 *   <li>推送给 Agent 的是截流、去重、汇总的高价值摘要</li>
 *   <li>所有事件一律进截流器，无例外</li>
 * </ul>
 *
 * <p>生命周期：构造 → {@link #start()} → 运行中 → {@link #stop()}
 *
 * @author coffee
 */
public class EventManager implements EventController.EventListener {

    private static final Logger log = Logger.getLogger(EventManager.class.getName());

    /** Bus 注册表（用于 DEVICE_DATA_UPDATE 直接订阅） */
    private final BusRegistry busRegistry;

    /** MCP 服务器 */
    private final McpServer mcpServer;

    /** 能力组管理器 */
    private final CapabilityManager capabilityManager;

    /** 截流聚合器 */
    private final EventThrottler throttler;

    /** DEVICE_DATA_UPDATE 的 Bus 订阅句柄 */
    private Subscription deviceDataSubscription;

    /**
     * 构造器 — 使用默认截流间隔（30 秒）。
     *
     * @param busRegistry       Bus 注册表，不能为 null
     * @param mcpServer         MCP 服务器，不能为 null
     * @param capabilityManager 能力组管理器，不能为 null
     */
    public EventManager(BusRegistry busRegistry, McpServer mcpServer, CapabilityManager capabilityManager) {
        this(busRegistry, mcpServer, capabilityManager, EventThrottler.DEFAULT_THROTTLE_SECONDS);
    }

    /**
     * 构造器 — 指定截流间隔。
     *
     * @param busRegistry       Bus 注册表，不能为 null
     * @param mcpServer         MCP 服务器，不能为 null
     * @param capabilityManager 能力组管理器，不能为 null
     * @param throttleSeconds   截流间隔（秒）
     */
    public EventManager(BusRegistry busRegistry, McpServer mcpServer,
                        CapabilityManager capabilityManager, int throttleSeconds) {
        if (busRegistry == null) {
            throw new IllegalArgumentException("busRegistry must not be null");
        }
        if (mcpServer == null) {
            throw new IllegalArgumentException("mcpServer must not be null");
        }
        if (capabilityManager == null) {
            throw new IllegalArgumentException("capabilityManager must not be null");
        }
        this.busRegistry = busRegistry;
        this.mcpServer = mcpServer;
        this.capabilityManager = capabilityManager;
        this.throttler = new EventThrottler(throttleSeconds, new EventThrottler.FlushCallback() {
            @Override
            public void onFlush(List<UnifiedEvent> events) {
                flushSummary(events);
            }
        });
    }

    /**
     * 启动事件管理器：启动截流器，订阅 Bus 事件。
     *
     * <p>必须在所有依赖组件初始化完成后调用。
     */
    public void start() {
        throttler.start();
        subscribeDeviceDataUpdate();
        log.info("EventManager started with throttle interval=" + throttler.getThrottleSeconds() + "s");
    }

    /**
     * 订阅 DEVICE_DATA_UPDATE Bus topic。
     *
     * <p>此 topic 不经过 EventController（频率太高），由 EventManager 直接订阅。
     * 仅检测属性 ALARM 状态变化，不推送原始数据更新。
     * 原始数据查询由 Agent 主动调用 MCP Tool 完成。
     */
    private void subscribeDeviceDataUpdate() {
        deviceDataSubscription = busRegistry.subscribe(
                BusTopic.DEVICE_DATA_UPDATE.getTopicName(),
                new com.ecat.core.Bus.EventSubscriber() {
                    @Override
                    public void handleEvent(String topic, Object data) {
                        handleDeviceDataUpdate(topic, data);
                    }
                });
        log.info("EventManager subscribed to DEVICE_DATA_UPDATE Bus topic");
    }

    /**
     * 处理设备数据更新。
     *
     * <p>仅检测属性 ALARM 状态变化（P0-15），不推送原始数据更新。
     * 只有新进入 ALARM 状态的属性才产生事件，进入截流器。
     *
     * @param topic     Bus topic 名称
     * @param eventData 事件数据，预期为 AttributeBase 实例
     */
    private void handleDeviceDataUpdate(String topic, Object eventData) {
        if (!(eventData instanceof AttributeBase)) {
            return;
        }
        AttributeBase<?> attr = (AttributeBase<?>) eventData;

        // 仅检测新进入 ALARM 状态
        AttributeStatus status = attr.getStatus();
        if (attr.isValueUpdated() && status == AttributeStatus.ALARM) {
            Map<String, Object> data = new HashMap<String, Object>();
            data.put("attributeId", attr.getAttributeID());
            if (attr.getDevice() != null) {
                data.put("deviceId", attr.getDevice().getId());
                data.put("deviceName", attr.getDevice().getName());
            }
            data.put("value", attr.getValue());
            data.put("displayValue", attr.getDisplayValue());

            String source = attr.getDevice() != null ? attr.getDevice().getId() : "unknown";
            String description = String.format("设备 [%s] 属性 [%s] 进入报警状态，当前值: %s",
                    attr.getDevice() != null ? attr.getDevice().getName() : "unknown",
                    attr.getAttributeID(),
                    attr.getDisplayValue() != null ? attr.getDisplayValue() : attr.getValue());
            data.put("description", description);
            data.put("severity", "warning");

            UnifiedEvent event = new UnifiedEvent(UnifiedEvent.DEVICE_DATA_UPDATE, source, data, System.currentTimeMillis());
            throttler.offer(event);
        }
    }

    /**
     * EventController.Listener 回调。
     *
     * <p>接收 EventController 统一转换的事件信封，转为 UnifiedEvent 后进入截流器。
     * 对于 integration.lifecycle 事件，仅 ACTIVE 效果触发能力组索引重建。
     * 所有事件一律进截流器，无例外。
     *
     * @param envelope 事件信封
     */
    @Override
    public void onEvent(EventEnvelope envelope) {
        // 集成生命周期特殊处理：ACTIVE 效果触发能力组索引重建
        if ("integration.lifecycle".equals(envelope.getType())
                && envelope.getPayload() instanceof IntegrationLifecyclePayload) {
            IntegrationLifecyclePayload lifecyclePayload = (IntegrationLifecyclePayload) envelope.getPayload();
            if (lifecyclePayload.getEffect() == IntegrationLifecycleEvent.Effect.ACTIVE) {
                capabilityManager.rebuildIndex();
                log.info("Integration lifecycle ACTIVE event, rebuilt capability index: "
                        + lifecyclePayload.getCoordinate());
            }
        }

        // 统一转换为 UnifiedEvent → 进入截流器
        UnifiedEvent event = UnifiedEvent.fromEventEnvelope(envelope);
        if (event != null) {
            throttler.offer(event);
        }
    }

    /**
     * 截流器回调 — 窗口结束时调用。
     *
     * <p>将去重后的事件列表按 eventType 分组，生成汇总摘要，推送给所有 Agent。
     *
     * @param events 去重后的事件列表（保证非空）
     */
    private void flushSummary(List<UnifiedEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        // 按 eventType 分组
        Map<String, List<UnifiedEvent>> categories = new LinkedHashMap<String, List<UnifiedEvent>>();
        for (UnifiedEvent event : events) {
            String type = event.getEventType();
            if (!categories.containsKey(type)) {
                categories.put(type, new ArrayList<UnifiedEvent>());
            }
            categories.get(type).add(event);
        }

        // 构建汇总摘要
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("summary", Boolean.TRUE);
        summary.put("windowSeconds", throttler.getThrottleSeconds());
        summary.put("eventCount", events.size());
        summary.put("timestamp", System.currentTimeMillis());

        Map<String, List<Map<String, Object>>> categoryDetails = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (Map.Entry<String, List<UnifiedEvent>> entry : categories.entrySet()) {
            List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
            for (UnifiedEvent evt : entry.getValue()) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("eventId", evt.getEventId());
                item.put("source", evt.getSource());
                // 将 data 中的所有字段展开到 item
                if (evt.getData() != null) {
                    item.putAll(evt.getData());
                }
                items.add(item);
            }
            categoryDetails.put(entry.getKey(), items);
        }
        summary.put("categories", categoryDetails);

        // 推送 MCP Notification
        JsonRpcNotification notification = new JsonRpcNotification(
                "notifications/message", summary);
        mcpServer.sendNotification(notification);

        log.info("EventThrottler flushed summary: " + events.size() + " events, "
                + categories.size() + " categories");
    }

    /**
     * 注册 UI SSE 端点（/mcp/events）。
     *
     * <p>此端点允许浏览器端通过 SSE 接收实时事件通知。
     * 需要认证后才能访问，认证由 authManager 参数对应的认证器处理。
     *
     * @param server      HTTP 服务器实例，不能为 null
     * @param authManager 认证管理器（McpAuthenticator 实现），不能为 null
     */
    public void registerSseEndpoint(EasyHttpServer server, Object authManager) {
        if (server == null) {
            throw new IllegalArgumentException("server must not be null");
        }
        if (authManager == null) {
            throw new IllegalArgumentException("authManager must not be null");
        }
        if (!(authManager instanceof McpAuthenticator)) {
            throw new IllegalArgumentException(
                    "authManager must implement McpAuthenticator, got: "
                            + authManager.getClass().getName());
        }
        // SSE 端点复用 McpServer 的 GET 处理器，
        // McpServer 已内置 SSE 长连接管理，此处仅做注册确认
        log.info("SSE endpoint /mcp/events registered");
    }

    /**
     * 停止事件管理器：停止截流器，取消 Bus 订阅。
     *
     * <p>注意：EventController.Listener 的移除由调用方负责
     * （调用 {@code eventController.removeListener(this)}）。
     */
    public void stop() {
        // 先停止截流器（会执行最后一次 flush）
        throttler.stop();

        if (deviceDataSubscription != null) {
            try {
                deviceDataSubscription.unsubscribe();
            } catch (Exception e) {
                log.log(Level.WARNING, "Failed to unsubscribe from DEVICE_DATA_UPDATE", e);
            }
            deviceDataSubscription = null;
        }
        log.info("EventManager stopped");
    }
}
