package com.ecat.integration.agentbridge.audit;

import com.ecat.integration.agentbridge.auth.AuditLoggerBridge;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 审计日志管理器。实现 {@link AuditLoggerBridge} 接口，记录 Agent 操作审计日志。
 *
 * <p>核心职责：
 * <ul>
 *   <li>按日期分割写入审计日志文件（{@code audit-{yyyy-MM-dd}.log}）</li>
 *   <li>每行一条 JSON 记录，使用 {@link AuditRecord#toLine()} 格式</li>
 *   <li>支持按时间范围和数量限制查询历史记录</li>
 *   <li>实现 {@link AuditLoggerBridge#logConfirmation} 供确认管理器调用</li>
 * </ul>
 *
 * <p>日志目录默认为 {@code .ecat-data/agent-bridge/audit/}，
 * 可通过构造器参数自定义。
 *
 * <p>线程安全：写入操作使用 synchronized 保护，支持多线程并发调用。
 *
 * @author coffee
 */
public class AuditLogger implements AuditLoggerBridge {

    private static final Logger log = Logger.getLogger(AuditLogger.class.getName());

    /** 默认审计日志目录 */
    private static final String DEFAULT_LOG_DIR = ".ecat-data/agent-bridge/audit";

    /** 日志文件名前缀 */
    private static final String FILE_PREFIX = "audit-";

    /** 日志文件名后缀 */
    private static final String FILE_SUFFIX = ".log";

    /** 日期格式（用于文件名分割和查询） */
    private final SimpleDateFormat dateFormat;

    /** 审计日志目录 */
    private final File logDir;

    /** 缓存的文件写入器：日期字符串 -> BufferedWriter */
    private final Map<String, BufferedWriter> writers = new HashMap<String, BufferedWriter>();

    /**
     * 构造器（使用默认日志目录）。
     *
     * <p>日志目录为 {@code .ecat-data/agent-bridge/audit/}，
     * 如果目录不存在会自动创建。
     */
    public AuditLogger() {
        this(new File(DEFAULT_LOG_DIR));
    }

    /**
     * 构造器（自定义日志目录）。
     *
     * @param logDir 日志目录，不能为 null
     */
    public AuditLogger(File logDir) {
        if (logDir == null) {
            throw new IllegalArgumentException("logDir must not be null");
        }
        this.logDir = logDir;
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        if (!logDir.exists()) {
            if (!logDir.mkdirs()) {
                log.warning("Failed to create audit log directory: " + logDir.getAbsolutePath());
            }
        }
    }

    /**
     * 写入审计记录（按日期分割文件）。
     *
     * <p>线程安全，同一时刻只有一个线程可以写入。
     *
     * @param record 审计记录，不能为 null
     */
    public synchronized void log(AuditRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        String dateStr = dateFormat.format(record.getTimestamp());
        try {
            BufferedWriter writer = getWriter(dateStr);
            writer.write(record.toLine());
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            log.log(Level.SEVERE, "Failed to write audit record to " + dateStr, e);
        }
    }

    /**
     * 记录工具执行审计日志的便捷方法。
     *
     * <p>自动生成当前时间戳和随机请求 ID。
     *
     * @param agentId  Agent ID
     * @param toolName 工具名称
     * @param params   调用参数
     * @param result   操作结果
     * @param status   操作状态（success/error）
     */
    public void logToolExecution(String agentId, String toolName,
                                 Map<String, Object> params, String result, String status) {
        AuditRecord record = new AuditRecord(
                System.currentTimeMillis(),
                agentId,
                toolName,
                params,
                result,
                status,
                null,
                null
        );
        log(record);
    }

    /**
     * 记录工具执行审计日志（带会话和请求 ID）。
     *
     * @param agentId   Agent ID
     * @param toolName  工具名称
     * @param params    调用参数
     * @param result    操作结果
     * @param status    操作状态
     * @param sessionId 会话 ID
     * @param requestId 请求 ID
     */
    public void logToolExecution(String agentId, String toolName,
                                 Map<String, Object> params, String result, String status,
                                 String sessionId, String requestId) {
        AuditRecord record = new AuditRecord(
                System.currentTimeMillis(),
                agentId,
                toolName,
                params,
                result,
                status,
                sessionId,
                requestId
        );
        log(record);
    }

    /**
     * 实现 {@link AuditLoggerBridge} 接口，记录确认操作日志。
     *
     * @param confirmationId 确认请求 ID
     * @param action         操作类型（confirmed/rejected/expired/created）
     * @param toolName       关联的工具名称
     * @param requesterId    请求者 ID
     */
    @Override
    public void logConfirmation(String confirmationId, String action,
                                String toolName, String requesterId) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("confirmationId", confirmationId);
        params.put("action", action);
        params.put("requesterId", requesterId);

        String status;
        if ("confirmed".equals(action)) {
            status = AuditRecord.STATUS_CONFIRMED;
        } else if ("rejected".equals(action)) {
            status = AuditRecord.STATUS_REJECTED;
        } else {
            status = action;
        }

        AuditRecord record = new AuditRecord(
                System.currentTimeMillis(),
                requesterId,
                toolName,
                params,
                "Confirmation " + action + ": " + confirmationId,
                status,
                null,
                confirmationId
        );
        log(record);
    }

    /**
     * 查询审计记录。
     *
     * <p>按时间范围 [from, to] 查询，返回最多 limit 条记录（按时间倒序）。
     * 扫描 [from, to] 范围内所有日期的日志文件。
     *
     * @param from  起始时间戳（epoch 毫秒），包含
     * @param to    结束时间戳（epoch 毫秒），包含
     * @param limit 返回记录数上限
     * @return 审计记录列表（按时间倒序），查询失败时返回空列表
     */
    public List<AuditRecord> query(long from, long to, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        List<AuditRecord> results = new ArrayList<AuditRecord>();
        // 扫描日期范围内的日志文件
        long currentDay = startOfDay(from);
        long endDay = startOfDay(to);
        while (currentDay <= endDay) {
            String dateStr = dateFormat.format(currentDay);
            File file = new File(logDir, FILE_PREFIX + dateStr + FILE_SUFFIX);
            if (file.exists() && file.canRead()) {
                readRecordsFromFile(file, from, to, results);
            }
            // 下一天
            currentDay += 24 * 60 * 60 * 1000L;
        }
        // 按时间倒序排列
        Collections.sort(results, new java.util.Comparator<AuditRecord>() {
            @Override
            public int compare(AuditRecord a, AuditRecord b) {
                return Long.compare(b.getTimestamp(), a.getTimestamp());
            }
        });
        // 截取 limit 条
        if (results.size() > limit) {
            return results.subList(0, limit);
        }
        return results;
    }

    /**
     * 关闭所有文件写入器。
     */
    public synchronized void close() {
        for (Map.Entry<String, BufferedWriter> entry : writers.entrySet()) {
            try {
                entry.getValue().close();
            } catch (IOException e) {
                log.log(Level.FINE, "Failed to close writer for " + entry.getKey(), e);
            }
        }
        writers.clear();
        log.info("AuditLogger closed, all writers released");
    }

    // ===== 内部方法 =====

    /**
     * 获取指定日期的 BufferedWriter（带缓存）。
     *
     * @param dateStr 日期字符串（yyyy-MM-dd）
     * @return BufferedWriter 实例
     * @throws IOException 文件打开失败时抛出
     */
    private BufferedWriter getWriter(String dateStr) throws IOException {
        BufferedWriter writer = writers.get(dateStr);
        if (writer == null) {
            File file = new File(logDir, FILE_PREFIX + dateStr + FILE_SUFFIX);
            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file, true), StandardCharsets.UTF_8));
            writers.put(dateStr, writer);
        }
        return writer;
    }

    /**
     * 从单个日志文件读取符合条件的记录。
     *
     * @param file    日志文件
     * @param from    起始时间戳
     * @param to      结束时间戳
     * @param results 结果收集列表
     */
    private void readRecordsFromFile(File file, long from, long to, List<AuditRecord> results) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    AuditRecord record = AuditRecord.fromLine(line);
                    if (record.getTimestamp() >= from && record.getTimestamp() <= to) {
                        results.add(record);
                    }
                } catch (Exception e) {
                    // 跳过格式错误的行
                    log.log(Level.FINE, "Skipping malformed audit line: " + line, e);
                }
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to read audit file: " + file.getAbsolutePath(), e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    /**
     * 获取指定时间戳所在天的起始时间（00:00:00.000）。
     *
     * @param timestamp epoch 毫秒
     * @return 当天起始时间的 epoch 毫秒
     */
    private static long startOfDay(long timestamp) {
        return (timestamp / (24 * 60 * 60 * 1000L)) * (24 * 60 * 60 * 1000L);
    }
}
