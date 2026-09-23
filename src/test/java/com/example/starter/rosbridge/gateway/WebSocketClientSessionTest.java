package com.example.starter.rosbridge.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.starter.rosbridge.protocol.ProtocolJson;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 单客户端发送会话测试：满队丢弃最旧未确认帧、RESUME_GAP 区间、
 * ACK 后帧不被当作未确认、慢客户端之间互不影响。
 */
class WebSocketClientSessionTest {

    private OutboundFrame topic(long cursor) {
        return OutboundFrame.topic("{\"op\":\"TOPIC\",\"topic\":\"/t\",\"cursor\":" + cursor + "}", "/t", cursor);
    }

    @Test
    void queueOverflowDropsOldestUnackedAndEmitsResumeGap() throws Exception {
        List<String> sent = new ArrayList<>();
        CountDownLatch mayProceed = new CountDownLatch(1);
        // 第一条阻塞写出，后续全部积压在有界队列中
        WebSocketClientSession session = new WebSocketClientSession("slow", payload -> {
            sent.add(payload);
            mayProceed.await(5, TimeUnit.SECONDS);
        }, 4, 0);

        session.enqueue(topic(1)); // 正在发送，阻塞
        Thread.sleep(50);
        for (long c = 2; c <= 6; c++) {
            session.enqueue(topic(c));
        }
        mayProceed.countDown();
        session.awaitDrained(2000);
        session.close();

        // 第一条已发出；2、3 被丢弃（GAP 标记占用一个槽位），产生一个 RESUME_GAP，其余继续投递
        List<JsonNode> gaps = findFrames(sent, "RESUME_GAP");
        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).path("fromCursor").asLong()).isEqualTo(2);
        assertThat(gaps.get(0).path("toCursor").asLong()).isEqualTo(3);
        List<Long> topicCursors = topicCursors(sent);
        assertThat(topicCursors).containsExactly(1L, 4L, 5L, 6L);
        // GAP 必须排在后续数据之前
        assertThat(sent.indexOf(write(gaps.get(0)))).isLessThan(sent.indexOf(topicPayload(4)));
    }

    @Test
    void consecutiveDropsMergeIntoSingleGapRange() throws Exception {
        List<String> sent = new ArrayList<>();
        CountDownLatch mayProceed = new CountDownLatch(1);
        WebSocketClientSession session = new WebSocketClientSession("slow2", payload -> {
            sent.add(payload);
            mayProceed.await(5, TimeUnit.SECONDS);
        }, 3, 0);

        session.enqueue(topic(1));
        Thread.sleep(50);
        for (long c = 2; c <= 8; c++) {
            session.enqueue(topic(c)); // 连续丢弃 2..6
        }
        mayProceed.countDown();
        session.awaitDrained(2000);
        session.close();

        List<JsonNode> gaps = findFrames(sent, "RESUME_GAP");
        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).path("fromCursor").asLong()).isEqualTo(2);
        assertThat(gaps.get(0).path("toCursor").asLong()).isEqualTo(6);
        assertThat(topicCursors(sent)).containsExactly(1L, 7L, 8L);
    }

    @Test
    void slowClientDoesNotAffectAnotherClient() throws Exception {
        CountDownLatch mayProceed = new CountDownLatch(1);
        List<String> fastSent = new ArrayList<>();
        List<String> slowSent = new ArrayList<>();
        WebSocketClientSession slow = new WebSocketClientSession("slowC", payload -> {
            slowSent.add(payload);
            mayProceed.await(5, TimeUnit.SECONDS);
        }, 4, 0);
        WebSocketClientSession fast = new WebSocketClientSession("fastC", fastSent::add, 128, 0);

        slow.enqueue(topic(1));
        Thread.sleep(50);
        for (long c = 2; c <= 20; c++) {
            slow.enqueue(topic(c));
            fast.enqueue(topic(c));
        }
        fast.awaitDrained(2000);
        // 快客户端 19 条全部收到，且没有任何 GAP
        assertThat(topicCursors(fastSent)).containsExactlyElementsOf(range(2, 20));
        assertThat(findFrames(fastSent, "RESUME_GAP")).isEmpty();

        mayProceed.countDown();
        slow.awaitDrained(2000);
        slow.close();
        fast.close();
        // 慢客户端出现了 GAP，证明丢弃只发生在它自己的会话
        assertThat(findFrames(slowSent, "RESUME_GAP")).isNotEmpty();
    }

    @Test
    void acknowledgedFramesAreRemovedWithoutGapWhenEvicted() throws Exception {
        List<String> sent = new ArrayList<>();
        CountDownLatch mayProceed = new CountDownLatch(1);
        WebSocketClientSession session = new WebSocketClientSession("ackc", payload -> {
            sent.add(payload);
            mayProceed.await(5, TimeUnit.SECONDS);
        }, 2, 0);

        session.enqueue(topic(1));
        Thread.sleep(50);
        session.enqueue(topic(2));
        session.enqueue(topic(3));
        session.acknowledge("/t", 2); // 2 已确认
        session.enqueue(topic(4));    // 满队：最旧帧 2 已确认，直接淘汰且无 GAP
        mayProceed.countDown();
        session.awaitDrained(2000);
        session.close();

        assertThat(findFrames(sent, "RESUME_GAP")).isEmpty();
        assertThat(topicCursors(sent)).containsExactly(1L, 3L, 4L);
    }

    private static List<Long> range(long from, long to) {
        List<Long> list = new ArrayList<>();
        for (long c = from; c <= to; c++) {
            list.add(c);
        }
        return list;
    }

    private static List<JsonNode> findFrames(List<String> sent, String op) {
        List<JsonNode> result = new ArrayList<>();
        for (String p : sent) {
            try {
                JsonNode n = ProtocolJson.MAPPER.readTree(p);
                if (op.equals(n.path("op").asText())) {
                    result.add(n);
                }
            } catch (Exception ignored) {
                // 忽略解析失败
            }
        }
        return result;
    }

    private static List<Long> topicCursors(List<String> sent) throws Exception {
        List<Long> cursors = new ArrayList<>();
        for (String p : sent) {
            JsonNode n = ProtocolJson.MAPPER.readTree(p);
            if ("TOPIC".equals(n.path("op").asText())) {
                cursors.add(n.path("cursor").asLong());
            }
        }
        return cursors;
    }

    private static String write(JsonNode node) throws Exception {
        return ProtocolJson.MAPPER.writeValueAsString(node);
    }

    private static String topicPayload(long cursor) {
        return "{\"op\":\"TOPIC\",\"topic\":\"/t\",\"cursor\":" + cursor + "}";
    }
}
