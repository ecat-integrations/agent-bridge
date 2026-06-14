package com.ecat.integration.agentbridge.auth;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Agent 身份信息，包含唯一标识、名称、角色和权限集合。
 * <p>
 * 每个 Agent 连接认证通过后，会生成对应的 AgentIdentity 实例，
 * 用于后续的权限校验和操作审计。
 *
 * @author coffee
 */
public class AgentIdentity {

    /** Agent 唯一标识 */
    private final String agentId;

    /** Agent 名称 */
    private final String name;

    /** Agent 角色 */
    private final String role;

    /** Agent 权限集合（不可变） */
    private final Set<String> permissions;

    /**
     * 构造 AgentIdentity。
     *
     * @param agentId     Agent 唯一标识，不能为 null
     * @param name        Agent 名称，不能为 null
     * @param role        Agent 角色，不能为 null
     * @param permissions 权限集合，不能为 null，内部会拷贝为不可变集合
     */
    public AgentIdentity(String agentId, String name, String role, Set<String> permissions) {
        if (agentId == null) {
            throw new IllegalArgumentException("agentId must not be null");
        }
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        if (permissions == null) {
            throw new IllegalArgumentException("permissions must not be null");
        }
        this.agentId = agentId;
        this.name = name;
        this.role = role;
        this.permissions = Collections.unmodifiableSet(new HashSet<String>(permissions));
    }

    /**
     * 获取 Agent 唯一标识。
     *
     * @return agentId
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * 获取 Agent 名称。
     *
     * @return name
     */
    public String getName() {
        return name;
    }

    /**
     * 获取 Agent 角色。
     *
     * @return role
     */
    public String getRole() {
        return role;
    }

    /**
     * 获取 Agent 权限集合（不可变）。
     *
     * @return 权限集合的不可变视图
     */
    public Set<String> getPermissions() {
        return permissions;
    }

    /**
     * 检查 Agent 是否拥有指定权限。
     *
     * @param permission 要检查的权限标识，不能为 null
     * @return 如果拥有该权限返回 true，否则返回 false
     */
    public boolean hasPermission(String permission) {
        if (permission == null) {
            throw new IllegalArgumentException("permission must not be null");
        }
        return permissions.contains(permission);
    }

    @Override
    public String toString() {
        return "AgentIdentity{" +
                "agentId='" + agentId + '\'' +
                ", name='" + name + '\'' +
                ", role='" + role + '\'' +
                ", permissions=" + permissions +
                '}';
    }
}
