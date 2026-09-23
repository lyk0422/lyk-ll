package com.example.starter.rosbridge.ws;

import com.example.starter.rosbridge.gateway.GatewayService;
import com.example.starter.rosbridge.protocol.Protocol;
import com.example.starter.rosbridge.ros.Qos;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * rosbridge 风格 WebSocket 处理器：解析入帧并委派给 {@link GatewayService}。
 *
 * <p>clientId 可通过连接 URL 查询参数 {@code ?clientId=xxx} 提供（握手成功即建立网关会话），
 * 也可在首帧 {@code connect} 中提供。未携带 clientId 时服务端回 error 并关闭。</p>
 */
public class RosbridgeWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RosbridgeWebSocketHandler.class);

    private final GatewayService gateway;

    public RosbridgeWebSocketHandler(GatewayService gateway) {
        this.gateway = gateway;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketClientTransport transport = new WebSocketClientTransport(session);
        session.getAttributes().put("transport", transport);
        String queryClientId = clientIdFromQuery(session);
        if (queryClientId != null) {
            transport.setClientId(queryClientId);
            gateway.connect(transport, null, null);
        }
    }

    private String clientIdFromQuery(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) {
            return null;
        }
        for (String pair : uri.getQuery().split("&")) {
            int i = pair.indexOf('=');
            if (i > 0 && "clientId".equals(pair.substring(0, i))) {
                return pair.substring(i + 1);
            }
        }
        return null;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        WebSocketClientTransport transport =
                (WebSocketClientTransport) session.getAttributes().get("transport");
        JsonNode node;
        try {
            node = Protocol.MAPPER.readTree(message.getPayload());
        } catch (Exception e) {
            safeSend(session, Protocol.error(null, "invalid JSON frame"));
            return;
        }
        String op = node.path("op").asText("");

        try {
            switch (op) {
                case Protocol.OP_CONNECT -> handleConnect(transport, node);
                case Protocol.OP_PING -> handlePing(transport, node);
                default -> dispatch(transport, op, node);
            }
        } catch (RuntimeException e) {
            log.warn("处理帧失败 op={}: {}", op, e.toString());
            safeSend(session, Protocol.error(op, "internal error"));
        }
    }

    private void handleConnect(WebSocketClientTransport transport, JsonNode node) {
        String clientId = node.path("clientId").asText("");
        if (clientId.isBlank()) {
            safeSendRaw(transport, Protocol.error(Protocol.OP_CONNECT, "clientId required"));
            transport.close();
            return;
        }
        transport.setClientId(clientId);
        Integer rate = intOrNull(node, "rateLimitPerSec");
        Integer cap = intOrNull(node, "queueCap");
        gateway.connect(transport, rate, cap);
    }

    private void handlePing(WebSocketClientTransport transport, JsonNode node) {
        long nonce = node.path("nonce").asLong(0L);
        transport.sendText(Protocol.pong(nonce));
    }

    private void dispatch(WebSocketClientTransport transport, String op, JsonNode node) {
        String clientId = transport.clientId();
        if (clientId == null) {
            transport.sendText(Protocol.error(op, "send connect first"));
            return;
        }
        switch (op) {
            case Protocol.OP_SUBSCRIBE -> gateway.subscribe(clientId,
                    required(node, "topic"), required(node, "type"), parseQos(node));
            case Protocol.OP_UNSUBSCRIBE -> gateway.unsubscribe(clientId,
                    required(node, "topic"), required(node, "type"), parseQos(node));
            case Protocol.OP_ACK -> gateway.ack(clientId,
                    required(node, "topic"), required(node, "type"), parseQos(node),
                    node.path("cursor").asLong(0L));
            case Protocol.OP_CALL_SERVICE -> gateway.callService(clientId,
                    required(node, "requestId"), required(node, "service"),
                    node.path("type").asText(""), node.get("args"),
                    longOrNull(node, "timeoutMs"));
            case Protocol.OP_SEND_GOAL -> gateway.sendGoal(clientId,
                    required(node, "goalId"), required(node, "action"),
                    node.path("type").asText(""), node.get("goal"));
            case Protocol.OP_CANCEL_GOAL -> gateway.cancelGoal(clientId,
                    required(node, "goalId"));
            default -> transport.sendText(Protocol.error(op, "unknown op: " + op));
        }
    }

    private static Qos parseQos(JsonNode node) {
        JsonNode q = node.get("qos");
        if (q == null || q.isNull()) {
            return Qos.defaults();
        }
        if (q.isTextual()) {
            // 允许直接传 qos 键字符串（不可靠，仅调试用）
            return Qos.defaults();
        }
        return new Qos(q.path("reliability").asText(Qos.RELIABLE),
                q.path("durability").asText(Qos.VOLATILE),
                q.path("depth").asInt(10));
    }

    private static String required(JsonNode node, String field) {
        String v = node.path(field).asText("");
        if (v.isBlank()) {
            throw new IllegalArgumentException(field + " required");
        }
        return v;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isNumber() ? v.intValue() : null;
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isNumber() ? v.longValue() : null;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Map<String, Object> attrs = session.getAttributes();
        WebSocketClientTransport transport =
                (WebSocketClientTransport) attrs.get("transport");
        if (transport != null && transport.clientId() != null) {
            gateway.disconnect(transport);
        }
    }

    private void safeSend(WebSocketSession session, String text) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(text));
            }
        } catch (Exception ignored) {
            // 尽力而为
        }
    }

    private void safeSendRaw(WebSocketClientTransport transport, String text) {
        transport.sendText(text);
    }
}
