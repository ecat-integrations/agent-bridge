package com.ecat.integration.agentbridge.subagent;

/**
 * 工具安全级别。
 *
 * <p>决定 useTools 执行前是否需要人工确认（{@code HIGH_RISK} 经 ConfirmationManager 走确认回路）。
 *
 * @author coffee
 */
public enum SafetyLevel {
    /** 安全：只读或无副作用，可直接执行 */
    SAFE,
    /** 中等：有写副作用但风险可控，默认级别 */
    MODERATE,
    /** 高风险：删除/不可逆操作，执行前必须人工确认 */
    HIGH_RISK
}
