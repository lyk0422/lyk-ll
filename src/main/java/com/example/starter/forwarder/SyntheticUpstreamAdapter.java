package com.example.starter.forwarder;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

/**
 * 合成上游适配器：不依赖真实 micro-ROS / 云端，按配置的失败概率模拟网络抖动，
 * 并记录已发送消息便于本地观察。
 */
@Component
public class SyntheticUpstreamAdapter implements UpstreamAdapter {

    private final ForwarderProperties properties;
    private final List<String> sentLog = new CopyOnWriteArrayList<>();

    public SyntheticUpstreamAdapter(ForwarderProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean send(MessageView message) {
        double rate = properties.getUpstreamFailureRate();
        if (rate > 0 && ThreadLocalRandom.current().nextDouble() < rate) {
            return false;
        }
        sentLog.add(message.dedupKey());
        return true;
    }

    /**
     * 已发送消息的 dedupKey 列表（仅用于本地演示观察）。
     */
    public List<String> sentLog() {
        return List.copyOf(sentLog);
    }
}
