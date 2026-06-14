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

package com.ecat.integration.agentbridge.config;

import com.ecat.core.ConfigEntry.ConfigEntry;
import com.ecat.core.ConfigEntry.ConfigEntryRegistry;
import com.ecat.core.ConfigFlow.AbstractConfigFlow;
import com.ecat.core.ConfigFlow.ConfigFlowResult;
import com.ecat.core.ConfigFlow.ConfigSchema;
import com.ecat.core.ConfigFlow.ConfigItem.TextConfigItem;
import com.ecat.core.ConfigFlow.FlowContext;
import com.ecat.core.Utils.DateTimeUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Agent Bridge 配置向导，用于配置 Agent Token。
 *
 * <p>继承 {@link AbstractConfigFlow}，提供两步配置流程：
 * <ol>
 *   <li>{@code token_setup} — 配置 Agent Token（输入名称和角色，自动生成 token）</li>
 *   <li>{@code final_confirm} — 确认配置并展示生成的 token</li>
 * </ol>
 *
 * <p>Token 管理通过 {@link TokenManagerCallback} 回调接口与 auth 模块解耦，
 * 避免对 {@code auth/} 包的直接依赖。
 *
 * <p>反射创建支持：通过 {@link #setTokenManager(TokenManagerCallback)} 设置静态引用，
 * 无参构造器使用静态引用创建实例。
 *
 * @author coffee
 */
public class AgentBridgeConfigFlow extends AbstractConfigFlow {

    /** UniqueId 前缀 */
    public static final String UNIQUE_ID_PREFIX = "com.ecat.integrations.agent-bridge.token.";

    /** 静态 Token 管理回调，由 AgentBridgeIntegration 在初始化时设置 */
    private static TokenManagerCallback tokenManagerStatic = null;

    /** Token 管理回调实例 */
    private final TokenManagerCallback tokenManager;

    /**
     * 设置静态 Token 管理回调。
     * 由 {@code AgentBridgeIntegration.onStart()} 调用，确保反射创建时有可用实例。
     *
     * @param tokenManager Token 管理回调，不能为 null
     */
    public static void setTokenManager(TokenManagerCallback tokenManager) {
        tokenManagerStatic = tokenManager;
    }

    /**
     * 无参构造器，用于 ConfigFlowRegistry 反射实例化。
     * 要求 {@link #setTokenManager(TokenManagerCallback)} 已被调用。
     *
     * @throws IllegalStateException 如果 TokenManager 未设置
     */
    public AgentBridgeConfigFlow() {
        this(requireStaticTokenManager());
    }

    /**
     * 验证静态 TokenManager 已设置，否则抛出 IllegalStateException。
     *
     * @return 静态 TokenManager 实例
     * @throws IllegalStateException 如果 TokenManager 未设置
     */
    private static TokenManagerCallback requireStaticTokenManager() {
        if (tokenManagerStatic == null) {
            throw new IllegalStateException(
                    "TokenManager not set. Call setTokenManager() before creating AgentBridgeConfigFlow.");
        }
        return tokenManagerStatic;
    }

    /**
     * 构造器，注册配置流程步骤。
     *
     * @param tokenManager Token 管理回调，不能为 null
     */
    public AgentBridgeConfigFlow(TokenManagerCallback tokenManager) {
        super();
        if (tokenManager == null) {
            throw new IllegalArgumentException("tokenManager must not be null");
        }
        this.tokenManager = tokenManager;

        // 注册用户入口步骤
        registerStepUser("token_setup", "配置 Agent Token", this::stepTokenSetup);

        // 注册普通步骤：最终确认
        registerStep("final_confirm", this::stepFinalConfirm, "确认配置");
    }

    /**
     * Token 配置步骤。
     *
     * <p>当无用户输入时：显示 token 名称和角色输入表单。
     * <p>当有用户输入时：验证输入，生成 token，跳转到确认步骤。
     *
     * @param userInput 用户输入数据，首次显示时为 null 或空
     * @param ctx       流程上下文
     * @return 配置流程结果
     */
    private ConfigFlowResult stepTokenSetup(Map<String, Object> userInput, FlowContext ctx) {
        if (userInput == null || userInput.isEmpty()) {
            return showForm("token_setup", createTokenSetupSchema(), new HashMap<String, Object>());
        }

        // 验证 token 名称
        String name = getParamString(userInput, "name");
        if (name == null || name.trim().isEmpty()) {
            Map<String, Object> errors = new HashMap<String, Object>();
            errors.put("name", "Token 名称是必填项");
            return showForm("token_setup", createTokenSetupSchema(), errors);
        }
        if (name.trim().length() < 3 || name.trim().length() > 64) {
            Map<String, Object> errors = new HashMap<String, Object>();
            errors.put("name", "Token 名称长度需在 3-64 个字符之间");
            return showForm("token_setup", createTokenSetupSchema(), errors);
        }

        // 验证角色
        String role = getParamString(userInput, "role");
        if (role == null || role.trim().isEmpty()) {
            Map<String, Object> errors = new HashMap<String, Object>();
            errors.put("role", "角色是必填项");
            return showForm("token_setup", createTokenSetupSchema(), errors);
        }

        // 生成 token（通过回调接口）
        String token = tokenManager.generateToken(name.trim(), role.trim());

        // 存储到上下文
        ctx.getEntryData().put("name", name.trim());
        ctx.getEntryData().put("role", role.trim());
        ctx.getEntryData().put("generatedAt", DateTimeUtils.now().toString());
        // rawToken 不持久化到 ConfigEntry，仅在此步骤中临时展示
        ctx.getEntryData().put("_rawToken", token);

        // 设置 uniqueId（带重复检查）
        try {
            ctx.setEntryUniqueId(UNIQUE_ID_PREFIX + name.trim());
        } catch (ConfigEntryRegistry.DuplicateUniqueIdException e) {
            Map<String, Object> errors = new HashMap<String, Object>();
            errors.put("name", "Token 名称 '" + name.trim() + "' 已存在");
            return showForm("token_setup", createTokenSetupSchema(), errors);
        }
        ctx.setEntryTitle("Agent Token: " + name.trim());

        log.info("Agent token generated via config flow: {}", name.trim());

        // 跳转到确认步骤（显示生成的 token）
        return showForm("final_confirm", createConfirmSchema(name.trim(), role.trim(), token),
                new HashMap<String, Object>());
    }

    /**
     * 最终确认步骤。
     *
     * <p>当无用户输入时：重新生成确认页面（步骤漫游回来时）。
     * <p>当有用户输入时：确认配置，创建 ConfigEntry。
     *
     * @param userInput 用户输入数据
     * @return 配置流程结果
     */
    private ConfigFlowResult stepFinalConfirm(Map<String, Object> userInput) {
        if (userInput == null || userInput.isEmpty()) {
            // 步骤漫游回来时重新显示确认页面
            String name = (String) context.getEntryData().get("name");
            String role = (String) context.getEntryData().get("role");
            String rawToken = (String) context.getEntryData().get("_rawToken");
            if (name == null || rawToken == null) {
                return abort("Missing token data, please start over.");
            }
            return showForm("final_confirm", createConfirmSchema(name, role, rawToken),
                    new HashMap<String, Object>());
        }

        // 用户点击确认 — 创建 ConfigEntry
        // 从 entryData 中移除临时字段 _rawToken，不持久化
        String rawToken = (String) context.getEntryData().remove("_rawToken");

        ConfigFlowResult result = createEntry();
        // 将 rawToken 附加到返回结果中，供前端展示
        result.getEntryData().put("rawToken", rawToken);
        return result;
    }

    // ==================== Schema 构建方法 ====================

    /**
     * 创建 Token 配置表单 Schema。
     *
     * @return 包含 name 和 role 字段的 ConfigSchema
     */
    private ConfigSchema createTokenSetupSchema() {
        return new ConfigSchema()
                .addField(new TextConfigItem("name", true)
                        .displayName("Token 名称")
                        .placeholder("输入 Agent Token 名称，如 'monitor-agent'")
                        .length(3, 64))
                .addField(new TextConfigItem("role", true, "agent")
                        .displayName("角色")
                        .placeholder("Agent 角色，如 'agent'、'admin'")
                        .length(2, 32));
    }

    /**
     * 创建确认页面 Schema，展示生成的 token 信息。
     *
     * @param name    Token 名称
     * @param role    Agent 角色
     * @param rawToken 生成的原始 token 字符串
     * @return 包含确认信息的 ConfigSchema
     */
    private ConfigSchema createConfirmSchema(String name, String role, String rawToken) {
        return new ConfigSchema()
                .addField(new TextConfigItem("name", false, name)
                        .displayName("Token 名称")
                        .readOnly(true))
                .addField(new TextConfigItem("role", false, role)
                        .displayName("角色")
                        .readOnly(true))
                .addField(new TextConfigItem("token", false, rawToken)
                        .displayName("生成的 Token")
                        .readOnly(true));
    }

    // ==================== 工具方法 ====================

    /**
     * 安全地从用户输入中提取字符串参数。
     *
     * @param input 用户输入 Map
     * @param key   参数键
     * @return 参数值字符串，不存在时返回 null
     */
    private String getParamString(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    // ==================== 回调接口 ====================

    /**
     * Token 管理回调接口，ConfigFlow 使用此接口与 auth 模块解耦。
     *
     * <p>实现类（通常是 {@code AgentAuthManager} 的适配器）负责实际的 token 生成逻辑。
     * 这样 ConfigFlow 不需要直接依赖 {@code auth/} 包的具体实现类。
     *
     * @author coffee
     */
    public interface TokenManagerCallback {

        /**
         * 生成 Agent token。
         *
         * @param name Agent 名称
         * @param role Agent 角色
         * @return 生成的原始 token 字符串
         */
        String generateToken(String name, String role);
    }
}
