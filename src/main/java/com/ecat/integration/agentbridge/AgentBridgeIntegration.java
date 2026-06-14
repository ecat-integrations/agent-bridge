/*
 * Copyright (c) 2026 ECAT Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ecat.integration.agentbridge;

import com.ecat.core.Bus.BusTopic;
import com.ecat.core.Bus.IntegrationLifecycleEvent;
import com.ecat.core.Bus.EventSubscriber;
import com.ecat.core.Bus.Subscription;
import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.ConfigFlow.AbstractConfigFlow;
import com.ecat.core.EcatCore;
import com.ecat.core.Integration.IntegrationBase;
import com.ecat.core.Integration.IntegrationLoadOption;
import com.ecat.integration.EcatCoreApiIntegration.EcatCoreApiIntegration;
import com.ecat.integration.HttpServerIntegration.EasyHttpServer;
import com.ecat.integration.HttpServerIntegration.HttpServerIntegration;
import com.ecat.integration.agentbridge.auth.AgentAuthManager;
import com.ecat.integration.agentbridge.auth.AuditLoggerBridge;
import com.ecat.integration.agentbridge.auth.ConfirmationManager;
import com.ecat.integration.agentbridge.audit.AuditLogger;
import com.ecat.integration.agentbridge.capability.CapabilityManager;
import com.ecat.integration.agentbridge.subagent.CliParser;
import com.ecat.integration.agentbridge.subagent.SubAgentRegistry;
import com.ecat.integration.agentbridge.subagent.ToolDispatcher;
import com.ecat.integration.agentbridge.subagent.AbstractSubAgent;
import com.ecat.integration.agentbridge.subagent.impl.DeviceSubAgent;
import com.ecat.integration.agentbridge.subagent.impl.LogicDeviceSubAgent;
import com.ecat.integration.agentbridge.subagent.impl.MediaSubAgent;
import com.ecat.integration.agentbridge.subagent.impl.EventSubAgent;
import com.ecat.integration.agentbridge.config.AgentBridgeConfigFlow;
import com.ecat.integration.agentbridge.event.EventManager;
import com.ecat.integration.agentbridge.mcp.McpRequestHandler;
import com.ecat.integration.agentbridge.mcp.McpServer;
import com.ecat.integration.agentbridge.mcp.JsonRpcRequest;
import com.ecat.integration.agentbridge.mcp.JsonRpcResponse;
import com.ecat.integration.agentbridge.mcp.McpException;
import com.ecat.integration.agentbridge.mcp.McpSession;
import com.ecat.integration.agentbridge.prompt.PromptManager;
import com.ecat.integration.agentbridge.resource.ResourceManager;
import com.ecat.integration.agentbridge.tool.ToolExecutor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent Bridge 主集成类，组装所有子组件。
 *
 * <p>Agent Bridge 通过 MCP（Model Context Protocol）协议向外部 AI Agent 暴露 ECAT 平台能力，
 * 包括设备管理、数据采集、告警处理等功能。
 *
 * <p>生命周期方法按以下顺序执行：
 * <ol>
 *   <li>{@link #onLoad(EcatCore, IntegrationLoadOption)} — 获取 HttpServerIntegration 依赖</li>
 *   <li>{@link #onInit()} — 无操作（子组件初始化延迟到 onStart）</li>
 *   <li>{@link #onStart()} — 初始化所有子组件，注册路由，订阅事件</li>
 *   <li>{@link #onAllExistEntriesLoaded(List)} — 从 ConfigEntry 恢复 token 状态</li>
 *   <li>{@link #onPause()} — 停止 MCP 服务和事件订阅</li>
 *   <li>{@link #onRelease()} — 释放所有资源</li>
 * </ol>
 *
 * <p>依赖集成：
 * <ul>
 *   <li>{@code integration-httpserver} — 提供 HTTP 服务器（必需）</li>
 *   <li>{@code integration-ecat-core-api} — 提供 API 路由元数据和内部 session token（可选）</li>
 * </ul>
 *
 * @author coffee
 */
public class AgentBridgeIntegration extends IntegrationBase {

    /** MCP 服务监听 IP */
    private static final String SERVER_IP = "127.0.0.1";

    /** MCP 服务监听端口（与 ecat-core-api 共享同一 HTTP 服务器） */
    private static final int SERVER_PORT = 9999;

    /** MCP endpoint 路径 */
    private static final String MCP_ENDPOINT = "/mcp";

    /** HttpServerIntegration 池化引用 */
    private HttpServerIntegration httpServerPool;

    /** 共享 HTTP 服务器实例 */
    private EasyHttpServer server;

    /** Agent 认证管理器 */
    private AgentAuthManager authManager;

    /** MCP 服务器（HTTP 路由注册 + 会话管理） */
    private McpServer mcpServer;

    /** 能力索引管理器（路由发现 + 工具注册） */
    private CapabilityManager capabilityManager;

    /** 工具执行器（代理 HTTP 请求到 ecat-core-api） */
    private ToolExecutor toolExecutor;

    /** SubAgent 候选注册表（BCP 自发现 + 能力索引） */
    private SubAgentRegistry subAgentRegistry;

    /** CLI 解析器（useTools 命令解析） */
    private CliParser cliParser;

    /** BCP 两工具路由调度器（getTools/useTools） */
    private ToolDispatcher toolDispatcher;

    /** 集成生命周期订阅句柄（SubAgent 自发现） */
    private Subscription selfDiscoverySubscription;

    /** 资源管理器（MCP resources 协议） */
    private ResourceManager resourceManager;

    /** 事件管理器（SSE 推送 + Bus 事件订阅） */
    private EventManager eventManager;

    /** 确认请求管理器（高风险操作人工确认） */
    private ConfirmationManager confirmationManager;

    /** 审计日志记录器 */
    private AuditLogger auditLogger;

