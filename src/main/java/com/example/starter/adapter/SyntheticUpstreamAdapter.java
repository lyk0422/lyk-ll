package com.example.starter.adapter;

import com.example.starter.model.DeviceMessage;
import com.example.starter.service.AckSink;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 合成上游适配器实现：联网时接收全部消息并按投递顺序记录，断网时投递失败。
 * 通过联网/断网切换模拟网络抖动与离线恢复；autoAck 开启时模拟 ACK 立即可靠到达，
 * 默认关闭，由真实 ACK 入口决定确认时机，便于模拟重复、乱序与丢失的 ACK。
 */
@Component
public class SyntheticUpstreamAdapter implements UpstreamAdapter {

    private static final Logger log = LoggerFactory.getLogger(SyntheticUpstreamAdapter.class);

    private final AtomicBoolean online = new AtomicBoolean(true);
    private final AtomicBoolean autoAck = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<String> deliveredMessageIds = new ConcurrentLinkedQueue<>();
    private final AtomicInteger deliverAttempts = new AtomicInteger();

    private volatile AckSink ackSink;

    /** 注入 ACK 回调（自动 ACK 模式使用）。 */
    public void setAckSink(AckSink ackSink) {
        this.ackSink = ackSink;
    }

    /** 设置模拟链路状态（true=联网，false=断网）。 */
    public void setOnline(boolean value) {
        online.set(value);
        log.info("模拟上游链路状态切换为 {}", value ? "联网" : "断网");
    }

    /** 当前模拟链路是否联网。 */
    public boolean isOnline() {
        return online.get();
    }

    /** 设置是否在投递成功后立即自动回调 ACK。 */
    public void setAutoAck(boolean value) {
        autoAck.set(value);
    }

    @Override
    public boolean deliver(DeviceMessage message) {
        deliverAttempts.incrementAndGet();
        if (!online.get()) {
            log.debug("断网期间投递失败，消息保持待发: {}", message.getMessageId());
            return false;
        }
        deliveredMessageIds.add(message.getMessageId());
        log.debug("上游已接收消息: {}", message.getMessageId());
        if (autoAck.get()) {
            AckSink sink = ackSink;
            if (sink != null) {
                sink.ack(message.getMessageId());
            }
        }
        return true;
    }

    /** 返回成功投递记录的 dedupKey 快照（按投递先后），供测试核验补发顺序。 */
    public List<String> getDeliveredMessageIds() {
        return new ArrayList<>(deliveredMessageIds);
    }

    /** 投递尝试总次数（含失败），供测试核验重试。 */
    public int getDeliverAttempts() {
        return deliverAttempts.get();
    }

    /** 清空投递记录（不影响消息数据），供测试隔离使用。 */
    public void clearDeliveryLog() {
        deliveredMessageIds.clear();
        deliverAttempts.set(0);
    }
}
