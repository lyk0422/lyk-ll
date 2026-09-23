package com.example.starter.adapter;

import com.example.starter.model.DeviceMessage;

/**
 * 合成上游适配器：模拟 micro-ROS 上游链路（含网络抖动）。
 */
public interface UpstreamAdapter {

    /**
     * 投递一条消息。
     *
     * @return true 表示上游已接收（等待 ACK）；false 表示链路不可用或发送失败
     */
    boolean deliver(DeviceMessage message);

    /** 当前上游链路是否联网（false 表示离线，消息只做本地缓存）。 */
    boolean isOnline();
}
