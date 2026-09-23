package com.example.starter.adapter;

import com.example.starter.asrun.AsRunRecord;

/**
 * 播出适配器：向（合成的）播出设备发送切换指令。实现不连接真实广电设备。
 */
public interface PlayoutAdapter {

    /**
     * 指定 as-run 记录对应的素材开始播出。
     */
    void onAir(AsRunRecord record);

    /**
     * 指定 as-run 记录对应的素材停止播出。
     */
    void offAir(AsRunRecord record);
}