    /** 提示词管理器（MCP prompts 协议） */
    private PromptManager promptManager;

    /** INTEGRATIONS_ALL_LOADED 事件订阅 */
    private Subscription integrationsLoadedSubscription;

    // ==================== 生命周期方法 ====================

    /**
     * 加载阶段：获取 HttpServerIntegration 依赖。
     *
     * <p>验证 integration-httpserver 已加载，否则抛出 {@link IllegalStateException}。
     *
     * @param core       ECAT 核心实例
     * @param loadOption 集成加载选项
     * @throws IllegalStateException 如果 integration-httpserver 未加载
     */
    @Override
    public void onLoad(EcatCore core, IntegrationLoadOption loadOption) {
        super.onLoad(core, loadOption);
        this.httpServerPool = (HttpServerIntegration)
                integrationRegistry.getIntegration("com.ecat:integration-httpserver");
        if (httpServerPool == null) {
            throw new IllegalStateException(
                    "integration-httpserver is required but not loaded. "
                    + "Ensure it is enabled in integrations.yml and listed as a dependency.");
        }

        // 提前创建 authManager，因为 getConfigFlow() 在 onLoad 之后、onStart 之前被调用，
        // 此时 authManager 必须已存在才能返回有效的 ConfigFlow 实例
        authManager = new AgentAuthManager();

        // 注册 ConfigFlow 的静态 TokenManager 回调（供无参构造器反射实例化使用）
        AgentBridgeConfigFlow.setTokenManager(new AgentBridgeConfigFlow.TokenManagerCallback() {
            @Override
            public String generateToken(String name, String role) {
                Set<String> permissions = new HashSet<String>();
                permissions.add("tools:read");
                permissions.add("resources:read");
                permissions.add("prompts:read");
                if ("admin".equals(role)) {
                    permissions.add("tools:write");
                    permissions.add("tools:dangerous");
                }
                return authManager.generateToken(name, role, permissions).getToken();
            }
        });
    }

    /**
     * 初始化阶段：无操作。
     *
     * <p>子组件初始化延迟到 {@link #onStart()} 执行，
     * 因为 onStart 阶段 HttpServerIntegration 已注册完成。
     */
    @Override
    public void onInit() {
        log.info("Agent Bridge integration initializing");
    }

