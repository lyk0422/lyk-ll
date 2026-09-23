package com.example.starter.upstream;

import com.example.starter.po.DeviceMessage;

/**
 * 合成上游适配器：模拟 micro-ROS 上行链路与网络抖动。
 * 不依赖真实 micro-ROS；ACK 由独立通道异步到达。
 */
public interface UpstreamClient {

    /**
     * 尝试把消息投递给上游。
     *
     * @return true 表示上游已接收（进入 in-flight，等待 ACK）；false 表示当前链路不可达
     */
    boolean dispatch(DeviceMessage message);

    /** 当前模拟链路是否在线。 */
    boolean isOnline();

    /** 切换模拟链路状态。 */
    void setOnline(boolean online);
}
