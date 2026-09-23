package com.example.starter.upstream;

import com.example.starter.po.DeviceMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 合成上游适配器：用内存状态模拟 micro-ROS 上行链路的通断与抖动，不依赖真实 micro-ROS。
 * <ul>
 *   <li>{@link #setOnline(boolean)} 模拟断网/恢复；</li>
 *   <li>进程启动时的初始链路状态由 {@code forwarder.upstream.initially-online} 控制，
 *       网络状态本身不持久化，跨进程恢复测试可让新进程以"离线"启动；</li>
 *   <li>可选失败率 {@code failureRate}（0~1）模拟随机丢链，默认 0（在线即接收）；</li>
 *   <li>记录已 dispatch 的消息（在测试中用于核对补发顺序）。</li>
 * </ul>
 */
@Component
public class SyntheticUpstreamClient implements UpstreamClient {

    private final AtomicBoolean online;
    private final AtomicInteger dispatchCount = new AtomicInteger();
    private final CopyOnWriteArrayList<String> dispatchedKeys = new CopyOnWriteArrayList<>();
    private volatile double failureRate;

    public SyntheticUpstreamClient(
            @Value("${forwarder.upstream.initially-online:true}") boolean initiallyOnline) {
        this.online = new AtomicBoolean(initiallyOnline);
    }

    @Override
    public boolean dispatch(DeviceMessage message) {
        if (!online.get()) {
            return false;
        }
        double rate = failureRate;
        if (rate > 0 && Math.random() < rate) {
            return false;
        }
        dispatchCount.incrementAndGet();
        dispatchedKeys.add(message.dedupKey());
        return true;
    }

    @Override
    public boolean isOnline() {
        return online.get();
    }

    @Override
    public void setOnline(boolean value) {
        online.set(value);
    }

    /** 设置模拟随机失败率，范围 [0,1]。 */
    public void setFailureRate(double rate) {
        this.failureRate = Math.max(0, Math.min(1, rate));
    }

    /** 本进程累计成功 dispatch 次数。 */
    public int dispatchCount() {
        return dispatchCount.get();
    }

    /** 本进程成功 dispatch 的去重键顺序（含重试造成的重复发送）。 */
    public List<String> dispatchedKeys() {
        return List.copyOf(dispatchedKeys);
    }

    /** 测试辅助：重置统计与发送记录。 */
    public void resetStats() {
        dispatchCount.set(0);
        dispatchedKeys.clear();
    }
}
