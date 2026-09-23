package com.example.starter.forwarder;

/**
 * 上游（云端）适配器：发送一条消息，返回是否被上游接收。
 * 发送成功不代表已确认，确认通过 ACK 通道单独到达。
 */
public interface UpstreamAdapter {

    /**
     * 尝试发送一条消息。
     *
     * @return true 表示上游已接收（等待 ACK）；false 表示发送失败（网络抖动等）
     */
    boolean send(MessageView message);
}