    /**
     * 启动阶段：初始化所有子组件并注册路由。
     *
     * <p>子组件初始化顺序（按依赖关系排列）：
     * <ol>
     *   <li>获取共享 HTTP 服务器</li>
     *   <li>创建 AgentAuthManager</li>
     *   <li>创建 AuditLogger</li>
     *   <li>创建 ConfirmationManager（注入 AuditLoggerBridge 适配器）</li>
     *   <li>创建 CapabilityManager</li>
     *   <li>创建 ToolExecutor（注入 InternalTokenProvider 适配器）</li>
     *   <li>创建 ResourceManager（注入 core, CapabilityManager, AuditLogger）</li>
     *   <li>创建 PromptManager</li>
     *   <li>创建 McpServer（注入 server, authenticator, endpoint, requestHandler）</li>
     *   <li>创建 EventManager（注入 busRegistry, mcpServer, capabilityManager）</li>
     *   <li>注册 EventManager 为 EventController.Listener（统一事件通道）</li>
     *   <li>注册 ConfigFlow 静态回调</li>
     *   <li>启动 McpServer（注册 /mcp 路由）</li>
     *   <li>注册 SSE 端点</li>
     *   <li>订阅 DEVICE_DATA_UPDATE Bus 事件（其余通过 EventController.Listener）</li>
     *   <li>订阅 INTEGRATIONS_ALL_LOADED 事件</li>
     * </ol>
     */
    @Override
    public void onStart() {
        // 1. 获取共享 HTTP 服务器
        server = httpServerPool.getServer(SERVER_IP, SERVER_PORT);
        if (server == null) {
            throw new IllegalStateException(
                    "Failed to get shared HTTP server at " + SERVER_IP + ":" + SERVER_PORT
                    + ". Ensure integration-httpserver is started first.");
        }

        // 2. authManager 已在 onLoad() 中创建

        // 3. 创建 AuditLogger
        auditLogger = new AuditLogger();

        // 4. 创建 ConfirmationManager（注入 AuditLoggerBridge 适配器）
        confirmationManager = new ConfirmationManager(new AuditLoggerBridge() {
            @Override
            public void logConfirmation(String confirmationId, String action,
                                        String toolName, String requesterId) {
                auditLogger.logConfirmation(confirmationId, action, toolName, requesterId);
            }
        });

        // 5. 创建 CapabilityManager
        capabilityManager = new CapabilityManager(httpServerPool, SERVER_IP, SERVER_PORT);

        // 6. 创建 ToolExecutor（注入 InternalTokenProvider 适配器）
        toolExecutor = new ToolExecutor(new ToolExecutor.InternalTokenProvider() {
            @Override
            public String getInternalToken() {
                return authManager.getInternalToken();
            }
        });

        // 7. 创建 ResourceManager（注入 core, 然后通过 setter 注入依赖）
        resourceManager = new ResourceManager(core);
        resourceManager.setCapabilityManager(capabilityManager);
        resourceManager.setAuditLogger(auditLogger);

        // 8. 创建 PromptManager
        promptManager = new PromptManager();

        // BCP SubAgent 装配：候选注册表 + CLI 解析器 + 路由调度器
        // P0 候选 SubAgent 在 Phase 3 逐步填入；当前 DeviceSubAgent（依赖 core-api）
        List<AbstractSubAgent> candidates = new ArrayList<AbstractSubAgent>();
        candidates.add(new DeviceSubAgent());
        candidates.add(new LogicDeviceSubAgent());
        candidates.add(new MediaSubAgent());
        candidates.add(new EventSubAgent());
        subAgentRegistry = new SubAgentRegistry(candidates);
        // 初始 buildIndex：agent-bridge 依赖 core-api，启动时 core-api 已 ACTIVE
        subAgentRegistry.buildIndex(new HashSet<String>(Arrays.asList(
                "com.ecat:integration-ecat-core-api")));
        cliParser = new CliParser();
        String baseUrl = "http://" + SERVER_IP + ":" + SERVER_PORT;
        toolDispatcher = new ToolDispatcher(subAgentRegistry, cliParser, toolExecutor, baseUrl);

        // 自发现：订阅集成生命周期，集成 ACTIVE（ADDED/ENABLED/UPGRADED）即时注册其 SubAgent，
        // DISABLED/REMOVED 时移除。PENDING_RESTART 不处理（待重启后再生效）。
        selfDiscoverySubscription = core.getBusRegistry().subscribe(
                BusTopic.INTEGRATION_LIFECYCLE.getTopicName(),
                new EventSubscriber() {
                    @Override
                    public void handleEvent(String topic, Object data) {
                        if (!(data instanceof IntegrationLifecycleEvent)) {
                            return;
                        }
                        IntegrationLifecycleEvent evt = (IntegrationLifecycleEvent) data;
                        if (evt.getEffect() != IntegrationLifecycleEvent.Effect.ACTIVE) {
                            return;
                        }
                        IntegrationLifecycleEvent.Action action = evt.getAction();
                        if (action == IntegrationLifecycleEvent.Action.ADDED
                                || action == IntegrationLifecycleEvent.Action.ENABLED
                                || action == IntegrationLifecycleEvent.Action.UPGRADED) {
                            subAgentRegistry.onIntegrationActive(evt.getCoordinate());
                            log.info("SubAgent 自发现：集成 ACTIVE " + evt.getCoordinate());
                        } else if (action == IntegrationLifecycleEvent.Action.DISABLED
                                || action == IntegrationLifecycleEvent.Action.REMOVED) {
                            subAgentRegistry.onIntegrationStopped(evt.getCoordinate());
                            log.info("SubAgent 自发现：集成移除 " + evt.getCoordinate());
                        }
                    }
                });

        // 9. 创建 McpRequestHandler 实现（内部匿名类）
        McpRequestHandler requestHandler = new McpRequestHandlerImpl(
                capabilityManager, toolExecutor, resourceManager,
                confirmationManager, promptManager, toolDispatcher);

        // 10. 创建 McpServer
        mcpServer = new McpServer(server, authManager, MCP_ENDPOINT, requestHandler);

        // 11. 创建 EventManager（注入 busRegistry, mcpServer, capabilityManager）
        eventManager = new EventManager(
                core.getBusRegistry(), mcpServer, capabilityManager);

        // 12. 注册 EventManager 为 EventController.Listener（统一事件通道）
        EcatCoreApiIntegration apiIntegration =
                (EcatCoreApiIntegration) integrationRegistry.getIntegration("com.ecat:integration-ecat-core-api");
        if (apiIntegration != null && apiIntegration.getEventController() != null) {
            apiIntegration.getEventController().addListener(eventManager);
            log.info("EventManager registered as EventController.Listener");
        } else {
            log.warn("EventController not available, EventManager won't receive unified events");
        }

        // 13. ConfigFlow 静态回调已在 onLoad() 中设置

        // 14. 启动 McpServer — 注册 POST/GET/DELETE /mcp 路由
        mcpServer.start();

        // 15. 注册 SSE 端点
        eventManager.registerSseEndpoint(server, authManager);

        // 16. 启动 EventManager（启动截流器 + 订阅 DEVICE_DATA_UPDATE Bus 事件）
        eventManager.start();

        // 17. 订阅 INTEGRATIONS_ALL_LOADED 事件
        subscribeIntegrationsLoadedEvent();

        log.info("Agent Bridge started on {}:{} — MCP endpoint ready at {}",
                SERVER_IP, SERVER_PORT, MCP_ENDPOINT);
    }

    /**
     * 所有已持久化 ConfigEntry 加载完成后的回调。
     *
     * <p>从 ConfigEntry 中恢复已保存的 token 哈希到 AgentAuthManager 内存状态。
     *
     * @param entries 该集成的所有已加载 ConfigEntry
     */
    @Override
    public void onAllExistEntriesLoaded(List<ConfigEntry> entries) {
        super.onAllExistEntriesLoaded(entries);
        if (authManager != null) {
            authManager.loadFromEntries(entries);
            log.info("Agent Bridge: restored {} token entries", entries.size());
        }
    }

    /**
     * 暂停阶段：停止 MCP 服务和事件订阅。
     */
    @Override
    public void onPause() {
        // 移除 EventController.Listener
        if (eventManager != null) {
            EcatCoreApiIntegration apiIntegration =
                    (EcatCoreApiIntegration) integrationRegistry.getIntegration("com.ecat:integration-ecat-core-api");
            if (apiIntegration != null && apiIntegration.getEventController() != null) {
                apiIntegration.getEventController().removeListener(eventManager);
            }
            eventManager.stop();
        }
        if (mcpServer != null) {
            mcpServer.stop();
        }
        if (integrationsLoadedSubscription != null) {
            integrationsLoadedSubscription.unsubscribe();
            integrationsLoadedSubscription = null;
        }
        if (selfDiscoverySubscription != null) {
            selfDiscoverySubscription.unsubscribe();
            selfDiscoverySubscription = null;
        }
        log.info("Agent Bridge paused");
    }

