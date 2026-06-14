package com.ecat.integration.agentbridge.capability;

import java.util.Collections;
import java.util.List;

/**
 * 能力组摘要 DTO，用于 MCP tools/list 和搜索结果的轻量级描述。
 *
 * <p>每个能力组（capabilityGroup）对应一组相关的 ECAT API 路由，
 * 例如 "device-management"、"calibration"、"media" 等。
 * AI Agent 通过搜索和浏览能力组摘要来决定加载哪些工具。
 *
 * @author coffee
 */
public class CapabilityGroupSummary {

    /** 能力组唯一标识（对应 RouteDescriptor.capabilityGroup） */
    private final String groupId;

    /** 显示名称 */
    private final String displayName;

    /** 能力组描述 */
    private final String description;

    /** 该能力组包含的工具数量 */
    private final int toolCount;

    /** 安全级别（SAFE / MODERATE / HIGH_RISK） */
    private final String safetyLevel;

    /** 标签列表，用于搜索匹配 */
    private final List<String> tags;

    /**
     * 全参构造器。
     *
     * @param groupId      能力组 ID
     * @param displayName  显示名称
     * @param description  描述
     * @param toolCount    工具数量
     * @param safetyLevel  安全级别
     * @param tags         标签列表（可为 null）
     */
    public CapabilityGroupSummary(String groupId, String displayName, String description,
                                  int toolCount, String safetyLevel, List<String> tags) {
        if (groupId == null || groupId.isEmpty()) {
            throw new IllegalArgumentException("groupId must not be null or empty");
        }
        this.groupId = groupId;
        this.displayName = displayName;
        this.description = description;
        this.toolCount = toolCount;
        this.safetyLevel = safetyLevel;
        this.tags = tags != null
                ? Collections.unmodifiableList(tags)
                : Collections.<String>emptyList();
    }

    /**
     * 获取能力组 ID。
     *
     * @return 能力组唯一标识
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * 获取显示名称。
     *
     * @return 显示名称
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 获取描述。
     *
     * @return 能力组描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 获取工具数量。
     *
     * @return 该能力组包含的工具数量
     */
    public int getToolCount() {
        return toolCount;
    }

    /**
     * 获取安全级别。
     *
     * @return 安全级别字符串
     */
    public String getSafetyLevel() {
        return safetyLevel;
    }

    /**
     * 获取标签列表（不可变）。
     *
     * @return 标签列表
     */
    public List<String> getTags() {
        return tags;
    }
}
