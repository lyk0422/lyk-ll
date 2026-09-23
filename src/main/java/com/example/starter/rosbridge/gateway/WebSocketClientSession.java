package com.example.starter.rosbridge.gateway;

import com.example.starter.rosbridge.protocol.Frames;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketSession;

/**
 * 单客户端发送会话：
 * <ul>
 *   <li>有界发送队列（容量可按客户端配置）；</li>
 *   <li>令牌桶发送速率限制；</li>
 *   <li>队列满时只丢弃该客户端最旧的未确认 Topic 帧，聚合成单个
 *       {@code RESUME_GAP} 标记帧（含丢失游标闭区间）排在后续数据之前，
 *       不影响其他客户端。</li>
 * </ul>
 */
public class WebSocketClientSession implements ClientSession {

    private static final Logger log = LoggerFactory.getLogger(WebSocketClientSession.class);

    private final String clientId;
    private final FrameSink sink;
    private final int capacity;
    private final double ratePerSecond;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final LinkedList<Item> queue = new LinkedList<>();
    private final Thread senderThread;
    private volatile boolean open = true;

    /** topic -> 客户端已 ACK 游标。 */
    private final Map<String, Long> ackedCursors = new ConcurrentHashMap<>();

    // 令牌桶
    private double tokens;
    private long lastRefillNanos = System.nanoTime();

    public WebSocketClientSession(String clientId, WebSocketSession session, int capacity, int ratePerSecond) {
        this(clientId, payload -> {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new org.springframework.web.socket.TextMessage(payload));
                }
            }
        }, capacity, ratePerSecond);
    }

    public WebSocketClientSession(String clientId, FrameSink sink, int capacity, int ratePerSecond) {
        this.clientId = clientId;
        this.sink = sink;
        this.capacity = Math.max(2, capacity);
        this.ratePerSecond = ratePerSecond;
        this.tokens = Math.max(1.0, ratePerSecond);
        this.senderThread = new Thread(this::sendLoop, "ws-sender-" + clientId);
        this.senderThread.setDaemon(true);
        this.senderThread.start();
    }

    /** 更新该客户端某 Topic 的已确认游标（仅前进）。 */
    @Override
    public void acknowledge(String topic, long cursor) {
        ackedCursors.merge(topic, cursor, Math::max);
    }

    @Override
    public void enqueue(OutboundFrame frame) {
        lock.lock();
        try {
            if (!open) {
                return;
            }
            if (frame.topicFrame()) {
                while (queue.size() >= capacity) {
                    evictOldestUnacked();
                }
            } else {
                // 控制帧不主动丢弃，但仍保证有界：淘汰最旧未确认 Topic 帧直到腾出空间
                while (queue.size() >= capacity) {
                    evictOldestUnacked();
                }
            }
            queue.addLast(Item.data(frame));
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 丢弃队列中最旧的未确认 Topic 帧：
     * 连续游标合并进已在队中的同 topic RESUME_GAP 标记；否则移除帧并把标记插到队首，
     * 保证 GAP 先于后续数据送达，且不会重复入队。
     */
    private void evictOldestUnacked() {
        Iterator<Item> it = queue.iterator();
        while (it.hasNext()) {
            Item item = it.next();
            OutboundFrame f = item.frame;
            if (f == null || !f.topicFrame()) {
                continue;
            }
            if (f.cursor() <= ackedCursors.getOrDefault(f.topic(), 0L)) {
                // 已确认帧直接移除，不通知
                it.remove();
                return;
            }
            it.remove();
            mergeOrInsertGap(f.topic(), f.cursor());
            log.debug("client={} slow consumer, dropped cursor={} topic={}", clientId, f.cursor(), f.topic());
            return;
        }
        // 队列里没有可丢弃的 Topic 帧（全为控制帧/GAP），移除最旧项保证有界
        if (!queue.isEmpty()) {
            queue.removeFirst();
        }
    }

    /** 将被丢弃游标并入同 topic 的 GAP 标记；没有则在队首插入新标记。 */
    private void mergeOrInsertGap(String topic, long cursor) {
        for (Item item : queue) {
            GapState gap = item.gap;
            if (gap != null && gap.topic.equals(topic)) {
                // 被丢弃的总是当前最旧帧，游标连续则扩展区间，非连续记录较小者
                gap.fromCursor = Math.min(gap.fromCursor, cursor);
                gap.toCursor = Math.max(gap.toCursor, cursor);
                return;
            }
        }
        queue.addFirst(Item.gap(new GapState(topic, cursor, cursor)));
    }

    private void sendLoop() {
        while (true) {
            String payload;
            lock.lock();
            try {
                while (queue.isEmpty() && open) {
                    notEmpty.awaitUninterruptibly();
                }
                if (queue.isEmpty()) {
                    return;
                }
                Item item = queue.removeFirst();
                if (item.gap != null) {
                    payload = Frames.resumeGap(item.gap.topic, item.gap.fromCursor, item.gap.toCursor);
                } else {
                    payload = item.frame.payload();
                }
            } finally {
                lock.unlock();
            }
            acquireRatePermit();
            try {
                sink.send(payload);
            } catch (Exception e) {
                log.warn("client={} send failed, closing session: {}", clientId, e.toString());
                close();
                return;
            }
        }
    }

    private void acquireRatePermit() {
        if (ratePerSecond <= 0) {
            return;
        }
        long waitNanos;
        lock.lock();
        try {
            refill();
            waitNanos = tokens < 1.0
                    ? (long) ((1.0 - tokens) * 1_000_000_000L / ratePerSecond)
                    : 0L;
        } finally {
            lock.unlock();
        }
        if (waitNanos > 0) {
            try {
                Thread.sleep(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lock.lock();
        try {
            refill();
            tokens -= 1.0;
        } finally {
            lock.unlock();
        }
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSeconds > 0) {
            tokens = Math.min(Math.max(1.0, ratePerSecond), tokens + elapsedSeconds * ratePerSecond);
            lastRefillNanos = now;
        }
    }

    /** 测试辅助：等待队列中的帧全部发送完成。 */
    public void awaitDrained(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            lock.lock();
            try {
                if (queue.isEmpty()) {
                    return;
                }
            } finally {
                lock.unlock();
            }
            Thread.sleep(5);
        }
    }

    /** 测试辅助：当前排队项数。 */
    public int queuedSize() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (!open) {
                return;
            }
            open = false;
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    /** RESUME_GAP 可变标记，随丢弃发生原地合并区间。 */
    private static final class GapState {
        final String topic;
        long fromCursor;
        long toCursor;

        GapState(String topic, long fromCursor, long toCursor) {
            this.topic = topic;
            this.fromCursor = fromCursor;
            this.toCursor = toCursor;
        }
    }

    /** 队列项：普通帧或 GAP 标记二选一。 */
    private static final class Item {
        final OutboundFrame frame;
        final GapState gap;

        private Item(OutboundFrame frame, GapState gap) {
            this.frame = frame;
            this.gap = gap;
        }

        static Item data(OutboundFrame frame) {
            return new Item(frame, null);
        }

        static Item gap(GapState gap) {
            return new Item(null, gap);
        }
    }
}
