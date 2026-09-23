package com.example.starter.rosbridge.gateway;

/**
 * 单客户端发送会话：维护有界发送队列、令牌桶速率限制与 WebSocket 写出。
 * 队列满时丢弃该客户端最旧未确认消息并生成 RESUME_GAP，不影响其他客户端。
 */
public interface ClientSession {

    /** 入队一条待发送帧；慢消费者满队列时按协议丢弃旧帧。 */
    void enqueue(OutboundFrame frame);

    /** 更新该客户端某 Topic 的已确认游标（仅前进），用于判定“未确认消息”。 */
    default void acknowledge(String topic, long cursor) {
    }

    /** 关闭会话并释放资源。 */
    void close();

    boolean isOpen();
}