    /**
     * 释放阶段：释放所有资源。
     *
     * <p>停止事件管理器、MCP 服务器，关闭审计日志和确认管理器。
     */
    @Override
    public void onRelease() {
        // 移除 EventController.Listener 并停止 EventManager
        if (eventManager != null) {
            EcatCoreApiIntegration apiIntegration =
                    (EcatCoreApiIntegration) integrationRegistry.getIntegration("com.ecat:integration-ecat-core-api");
            if (apiIntegration != null && apiIntegration.getEventController() != null) {
                apiIntegration.getEventController().removeListener(eventManager);
            }
            eventManager.stop();
            eventManager = null;
        }
        if (mcpServer != null) {
            mcpServer.stop();
            mcpServer = null;
        }
        if (confirmationManager != null) {
            confirmationManager.shutdown();
            confirmationManager = null;
        }
        if (auditLogger != null) {
            auditLogger.close();
            auditLogger = null;
        }
        if (integrationsLoadedSubscription != null) {
            integrationsLoadedSubscription.unsubscribe();
            integrationsLoadedSubscription = null;
        }
        httpServerPool = null;
        server = null;
        authManager = null;
        capabilityManager = null;
        toolExecutor = null;
        resourceManager = null;
        promptManager = null;
        log.info("Agent Bridge released");
    }

    // ==================== ConfigFlow ====================

    /**
     * 返回 Agent Bridge 配置流程实例。
     *
     * <p>每次调用返回新实例，因为 {@link AbstractConfigFlow} 是有状态的。
     * 传入 TokenManagerCallback 适配器作为 Token 管理回调。
     *
     * @return AgentBridgeConfigFlow 实例，authManager 未初始化时返回 null
     */
    @Override
    public AbstractConfigFlow getConfigFlow() {
        if (authManager == null) {
            return null;
        }
        return new AgentBridgeConfigFlow(new AgentBridgeConfigFlow.TokenManagerCallback() {
            @Override
            public String generateToken(String name, String role) {
                Set<String> permissions = new HashSet<String>();
                permissions.add("tools:read");
                permissions.add("resources:read");
                permissions.add("prompts:read");
                if ("admin".equals(role)) {
                    permissions.add("tools:write");
                    permissions.add("tools:dangerous");
                }
                return authManager.generateToken(name, role, permissions).getToken();
            }
        });
    }

    // ==================== ConfigEntry 操作 ====================

    /**
     * 创建配置条目。
     *
     * @param entry 配置条目
     * @return 创建后的配置条目
     */
    @Override
    public ConfigEntry createEntry(ConfigEntry entry) {
        log.info("Agent Bridge entry created: {}", entry.getUniqueId());
        return entry;
    }

    /**
     * 重新配置条目。
     *
     * @param entryId   配置条目 ID
     * @param newEntry  新的配置数据
     * @return 更新后的配置条目
     */
    @Override
    public ConfigEntry reconfigureEntry(String entryId, ConfigEntry newEntry) {
        log.info("Agent Bridge entry reconfigured: {}", entryId);
        return newEntry;
    }

    /**
     * 删除配置条目。
     *
     * <p>调用链：{@code ConfigEntryRegistry.removeEntry()} → {@code notifyIntegrationRemove()}
     * → 本方法。本方法在 Registry 从缓存移除 entry 之前被调用（参见
     * {@code ConfigEntryRegistry.removeEntry} 的顺序：先通知 integration，再 entryCache.remove），
     * 因此可通过 {@code getByEntryId} 读取 entry 数据。
     *
     * <p><b>注意：不能调用 {@code super.removeEntry(entryId)}。</b>
     * {@code IntegrationBase.removeEntry} 会回调 {@code registry.removeEntry(entryId)}，
     * 而 Registry 正处于 removeEntry 流程中 → 重新进入 {@code ConfigEntryRegistry.removeEntry}
     * → 无限递归 StackOverflowError，表现为 {@code DELETE /core-api/config-flow/entries/{id}} 挂起无响应。
     * 参照 {@code IntegrationDeviceBase.removeEntry()} 的正确做法：只清理集成自身资源，
     * Registry 自身负责缓存移除和持久化删除。
     *
     * <p>本方法的职责：如果删除的是 token 类型 ConfigEntry，撤销对应 token（删除即失效）。
     *
     * @param entryId 配置条目 ID
     */
    @Override
    public void removeEntry(String entryId) {
        if (authManager == null || core == null) {
            // 集成尚未 onLoad 完成（core/authManager 未注入），无 token 资源可清理
            log.warn("removeEntry skipped (integration not fully loaded): {}", entryId);
            return;
        }

        ConfigEntry entry = core.getEntryRegistry().getByEntryId(entryId);
        if (entry == null) {
            // Registry 已移除 entry（理论上不会发生，回调在移除前），无法撤销 token
            log.warn("removeEntry: entry not found in registry, cannot revoke token: {}", entryId);
            return;
        }

        String uniqueId = entry.getUniqueId();
        boolean isTokenEntry = uniqueId != null
                && uniqueId.startsWith(AgentBridgeConfigFlow.UNIQUE_ID_PREFIX);
        if (!isTokenEntry) {
            // 非 token 类型 entry，无需撤销 token
            log.debug("removeEntry: non-token entry, nothing to revoke: {}", entryId);
            return;
        }

        // token 类型 entry：撤销对应 token（安全要求：删除即失效）
        Map<String, Object> data = entry.getData();
        Object tokenHashObj = (data != null) ? data.get("tokenHash") : null;
        if (!(tokenHashObj instanceof String)) {
            // 数据完整性问题：token entry 必须包含 tokenHash 字段
            // 抛异常明确告知系统错误（notifyIntegrationRemove 会捕获并记录，不影响 Registry 移除流程）
            throw new IllegalStateException(
                    "Token entry missing 'tokenHash' field (data corruption): " + entryId);
        }
        authManager.revokeByTokenHash((String) tokenHashObj);
        log.info("Agent token revoked and entry removed: {}", uniqueId);
    }

    // ==================== INTEGRATIONS_ALL_LOADED 回调 ====================

