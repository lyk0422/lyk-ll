package com.example.starter.forwarder.upstream;

import com.example.starter.forwarder.model.DeviceMessage;

/**
 * 上游适配器（合成实现，模拟 micro-ROS 代理/网络抖动）。
 */
public interface UpstreamAdapter {

    /**
     * 发送一条消息；实现可按概率抛异常模拟网络故障。
     */
    void send(DeviceMessage message) throws Exception;
}
