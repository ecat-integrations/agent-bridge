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
import com.ecat.integration.agentbridge.subagent.CliParser;
import com.ecat.integration.agentbridge.subagent.SubAgentRegistry;
import com.ecat.integration.agentbridge.subagent.ToolDispatcher;
import com.ecat.integration.agentbridge.subagent.AbstractSubAgent;
import com.ecat.integration.agentbridge.subagent.impl.DeviceSubAgent;
import com.ecat.integration.agentbridge.subagent.impl.LogicDeviceSubAgent;
import com.ecat.integration.agentbridge.subagent.impl.MediaSubAgent;
import com.ecat.integration.agentbridge.subagent.impl.EventSubAgent;
import com.ecat.integration.agentbridge.config.AgentBridgeConfigFlow;
import com.ecat.integration.agentbridge.mcp.McpRequestHandler;
import com.ecat.integration.agentbridge.mcp.McpServer;
import com.ecat.integration.agentbridge.mcp.JsonRpcRequest;
import com.ecat.integration.agentbridge.mcp.JsonRpcResponse;
import com.ecat.integration.agentbridge.mcp.McpException;
import com.ecat.integration.agentbridge.mcp.McpSession;
import com.ecat.integration.agentbridge.tool.ToolExecutor;
import com.ecat.integration.agentbridge.tool.MediaUrlSigner;

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
                return authManager.generateToken(name, role).getToken();
            }

            @Override
            public String hashToken(String rawToken) {
                return AgentAuthManager.sha256(rawToken);
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
     *   <li>authManager 已在 onLoad() 中创建</li>
     *   <li>创建 ToolExecutor（注入 InternalTokenProvider 适配器）</li>
     *   <li>BCP 装配 SubAgent：SubAgentRegistry + CliParser + ToolDispatcher</li>
     *   <li>订阅 INTEGRATION_LIFECYCLE（SubAgent 自发现）</li>
     *   <li>创建 McpRequestHandlerImpl（仅注入 ToolDispatcher — BCP 仅暴露 tools 通道，doc 05 §1.4）</li>
     *   <li>创建 McpServer（注入 server, authenticator, endpoint, requestHandler）</li>
     *   <li>启动 McpServer（注册 /mcp 路由）</li>
     *   <li>订阅 INTEGRATIONS_ALL_LOADED 事件（对账 SubAgent 索引 + 获取内部 token）</li>
     * </ol>
     *
     * <p>BCP 架构砍掉旧组件：不再创建 CapabilityManager / ResourceManager / PromptManager /
     * EventManager，不再注册 EventController.Listener 与 SSE 端点（resources/prompts/notifications
     * 通道不暴露，见 doc 05 §1.4、doc 06 §2.4）。
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

        // 3. 创建 ToolExecutor（注入 InternalTokenProvider 适配器）
        toolExecutor = new ToolExecutor(new ToolExecutor.InternalTokenProvider() {
            @Override
            public String getInternalToken() {
                return authManager.getInternalToken();
            }
        });

        // 4. BCP SubAgent 装配：候选注册表 + CLI 解析器 + 路由调度器
        // P0 候选 SubAgent 在 Phase 3 逐步填入；当前 DeviceSubAgent（依赖 core-api）
        // MediaSubAgent 注入 MediaUrlSigner：复用 ecat-core-api 的 AuthManager.signMediaUrl，
        // 进程内强类型直调（非反射）生成 agent 可直接 GET 的无 token 下载 URL（Plan A）。
        // agent-bridge 依赖 ecat-core-api，启动时 core-api 已 ACTIVE、authManager 已就绪；
        // 缺失即启动时序异常，严格模式抛异常（不构造无签名器的半功能 MediaSubAgent）。
        EcatCoreApiIntegration coreApi = (EcatCoreApiIntegration)
                integrationRegistry.getIntegration("com.ecat:integration-ecat-core-api");
        if (coreApi == null || coreApi.getAuthManager() == null) {
            throw new IllegalStateException(
                    "ecat-core-api 或其 AuthManager 在 agent-bridge 启动时不可用，无法装配 MediaUrlSigner");
        }
        MediaUrlSigner mediaUrlSigner = new MediaUrlSigner(coreApi.getAuthManager());
        List<AbstractSubAgent> candidates = new ArrayList<AbstractSubAgent>();
        candidates.add(new DeviceSubAgent());
        candidates.add(new LogicDeviceSubAgent());
        candidates.add(new MediaSubAgent(mediaUrlSigner));
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

        // 5. 创建 McpRequestHandler 实现（BCP 仅注入 ToolDispatcher：MCP 只暴露 tools 通道，doc 05 §1.4）
        McpRequestHandler requestHandler = new McpRequestHandlerImpl(toolDispatcher);

        // 6. 创建 McpServer
        mcpServer = new McpServer(server, authManager, MCP_ENDPOINT, requestHandler);

        // BCP 架构砍掉 SSE 推送 / notifications / resources / prompts 通道（doc 05 §1.4），
        // 故不再创建 EventManager、不再注册 EventController.Listener、不再注册 SSE 端点。

        // 7. ConfigFlow 静态回调已在 onLoad() 中设置

        // 8. 启动 McpServer — 注册 POST/DELETE /mcp 路由
        mcpServer.start();

        // 9. 订阅 INTEGRATIONS_ALL_LOADED 事件
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
     * <p>停止 MCP 服务器并取消事件订阅。
     */
    @Override
    public void onRelease() {
        if (mcpServer != null) {
            mcpServer.stop();
            mcpServer = null;
        }
        if (integrationsLoadedSubscription != null) {
            integrationsLoadedSubscription.unsubscribe();
            integrationsLoadedSubscription = null;
        }
        httpServerPool = null;
        server = null;
        authManager = null;
        toolExecutor = null;
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
                return authManager.generateToken(name, role).getToken();
            }

            @Override
            public String hashToken(String rawToken) {
                return AgentAuthManager.sha256(rawToken);
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
     *   <li>全量对账 SubAgent 索引（解决启动时序竞争，Bug #3）</li>
     *   <li>获取 ecat-core-api 内部 session token</li>
     * </ol>
     *
     * <p>BCP 架构下不再构建 CapabilityManager 能力索引（旧 CapabilityManager 已移除，
     * 能力发现下沉到 SubAgent，见 doc 06 §2.3/§2.4）。
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
                        obtainInternalToken();
                        log.info("Agent Bridge initialized: SubAgent index reconciled, internal token obtained");
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

        private final ToolDispatcher toolDispatcher;

        /**
         * 构造器。
         *
         * <p>BCP 架构下 MCP 仅暴露 tools 通道（getTools/useTools），能力下沉到 SubAgent
         * 由 toolDispatcher 路由；resources/prompts 分支返回空列表保留 MCP 握手兼容
         * （doc 05 §1.4 / doc 06 §2.4）。旧内置工具（search/load capabilities、audit、
         * confirm、async-result）及其依赖（CapabilityManager/ResourceManager/PromptManager/
         * ConfirmationManager）已废弃移除。
         *
         * @param toolDispatcher BCP 两工具路由调度器（getTools/useTools）
         */
        McpRequestHandlerImpl(ToolDispatcher toolDispatcher) {
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
            // getTools 的 description 即能力菜单（BCP/Nexus 元工具模式，doc 03 §Part 3）：
            // 模型在 tools/list 阶段即可读到「领域 Agents: agent: [tools,...]」清单，无需调用。
            tools.add(buildBcpToolSchema("getTools",
                    toolDispatcher.buildGetToolsDescription(),
                    true,
                    toolDispatcher.buildGetToolsParamHint()));
            tools.add(buildBcpToolSchema("useTools",
                    toolDispatcher.buildUseToolsDescription(),
                    false,
                    "完整 CLI 命令串，如 \"device list\""));
            Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
            result.put("tools", tools);
            return JsonRpcResponse.success(request.getId(), result);
        }

        /**
         * 构造 BCP 工具 schema（name/description/inputSchema）。
         *
         * @param name          工具名（getTools/useTools）
         * @param description   工具描述
         * @param toolOptional  tool 参数是否可选（getTools 可选，useTools 必填）
         * @param toolParamHint tool 参数的语义说明（getTools 传领域 agent 名；useTools 传 CLI 串）——
         *                      两工具共用 tool 参数但语义不同，各自点破避免 agent 困惑
         */
        private Map<String, Object> buildBcpToolSchema(String name, String description,
                                                       boolean toolOptional, String toolParamHint) {
            Map<String, Object> tool = new java.util.LinkedHashMap<String, Object>();
            tool.put("name", name);
            tool.put("description", description);

            Map<String, Object> inputSchema = new java.util.LinkedHashMap<String, Object>();
            inputSchema.put("type", "object");
            Map<String, Object> toolProp = new java.util.LinkedHashMap<String, Object>();
            toolProp.put("type", "string");
            toolProp.put("description", toolParamHint);
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
                    // 透传当前请求 Host（进程内工具如 media get-download-url 据此拼下载 URL）
                    return toolResultToResponse(request, toolDispatcher.useTools(cli, session.getRequestHostPort()));
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
         * 处理 resources/list 请求。
         *
         * <p>BCP 架构仅暴露 tools 通道（doc 05 §1.4），resources 不对外暴露。
         * 保留此方法以兼容 MCP 握手探测（doc 06 §2.4），固定返回空列表。
         */
        private JsonRpcResponse handleResourcesList(JsonRpcRequest request, McpSession session) {
            Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
            result.put("resources", new java.util.ArrayList<Object>());
            return JsonRpcResponse.success(request.getId(), result);
        }

        /**
         * 处理 resources/read 请求。
         *
         * <p>BCP 架构仅暴露 tools 通道（doc 05 §1.4），resources 不对外暴露。
         * 保留此方法以兼容 MCP 握手探测（doc 06 §2.4），固定返回空 contents 列表，
         * 不再校验 uri / 读取资源。
         */
        private JsonRpcResponse handleResourcesRead(JsonRpcRequest request, McpSession session) {
            Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
            result.put("contents", new java.util.ArrayList<Object>());
            return JsonRpcResponse.success(request.getId(), result);
        }

        /**
         * 处理 prompts/list 请求。
         *
         * <p>BCP 架构仅暴露 tools 通道（doc 05 §1.4），prompts 不对外暴露。
         * 保留此方法以兼容 MCP 握手探测（doc 06 §2.4），固定返回空列表。
         */
        private JsonRpcResponse handlePromptsList(JsonRpcRequest request, McpSession session) {
            Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
            result.put("prompts", new java.util.ArrayList<Object>());
            return JsonRpcResponse.success(request.getId(), result);
        }

        /**
         * 处理 prompts/get 请求。
         *
         * <p>BCP 架构仅暴露 tools 通道（doc 05 §1.4），prompts 不对外暴露。
         * 保留此方法以兼容 MCP 握手探测（doc 06 §2.4），固定返回空 messages 列表，
         * 不再校验 name / 渲染模板。
         */
        private JsonRpcResponse handlePromptsGet(JsonRpcRequest request, McpSession session) {
            Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
            result.put("messages", new java.util.ArrayList<Object>());
            return JsonRpcResponse.success(request.getId(), result);
        }
    }
}
