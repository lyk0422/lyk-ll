package com.example.starter.rosbridge.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.rosbridge.ros.SyntheticRosAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 真实 WebSocket 入口的集成测试：启动嵌入式容器（H2 + 合成 ROS 适配器），
 * 用标准 WebSocket 客户端走完整 connect/subscribe/publish/ack/重连续传，
 * 断言实际收到的帧内容，而不是仅验证上下文启动。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RosbridgeWebSocketIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    SyntheticRosAdapter adapter;

    private final StandardWebSocketClient client = new StandardWebSocketClient();

    private static class CollectingHandler extends TextWebSocketHandler {
        final List<String> messages = new CopyOnWriteArrayList<>();
        final CompletableFuture<WebSocketSession> open = new CompletableFuture<>();
        volatile CloseStatus closeStatus;

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            open.complete(session);
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            this.closeStatus = status;
        }
    }

    private CollectingHandler newConnection(String uri) throws Exception {
        CollectingHandler h = new CollectingHandler();
        WebSocketSession session = client.execute(h, uri).get(10, TimeUnit.SECONDS);
        h.open.complete(session);
        return h;
    }

    private static JsonNode send(WebSocketSession session, String json) throws Exception {
        session.sendMessage(new TextMessage(json));
        return null;
    }

    private JsonNode awaitFrame(CollectingHandler h, int index) throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        while (h.messages.size() <= index && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(h.messages.size()).isGreaterThan(index);
        return com.example.starter.rosbridge.protocol.Protocol.MAPPER
                .readTree(h.messages.get(index));
    }

    private List<JsonNode> drain(CollectingHandler h) throws Exception {
        Thread.sleep(200);
        List<JsonNode> out = new ArrayList<>();
        for (String m : h.messages) {
            out.add(com.example.starter.rosbridge.protocol.Protocol.MAPPER.readTree(m));
        }
        return out;
    }

    @Test
    void subscribeAckReconnectOverRealWebsocket() throws Exception {
        String base = "ws://localhost:" + port + "/rosbridge";

        // 连接 1：query 参数携带 clientId
        CollectingHandler h1 = newConnection(base + "?clientId=ws-client");
        WebSocketSession s1 = h1.open.get(10, TimeUnit.SECONDS);
        JsonNode connected = awaitFrame(h1, 0);
        assertThat(connected.path("op").asText()).isEqualTo("connected");

        send(s1, """
                {"op":"subscribe","topic":"/ws_chatter","type":"std_msgs/String"}
                """);
        JsonNode subscribed = awaitFrame(h1, 1);
        assertThat(subscribed.path("op").asText()).isEqualTo("subscribed");

        ObjectNode m1 = JsonNodeFactory.instance.objectNode().put("data", "hello");
        ObjectNode m2 = JsonNodeFactory.instance.objectNode().put("data", "world");
        adapter.emit("/ws_chatter", m1);
        adapter.emit("/ws_chatter", m2);
        JsonNode p1 = awaitFrame(h1, 2);
        JsonNode p2 = awaitFrame(h1, 3);
        assertThat(p1.path("op").asText()).isEqualTo("publish");
        assertThat(p1.path("cursor").asLong()).isEqualTo(1L);
        assertThat(p1.path("msg").path("data").asText()).isEqualTo("hello");
        assertThat(p2.path("cursor").asLong()).isEqualTo(2L);

        // ACK 游标 2
        send(s1, """
                {"op":"ack","topic":"/ws_chatter","type":"std_msgs/String","cursor":2}
                """);

        // 断连后再到达一条消息（上游宽限内留存）
        s1.close();
        Thread.sleep(300);
        adapter.emit("/ws_chatter", JsonNodeFactory.instance.objectNode().put("data", "reconnect"));

        // 同 JVM 重连：只收到游标 > 2 的消息
        CollectingHandler h2 = newConnection(base + "?clientId=ws-client");
        WebSocketSession s2 = h2.open.get(10, TimeUnit.SECONDS);
        List<JsonNode> frames = drain(h2);
        List<JsonNode> publishes = frames.stream()
                .filter(f -> "publish".equals(f.path("op").asText())).toList();
        assertThat(publishes).hasSize(1);
        assertThat(publishes.get(0).path("cursor").asLong()).isEqualTo(3L);
        assertThat(publishes.get(0).path("msg").path("data").asText()).isEqualTo("reconnect");
        s2.close();
    }

    @Test
    void connectFrameWithoutClientIdIsRejected() throws Exception {
        CollectingHandler h = new CollectingHandler();
        WebSocketSession s = client.execute(h,
                "ws://localhost:" + port + "/rosbridge").get(10, TimeUnit.SECONDS);
        h.open.complete(s);
        s.sendMessage(new TextMessage("{\"op\":\"subscribe\",\"topic\":\"/x\"}"));
        JsonNode err = awaitFrame(h, 0);
        assertThat(err.path("op").asText()).isEqualTo("error");
        s.close();
    }

    @Test
    void serviceCallOverRealWebsocket() throws Exception {
        CollectingHandler h = newConnection(
                "ws://localhost:" + port + "/rosbridge?clientId=svc-client");
        WebSocketSession s = h.open.get(10, TimeUnit.SECONDS);
        awaitFrame(h, 0); // connected
        s.sendMessage(new TextMessage("""
                {"op":"call_service","requestId":"r1","service":"/echo",
                 "args":{"value":7},"timeoutMs":5000}
                """));
        JsonNode resp = awaitFrame(h, 1);
        assertThat(resp.path("op").asText()).isEqualTo("service_response");
        assertThat(resp.path("requestId").asText()).isEqualTo("r1");
        assertThat(resp.path("status").asText()).isEqualTo("OK");
        assertThat(resp.path("values").path("value").asInt()).isEqualTo(7);
        s.close();
    }
}
