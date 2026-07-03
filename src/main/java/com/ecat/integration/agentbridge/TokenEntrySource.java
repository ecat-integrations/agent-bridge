package com.ecat.integration.agentbridge;

/**
 * token entry 的来源类型（写入 {@code ConfigEntry.data} 的 {@code "source"} 字段）。
 *
 * <p>应用场景：区分 token entry 是用户经 ConfigFlow 自建（无 source 字段），
 * 还是由其他集成（如 integration-llm-agent）经
 * {@link AgentBridgeIntegration#issueManagedToken} 程序化托管（{@link #MANAGED}）。
 * 用 enum 而非字面量字符串，避免散落硬编码、防拼错（项目纪律：可枚举集合用 enum）。
 *
 * <p>序列化：写入 ConfigEntry.data 时用 {@link #name()}（"MANAGED"），YAML 持久化为
 * 字符串；读取时按 {@link #name()} 比对。ConfigEntry.data 本身是开放式 kv（W1 白名单），
 * 存 enum.name() 字符串值，不破坏既有序列化。
 * 
 * @author coffee
 */
public enum TokenEntrySource {
    /** 由其他集成程序化签发托管（如 integration-llm-agent 调 issueManagedToken） */
    MANAGED
}