    /**
     * 订阅 INTEGRATIONS_ALL_LOADED 事件。
     *
     * <p>当所有集成加载完成后：
     * <ol>
     *   <li>构建能力索引（从 ecat-core-api 路由元数据生成 MCP 工具）</li>
     *   <li>获取 ecat-core-api 内部 session token</li>
     * </ol>
     */
    private void subscribeIntegrationsLoadedEvent() {
        integrationsLoadedSubscription = core.getBusRegistry().subscribe(
                BusTopic.INTEGRATIONS_ALL_LOADED.getTopicName(),
                new EventSubscriber() {
                    @Override
                    public void handleEvent(String topic, Object eventData) {
                        // 全量对账 SubAgent 索引：解决启动时序竞争，确保所有已加载依赖集成的
                        // SubAgent（如依赖 media-api 的 media agent）被注册（Bug #3）。
                        reconcileSubAgentsFromRegistry();
                        capabilityManager.buildIndex();
                        obtainInternalToken();
                        log.info("Agent Bridge initialized: capability index built, internal token obtained");
                    }
                });
    }

    /**
     * 从 ecat-core-api 获取内部 session token。
     *
     * <p>此 token 用于 Agent Bridge 向 ecat-core-api 发起请求时的身份认证。
     * 仅在 integration-ecat-core-api 已加载且 AuthManager 可用时获取。
     */
    private void obtainInternalToken() {
        EcatCoreApiIntegration coreApi = (EcatCoreApiIntegration)
                integrationRegistry.getIntegration("com.ecat:integration-ecat-core-api");
        if (coreApi != null && coreApi.getAuthManager() != null) {
            String sessionToken = coreApi.getAuthManager().createInternalSession("agent-bridge-internal");
            authManager.setInternalToken(sessionToken);
            log.info("Agent Bridge: obtained internal session token from ecat-core-api");
        } else {
            log.warn("Agent Bridge: ecat-core-api not available, internal token not obtained");
        }
    }

    /**
     * 从 IntegrationRegistry 当前已加载集成集合，全量对账重建 SubAgent 索引。
     *
     * <p>在 {@link BusTopic#INTEGRATIONS_ALL_LOADED} 事件回调中调用，解决启动时序竞争（Bug #3）：
     * <ul>
     *   <li>agent-bridge 启动时初始 {@code buildIndex} 仅注入 {@code com.ecat:integration-ecat-core-api}
     *       （agent-bridge 启动时唯一确定已 ACTIVE 的直接依赖）</li>
     *   <li>自发现（{@code INTEGRATION_LIFECYCLE} 订阅）在 {@code start()} 阶段才建立，
     *       而依赖集成（如 {@code com.ecat:integration-media-api}）可能在订阅建立前已 ACTIVE
     *       → 错过事件 → 其 SubAgent（media）永不注册，getTools 缺该 agent</li>
     * </ul>
     *
     * <p>本方法在"所有集成加载完成"这一确定时点做一次全量对账：以
     * {@link IntegrationRegistry#getAllCoordinates()} 返回的已加载集成集合调用
     * {@link SubAgentRegistry#buildIndex(Set)}，确保所有已加载依赖集成的 SubAgent 都被注册。
     * 语义与 {@link SubAgentRegistry#buildIndex(Set)} 一致：requiredIntegration 命中已加载集合的
     * 候选 agent 被注册，其余移除。
     *
     * <p>包级可见以便同包单元测试（{@code AgentBridgeIntegrationSubAgentReconcileTest}）直接调用。
     */
    void reconcileSubAgentsFromRegistry() {
        if (integrationRegistry == null || subAgentRegistry == null) {
            log.warn("reconcileSubAgentsFromRegistry skipped (integrationRegistry/subAgentRegistry not initialized): "
                    + "integrationRegistry={}, subAgentRegistry={}",
                    integrationRegistry != null, subAgentRegistry != null);
            return;
        }
        Set<String> active = integrationRegistry.getAllCoordinates();
        subAgentRegistry.buildIndex(active);
        log.info("SubAgent index reconciled from {} loaded integration(s): agents={}",
                active.size(), subAgentRegistry.buildCapabilityIndex().keySet());
    }

    // ==================== McpRequestHandler 内部实现 ====================

    /**
     * MCP 请求处理器内部实现，将 JSON-RPC 方法分派到各子组件。
     *
     * <p>处理以下 MCP 标准方法：
     * <ul>
     *   <li>{@code initialize} — MCP 握手初始化</li>
     *   <li>{@code tools/list} — 列出可用工具</li>
     *   <li>{@code tools/call} — 调用指定工具</li>
     *   <li>{@code resources/list} — 列出可用资源</li>
     *   <li>{@code resources/read} — 读取指定资源</li>
     *   <li>{@code prompts/list} — 列出可用提示词</li>
     *   <li>{@code prompts/get} — 获取指定提示词</li>
     * </ul>
     *
     * <p>非标准方法返回 METHOD_NOT_FOUND 错误。
     */
    private class McpRequestHandlerImpl implements McpRequestHandler {

        private final CapabilityManager capManager;
        private final ToolExecutor tExecutor;
        private final ResourceManager rManager;
        private final ConfirmationManager confManager;
        private final PromptManager pManager;
        private final ToolDispatcher toolDispatcher;

        /**
         * 构造器。
         *
         * @param capManager     能力组管理器（Phase 5 废弃，暂保留兼容）
         * @param tExecutor      工具执行器
         * @param rManager       资源管理器（Phase 5 废弃）
         * @param confManager    确认请求管理器
         * @param pManager       提示词管理器（Phase 5 废弃）
         * @param toolDispatcher BCP 两工具路由调度器（getTools/useTools）
         */
        McpRequestHandlerImpl(CapabilityManager capManager, ToolExecutor tExecutor,
                              ResourceManager rManager, ConfirmationManager confManager,
                              PromptManager pManager, ToolDispatcher toolDispatcher) {
            this.capManager = capManager;
            this.tExecutor = tExecutor;
            this.rManager = rManager;
            this.confManager = confManager;
            this.pManager = pManager;
            this.toolDispatcher = toolDispatcher;
        }

