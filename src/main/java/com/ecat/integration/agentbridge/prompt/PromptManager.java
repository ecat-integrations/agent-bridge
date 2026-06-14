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

package com.ecat.integration.agentbridge.prompt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词管理器，管理 MCP prompts 资源。
 *
 * <p>提供 MCP 协议的 prompts/list 和 prompts/get 两个标准方法。
 * 内置三个系统提示词模板：
 * <ul>
 *   <li>{@code ecat_system_overview} — ECAT 系统概述（无参数）</li>
 *   <li>{@code ecat_device_guide} — 设备交互指南（参数：device_type）</li>
 *   <li>{@code ecat_safety_guide} — 安全操作指南（无参数）</li>
 * </ul>
 *
 * <p>线程安全：内部使用 {@link ConcurrentHashMap} 存储模板。
 *
 * @author coffee
 */
public class PromptManager {

    /** 已注册的提示词模板：name -> PromptTemplate */
    private final ConcurrentHashMap<String, PromptTemplate> templates =
            new ConcurrentHashMap<String, PromptTemplate>();

    /**
     * 构造 PromptManager，注册内置提示词模板。
     */
    public PromptManager() {
        registerBuiltInTemplates();
    }

    /**
     * 返回 MCP prompts/list 响应数据。
     *
     * <p>返回格式为模板列表，每个模板包含 name、description 和 arguments 数组。
     * 符合 MCP 协议 prompts/list 方法的响应规范。
     *
     * @return 提示词列表，每个元素为 Map 格式的模板描述
     */
    public List<Map<String, Object>> listPrompts() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (PromptTemplate tmpl : templates.values()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", tmpl.getName());
            item.put("description", tmpl.getDescription());

            List<Map<String, Object>> args = new ArrayList<Map<String, Object>>();
            for (PromptTemplate.Argument arg : tmpl.getArguments()) {
                Map<String, Object> argMap = new LinkedHashMap<String, Object>();
                argMap.put("name", arg.getName());
                argMap.put("description", arg.getDescription());
                argMap.put("required", arg.isRequired());
                args.add(argMap);
            }
            item.put("arguments", args);
            result.add(item);
        }
        return result;
    }

    /**
     * 返回 MCP prompts/get 响应数据。
     *
     * <p>根据模板名称查找模板，使用传入的参数替换模板中的占位符，
     * 返回包含 description 和 messages 数组的响应对象。
     *
     * <p>messages 数组中包含一个 role=user 的消息，content 为替换后的模板内容。
     *
     * @param name      模板名称，不能为 null
     * @param arguments 参数键值对，可为 null
     * @return MCP prompts/get 响应 Map
     * @throws IllegalArgumentException 如果模板名称不存在
     */
    public Map<String, Object> getPrompt(String name, Map<String, String> arguments) {
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }
        PromptTemplate tmpl = templates.get(name);
        if (tmpl == null) {
            throw new IllegalArgumentException("Prompt not found: " + name);
        }

        String rendered = tmpl.apply(arguments);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("description", tmpl.getDescription());

        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", "user");

        Map<String, Object> content = new LinkedHashMap<String, Object>();
        content.put("type", "text");
        content.put("text", rendered);
        message.put("content", content);

        messages.add(message);
        result.put("messages", messages);

        return result;
    }

    /**
     * 注册自定义提示词模板。
     *
     * @param template 提示词模板，不能为 null
     * @throws IllegalArgumentException 如果同名模板已存在
     */
    public void registerTemplate(PromptTemplate template) {
        if (template == null) {
            throw new IllegalArgumentException("template must not be null");
        }
        PromptTemplate existing = templates.putIfAbsent(template.getName(), template);
        if (existing != null) {
            throw new IllegalArgumentException(
                    "Prompt template already exists: " + template.getName());
        }
    }

    /**
     * 注册内置提示词模板。
     *
     * <p>三个内置模板：
     * <ol>
     *   <li>{@code ecat_system_overview} — ECAT 平台能力和架构概述</li>
     *   <li>{@code ecat_device_guide} — 设备交互指南（参数：device_type）</li>
     *   <li>{@code ecat_safety_guide} — 安全操作规范和风险提示</li>
     * </ol>
     */
    private void registerBuiltInTemplates() {
        // ecat_system_overview — 无参数
        templates.put("ecat_system_overview", new PromptTemplate(
                "ecat_system_overview",
                "ECAT 系统概述：平台核心能力、架构和集成概览",
                "ECAT 是一个工业物联网设备管理平台。核心能力包括：\n"
                + "- 设备管理：支持多种通信协议（Modbus RTU/TCP、Serial、HTTP）的设备接入\n"
                + "- 数据采集：实时采集设备数据，支持属性读写和状态监控\n"
                + "- 告警管理：基于阈值的设备告警，支持多级报警策略\n"
                + "- 集成扩展：模块化架构，通过集成包扩展功能\n"
                + "- API 接口：完整的 REST API，支持设备 CRUD、属性操作、配置流程\n\n"
                + "Agent Bridge 通过 MCP 协议向外部 AI Agent 暴露设备管理能力。",
                Collections.<PromptTemplate.Argument>emptyList()));

        // ecat_device_guide — 参数: device_type
        List<PromptTemplate.Argument> deviceArgs =
                new ArrayList<PromptTemplate.Argument>();
        deviceArgs.add(new PromptTemplate.Argument(
                "device_type", "设备类型（如 modbus_rtu, modbus_tcp, serial, http）", true));

        templates.put("ecat_device_guide", new PromptTemplate(
                "ecat_device_guide",
                "设备交互指南：根据设备类型提供操作指导",
                "设备类型：{device_type}\n\n"
                + "通用操作流程：\n"
                + "1. 查询可用设备列表（tools: get_core_api_devices）\n"
                + "2. 获取设备属性信息（tools: get_core_api_devices__id_）\n"
                + "3. 读取/写入属性值（tools: put_core_api_devices__id__attributes__attrid__value）\n\n"
                + "注意事项：\n"
                + "- 写入操作前务必确认目标设备和属性\n"
                + "- 高风险操作（如设备配置变更）需要人工确认\n"
                + "- 建议先读取当前值再执行写入操作",
                deviceArgs));

        // ecat_safety_guide — 无参数
        templates.put("ecat_safety_guide", new PromptTemplate(
                "ecat_safety_guide",
                "安全操作指南：操作规范、风险等级和审批流程",
                "ECAT 安全操作规范：\n\n"
                + "风险等级分类：\n"
                + "- SAFE（安全）：只读操作，如查询设备列表、读取属性值\n"
                + "- CAUTION（注意）：低影响写入操作，如修改非关键配置\n"
                + "- DANGEROUS（危险）：高风险操作，如设备控制命令、系统配置变更\n\n"
                + "审批流程：\n"
                + "- SAFE 级别操作：Agent 可直接执行\n"
                + "- CAUTION 级别操作：建议通知操作人员\n"
                + "- DANGEROUS 级别操作：必须获得人工确认后才能执行\n\n"
                + "操作建议：\n"
                + "- 优先使用只读工具了解当前状态\n"
                + "- 批量操作前先在单个设备上验证\n"
                + "- 关注设备在线状态，离线设备的操作会失败",
                Collections.<PromptTemplate.Argument>emptyList()));
    }
}
