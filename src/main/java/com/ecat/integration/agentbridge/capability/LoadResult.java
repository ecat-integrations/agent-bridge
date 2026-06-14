package com.ecat.integration.agentbridge.capability;

/**
 * 加载能力组的结果 DTO，描述一次 loadCapabilities 操作的执行状态。
 *
 * <p>通过工厂方法创建：
 * <ul>
 *   <li>{@link #success(String, int)} — 加载成功</li>
 *   <li>{@link #failure(String, String)} — 加载失败</li>
 * </ul>
 *
 * @author coffee
 */
public class LoadResult {

    /** 能力组 ID */
    private final String groupId;

    /** 是否加载成功 */
    private final boolean success;

    /** 加载的工具数量（成功时有值） */
    private final int toolCount;

    /** 错误消息（失败时有值） */
    private final String errorMessage;

    /**
     * 全参构造器。
     *
     * @param groupId      能力组 ID
     * @param success      是否成功
     * @param toolCount    工具数量
     * @param errorMessage 错误消息（可为 null）
     */
    public LoadResult(String groupId, boolean success, int toolCount, String errorMessage) {
        this.groupId = groupId;
        this.success = success;
        this.toolCount = toolCount;
        this.errorMessage = errorMessage;
    }

    /**
     * 创建成功结果。
     *
     * @param groupId   能力组 ID
     * @param toolCount 成功加载的工具数量
     * @return 成功的 LoadResult
     */
    public static LoadResult success(String groupId, int toolCount) {
        return new LoadResult(groupId, true, toolCount, null);
    }

    /**
     * 创建失败结果。
     *
     * @param groupId      能力组 ID
     * @param errorMessage 错误消息
     * @return 失败的 LoadResult
     */
    public static LoadResult failure(String groupId, String errorMessage) {
        return new LoadResult(groupId, false, 0, errorMessage);
    }

    /**
     * 获取能力组 ID。
     *
     * @return 能力组 ID
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * 判断是否加载成功。
     *
     * @return true 表示成功
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * 获取加载的工具数量。
     *
     * @return 工具数量，失败时为 0
     */
    public int getToolCount() {
        return toolCount;
    }

    /**
     * 获取错误消息。
     *
     * @return 错误消息，成功时为 null
     */
    public String getErrorMessage() {
        return errorMessage;
    }
}
