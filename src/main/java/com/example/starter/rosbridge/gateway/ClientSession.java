package com.example.starter.rosbridge.gateway;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单个客户端连接的内存态：有界发送队列、令牌桶限速、慢消费者丢弃策略。
 *
 * <p>队列满时：仅丢弃队列中最旧的一条 Topic 消息，并在独立的缺口队列中记录
 * RESUME_GAP 游标范围（同 Topic 相邻丢弃合并为一个缺口）。被丢弃的一定是该 Topic
 * 当前最旧的未发送游标，因此缺口范围始终在该 Topic 其余排队消息之前；出队时缺口
 * 优先于普通消息发送。丢弃只影响本客户端，不触碰共享留存与其他客户端。控制帧
 * （connected/service_response/action_result 等）不被丢弃。</p>
 *
 * <p>线程模型：入队/出队均在会话锁内完成；实际网络发送在锁外执行。</p>
 */
public class ClientSession {

    private static final Logger log = LoggerFactory.getLogger(ClientSession.class);

    private final String clientId;
    private final ClientTransport transport;
    private final int ratePerSec;
    private final int queueCap;

    private final Deque<OutboundEntry> queue = new ArrayDeque<>();
    /** 待发送的缺口帧；按丢弃产生顺序排列，出队时整体优先于普通消息。 */
    private final Deque<OutboundEntry.Gap> gaps = new ArrayDeque<>();

    // 令牌桶：容量=每秒配额，按经过时间连续补充
    private double tokens;
    private long lastRefillNanos = System.nanoTime();

    public ClientSession(String clientId, ClientTransport transport,
                         int ratePerSec, int queueCap) {
        this.clientId = clientId;
        this.transport = transport;
        this.ratePerSec = ratePerSec;
        this.queueCap = queueCap;
        this.tokens = ratePerSec;
    }

    public String clientId() {
        return clientId;
    }

    public ClientTransport transport() {
        return transport;
    }

    public int ratePerSec() {
        return ratePerSec;
    }

    public int queueCap() {
        return queueCap;
    }

    public synchronized int queuedSize() {
        return queue.size();
    }

    public synchronized int gapCount() {
        return gaps.size();
    }

    /** 入队一条 Topic 消息；队列满时按最旧未确认 Topic 丢弃策略处理。 */
    public void enqueueTopic(long sharedId, long cursor, String frame,
                             String topic, String type, String qosKey) {
        enqueue(new OutboundEntry.Topic(sharedId, cursor, topic, type, qosKey, frame));
    }

    /** 控制帧入队；不会被慢消费者策略丢弃（必要时略微突破上限，数量受请求频率约束）。 */
    public synchronized void enqueueText(String frame) {
        if (!transport.isOpen()) {
            return;
        }
        if (queue.size() >= queueCap && !evictOldestTopic()) {
            log.debug("客户端 {} 控制帧队列已满，放行控制帧（当前 {} 条）", clientId, queue.size());
        }
        queue.addLast(new OutboundEntry.Text(frame));
    }

    /** 重连后批量补投留存消息（游标升序），同样应用队列上限与丢弃策略。 */
    public void enqueueResumeBatch(List<OutboundEntry.Topic> backlog) {
        for (OutboundEntry.Topic t : backlog) {
            enqueue(t);
        }
    }

    private synchronized void enqueue(OutboundEntry entry) {
        if (!transport.isOpen()) {
            return;
        }
        if (queue.size() >= queueCap) {
            evictOldestTopic();
        }
        queue.addLast(entry);
    }

    /** 丢弃队列中最旧的 Topic 消息并登记缺口。无 Topic 可丢时返回 false。 */
    private boolean evictOldestTopic() {
        OutboundEntry.Topic dropped = null;
        for (OutboundEntry e : queue) {
            if (e instanceof OutboundEntry.Topic t) {
                dropped = t;
                break;
            }
        }
        if (dropped == null) {
            return false;
        }
        queue.remove(dropped);
        recordGap(dropped);
        return true;
    }

    /**
     * 登记缺口：与同 Topic 的末个缺口相邻则扩展，否则新增。被丢弃游标总是同 Topic
     * 当前最旧未发送项，因此新游标恰好接在末个缺口末端之后。
     */
    private void recordGap(OutboundEntry.Topic dropped) {
        OutboundEntry.Gap last = gaps.peekLast();
        if (last != null && last.topic().equals(dropped.topic())
                && dropped.cursor() == last.toCursor() + 1) {
            last.absorb(dropped.cursor());
        } else {
            gaps.addLast(new OutboundEntry.Gap(
                    dropped.topic(), dropped.type(), dropped.qosKey(), dropped.cursor()));
        }
        log.debug("客户端 {} 慢消费者丢弃游标 {}", clientId, dropped.cursor());
    }

    /**
     * 在令牌桶允许的范围内发送一批帧，缺口帧优先于普通消息。网络写在锁外完成；
     * 发送失败时把未发出的条目（含失败条目）按原顺序放回各自队列头部。
     */
    public void pump() {
        List<OutboundEntry> ready;
        synchronized (this) {
            if (!transport.isOpen()) {
                return;
            }
            if (queue.isEmpty() && gaps.isEmpty()) {
                return;
            }
            refill();
            if (tokens < 1.0d) {
                return;
            }
            int allow = (int) Math.floor(tokens);
            ready = new ArrayList<>();
            while (allow > 0 && !gaps.isEmpty()) {
                ready.add(gaps.pollFirst());
                allow--;
            }
            while (allow > 0 && !queue.isEmpty()) {
                ready.add(queue.pollFirst());
                allow--;
            }
            if (ready.isEmpty()) {
                return;
            }
            tokens -= ready.size();
        }
        int sent = 0;
        for (OutboundEntry e : ready) {
            if (!transport.sendText(e.render())) {
                break;
            }
            sent++;
        }
        if (sent < ready.size()) {
            synchronized (this) {
                // 逆序 addFirst 以保持未发送片段的原始顺序
                List<OutboundEntry> unsent = ready.subList(sent, ready.size());
                for (int i = unsent.size() - 1; i >= 0; i--) {
                    OutboundEntry e = unsent.get(i);
                    if (e instanceof OutboundEntry.Gap g) {
                        gaps.addFirst(g);
                    } else {
                        queue.addFirst(e);
                    }
                }
            }
        }
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0d;
        lastRefillNanos = now;
        if (elapsedSec > 0) {
            tokens = Math.min(ratePerSec, tokens + elapsedSec * ratePerSec);
        }
    }

    /** 测试辅助：立即补满令牌。 */
    synchronized void refillForTest() {
        tokens = ratePerSec;
        lastRefillNanos = System.nanoTime();
    }
}
