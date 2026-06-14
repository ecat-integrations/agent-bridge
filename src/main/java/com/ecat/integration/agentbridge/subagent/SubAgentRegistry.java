package com.ecat.integration.agentbridge.subagent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SubAgent 候选注册表 + 动态能力索引。
 *
 * <p>核心职责（doc 05 §4.1）：
 * <ul>
 *   <li>持有全部候选 SubAgent（构造时注入，P0 硬编码）</li>
 *   <li>{@link #buildIndex(Set)} 按 ACTIVE 集成集合过滤，注册命中的 agent</li>
 *   <li>{@link #onIntegrationActive(String)} / {@link #onIntegrationStopped(String)}
 *       增量更新（自发现：新集成 ACTIVE 即时注册其 agent）</li>
 *   <li>{@link #buildCapabilityIndex()} 拼接 {agentName: [toolName,...]} 索引，供 getTools description 使用</li>
 * </ul>
 *
 * <p>线程安全说明：P0 集成加载在单线程完成，未加同步；后续多 Agent 并发查询再评估。
 *
 * @author coffee
 */
public class SubAgentRegistry {

    /** 全部候选 SubAgent（不可变，构造时确定） */
    private final List<AbstractSubAgent> candidates;

    /** 当前 ACTIVE 集成集合 */
    private final Set<String> activeIntegrations = new HashSet<String>();

    /** 已注册的 agent（agentName → agent），按注册顺序 */
    private final Map<String, AbstractSubAgent> activeAgents = new LinkedHashMap<String, AbstractSubAgent>();

    /**
     * @param candidates 全部候选 SubAgent，不能为 null
     */
    public SubAgentRegistry(List<AbstractSubAgent> candidates) {
        if (candidates == null) {
            throw new IllegalArgumentException("candidates must not be null");
        }
        this.candidates = new ArrayList<AbstractSubAgent>(candidates);
    }

    /**
     * 按 ACTIVE 集成集合重建已注册 agent 索引。
     *
     * <p>requiredIntegration 命中 ACTIVE 集合的候选 agent 被注册，其余移除。
     *
     * @param activeIntegrations 当前 ACTIVE 的集成 coordinate 集合
     */
    public void buildIndex(Set<String> activeIntegrations) {
        this.activeIntegrations.clear();
        this.activeIntegrations.addAll(activeIntegrations);
        activeAgents.clear();
        for (AbstractSubAgent agent : candidates) {
            if (this.activeIntegrations.contains(agent.getRequiredIntegration())) {
                activeAgents.put(agent.getAgentName(), agent);
            }
        }
    }

    /**
     * 集成 ACTIVE 时增量注册其对应候选 agent（自发现）。
     *
     * @param coordinate 变为 ACTIVE 的集成 coordinate
     */
    public void onIntegrationActive(String coordinate) {
        activeIntegrations.add(coordinate);
        for (AbstractSubAgent agent : candidates) {
            if (coordinate.equals(agent.getRequiredIntegration())
                    && !activeAgents.containsKey(agent.getAgentName())) {
                activeAgents.put(agent.getAgentName(), agent);
            }
        }
    }

    /**
     * 集成 STOPPED 时移除其对应 agent。
     *
     * @param coordinate 变为 STOPPED 的集成 coordinate
     */
    public void onIntegrationStopped(String coordinate) {
        activeIntegrations.remove(coordinate);
        Iterator<Map.Entry<String, AbstractSubAgent>> it = activeAgents.entrySet().iterator();
        while (it.hasNext()) {
            if (coordinate.equals(it.next().getValue().getRequiredIntegration())) {
                it.remove();
            }
        }
    }

    /**
     * 按名查找已注册的 agent。
     *
     * @param name agent 名（CLI 首 token）
     * @return agent 实例，未注册时返回 null
     */
    public AbstractSubAgent findAgent(String name) {
        return activeAgents.get(name);
    }

    /**
     * 构建能力索引：{agentName: [toolName,...]}，仅含已注册 agent，按注册顺序。
     *
     * @return 能力索引（不可变视图）
     */
    public Map<String, List<String>> buildCapabilityIndex() {
        Map<String, List<String>> index = new LinkedHashMap<String, List<String>>();
        for (AbstractSubAgent agent : activeAgents.values()) {
            List<String> toolNames = new ArrayList<String>();
            for (ToolDescriptor tool : agent.getTools()) {
                toolNames.add(tool.getToolName());
            }
            index.put(agent.getAgentName(), toolNames);
        }
        return index;
    }
}
