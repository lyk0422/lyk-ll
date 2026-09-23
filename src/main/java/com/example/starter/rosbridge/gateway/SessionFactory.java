package com.example.starter.rosbridge.gateway;

import org.springframework.web.socket.WebSocketSession;

/**
 * 客户端发送会话工厂，便于测试注入可控会话。
 */
@FunctionalInterface
public interface SessionFactory {

    ClientSession create(String clientId, WebSocketSession wsSession, int ratePerSecond, int queueCapacity);
}
