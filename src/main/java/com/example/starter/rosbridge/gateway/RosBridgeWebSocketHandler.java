package com.example.starter.rosbridge.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.starter.rosbridge.protocol.Frames;
import com.example.starter.rosbridge.protocol.Op;
import com.example.starter.rosbridge.protocol.ProtocolJson;
import java.io.IOException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * rosbridge 风格 WebSocket 入口，路径 /rosbridge。
 * clientId 可通过查询参数 {@code ?clientId=xxx} 声明，也可在首帧
 * {@code {"op":"CONNECT","clientId":"xxx"}} 中声明（可附带 ratePerSecond/queueCapacity）。
 */
public class RosBridgeWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RosBridgeWebSocketHandler.class);
    private static final String ATTR_CLIENT = "rosbridge.clientId";
    private static final String ATTR_SESSION = "rosbridge.sessionId";

    private final GatewayCore gatewayCore;

    public RosBridgeWebSocketHandler(GatewayCore gatewayCore) {
        this.gatewayCore = gatewayCore;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String clientId = queryParam(session, "clientId");
        if (clientId != null && !clientId.isBlank()) {
            register(session, clientId.trim(), null);
        }
        // 否则等待 CONNECT 帧
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String clientId = (String) session.getAttributes().get(ATTR_CLIENT);
        if (clientId == null) {
            JsonNode node;
            try {
                node = ProtocolJson.MAPPER.readTree(message.getPayload());
            } catch (Exception e) {
                sendRaw(session, Frames.error(null, "BAD_FRAME", "payload must be valid JSON"));
                return;
            }
            if (Op.CONNECT.equals(node.path("op").asText(null))) {
                String requested = node.path("clientId").asText(null);
                if (requested == null || requested.isBlank()) {
                    sendRaw(session, Frames.error(Op.CONNECT, "BAD_FRAME", "clientId required"));
                    return;
                }
                Integer rate = node.has("ratePerSecond") ? node.get("ratePerSecond").asInt() : null;
                Integer capacity = node.has("queueCapacity") ? node.get("queueCapacity").asInt() : null;
                register(session, requested.trim(), new ConnectOverrides(rate, capacity));
                return;
            }
            sendRaw(session, Frames.error(null, "NOT_CONNECTED", "send CONNECT first"));
            return;
        }
        gatewayCore.handleText(clientId, message.getPayload());
    }

    private void register(WebSocketSession session, String clientId, ConnectOverrides overrides) {
        String sessionId = GatewayCore.newSessionId();
        session.getAttributes().put(ATTR_CLIENT, clientId);
        session.getAttributes().put(ATTR_SESSION, sessionId);
        Integer rate = overrides == null ? null : overrides.rate();
        Integer cap = overrides == null ? null : overrides.capacity();
        gatewayCore.connect(clientId, sessionId, session, rate, cap);
        log.info("websocket connected clientId={} sessionId={}", clientId, sessionId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String clientId = (String) session.getAttributes().get(ATTR_CLIENT);
        String sessionId = (String) session.getAttributes().get(ATTR_SESSION);
        if (clientId != null && sessionId != null) {
            log.info("websocket closed clientId={} sessionId={} status={}", clientId, sessionId, status);
            gatewayCore.disconnect(clientId, sessionId);
        }
    }

    private static String queryParam(WebSocketSession session, String name) {
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) {
            return null;
        }
        for (String pair : uri.getQuery().split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && pair.substring(0, idx).equals(name)) {
                return pair.substring(idx + 1);
            }
        }
        return null;
    }

    private void sendRaw(WebSocketSession session, String payload) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(payload));
                }
            }
        } catch (IOException e) {
            log.warn("failed to send error frame: {}", e.toString());
        }
    }

    private record ConnectOverrides(Integer rate, Integer capacity) {
    }
}
