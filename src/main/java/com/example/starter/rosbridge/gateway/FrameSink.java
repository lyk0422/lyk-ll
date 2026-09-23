package com.example.starter.rosbridge.gateway;

/**
 * 帧实际写出端（默认包装 WebSocketSession，测试可替换为可控阻塞写出端）。
 */
@FunctionalInterface
public interface FrameSink {

    void send(String payload) throws Exception;
}
