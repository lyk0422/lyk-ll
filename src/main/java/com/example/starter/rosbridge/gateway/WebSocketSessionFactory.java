package com.example.starter.rosbridge.gateway;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * 默认会话工厂：创建基于 WebSocket 的限速有界队列会话。
 */
@Component
public class WebSocketSessionFactory implements SessionFactory {

    @Override
    public ClientSession create(String clientId, WebSocketSession wsSession, int ratePerSecond, int queueCapacity) {
        return new WebSocketClientSession(clientId, wsSession, queueCapacity, ratePerSecond);
    }
}
