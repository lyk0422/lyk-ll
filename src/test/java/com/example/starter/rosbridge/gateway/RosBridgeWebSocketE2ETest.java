package com.example.starter.rosbridge.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.starter.rosbridge.protocol.ProtocolJson;
import com.example.starter.rosbridge.ros.SubscriptionKey;
import com.example.starter.rosbridge.ros.SyntheticRosAdapter;
import com.example.starter.rosbridge.store.GatewayRepository;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 真实 WebSocket 端到端测试：本地随机端口启动服务，标准 WebSocket 客户端完成
 * CONNECT/订阅/ACK/断连重连补发全链路（H2 + 合成 ROS，无外部依赖）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RosBridgeWebSocketE2ETest {

    @LocalServerPort
    private int port;

    @Autowired
    private SyntheticRosAdapter ros;

    @Autowired
    private GatewayCore gateway;

    @Autowired
    private GatewayRepository repository;

    @Autowired
    private BoundedMessageLedger ledger;

    @BeforeEach
    void clean() {
        gateway.releaseAllForTesting();
        repository.deleteAll();
        ledger.clear();
    }

    private static final class CollectingHandler extends TextWebSocketHandler {
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            frames.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            frames.add("{\"op\":\"__CLOSED__\"}");
        }

        JsonNode poll(String op, long timeoutMs) throws Exception {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                String payload = frames.poll(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (payload == null) {
                    break;
                }
                JsonNode node = ProtocolJson.MAPPER.readTree(payload);
                if (op.equals(node.path("op").asText())) {
                    return node;
                }
            }
            return null;
        }
    }

    private WebSocketSession connect(CollectingHandler handler, String clientId) throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        String uri = "ws://localhost:" + port + "/rosbridge?clientId=" + clientId;
        return client.execute(handler, uri).get(5, TimeUnit.SECONDS);
    }

    @Test
    void subscribeAckDisconnectAndResumeOverRealWebsocket() throws Exception {
        CollectingHandler h1 = new CollectingHandler();
        WebSocketSession s1 = connect(h1, "e2e-client");
        try {
            JsonNode connack = h1.poll("CONNACK", 3000);
            assertThat(connack).isNotNull();
            assertThat(connack.path("clientId").asText()).isEqualTo("e2e-client");

            s1.sendMessage(new TextMessage("{\"op\":\"SUBSCRIBE\",\"topic\":\"/e2e\",\"type\":\"std_msgs/String\"}"));
            assertThat(h1.poll("SUBSCRIBED", 3000)).isNotNull();

            SubscriptionKey key = new SubscriptionKey("/e2e", "std_msgs/String", "{}");
            assertThat(ros.publish(key, ProtocolJson.MAPPER.createObjectNode().put("seq", 1))).isTrue();
            assertThat(ros.publish(key, ProtocolJson.MAPPER.createObjectNode().put("seq", 2))).isTrue();
            assertThat(h1.poll("TOPIC", 3000).path("cursor").asLong()).isEqualTo(1);
            assertThat(h1.poll("TOPIC", 3000).path("cursor").asLong()).isEqualTo(2);

            s1.sendMessage(new TextMessage("{\"op\":\"ACK\",\"topic\":\"/e2e\",\"cursor\":2}"));
        } finally {
            s1.close();
            assertThat(h1.poll("__CLOSED__", 3000)).isNotNull();
        }

        // 断连期间（宽限 200ms 内）上游仍在，消息继续进入共享游标与补发账本
        SubscriptionKey key = new SubscriptionKey("/e2e", "std_msgs/String", "{}");
        assertThat(ros.publish(key, ProtocolJson.MAPPER.createObjectNode().put("seq", 3))).isTrue();

        CollectingHandler h2 = new CollectingHandler();
        WebSocketSession s2 = connect(h2, "e2e-client");
        try {
            assertThat(h2.poll("CONNACK", 3000)).isNotNull();
            s2.sendMessage(new TextMessage("{\"op\":\"SUBSCRIBE\",\"topic\":\"/e2e\",\"type\":\"std_msgs/String\"}"));
            assertThat(h2.poll("SUBSCRIBED", 3000)).isNotNull();

            JsonNode resumed = h2.poll("TOPIC", 3000);
            assertThat(resumed).isNotNull();
            assertThat(resumed.path("cursor").asLong()).isEqualTo(3);
            assertThat(resumed.path("resume").asBoolean()).isTrue();
            assertThat(resumed.path("data").path("seq").asLong()).isEqualTo(3);

            assertThat(ros.publish(key, ProtocolJson.MAPPER.createObjectNode().put("seq", 4))).isTrue();
            JsonNode live = h2.poll("TOPIC", 3000);
            assertThat(live.path("cursor").asLong()).isEqualTo(4);
            assertThat(live.path("resume").asBoolean()).isFalse();

            // Service 与 Action 走同一真实通道
            s2.sendMessage(new TextMessage(
                    "{\"op\":\"CALL_SERVICE\",\"requestId\":\"e2e-r1\",\"service\":\"/echo\",\"args\":{\"v\":7}}"));
            JsonNode resp = h2.poll("SERVICE_RESPONSE", 3000);
            assertThat(resp.path("status").asText()).isEqualTo("COMPLETED");
            assertThat(resp.path("result").path("echo").path("v").asInt()).isEqualTo(7);
        } finally {
            s2.close();
        }
    }
}
