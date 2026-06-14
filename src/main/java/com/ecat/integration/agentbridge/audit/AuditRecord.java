package com.ecat.integration.agentbridge.audit;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 审计记录 DTO。记录 Agent 的每一次工具调用操作。
 *
 * <p>每条记录包含操作的时间戳、Agent ID、工具名称、参数、结果、状态、
 * 会话 ID 和请求 ID。审计记录按日期写入日志文件，每行一条 JSON 记录。
 *
 * <p>状态取值：
 * <ul>
 *   <li>{@code success} — 操作成功</li>
 *   <li>{@code error} — 操作失败</li>
 *   <li>{@code confirmed} — 高风险操作已确认</li>
 *   <li>{@code rejected} — 高风险操作已拒绝</li>
 * </ul>
 *
 * @author coffee
 */
public class AuditRecord {

    /** 操作成功 */
    public static final String STATUS_SUCCESS = "success";

    /** 操作失败 */
    public static final String STATUS_ERROR = "error";

    /** 操作已确认 */
    public static final String STATUS_CONFIRMED = "confirmed";

    /** 操作已拒绝 */
    public static final String STATUS_REJECTED = "rejected";

    /** 时间戳（epoch 毫秒） */
    private final long timestamp;

    /** Agent 唯一标识 */
    private final String agentId;

    /** 调用的工具名称 */
    private final String toolName;

    /** 工具调用参数（不可变） */
    private final Map<String, Object> params;

    /** 操作结果描述 */
    private final String result;

    /** 操作状态 */
    private final String status;

    /** 会话 ID */
    private final String sessionId;

    /** 请求 ID */
    private final String requestId;

    /**
     * 全参构造器。
     *
     * @param timestamp 时间戳（epoch 毫秒）
     * @param agentId   Agent ID
     * @param toolName  工具名称
     * @param params    调用参数，可为 null
     * @param result    操作结果描述
     * @param status    操作状态（success/error/confirmed/rejected）
     * @param sessionId 会话 ID
     * @param requestId 请求 ID
     */
    public AuditRecord(long timestamp, String agentId, String toolName,
                       Map<String, Object> params, String result, String status,
                       String sessionId, String requestId) {
        this.timestamp = timestamp;
        this.agentId = agentId;
        this.toolName = toolName;
        this.params = params != null
                ? Collections.unmodifiableMap(new HashMap<String, Object>(params))
                : Collections.<String, Object>emptyMap();
        this.result = result;
        this.status = status;
        this.sessionId = sessionId;
        this.requestId = requestId;
    }

    /**
     * 获取时间戳。
     *
     * @return epoch 毫秒
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * 获取 Agent ID。
     *
     * @return Agent 唯一标识
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * 获取工具名称。
     *
     * @return 工具名称
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 获取调用参数（不可变）。
     *
     * @return 参数 Map
     */
    public Map<String, Object> getParams() {
        return params;
    }

    /**
     * 获取操作结果描述。
     *
     * @return 结果字符串
     */
    public String getResult() {
        return result;
    }

    /**
     * 获取操作状态。
     *
     * @return 状态字符串（success/error/confirmed/rejected）
     */
    public String getStatus() {
        return status;
    }

    /**
     * 获取会话 ID。
     *
     * @return 会话 ID
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取请求 ID。
     *
     * @return 请求 ID
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * 格式化为单行 JSON（用于写入审计日志文件）。
     *
     * <p>JSON 字段按固定顺序输出：timestamp, agentId, toolName, params, result, status, sessionId, requestId。
     *
     * @return 单行 JSON 字符串
     */
    public String toLine() {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("timestamp", timestamp);
        map.put("agentId", agentId);
        map.put("toolName", toolName);
        map.put("params", params);
        map.put("result", result);
        map.put("status", status);
        map.put("sessionId", sessionId);
        map.put("requestId", requestId);
        return JSON.toJSONString(map);
    }

    /**
     * 从 JSON 行解析审计记录。
     *
     * @param line JSON 格式的单行字符串
     * @return 解析后的 AuditRecord
     * @throws IllegalArgumentException line 为 null 或格式无效时抛出
     */
    @SuppressWarnings("unchecked")
    public static AuditRecord fromLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            throw new IllegalArgumentException("line must not be null or empty");
        }
        JSONObject obj = JSON.parseObject(line.trim());
        if (obj == null) {
            throw new IllegalArgumentException("Invalid JSON line: " + line);
        }
        Map<String, Object> params = null;
        if (obj.containsKey("params")) {
            Object paramsObj = obj.get("params");
            if (paramsObj instanceof Map) {
                params = (Map<String, Object>) paramsObj;
            }
        }
        return new AuditRecord(
                obj.getLongValue("timestamp"),
                obj.getString("agentId"),
                obj.getString("toolName"),
                params,
                obj.getString("result"),
                obj.getString("status"),
                obj.getString("sessionId"),
                obj.getString("requestId")
        );
    }
}