        @Override
        public JsonRpcResponse handle(JsonRpcRequest request, McpSession session) throws McpException {
            String method = request.getMethod();
            if (method == null) {
                throw new McpException(JsonRpcResponse.INVALID_REQUEST, "method is required");
            }

            switch (method) {
                case "initialize":
                    return handleInitialize(request, session);
                case "notifications/initialized":
                    // Client notification — no response needed
                    return null;
                case "tools/list":
                    return handleToolsList(request, session);
                case "tools/call":
                    return handleToolsCall(request, session);
                case "resources/list":
                    return handleResourcesList(request, session);
                case "resources/read":
                    return handleResourcesRead(request, session);
                case "prompts/list":
                    return handlePromptsList(request, session);
                case "prompts/get":
                    return handlePromptsGet(request, session);
                default:
                    throw new McpException(JsonRpcResponse.METHOD_NOT_FOUND,
                            "Method not found: " + method);
            }
        }

        /**
         * 处理 initialize 请求 — MCP 握手。
         */
        private JsonRpcResponse handleInitialize(JsonRpcRequest request, McpSession session) {
            session.setInitialized(true);
            Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
            result.put("protocolVersion", "2025-03-26");

            // BCP 只暴露 tools 通道（resources/prompts/notifications 不暴露，doc 05 §1.4）
            Map<String, Object> capabilities = new java.util.LinkedHashMap<String, Object>();
            capabilities.put("tools", new java.util.LinkedHashMap<String, Object>());
            result.put("capabilities", capabilities);

            Map<String, Object> serverInfo = new java.util.LinkedHashMap<String, Object>();
            serverInfo.put("name", "ecat-agent-bridge");
            serverInfo.put("version", "2.0.0");
            result.put("serverInfo", serverInfo);

            return JsonRpcResponse.success(request.getId(), result);
        }

        /**
         * 处理 tools/list 请求。
         */
        private JsonRpcResponse handleToolsList(JsonRpcRequest request, McpSession session) {
            java.util.List<Map<String, Object>> tools = new java.util.ArrayList<Map<String, Object>>();
            tools.add(buildBcpToolSchema("getTools",
                    "发现可用工具。无参返回所有已注册 SubAgent 的全部工具；"
                    + "传 {\"tool\":\"<agentName>\"} 返回指定 SubAgent 的工具。"
                    + "当前可用 SubAgent: " + toolDispatcher.capabilityIndex().keySet(),
                    true));
            tools.add(buildBcpToolSchema("useTools",
                    "执行 CLI 命令。传 {\"tool\":\"<agent> <tool> [--flag value...]\"}，"
                    + "例如 {\"tool\":\"device list\"}。先用 getTools 查看可用命令与参数。",
                    false));
            Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
            result.put("tools", tools);
            return JsonRpcResponse.success(request.getId(), result);
        }

        /**
         * 构造 BCP 工具 schema（name/description/inputSchema）。
         *
         * @param name         工具名（getTools/useTools）
         * @param description  工具描述
         * @param toolOptional tool 参数是否可选（getTools 可选，useTools 必填）
         */
        private Map<String, Object> buildBcpToolSchema(String name, String description,
                                                       boolean toolOptional) {
            Map<String, Object> tool = new java.util.LinkedHashMap<String, Object>();
            tool.put("name", name);
            tool.put("description", description);

            Map<String, Object> inputSchema = new java.util.LinkedHashMap<String, Object>();
            inputSchema.put("type", "object");
            Map<String, Object> toolProp = new java.util.LinkedHashMap<String, Object>();
            toolProp.put("type", "string");
            toolProp.put("description", "CLI 命令串（useTools）或 SubAgent 名（getTools）");
            Map<String, Object> properties = new java.util.LinkedHashMap<String, Object>();
            properties.put("tool", toolProp);
            inputSchema.put("properties", properties);
            if (!toolOptional) {
                java.util.List<String> required = new java.util.ArrayList<String>();
                required.add("tool");
                inputSchema.put("required", required);
            }
            tool.put("inputSchema", inputSchema);
            return tool;
        }

        /**
         * 处理 tools/call 请求。
         */
        @SuppressWarnings("unchecked")
        private JsonRpcResponse handleToolsCall(JsonRpcRequest request, McpSession session)
                throws McpException {
            Map<String, Object> params = request.getParamsAsMap();
            String toolName = (String) params.get("name");
            if (toolName == null || toolName.isEmpty()) {
                throw new McpException(JsonRpcResponse.INVALID_PARAMS, "tool name is required");
            }

            Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");
            if (arguments == null) {
                arguments = new java.util.HashMap<String, Object>();
            }

            switch (toolName) {
                case "getTools": {
                    // tool 参数可选：null/空=全部 SubAgent 工具，指定=单个 SubAgent
                    String agentName = (String) arguments.get("tool");
                    return toolResultToResponse(request, toolDispatcher.getTools(agentName));
                }
                case "useTools": {
                    String cli = (String) arguments.get("tool");
                    if (cli == null || cli.isEmpty()) {
                        throw new McpException(JsonRpcResponse.INVALID_PARAMS,
                                "useTools 需要 'tool' 参数（CLI 命令串）");
                    }
                    return toolResultToResponse(request, toolDispatcher.useTools(cli));
                }
                default:
                    throw new McpException(JsonRpcResponse.INVALID_PARAMS,
                            "未知工具: " + toolName + "，可用: getTools, useTools");
            }
        }

