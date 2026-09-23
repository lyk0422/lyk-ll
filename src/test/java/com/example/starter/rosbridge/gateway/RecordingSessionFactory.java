package com.example.starter.rosbridge.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.starter.rosbridge.protocol.ProtocolJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.socket.WebSocketSession;

/**
 * 测试用会话工厂：录制全部出站帧到阻塞队列，不依赖真实 WebSocket。
 */
public class RecordingSessionFactory implements SessionFactory {

    private final Map<String, RecordingSession> sessions = new ConcurrentHashMap<>();

    @Override
    public ClientSession create(String clientId, WebSocketSession wsSession, int ratePerSecond, int queueCapacity) {
        RecordingSession session = new RecordingSession();
        sessions.put(clientId, session);
        return session;
    }

    public RecordingSession get(String clientId) {
        return sessions.get(clientId);
    }

    public JsonNode awaitFrame(String clientId, String op, long timeoutMillis) throws Exception {
        RecordingSession s = sessions.get(clientId);
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            String payload = s.frames.poll(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (payload == null) {
                break;
            }
            JsonNode node = ProtocolJson.MAPPER.readTree(payload);
            if (op.equals(node.path("op").asText())) {
                return node;
            }
            s.other.add(node);
        }
        return null;
    }

    /** 排空并返回剩余帧（断言无多余帧用）。 */
    public List<JsonNode> drain(String clientId) throws Exception {
        RecordingSession s = sessions.get(clientId);
        List<JsonNode> all = new ArrayList<>(s.other);
        String payload;
        while ((payload = s.frames.poll(50, TimeUnit.MILLISECONDS)) != null) {
            all.add(ProtocolJson.MAPPER.readTree(payload));
        }
        return all;
    }

    public static final class RecordingSession implements ClientSession {
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final List<JsonNode> other = new ArrayList<>();
        volatile boolean open = true;

        @Override
        public void enqueue(OutboundFrame frame) {
            if (open) {
                frames.add(frame.payload());
            }
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        RecordingSessionFactory recordingSessionFactory() {
            return new RecordingSessionFactory();
        }
    }
}
