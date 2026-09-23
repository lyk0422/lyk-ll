package com.example.starter.forwarder.upstream;

import com.example.starter.forwarder.model.DeviceMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 合成上游适配器：不依赖真实 micro-ROS，仅记录已发送消息并可按配置概率抛异常模拟网络抖动。
 *
 * <p>失败计数/已收消息只保存在内存中，仅用于模拟与测试断言；转发器本身的补发状态全部落 H2。</p>
 */
@Component
public class SyntheticUpstreamAdapter implements UpstreamAdapter {

    private static final Logger log = LoggerFactory.getLogger(SyntheticUpstreamAdapter.class);

    private final Random random = new Random(42);
    private final AtomicLong sentCounter = new AtomicLong();
    private final AtomicLong failedCounter = new AtomicLong();

    /** 0.0~1.0 的发送失败概率，模拟网络抖动；默认 0（不失败）。 */
    private volatile double failureRate = 0.0;

    @Override
    public void send(DeviceMessage message) {
        sentCounter.incrementAndGet();
        if (failureRate > 0.0 && random.nextDouble() < failureRate) {
            failedCounter.incrementAndGet();
            throw new IllegalStateException("模拟上游网络抖动: " + message.getDedupKey());
        }
        log.debug("合成上游已接收 deviceId={} topic={} seq={} dedupKey={}",
                message.getDeviceId(), message.getTopic(), message.getSeq(), message.getDedupKey());
    }

    public void setFailureRate(double failureRate) {
        this.failureRate = failureRate;
    }

    public long getSentCount() {
        return sentCounter.get();
    }

    public long getFailedCount() {
        return failedCounter.get();
    }

    public void resetStats() {
        sentCounter.set(0);
        failedCounter.set(0);
    }
}
