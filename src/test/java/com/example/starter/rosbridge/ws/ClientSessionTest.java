package com.example.starter.rosbridge.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClientSession} 单元测试：慢消费者丢弃最旧未确认消息、
 * RESUME_GAP 区间合并、ACK 清除、控制帧不入数据队列以及客户端间隔离。
 */
class ClientSessionTest {

    private ObjectMapper mapper;
    private ManualScheduledExecutor scheduler;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        scheduler = new ManualScheduledExecutor();
    }

    @Test
    void queueFullDropsOldestUnackedAndEmitsResumeGap() throws Exception {
        FakeWs ws = new FakeWs();
        ClientSession session = new ClientSession("c-slow", ws.session(), 0, 3, 0L,
                mapper, scheduler);
        ws.paused = true; // 模拟底层不可写：消息在发送队列堆积
        offer(session, 1);
        offer(session, 2);
        offer(session, 3);
        assertEquals(3, session.unackedCount());

        // 第 4 条使队列满：丢弃最旧游标 1，并在队首生成 RESUME_GAP[1,1]。
        offer(session, 4);

        ws.paused = false;
        scheduler.runPeriodicTasks();

        List<JsonNode> frames = ws.sentJson(mapper);
        assertEquals("RESUME_GAP", frames.get(0).get("op").asText());
        assertEquals(1, frames.get(0).get("fromCursor").asLong());
        assertEquals(1, frames.get(0).get("toCursor").asLong());
        assertEquals(1, frames.get(0).get("dropped").asInt());
        List<Long> cursors = publishCursors(frames);
        assertEquals(List.of(2L, 3L, 4L), cursors, "游标 1 被丢弃，2/3/4 保留");
    }

    @Test
    void consecutiveOverflowsMergeIntoSingleGapRange() throws Exception {
        FakeWs ws = new FakeWs();
        ClientSession session = new ClientSession("c-slow2", ws.session(), 0, 2, 0L,
                mapper, scheduler);
        ws.paused = true;
        offer(session, 1);
        offer(session, 2);
        offer(session, 3); // 丢弃 1
        offer(session, 4); // 丢弃 2

        ws.paused = false;
        scheduler.runPeriodicTasks();

        List<JsonNode> gapFrames = ws.sentJson(mapper).stream()
                .filter(f -> "RESUME_GAP".equals(f.get("op").asText())).toList();
        assertEquals(1, gapFrames.size(), "连续溢出应合并为单个 RESUME_GAP");
        assertEquals(1, gapFrames.get(0).get("fromCursor").asLong());
        assertEquals(2, gapFrames.get(0).get("toCursor").asLong());
        assertEquals(2, gapFrames.get(0).get("dropped").asInt());
        assertEquals(List.of(3L, 4L), publishCursors(ws.sentJson(mapper)));
    }

    @Test
    void ackRemovesConfirmedMessagesAndPreventsRedelivery() throws Exception {
        FakeWs ws = new FakeWs();
        ClientSession session = new ClientSession("c-ack", ws.session(), 0, 10, 0L,
                mapper, scheduler);
        ws.paused = true;
        offer(session, 1);
        offer(session, 2);
        long before = session.ack(2);
        assertEquals(0, before);
        assertEquals(0, session.unackedCount(), "ACK 后已确认消息应移出队列");

        // 游标不大于确认水位的消息不得再次入队。
        offer(session, 1);
        offer(session, 2);
        offer(session, 3);
        ws.paused = false;
        scheduler.runPeriodicTasks();
        assertEquals(List.of(3L), publishCursors(ws.sentJson(mapper)),
                "不得重复投递已确认游标 1、2");
    }

    @Test
    void slowConsumerDoesNotAffectOtherClient() throws Exception {
        FakeWs wsA = new FakeWs();
        FakeWs wsB = new FakeWs();
        ClientSession a = new ClientSession("c-A", wsA.session(), 0, 2, 0L, mapper, scheduler);
        ClientSession b = new ClientSession("c-B", wsB.session(), 0, 64, 0L, mapper, scheduler);
        wsA.paused = true;
        offer(a, 1);
        offer(a, 2);
        offer(a, 3); // A 丢弃 1

        offer(b, 10);
        offer(b, 11);
        offer(b, 12); // B 底层可写，offer 时已即时发出

        wsA.paused = false;
        scheduler.runPeriodicTasks();

        assertEquals(List.of(2L, 3L), publishCursors(wsA.sentJson(mapper)),
                "慢客户端 A 只保留最新 2 条");
        assertEquals(List.of(10L, 11L, 12L), publishCursors(wsB.sentJson(mapper)),
                "客户端 B 完整收到全部消息，不受 A 溢出影响");
        assertTrue(wsB.sent.stream().noneMatch(t -> t.contains("RESUME_GAP")),
                "客户端 B 不应收到任何 GAP 帧");
    }

    @Test
    void controlFrameBypassesDataQueueAndIsNotDroppedByOverflow() throws Exception {
        FakeWs ws = new FakeWs();
        ClientSession session = new ClientSession("c-ctrl", ws.session(), 0, 2, 0L,
                mapper, scheduler);
        ws.paused = true;
        offer(session, 1);
        offer(session, 2);
        offer(session, 3); // 数据队列溢出丢弃游标 1

        // 控制帧走独立通道：尝试直接写底层（暂停时会失败，但绝不挤占/驱逐数据队列）。
        ws.paused = false;
        session.sendControl(Map.of("op", "SUBSCRIBED", "topic", "/t"));

        List<JsonNode> frames = ws.sentJson(mapper);
        assertEquals("SUBSCRIBED", frames.get(0).get("op").asText(),
                "恢复可写后控制帧即时到达");
        assertEquals(2, session.unackedCount(), "控制帧不占用数据队列容量");
    }

    // ---- 辅助 ----

    private void offer(ClientSession session, long cursor) {
        try {
            Map<String, Object> frame = Map.of(
                    "op", "PUBLISH", "topic", "/t", "type", "std_msgs/msg/String",
                    "cursor", cursor, "msg", Map.of("data", "m" + cursor));
            session.offerData(cursor, mapper.writeValueAsString(frame));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<Long> publishCursors(List<JsonNode> frames) {
        return frames.stream()
                .filter(f -> "PUBLISH".equals(f.get("op").asText()))
                .map(f -> f.get("cursor").asLong()).toList();
    }

    /** 可在“暂停（写抛异常，消息滞留队列）/恢复（正常捕获帧）”间切换的假 WebSocket。 */
    private static final class FakeWs {
        private final List<String> sent = new CopyOnWriteArrayList<>();
        private volatile boolean paused;

        private WebSocketSession session() {
            return (WebSocketSession) Proxy.newProxyInstance(
                    WebSocketSession.class.getClassLoader(),
                    new Class<?>[]{WebSocketSession.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "sendMessage":
                                if (paused) {
                                    throw new java.io.IOException("模拟底层不可写");
                                }
                                sent.add(((TextMessage) args[0]).getPayload());
                                return null;
                            case "isOpen":
                                return true;
                            case "close":
                                return null;
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }

        private List<JsonNode> sentJson(ObjectMapper mapper) {
            List<JsonNode> nodes = new ArrayList<>();
            for (String text : sent) {
                try {
                    nodes.add(mapper.readTree(text));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            return nodes;
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
