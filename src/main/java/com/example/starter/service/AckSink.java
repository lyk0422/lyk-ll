package com.example.starter.service;

/**
 * ACK 回调接口，由转发服务实现、供上游适配器在模拟收到确认时回调。
 */
public interface AckSink {

    /**
     * 处理一条 ACK；重复 ACK 幂等、乱序 ACK 不误删未确认消息。
     *
     * @return true 表示此次 ACK 首次确认了某条 in-flight 消息；false 表示消息不存在/已确认
     */
    boolean ack(String dedupKey);
}
