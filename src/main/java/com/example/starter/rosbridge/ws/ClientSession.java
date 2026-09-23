package com.example.starter.rosbridge.ws;

import com.example.starter.rosbridge.protocol.Frames;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 单个 WebSocket 连接对应的客户端会话：负责令牌桶限速、待发送数据队列、
 * 慢消费者丢最旧未确认消息与 RESUME_GAP 通知。
 *
 * <p>线程模型：所有队列操作与 WebSocket 写入均在实例锁内完成，Spring 的
 * {@link WebSocketSession} 本身不保证多线程写入安全。一个 50ms 周期任务
 * 负责把排队消息按限速刷出。
 *
 * <p>队列元素分两类：DATA 携带单调游标，可被 ACK 清除、可在溢出时丢弃；
 * GAP 标记一段被丢弃的游标闭区间，按发生位置参与发送顺序，不占用消息容量配额。
 */
public class ClientSession {

    private static final Logger log = LoggerFactory.getLogger(ClientSession.class);
    /** 排队消息刷出周期（毫秒）。 */
    private static final long FLUSH_INTERVAL_MS = 50;

    private final String clientId;
    private final WebSocketSession wsSession;
    private final int rateLimitPerSecond;
    private final int queueCapacity;
    private final ObjectMapper mapper;
    private final LongSupplier nanoTimeSource;

    private final Deque<Element> queue = new ArrayDeque<>();
    /** 队列中 DATA 元素数量（GAP 不占容量配额）。 */
    private int dataCount;
    /** 客户端已确认的最高游标水位；大于该水位的消息才允许投递。 */
    private long ackedHigh;

    /** 令牌桶：当前可用令牌与上次填充时间。 */
    private double tokens;
    private long lastRefillNanos;

    private ScheduledFuture<?> flushTask;
    private volatile boolean closed;

    public ClientSession(String clientId, WebSocketSession wsSession, int rateLimitPerSecond,
                         int queueCapacity, long startAckCursor, ObjectMapper mapper,
                         ScheduledExecutorService scheduler) {
        this(clientId, wsSession, rateLimitPerSecond, queueCapacity, startAckCursor, mapper,
                scheduler, System::nanoTime);
    }

