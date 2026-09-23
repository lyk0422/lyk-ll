package com.example.starter.forwarder.testsupport;

import com.example.starter.forwarder.model.DeviceMessage;
import com.example.starter.forwarder.upstream.UpstreamAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用录制型上游适配器：记录已发送 dedupKey，支持按次数制造失败模拟网络抖动。
 */
public class RecordingUpstreamAdapter implements UpstreamAdapter {

    private final List<String> sentKeys = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, AtomicInteger> sendCount = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> failOnceKeys = new CopyOnWriteArrayList<>();
    private volatile boolean failAllOnce = false;

    @Override
    public void send(DeviceMessage message) {
        int times = sendCount.computeIfAbsent(message.getDedupKey(), k -> new AtomicInteger())
                .incrementAndGet();
        boolean shouldFail = failAllOnce || failOnceKeys.remove(message.getDedupKey());
        if (shouldFail) {
            failAllOnce = false;
            throw new IllegalStateException("模拟网络抖动: " + message.getDedupKey());
        }
        sentKeys.add(message.getDedupKey());
    }

    public List<String> getSentKeys() {
        return new ArrayList<>(sentKeys);
    }

    public int sendCountOf(String dedupKey) {
        AtomicInteger counter = sendCount.get(dedupKey);
        return counter == null ? 0 : counter.get();
    }

    public void failNextSendOnce() {
        this.failAllOnce = true;
    }

    public void failNextForKeys(List<String> keys) {
        failOnceKeys.addAll(keys);
    }

    public void reset() {
        sentKeys.clear();
        sendCount.clear();
        failOnceKeys.clear();
        failAllOnce = false;
    }
}
