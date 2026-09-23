package com.example.starter.rosbridge.testsupport;

import com.example.starter.rosbridge.gateway.ClientTransport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试用传输：记录全部已发送帧，可控开关状态，不依赖真实容器。
 */
public class RecordingTransport implements ClientTransport {

    private final String clientId;
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private volatile boolean open = true;
    private volatile int failAfter = Integer.MAX_VALUE;

    public RecordingTransport(String clientId) {
        this.clientId = clientId;
    }

    @Override
    public String clientId() {
        return clientId;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean sendText(String text) {
        if (!open) {
            return false;
        }
        if (sent.size() >= failAfter) {
            return false;
        }
        sent.add(text);
        return true;
    }

    @Override
    public void close() {
        open = false;
    }

    public List<String> sent() {
        return new ArrayList<>(sent);
    }

    public void setFailAfter(int n) {
        this.failAfter = n;
    }

    public void reopen() {
        open = true;
    }

    public void clear() {
        sent.clear();
    }
}