        /**
         * 将 ToolResult 转为 MCP tools/call 响应（{content:[{type:text,text:...}], isError}）。
         *
         * <p>content 序列化：String 直接用（错误文本），结构化对象（Map/List）JSON 序列化，
         * 避免错误文本被二次加引号。
         */
        private JsonRpcResponse toolResultToResponse(JsonRpcRequest request,
                                                     com.ecat.integration.agentbridge.tool.ToolResult result) {
            java.util.List<Map<String, Object>> content = new java.util.ArrayList<Map<String, Object>>();
            Map<String, Object> textContent = new java.util.LinkedHashMap<String, Object>();
            textContent.put("type", "text");
            Object c = result.getContent();
            textContent.put("text", c == null ? ""
                    : (c instanceof String ? (String) c : com.alibaba.fastjson2.JSON.toJSONString(c)));
            content.add(textContent);

            Map<String, Object> resp = new java.util.LinkedHashMap<String, Object>();
            resp.put("content", content);
            resp.put("isError", result.isError());
            return JsonRpcResponse.success(request.getId(), resp);
        }

        /**
         * 处理 ecat_search_capabilities 内置工具。
         */
        private JsonRpcResponse handleSearchCapabilities(JsonRpcRequest request,
                                                         Map<String, Object> params) {
            String query = (String) params.get("query");
            if (query == null) {
                query = "";
            }
            java.util.List<Map<String, Object>> results = new java.util.ArrayList<Map<String, Object>>();
            for (com.ecat.integration.agentbridge.capability.CapabilityGroupSummary summary
                    : capManager.searchCapabilities(query)) {
                Map<String, Object> entry = new java.util.LinkedHashMap<String, Object>();
                entry.put("groupId", summary.getGroupId());
                entry.put("displayName", summary.getDisplayName());
                entry.put("toolCount", summary.getToolCount());
                entry.put("safetyLevel", summary.getSafetyLevel());
                results.add(entry);
            }

            java.util.List<Map<String, Object>> content = new java.util.ArrayList<Map<String, Object>>();
            Map<String, Object> textContent = new java.util.LinkedHashMap<String, Object>();
            textContent.put("type", "text");
            textContent.put("text", com.alibaba.fastjson2.JSON.toJSONString(results));
            content.add(textContent);

            Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
            result.put("content", content);
            result.put("isError", false);
            return JsonRpcResponse.success(request.getId(), result);
        }

        /**
         * 处理 ecat_load_capabilities 内置工具。
         */
        private JsonRpcResponse handleLoadCapabilities(JsonRpcRequest request,
                                                        Map<String, Object> params,
                                                        McpSession session) {
            String groupId = (String) params.get("groupId");
            if (groupId == null || groupId.isEmpty()) {
                java.util.List<Map<String, Object>> content = new java.util.ArrayList<Map<String, Object>>();
                Map<String, Object> textContent = new java.util.LinkedHashMap<String, Object>();
                textContent.put("type", "text");
                textContent.put("text", "groupId is required");
                content.add(textContent);
                Map<String, Object> errResult = new java.util.LinkedHashMap<String, Object>();
                errResult.put("content", content);
                errResult.put("isError", true);
                return JsonRpcResponse.success(request.getId(), errResult);
            }

            com.ecat.integration.agentbridge.capability.LoadResult loadResult =
                    capManager.loadCapabilities(groupId, session, mcpServer);

            String message = loadResult.isSuccess()
                    ? "Loaded " + loadResult.getToolCount() + " tools from group: " + groupId
                    : loadResult.getErrorMessage();

            java.util.List<Map<String, Object>> content = new java.util.ArrayList<Map<String, Object>>();
            Map<String, Object> textContent = new java.util.LinkedHashMap<String, Object>();
            textContent.put("type", "text");
            textContent.put("text", message);
            content.add(textContent);

            Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
            result.put("content", content);
            result.put("isError", !loadResult.isSuccess());
            return JsonRpcResponse.success(request.getId(), result);
        }

        /**
         * 处理 query_audit_log 内置工具 — 查询审计日志。
         */
        private JsonRpcResponse handleQueryAuditLog(JsonRpcRequest request,
                                                     Map<String, Object> params) {
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");
            if (arguments == null) {
                arguments = new java.util.HashMap<String, Object>();
            }

            long endTime = System.currentTimeMillis();
            long startTime = endTime - 24 * 60 * 60 * 1000L; // 默认最近 24 小时
            if (arguments.get("startTime") != null) {
                try {
                    startTime = Long.parseLong(arguments.get("startTime").toString());
                } catch (NumberFormatException e) {
                    // 保持默认值
                }
            }
            if (arguments.get("endTime") != null) {
                try {
                    endTime = Long.parseLong(arguments.get("endTime").toString());
                } catch (NumberFormatException e) {
                    // 保持默认值
                }
            }
            int limit = 50;
            if (arguments.get("limit") != null) {
                try {
                    limit = Integer.parseInt(arguments.get("limit").toString());
                } catch (NumberFormatException e) {
                    // 保持默认值
                }
            }

            java.util.List<com.ecat.integration.agentbridge.audit.AuditRecord> records =
                    auditLogger != null ? auditLogger.query(startTime, endTime, limit)
                            : java.util.Collections.<com.ecat.integration.agentbridge.audit.AuditRecord>emptyList();

            java.util.List<Map<String, Object>> recordList = new java.util.ArrayList<Map<String, Object>>();
            for (com.ecat.integration.agentbridge.audit.AuditRecord record : records) {
                Map<String, Object> entry = new java.util.LinkedHashMap<String, Object>();
                entry.put("timestamp", record.getTimestamp());
                entry.put("agentId", record.getAgentId());
                entry.put("toolName", record.getToolName());
                entry.put("status", record.getStatus());
                entry.put("sessionId", record.getSessionId());
                recordList.add(entry);
            }

            java.util.List<Map<String, Object>> content = new java.util.ArrayList<Map<String, Object>>();
            Map<String, Object> textContent = new java.util.LinkedHashMap<String, Object>();
            textContent.put("type", "text");
            textContent.put("text", com.alibaba.fastjson2.JSON.toJSONString(recordList));
            content.add(textContent);

            Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
            result.put("content", content);
            result.put("isError", false);
            return JsonRpcResponse.success(request.getId(), result);
        }

