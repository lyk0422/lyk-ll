package com.example.starter.forwarder;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

/**
 * 合成网络状态：在线时可转发，离线时按 Topic 策略缓存或丢弃。
 */
@Component
public class NetworkState {

    private final AtomicBoolean online;

    public NetworkState(ForwarderProperties properties) {
        this.online = new AtomicBoolean(properties.isNetworkOnline());
    }

    public boolean isOnline() {
        return online.get();
    }

    public void setOnline(boolean value) {
        online.set(value);
    }
}
