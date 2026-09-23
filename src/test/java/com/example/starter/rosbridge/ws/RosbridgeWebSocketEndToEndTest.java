package com.example.starter.rosbridge.ws;

import com.example.starter.rosbridge.adapter.SyntheticRosAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 WebSocket 端到端测试：启动完整 Spring Boot 上下文（H2 MySQL 模式内存库、
 * schema.sql 自动初始化、合成 ROS 适配器），通过标准 JSR-356 WebSocket 客户端
 * 验证订阅共享、PUBLISH/ACK 与断线重连续投的线上 JSON 帧。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RosbridgeWebSocketEndToEndTest {

    private static final String TOPIC = "/e2e_chatter";

    @LocalServerPort
    private int port;

    @Autowired
    private SyntheticRosAdapter rosAdapter;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void subscribeAckAndReconnectOverRealWebSocket() throws Exception {
        // 两个客户端连接，验证共享上游。
        CapturingClient c1 = connect("e2e-c1");
        CapturingClient c2 = connect("e2e-c2");

        c1.send("""
                {"op":"SUBSCRIBE","topic":"/e2e_chatter","type":"std_msgs/msg/String","qos":{}}
                """);
        c2.send("""
                {"op":"SUBSCRIBE","topic":"/e2e_chatter","type":"std_msgs/msg/String","qos":{}}
                """);

        assertTrue(c1.awaitFrame(f -> "SUBSCRIBED".equals(f.path("op").asText())
                && TOPIC.equals(f.path("topic").asText())));
        assertTrue(c2.awaitFrame(f -> "SUBSCRIBED".equals(f.path("op").asText())));
        assertEquals(1, rosAdapter.upstreamSubscriptionCount(TOPIC),
                "两个客户端必须复用同一个真实上游订阅");

        emit(1);
        emit(2);
        emit(3);

        assertTrue(c1.awaitFrame(f -> f.path("cursor").asLong(-1) == 3));
        assertTrue(c2.awaitFrame(f -> f.path("cursor").asLong(-1) == 3));
        List<Long> c1Cursors = c1.publishCursors();
        List<Long> c2Cursors = c2.publishCursors();
        assertEquals(List.of(1L, 2L, 3L), c1Cursors);
        assertEquals(List.of(1L, 2L, 3L), c2Cursors, "两客户端收到相同的共享游标");

        // c1 ACK 到游标 2 后断连。
        c1.send("{\"op\":\"ACK\",\"cursor\":2}");
        c1.closeAndWait();

        // 离线期间产生的消息进入 H2。
        emit(4);
        emit(5);

        // 同 clientId 重连：只收到游标 > 2（含未确认的 3），1/2 不重复。
        CapturingClient c1b = connect("e2e-c1");
        List<Long> resumed = c1b.awaitPublishCursors(3);
        assertEquals(List.of(3L, 4L, 5L), resumed,
                "重连必须从大于确认游标处续投，且不重复已确认消息");

        c2.send("{\"op\":\"UNSUBSCRIBE\",\"topic\":\"/e2e_chatter\"}");
        assertTrue(c2.awaitFrame(f -> "UNSUBSCRIBED".equals(f.path("op").asText())));
        assertEquals(1, rosAdapter.upstreamSubscriptionCount(TOPIC),
                "c2 取消后 c1（重连）仍订阅，上游保留");

        c1b.send("{\"op\":\"UNSUBSCRIBE\",\"topic\":\"/e2e_chatter\"}");
        assertTrue(c1b.awaitFrame(f -> "UNSUBSCRIBED".equals(f.path("op").asText())));
        // 最后一个订阅者取消后释放上游（允许少量调度延迟，轮询等待）。
        long deadline = System.currentTimeMillis() + 2000;
        while (rosAdapter.upstreamSubscriptionCount(TOPIC) > 0
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, rosAdapter.upstreamSubscriptionCount(TOPIC),
                "最后一个客户端取消后必须释放上游资源");

        c1b.closeAndWait();
        c2.closeAndWait();
    }

    @Test
    void malformedFrameReturnsErrorFrame() throws Exception {
        CapturingClient c = connect("e2e-bad");
        c.session.sendMessage(new TextMessage("not-a-json"));
        assertTrue(c.awaitFrame(f -> "ERROR".equals(f.path("op").asText())),
                "非法 JSON 应收到 ERROR 帧且连接保持");
        c.closeAndWait();
    }

    private void emit(int seq) {
        rosAdapter.emit(TOPIC, mapper.createObjectNode().put("data", "msg-" + seq));
    }

    private CapturingClient connect(String clientId) throws Exception {
        CapturingClient client = new CapturingClient(clientId);
        StandardWebSocketClient wsClient = new StandardWebSocketClient();
        URI uri = URI.create("ws://localhost:" + port + "/ws?clientId=" + clientId);
        WebSocketSession session = wsClient.execute(client, null, uri)
                .get(5, TimeUnit.SECONDS);
        client.attach(session);
        client.awaitOpen();
        return client;
    }

    /** 收集服务端下发帧的测试 WebSocket 客户端。 */
    private static final class CapturingClient extends TextWebSocketHandler {
        private final String clientId;
        private final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        private final CountDownLatch openLatch = new CountDownLatch(1);
        private final CountDownLatch closeLatch = new CountDownLatch(1);
        private final ObjectMapper mapper = new ObjectMapper();
        private volatile WebSocketSession session;

        private CapturingClient(String clientId) {
            this.clientId = clientId;
        }

        private void attach(WebSocketSession session) {
            this.session = session;
        }

        private void awaitOpen() throws InterruptedException {
            assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            this.session = session;
            openLatch.countDown();
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            frames.add(mapper.readTree(message.getPayload()));
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session,
                                          org.springframework.web.socket.CloseStatus status) {
            closeLatch.countDown();
        }

        private void send(String json) throws Exception {
            session.sendMessage(new TextMessage(json.strip()));
        }

        private void closeAndWait() throws Exception {
            session.close();
            assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
        }

        private synchronized List<Long> publishCursors() {
            return frames.stream()
                    .filter(f -> "PUBLISH".equals(f.path("op").asText()))
                    .map(f -> f.path("cursor").asLong()).toList();
        }

        /** 等待直到出现匹配帧或超时。 */
        private boolean awaitFrame(java.util.function.Predicate<JsonNode> predicate)
                throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                synchronized (this) {
                    if (frames.stream().anyMatch(predicate)) {
                        return true;
                    }
                }
                Thread.sleep(10);
            }
            return frames.stream().anyMatch(predicate);
        }

        /** 等待直到收集到 expected 条 PUBLISH，返回其游标。 */
        private List<Long> awaitPublishCursors(int expected) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                List<Long> cursors = publishCursors();
                if (cursors.size() >= expected) {
                    return new ArrayList<>(cursors);
                }
                Thread.sleep(10);
            }
            return publishCursors();
        }
    }
}
