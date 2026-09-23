package com.example.starter.rosbridge.ws;

import com.example.starter.rosbridge.config.RosbridgeProperties;
import com.example.starter.rosbridge.protocol.ClientRequest;
import com.example.starter.rosbridge.protocol.Frames;
import com.example.starter.rosbridge.service.GatewayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.Map;

/**
 * rosbridge 风格 WebSocket 入口。客户端以 {@code /ws?clientId=c1} 连接，
 * 可选 {@code rateLimit}（条/秒，默认取网关配置）与
 * {@code queueCapacity}（队列上限，默认取网关配置）定制本连接配置。
 */
public class RosbridgeWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RosbridgeWebSocketHandler.class);
    private static final String ATTR_CLIENT_ID = "rosbridge.clientId";

    private final GatewayService gatewayService;
    private final ObjectMapper mapper;
    private final RosbridgeProperties properties;

    public RosbridgeWebSocketHandler(GatewayService gatewayService, ObjectMapper mapper,
                                     RosbridgeProperties properties) {
        this.gatewayService = gatewayService;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Map<String, String> query = parseQuery(session.getUri());
        String clientId = query.get("clientId");
        if (clientId == null || clientId.isBlank()) {
            log.warn("拒绝缺少 clientId 的连接 {}", session.getId());
            session.close(CloseStatus.POLICY_VIOLATION.withReason("缺少 clientId"));
            return;
        }
        int rateLimit = parseIntOrDefault(query.get("rateLimit"),
                properties.getDefaultRateLimitPerSecond());
        int queueCapacity = parseIntOrDefault(query.get("queueCapacity"),
                properties.getDefaultSendQueueCapacity());

        session.getAttributes().put(ATTR_CLIENT_ID, clientId);
        gatewayService.registerConnection(session, clientId, rateLimit, queueCapacity);
        log.info("客户端 {} 已连接（session={}, rateLimit={}, queueCapacity={}）",
                clientId, session.getId(), rateLimit, queueCapacity);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String clientId = (String) session.getAttributes().get(ATTR_CLIENT_ID);
        if (clientId == null) {
            return;
        }
        ClientRequest request;
        try {
            request = mapper.readValue(message.getPayload(), ClientRequest.class);
        } catch (Exception e) {
            log.debug("客户端 {} 发来非法 JSON: {}", clientId, e.toString());
            sendRawError(session, Frames.CODE_ERROR, "非法 JSON 帧");
            return;
        }
        gatewayService.handleRequest(clientId, request);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String clientId = (String) session.getAttributes().get(ATTR_CLIENT_ID);
        if (clientId == null) {
            return;
        }
        gatewayService.unregisterConnection(clientId, session);
        log.info("客户端 {} 连接关闭（{}），进入 {}ms 重连宽限期",
                clientId, status, properties.getReconnectGraceMs());
    }

    private void sendRawError(WebSocketSession session, String code, String message) {
        try {
            session.sendMessage(new TextMessage(
                    mapper.writeValueAsString(Frames.error(code, message))));
        } catch (Exception ignored) {
            // 连接已不可写，忽略。
        }
    }

    private static int parseIntOrDefault(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed < 0 ? defaultValue : parsed;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 解析 URI 查询参数为扁平 Map，重复键取最后一个。 */
    static Map<String, String> parseQuery(URI uri) {
        Map<String, String> result = new java.util.HashMap<>();
        if (uri == null || uri.getRawQuery() == null) {
            return result;
        }
        for (String pair : uri.getRawQuery().split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int idx = pair.indexOf('=');
            String key = idx < 0 ? pair : pair.substring(0, idx);
            String value = idx < 0 ? "" : pair.substring(idx + 1);
            result.put(java.net.URLDecoder.decode(key, java.nio.charset.StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8));
        }
        return result;
    }
}