    /** 测试用构造器：可注入可控纳秒时间源，使令牌桶填充随虚拟时钟推进。 */
    public ClientSession(String clientId, WebSocketSession wsSession, int rateLimitPerSecond,
                         int queueCapacity, long startAckCursor, ObjectMapper mapper,
                         ScheduledExecutorService scheduler, LongSupplier nanoTimeSource) {
        this.clientId = clientId;
        this.wsSession = wsSession;
        this.rateLimitPerSecond = Math.max(0, rateLimitPerSecond);
        this.queueCapacity = Math.max(1, queueCapacity);
        this.ackedHigh = startAckCursor;
        this.mapper = mapper;
        this.nanoTimeSource = nanoTimeSource;
        this.tokens = this.rateLimitPerSecond == 0 ? Double.MAX_VALUE : this.rateLimitPerSecond;
        this.lastRefillNanos = nanoTimeSource.getAsLong();
        this.flushTask = scheduler.scheduleAtFixedRate(this::flushSafely,
                FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private sealed interface Element permits DataElement, GapElement {
    }

    private record DataElement(long cursor, String frameText) implements Element {
    }

    private record GapElement(long fromCursor, long toCursor, int dropped) implements Element {
    }

    public String getClientId() {
        return clientId;
    }

    public WebSocketSession getWsSession() {
        return wsSession;
    }

    public boolean isOpen() {
        return !closed && wsSession.isOpen();
    }

    /** 当前已确认水位（测试用）。 */
    public synchronized long ackedCursor() {
        return ackedHigh;
    }

    /** 当前在队未确认数据消息数（测试用）。 */
    public synchronized int unackedCount() {
        return dataCount;
    }

    /**
     * 投递一条带游标的 Topic 数据消息。
     * 游标不大于已确认水位（历史已投或重连前已确认）时直接忽略，保证不重复。
     * 队列满时丢弃本客户端最旧未确认 DATA，在同位置插入/合并 RESUME_GAP，不影响其他客户端。
     */
    public synchronized void offerData(long cursor, String frameText) {
        if (closed || cursor <= ackedHigh) {
            return;
        }
        if (dataCount >= queueCapacity) {
            evictOldest(cursor);
        }
        queue.addLast(new DataElement(cursor, frameText));
        dataCount++;
        flushSafely();
    }

    /**
     * 移除队首最旧 DATA 并在该位置维护 GAP。被丢弃的游标一定比队列中其它 DATA 更旧，
     * 若队首已是 GAP（连续溢出）则向左扩展其区间并累加计数。
     */
    private void evictOldest(long incomingCursor) {
        // 找到最旧的 DATA（队首方向第一个 DATA）。
        Element oldestGap = queue.peekFirst();
        DataElement victim;
        if (oldestGap instanceof GapElement) {
            // 队首已是 GAP：它之后的第一个 DATA 即最旧 DATA。
            victim = (DataElement) queue.stream()
                    .filter(e -> e instanceof DataElement)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("队列已满却无 DATA 可丢弃"));
        } else {
            victim = (DataElement) oldestGap;
        }
        long droppedCursor = victim.cursor();
        queue.remove(victim);
        dataCount--;

        if (oldestGap instanceof GapElement g) {
            // 新丢的更旧：扩展左边界（游标相邻或更小都合并为一个连续告知区间）。
            GapElement merged = new GapElement(Math.min(g.fromCursor(), droppedCursor),
                    Math.max(g.toCursor(), droppedCursor), g.dropped() + 1);
            replaceFirstGap(merged);
        } else {
            queue.addFirst(new GapElement(droppedCursor, droppedCursor, 1));
        }
        log.debug("客户端 {} 慢消费队列满，丢弃游标 {}（新消息游标 {} 排队中）",
                clientId, droppedCursor, incomingCursor);
    }

    private void replaceFirstGap(GapElement merged) {
        // 队首即旧 GAP，原地替换为合并后的区间，其余元素顺序不变。
        queue.pollFirst();
        queue.addFirst(merged);
    }

    /**
     * 立即发送控制帧（订阅应答、Service/Action 帧、协议错误），
     * 不进入数据队列、不参与丢弃；与数据帧共用实例锁保证底层写入串行。
     */
    public synchronized void sendControl(Map<String, Object> frame) {
        if (closed) {
            return;
        }
        try {
            wsSession.sendMessage(new TextMessage(mapper.writeValueAsString(frame)));
        } catch (Exception e) {
            log.warn("客户端 {} 控制帧发送失败: {}", clientId, e.toString());
        }
    }

    /**
     * ACK 到指定游标：推进确认水位并清除队列中所有游标不大于它的 DATA，
     * 已在网络上发送的 DATA 也通过水位保证重连后不重投。返回更新前水位。
     */
    public synchronized long ack(long cursor) {
        long before = ackedHigh;
        if (cursor <= ackedHigh) {
            return before;
        }
        ackedHigh = cursor;
        List<Element> kept = new ArrayList<>();
        int keptData = 0;
        for (Element e : queue) {
            if (e instanceof DataElement d && d.cursor() <= cursor) {
                continue;
            }
            if (e instanceof GapElement g && g.toCursor() <= cursor) {
                // 丢失区间已落在确认水位之内，无需再告知。
                continue;
            }
            kept.add(e);
            if (e instanceof DataElement) {
                keptData++;
            }
        }
        queue.clear();
        queue.addAll(kept);
        dataCount = keptData;
        return before;
    }

    /** 关闭会话并停止刷盘任务，丢弃队列残留。 */
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (flushTask != null) {
            flushTask.cancel(false);
            flushTask = null;
        }
        queue.clear();
        dataCount = 0;
        try {
            if (wsSession.isOpen()) {
                wsSession.close();
            }
        } catch (Exception e) {
            log.debug("关闭客户端 {} WebSocket 异常: {}", clientId, e.toString());
        }
    }

    private void flushSafely() {
        try {
            flush();
        } catch (Exception e) {
            log.warn("客户端 {} 队列刷出失败: {}", clientId, e.toString());
        }
    }

    /** 按令牌桶限速尽可能多地把队首元素写出。 */
    private synchronized void flush() {
        if (closed || !wsSession.isOpen() || queue.isEmpty()) {
            return;
        }
        refillTokens();
        while (!queue.isEmpty()) {
            Element head = queue.pollFirst();
            // GAP 为控制通知，不受 Topic 数据速率限制。
            if (rateLimitPerSecond > 0 && tokens < 1.0d && !(head instanceof GapElement)) {
                queue.addFirst(head);
                break;
            }
            String text;
            if (head instanceof DataElement d) {
                text = d.frameText();
                dataCount--;
                if (rateLimitPerSecond > 0) {
                    tokens -= 1.0d;
                }
            } else {
                GapElement g = (GapElement) head;
                try {
                    text = mapper.writeValueAsString(
                            Frames.resumeGap(g.fromCursor(), g.toCursor(), g.dropped()));
                } catch (Exception e) {
                    log.warn("RESUME_GAP 序列化失败: {}", e.toString());
                    continue;
                }
            }
            try {
                wsSession.sendMessage(new TextMessage(text));
            } catch (Exception e) {
                // 发送失败：元素已出队，放回队首等待下个周期重试，避免丢失。
                queue.addFirst(head);
                if (head instanceof DataElement) {
                    dataCount++;
                }
                log.debug("客户端 {} 写入暂失败，消息保留队列: {}", clientId, e.toString());
                return;
            }
        }
    }

    private void refillTokens() {
        if (rateLimitPerSecond <= 0) {
            tokens = Double.MAX_VALUE;
            return;
        }
        long now = nanoTimeSource.getAsLong();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0d;
        lastRefillNanos = now;
        tokens = Math.min(rateLimitPerSecond, tokens + elapsedSeconds * rateLimitPerSecond);
    }
}
