package com.ecat.integration.agentbridge.subagent;

/**
 * CLI 参数元数据。
 *
 * <p>描述单个 CLI 参数的逻辑名、flag、类型、是否必填、是否 positional、是否路径参数。
 * 由 {@link CliParser}（positional 回填 + 类型强转）与 ToolExecutor（路径参数替换）共同使用。
 *
 * <p>不可变值对象，通过 {@link Builder} 构造。
 *
 * @author coffee
 */
public class ArgDescriptor {

    /** 参数类型，决定 CliParser 解析后的强转目标 */
    public enum Type {
        /** 字符串（默认） */
        STRING,
        /** 整数 */
        INTEGER,
        /** 浮点数 */
        NUMBER,
        /** 布尔（true/false） */
        BOOLEAN
    }

    private final String name;
    /** CLI flag（如 "--id"），纯 positional 参数为 null */
    private final String flag;
    private final Type type;
    private final boolean required;
    private final boolean positional;
    /** 是否路径参数（对应 httpPath 中的 {xxx}，供 ToolExecutor 替换） */
    private final boolean pathParam;
    private final String description;

    private ArgDescriptor(Builder b) {
        this.name = b.name;
        this.flag = b.flag;
        this.type = b.type;
        this.required = b.required;
        this.positional = b.positional;
        this.pathParam = b.pathParam;
        this.description = b.description;
    }

    /** 获取参数逻辑名（param key） */
    public String getName() { return name; }

    /** 获取 CLI flag，positional 参数返回 null */
    public String getFlag() { return flag; }

    /** 获取参数类型 */
    public Type getType() { return type; }

    /** 是否必填 */
    public boolean isRequired() { return required; }

    /** 是否 positional（按位置回填） */
    public boolean isPositional() { return positional; }

    /** 是否路径参数 */
    public boolean isPathParam() { return pathParam; }

    /** 获取描述 */
    public String getDescription() { return description; }

    /**
     * ArgDescriptor 构造器。
     *
     * <pre>
     * new ArgDescriptor.Builder("id").flag("--id").required().pathParam().build();
     * </pre>
     */
    public static class Builder {
        private final String name;
        private String flag;
        private Type type = Type.STRING;
        private boolean required;
        private boolean positional;
        private boolean pathParam;
        private String description;

        /**
         * @param name 参数逻辑名，不能为 null/空
         */
        public Builder(String name) {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("arg name must not be null/empty");
            }
            this.name = name;
        }

        /** 设置 CLI flag（默认按 name 推断为 "--" + name） */
        public Builder flag(String flag) { this.flag = flag; return this; }

        /** 设置参数类型（默认 STRING） */
        public Builder type(Type type) {
            if (type != null) { this.type = type; }
            return this;
        }

        /** 标记必填 */
        public Builder required() { this.required = true; return this; }

        /** 标记 positional（按位置回填） */
        public Builder positional() { this.positional = true; return this; }

        /** 标记路径参数 */
        public Builder pathParam() { this.pathParam = true; return this; }

        /** 设置描述 */
        public Builder description(String description) { this.description = description; return this; }

        /** 构建不可变 ArgDescriptor */
        public ArgDescriptor build() { return new ArgDescriptor(this); }
    }
}
