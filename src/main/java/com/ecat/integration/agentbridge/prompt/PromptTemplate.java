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
import java.util.List;
import java.util.Map;

/**
 * MCP 提示词模板，描述一个可供 AI Agent 使用的提示词。
 *
 * <p>每个 PromptTemplate 包含名称、描述、模板内容字符串和参数定义列表。
 * 模板中使用 {@code {argName}} 占位符表示参数，通过 {@link #apply(Map)} 替换为实际值。
 *
 * <p>内置模板由 {@link PromptManager} 在初始化时注册。
 *
 * @author coffee
 */
public class PromptTemplate {

    /** 提示词模板名称，全局唯一标识 */
    private final String name;

    /** 提示词模板描述，供 AI Agent 理解提示词用途 */
    private final String description;

    /** 模板内容，包含 {argName} 占位符 */
    private final String template;

    /** 参数定义列表（不可变） */
    private final List<Argument> arguments;

    /**
     * 构造 PromptTemplate。
     *
     * @param name        模板名称，不能为 null
     * @param description 模板描述，不能为 null
     * @param template    模板内容，不能为 null
     * @param arguments   参数定义列表，不能为 null，内部会拷贝为不可变列表
     */
    public PromptTemplate(String name, String description, String template,
                          List<Argument> arguments) {
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }
        if (description == null) {
            throw new IllegalArgumentException("description must not be null");
        }
        if (template == null) {
            throw new IllegalArgumentException("template must not be null");
        }
        if (arguments == null) {
            throw new IllegalArgumentException("arguments must not be null");
        }
        this.name = name;
        this.description = description;
        this.template = template;
        this.arguments = Collections.unmodifiableList(new ArrayList<Argument>(arguments));
    }

    /**
     * 获取模板名称。
     *
     * @return 模板名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取模板描述。
     *
     * @return 模板描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 获取模板内容。
     *
     * @return 模板内容字符串
     */
    public String getTemplate() {
        return template;
    }

    /**
     * 获取参数定义列表（不可变）。
     *
     * @return 参数列表
     */
    public List<Argument> getArguments() {
        return arguments;
    }

    /**
     * 将模板中的 {argName} 占位符替换为实际参数值。
     *
     * <p>对于模板中出现的每个 {@code {xxx}} 占位符，在 args Map 中查找对应的值进行替换。
     * 如果 args 为 null 或缺少某个参数，占位符保持原样不替换。
     *
     * @param args 参数键值对，key 为参数名，value 为参数值；可为 null
     * @return 替换后的字符串
     */
    public String apply(Map<String, String> args) {
        if (args == null || args.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : args.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result = result.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return result;
    }

    /**
     * 提示词参数定义。
     *
     * <p>描述模板中一个占位符参数的名称、用途和是否必填。
     */
    public static class Argument {

        /** 参数名称 */
        private final String name;

        /** 参数描述 */
        private final String description;

        /** 是否必填 */
        private final boolean required;

        /**
         * 构造参数定义。
         *
         * @param name        参数名称，不能为 null
         * @param description 参数描述，不能为 null
         * @param required    是否必填
         */
        public Argument(String name, String description, boolean required) {
            if (name == null) {
                throw new IllegalArgumentException("name must not be null");
            }
            if (description == null) {
                throw new IllegalArgumentException("description must not be null");
            }
            this.name = name;
            this.description = description;
            this.required = required;
        }

        /**
         * 获取参数名称。
         *
         * @return 参数名称
         */
        public String getName() {
            return name;
        }

        /**
         * 获取参数描述。
         *
         * @return 参数描述
         */
        public String getDescription() {
            return description;
        }

        /**
         * 判断参数是否必填。
         *
         * @return true 表示必填
         */
        public boolean isRequired() {
            return required;
        }
    }
}