        /**
         * 处理 confirm_operation 内置工具 — 确认高风险操作。
         */
        private JsonRpcResponse handleConfirmOperation(JsonRpcRequest request,
                                                        Map<String, Object> params) throws McpException {
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");
            if (arguments == null) {
                throw new McpException(JsonRpcResponse.INVALID_PARAMS, "arguments required");
            }

            String operationId = (String) arguments.get("operationId");
            String confirmCode = (String) arguments.get("confirmCode");
            if (operationId == null || confirmCode == null) {
                throw new McpException(JsonRpcResponse.INVALID_PARAMS,
                        "operationId and confirmCode are required");
            }

            // ConfirmationManager.confirm() 仅接受 confirmationId
            // confirmCode 作为操作验证的一部分暂存在 ConfirmationRequest 中
            boolean confirmed = confManager.confirm(operationId);
            String message = confirmed ? "Operation confirmed: " + operationId
                    : "Confirmation failed: invalid code or expired";

            java.util.List<Map<String, Object>> content = new java.util.ArrayList<Map<String, Object>>();
            Map<String, Object> textContent = new java.util.LinkedHashMap<String, Object>();
            textContent.put("type", "text");
            textContent.put("text", message);
            content.add(textContent);

            Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
            result.put("content", content);
            result.put("isError", !confirmed);
            return JsonRpcResponse.success(request.getId(), result);
        }

        /**
         * 处理 query_async_result 内置工具 — 查询异步操作结果。
         */
        private JsonRpcResponse handleQueryAsyncResult(JsonRpcRequest request,
                                                        Map<String, Object> params) throws McpException {
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");
            if (arguments == null) {
                throw new McpException(JsonRpcResponse.INVALID_PARAMS, "arguments required");
            }

            String executionId = (String) arguments.get("executionId");
            if (executionId == null || executionId.isEmpty()) {
                throw new McpException(JsonRpcResponse.INVALID_PARAMS, "executionId is required");
            }

            // 通过 ToolExecutor 调用 ecat-core-api 的 async result 端点
            Map<String, Object> callArgs = new java.util.HashMap<String, Object>();
            callArgs.put("executionId", executionId);

            String baseUrl = "http://" + SERVER_IP + ":" + SERVER_PORT;
            Map<String, Object> asyncResult = new java.util.LinkedHashMap<String, Object>();
            asyncResult.put("executionId", executionId);
            asyncResult.put("message", "Async result query delegated to ecat-core-api endpoint");

            java.util.List<Map<String, Object>> content = new java.util.ArrayList<Map<String, Object>>();
            Map<String, Object> textContent = new java.util.LinkedHashMap<String, Object>();
            textContent.put("type", "text");
            textContent.put("text", com.alibaba.fastjson2.JSON.toJSONString(asyncResult));
            content.add(textContent);

            Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
            result.put("content", content);
            result.put("isError", false);
            return JsonRpcResponse.success(request.getId(), result);
        }

        /**
         * 处理 resources/list 请求。
         */
        private JsonRpcResponse handleResourcesList(JsonRpcRequest request, McpSession session) {
            Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
            result.put("resources", rManager.listResources());
            return JsonRpcResponse.success(request.getId(), result);
        }

        /**
         * 处理 resources/read 请求。
         */
        private JsonRpcResponse handleResourcesRead(JsonRpcRequest request, McpSession session)
                throws McpException {
            Map<String, Object> params = request.getParamsAsMap();
            String uri = (String) params.get("uri");
            if (uri == null || uri.isEmpty()) {
                throw new McpException(JsonRpcResponse.INVALID_PARAMS, "uri is required");
            }

            Map<String, Object> resourceContent;
            try {
                resourceContent = rManager.readResource(uri);
            } catch (IllegalArgumentException e) {
                throw new McpException(JsonRpcResponse.INVALID_PARAMS, e.getMessage());
            }
            java.util.List<Map<String, Object>> contents = new java.util.ArrayList<Map<String, Object>>();
            Map<String, Object> textContent = new java.util.LinkedHashMap<String, Object>();
            textContent.put("uri", uri);
            textContent.put("mimeType", "application/json");
            textContent.put("text", com.alibaba.fastjson2.JSON.toJSONString(resourceContent));
            contents.add(textContent);

            Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
            result.put("contents", contents);
            return JsonRpcResponse.success(request.getId(), result);
        }

        /**
         * 处理 prompts/list 请求。
         */
        private JsonRpcResponse handlePromptsList(JsonRpcRequest request, McpSession session) {
            Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
            result.put("prompts", pManager.listPrompts());
            return JsonRpcResponse.success(request.getId(), result);
        }

        /**
         * 处理 prompts/get 请求。
         */
        private JsonRpcResponse handlePromptsGet(JsonRpcRequest request, McpSession session)
                throws McpException {
            Map<String, Object> params = request.getParamsAsMap();
            String name = (String) params.get("name");
            if (name == null || name.isEmpty()) {
                throw new McpException(JsonRpcResponse.INVALID_PARAMS, "name is required");
            }

            // 提取 arguments
            @SuppressWarnings("unchecked")
            Map<String, String> arguments = (Map<String, String>) params.get("arguments");

            Map<String, Object> result;
            try {
                result = pManager.getPrompt(name, arguments);
            } catch (IllegalArgumentException e) {
                throw new McpException(JsonRpcResponse.INVALID_PARAMS, e.getMessage());
            }
            return JsonRpcResponse.success(request.getId(), result);
        }
    }
}
