package com.ecat.integration.agentbridge.subagent;

import java.util.Collections;
import java.util.List;

/**
 * 单个 CLI 工具的元数据 + 内部 HTTP 映射。
 *
 * <p>不可变值对象，由两部分组成：
 * <ul>
 *   <li>CLI 元数据：toolName / description / usage / args / examples / safetyLevel，
 *       用于 getTools 返回给 Agent（CLI-first 格式，doc 05 §7.1）</li>
 *   <li>HTTP 映射：httpMethod / httpPath / async，供 ToolExecutor 执行（进程内调用的工具可不设）</li>
 * </ul>
 *
 * <p>{@code httpPath} 中的 {@code {xxx}} 占位符由 ToolExecutor 用 args 中
 * {@link ArgDescriptor#isPathParam()} 的参数值替换。
 *
 * @author coffee
 */
public class ToolDescriptor {

    private final String toolName;
    private final String description;
    private final String usage;
    private final List<ArgDescriptor> args;
    private final List<String> examples;
    private final SafetyLevel safetyLevel;
    private final String httpMethod;
    private final String httpPath;
    private final boolean async;

    private ToolDescriptor(Builder b) {
        this.toolName = b.toolName;
        this.description = b.description;
        this.usage = b.usage;
        this.args = b.args;
        this.examples = b.examples;
        this.safetyLevel = b.safetyLevel;
        this.httpMethod = b.httpMethod;
        this.httpPath = b.httpPath;
        this.async = b.async;
    }

    /** 获取工具名（CLI 次 token，如 "set-attribute"） */
    public String getToolName() { return toolName; }

    /** 获取工具描述 */
    public String getDescription() { return description; }

    /** 获取 CLI 用法串 */
    public String getUsage() { return usage; }

    /** 获取参数定义列表 */
    public List<ArgDescriptor> getArgs() { return args; }

    /** 获取示例列表 */
    public List<String> getExamples() { return examples; }

    /** 获取安全级别 */
    public SafetyLevel getSafetyLevel() { return safetyLevel; }

    /** 获取 HTTP 方法（GET/POST/...），进程内调用工具返回 null */
    public String getHttpMethod() { return httpMethod; }

    /** 获取 HTTP 路径（含 {xxx} 占位符），进程内调用工具返回 null */
    public String getHttpPath() { return httpPath; }

    /** 是否异步操作（HTTP 202） */
    public boolean isAsync() { return async; }

    /**
     * ToolDescriptor 构造器。
     *
     * <pre>
     * new ToolDescriptor.Builder("set-attribute")
     *     .description("设置设备属性值")
     *     .httpMethod("PUT").httpPath("/devices/{deviceId}/attributes/{attrId}/value").async()
     *     .safetyLevel(SafetyLevel.MODERATE)
     *     .args(Arrays.asList(
     *         new ArgDescriptor.Builder("deviceId").pathParam().required().build(),
     *         new ArgDescriptor.Builder("attrId").pathParam().required().build(),
     *         new ArgDescriptor.Builder("value").flag("--value").required().build()))
     *     .build();
     * </pre>
     */
    public static class Builder {
        private final String toolName;
        private String description;
        private String usage;
        private List<ArgDescriptor> args = Collections.emptyList();
        private List<String> examples = Collections.emptyList();
        private SafetyLevel safetyLevel = SafetyLevel.SAFE;
        private String httpMethod;
        private String httpPath;
        private boolean async;

        /** @param toolName 工具名，不能为 null/空 */
        public Builder(String toolName) {
            if (toolName == null || toolName.isEmpty()) {
                throw new IllegalArgumentException("toolName must not be null/empty");
            }
            this.toolName = toolName;
        }

        public Builder description(String description) { this.description = description; return this; }
        public Builder usage(String usage) { this.usage = usage; return this; }
        public Builder args(List<ArgDescriptor> args) {
            this.args = (args == null) ? Collections.<ArgDescriptor>emptyList() : args;
            return this;
        }
        public Builder examples(List<String> examples) {
            this.examples = (examples == null) ? Collections.<String>emptyList() : examples;
            return this;
        }
        public Builder safetyLevel(SafetyLevel safetyLevel) {
            if (safetyLevel != null) { this.safetyLevel = safetyLevel; }
            return this;
        }
        public Builder httpMethod(String httpMethod) { this.httpMethod = httpMethod; return this; }
        public Builder httpPath(String httpPath) { this.httpPath = httpPath; return this; }
        public Builder async() { this.async = true; return this; }

        public ToolDescriptor build() { return new ToolDescriptor(this); }
    }
}
