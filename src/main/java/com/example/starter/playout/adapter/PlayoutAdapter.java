package com.example.starter.playout.adapter;

/**
 * 播出适配器抽象；实现只做本地合成动作，不连接真实广电设备。
 */
public interface PlayoutAdapter {

    /**
     * 通知适配器开始播出某序号对应的素材。
     */
    void start(PlayoutCommand command);
}
