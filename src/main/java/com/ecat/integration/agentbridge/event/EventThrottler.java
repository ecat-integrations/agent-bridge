package com.ecat.integration.agentbridge.event;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 事件截流聚合器。
 *
 * <p>收集事件，按 source + eventType 去重，定时推送汇总摘要。
 * 所有推送给 Agent 的事件一律经过此截流器，无例外。
 *
 * <p>设计原则（P0-53~P0-55）：
 * <ul>
 *   <li>绝对不低于配置的最小间隔推送（默认 30 秒）</li>
 *   <li>同一 source + eventType 只保留最新一条</li>
 *   <li>窗口结束时有事件才推送，无事件不推送（不产生空心跳）</li>
 *   <li>所有事件一律进截流器，包括系统级事件，无例外</li>
 * </ul>
 *
 * <p>生命周期：构造 → {@link #start()} → {@link #offer(UnifiedEvent)} 反复调用 → {@link #stop()}
 *
 * @author coffee
 */
public class EventThrottler {

    private static final Logger log = Logger.getLogger(EventThrottler.class.getName());

    /** 默认截流间隔（秒） */
    public static final int DEFAULT_THROTTLE_SECONDS = 30;

    /** 截流间隔（秒） */
    private final int throttleSeconds;

    /** 窗口结束时回调，接收去重后的事件列表 */
    private final FlushCallback flushCallback;

    /**
     * 去重 Map。
     * key = eventType + ":" + source，value = 最新事件。
     * 同一 key 多次 offer 只保留最新。
     */
    private final ConcurrentHashMap<String, UnifiedEvent> dedupMap = new ConcurrentHashMap<String, UnifiedEvent>();

    /** 定时调度器 */
    private volatile ScheduledExecutorService scheduler;

    /** 定时任务句柄 */
    private volatile ScheduledFuture<?> timerTask;

    /** 是否已启动 */
    private volatile boolean started = false;

    /**
     * 回调接口。窗口结束时调用，传入去重后的事件列表。
     */
    public interface FlushCallback {
        /**
         * 处理截流窗口内去重后的事件列表。
         *
         * @param events 去重后的事件，可能为空（调用方应检查）
         */
        void onFlush(List<UnifiedEvent> events);
    }

    /**
     * 构造器 — 使用默认截流间隔（30 秒）。
     *
     * @param flushCallback 窗口结束回调，不能为 null
     */
    public EventThrottler(FlushCallback flushCallback) {
        this(DEFAULT_THROTTLE_SECONDS, flushCallback);
    }

    /**
     * 构造器 — 指定截流间隔。
     *
     * @param throttleSeconds 截流间隔（秒），必须大于 0
     * @param flushCallback   窗口结束回调，不能为 null
     */
    public EventThrottler(int throttleSeconds, FlushCallback flushCallback) {
        if (throttleSeconds <= 0) {
            throw new IllegalArgumentException("throttleSeconds must be > 0, got: " + throttleSeconds);
        }
        if (flushCallback == null) {
            throw new IllegalArgumentException("flushCallback must not be null");
        }
        this.throttleSeconds = throttleSeconds;
        this.flushCallback = flushCallback;
    }

    /**
     * 启动截流定时器。
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "event-throttler");
                t.setDaemon(true);
                return t;
            }
        });
        timerTask = scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                flush();
            }
        }, throttleSeconds, throttleSeconds, TimeUnit.SECONDS);
        started = true;
        log.info("EventThrottler started, interval=" + throttleSeconds + "s");
    }

    /**
     * 提交事件到截流器。
     *
     * <p>按 source + eventType 去重，同 key 只保留最新。
     * 必须先调用 {@link #start()} 启动定时器。
     *
     * @param event 事件，不能为 null
     */
    public void offer(UnifiedEvent event) {
        if (event == null) {
            return;
        }
        if (!started) {
            log.warning("EventThrottler not started, event dropped: " + event.getEventType());
            return;
        }
        String dedupKey = event.getEventType() + ":" + event.getSource();
        dedupMap.put(dedupKey, event);
    }

    /**
     * 获取截流间隔（秒）。
     *
     * @return 截流间隔
     */
    public int getThrottleSeconds() {
        return throttleSeconds;
    }

    /**
     * 获取当前截流窗口内待推送的事件数量（去重后）。
     *
     * @return 事件数量
     */
    public int getPendingCount() {
        return dedupMap.size();
    }

    /**
     * 停止截流器。先执行最后一次 flush，然后关闭定时器。
     */
    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;

        // 最后一次 flush，确保不丢事件
        try {
            flush();
        } catch (Exception e) {
            log.log(Level.WARNING, "Error during final flush", e);
        }

        if (timerTask != null) {
            timerTask.cancel(false);
            timerTask = null;
        }
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
        log.info("EventThrottler stopped");
    }

    /**
     * 定时触发：取出所有去重后的事件，回调推送。
     */
    private void flush() {
        if (dedupMap.isEmpty()) {
            return;
        }
        List<UnifiedEvent> events = new ArrayList<UnifiedEvent>(dedupMap.values());
        dedupMap.clear();
        try {
            flushCallback.onFlush(events);
        } catch (Exception e) {
            log.log(Level.WARNING, "Error in flush callback", e);
        }
    }
}
