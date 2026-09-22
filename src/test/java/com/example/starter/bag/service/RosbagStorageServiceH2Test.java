package com.example.starter.bag.service;

import com.example.starter.bag.model.SegmentStatus;
import com.example.starter.bag.repository.QuarantineRepository;
import com.example.starter.bag.web.BagExceptionHandler.ConflictException;
import com.example.starter.bag.web.dto.CorruptSegmentRequest;
import com.example.starter.bag.web.dto.CorruptSegmentRequest.FaultMode;
import com.example.starter.bag.web.dto.MessageView;
import com.example.starter.bag.web.dto.PlayRequest;
import com.example.starter.bag.web.dto.PlayResult;
import com.example.starter.bag.web.dto.RecoveryResult;
import com.example.starter.bag.web.dto.SegmentRecoveryReport;
import com.example.starter.bag.web.dto.SegmentView;
import com.example.starter.bag.web.dto.WriteMessageRequest;
import com.example.starter.bag.web.dto.WriteResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rosbag 存储核心能力 H2 集成测试：分段追加、批次幂等、序列号单调、索引化范围查询、
 * 写入中断/索引损坏恢复、段级校验与重复回放，以及同 Topic 真实并发写入边界。
 */
@SpringBootTest
class RosbagStorageServiceH2Test {

    /** 测试配置 bag.segment.max-size=3 */
    private static final int MAX_SIZE = 3;

    @Autowired
    private RosbagStorageService service;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private QuarantineRepository quarantineRepository;

    @BeforeEach
    void cleanTables() {
        // 每个场景独立数据，避免顺序依赖
        jdbc.update("DELETE FROM bag_msg_index", new MapSqlParameterSource());
        jdbc.update("DELETE FROM bag_message", new MapSqlParameterSource());
        jdbc.update("DELETE FROM bag_quarantine", new MapSqlParameterSource());
        jdbc.update("DELETE FROM bag_segment", new MapSqlParameterSource());
        jdbc.update("DELETE FROM bag_play_item", new MapSqlParameterSource());
        jdbc.update("DELETE FROM bag_play", new MapSqlParameterSource());
        jdbc.update("DELETE FROM bag_topic_lock", new MapSqlParameterSource());
    }

    private String uniqueTopic() {
        return "topic-" + UUID.randomUUID();
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private WriteResult write(String topic, long seq, long timestampNs, String payloadText, String batchNo) {
        return service.write(new WriteMessageRequest(topic, timestampNs, seq, "test/Type",
                payloadText == null ? null : b64(payloadText), batchNo));
    }

    private WriteResult write(String topic, long seq) {
        return write(topic, seq, 1000L + seq, "payload-" + seq, "batch-" + topic + "-" + seq);
    }

    // ---- 写入、分段与边界 ------------------------------------------------

    @Test
    void writeRollsSegmentsAndMaintainsBoundariesAndStatus() {
        String topic = uniqueTopic();
        for (long seq = 0; seq < 7; seq++) {
            write(topic, seq);
        }

        List<SegmentView> segments = service.segments(topic);
        assertEquals(3, segments.size());
        assertEquals(0, segments.get(0).segIndex());
        assertEquals(MAX_SIZE, segments.get(0).messageCount());
        assertEquals(SegmentStatus.SEALED, segments.get(0).status());
        assertEquals(1000L, segments.get(0).startNs());
        assertEquals(1002L, segments.get(0).endNs());
        assertEquals(0L, segments.get(0).startSeq());
        assertEquals(2L, segments.get(0).endSeq());
        assertNotNull(segments.get(0).checksum());

        assertEquals(1, segments.get(1).segIndex());
        assertEquals(MAX_SIZE, segments.get(1).messageCount());
        assertEquals(SegmentStatus.SEALED, segments.get(1).status());

        assertEquals(2, segments.get(2).segIndex());
        assertEquals(1, segments.get(2).messageCount());
        assertEquals(SegmentStatus.OPEN, segments.get(2).status());
        assertEquals(6L, segments.get(2).startSeq());
        assertEquals(6L, segments.get(2).endSeq());

        // 共 7 条消息、7 行索引
        assertEquals(7L, jdbc.queryForObject("SELECT COUNT(*) FROM bag_message WHERE topic = :t",
                new MapSqlParameterSource("t", topic), Long.class));
        assertEquals(7L, jdbc.queryForObject("SELECT COUNT(*) FROM bag_msg_index WHERE topic = :t",
                new MapSqlParameterSource("t", topic), Long.class));
    }

    @Test
    void duplicateBatchWithSameParamsIsIdempotent() {
        String topic = uniqueTopic();
        WriteResult first = write(topic, 0L, 1000L, "hello", "batch-x");
        assertFalse(first.duplicate());

        WriteMessageRequest retry = new WriteMessageRequest(topic, 1000L, 0L, "test/Type", b64("hello"), "batch-x");
        WriteResult second = service.write(retry);

        assertTrue(second.duplicate());
        assertEquals(first.messageId(), second.messageId());
        assertEquals(first.segmentId(), second.segmentId());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM bag_message WHERE topic = :t",
                new MapSqlParameterSource("t", topic), Long.class));
    }

    @Test
    void sameBatchWithDifferentParamsIsRejected() {
        String topic = uniqueTopic();
        write(topic, 0L, 1000L, "hello", "batch-x");

        WriteMessageRequest tampered = new WriteMessageRequest(topic, 2000L, 0L, "test/Type",
                b64("hello"), "batch-x");
        ConflictException ex = assertThrows(ConflictException.class, () -> service.write(tampered));
        assertTrue(ex.getMessage().contains("批次号"));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM bag_message WHERE topic = :t",
                new MapSqlParameterSource("t", topic), Long.class));
    }

    @Test
    void seqMustNotGoBackwards() {
        String topic = uniqueTopic();
        write(topic, 5L);
        ConflictException ex = assertThrows(ConflictException.class, () -> write(topic, 4L));
        assertTrue(ex.getMessage().contains("序列号不得倒退"));
        // 不同批次但重复已占用序号同样拒绝
        assertThrows(ConflictException.class,
                () -> write(topic, 5L, 1005L, "dup", "another-batch"));
    }

    // ---- 索引化范围查询 --------------------------------------------------

    @Test
    void queryAcrossMultipleTopicsAndRangesUsesOrderAndFilters() {
        String a = uniqueTopic();
        String b = uniqueTopic();
        for (long seq = 0; seq < 5; seq++) {
            write(a, seq);
        }
        for (long seq = 0; seq < 4; seq++) {
            write(b, seq);
        }

        // 序列号范围跨两个分段
        List<MessageView> views = service.query(a, null, null, 1L, 4L);
        assertEquals(List.of(1L, 2L, 3L, 4L), views.stream().map(MessageView::seq).toList());
        assertTrue(views.stream().allMatch(v -> v.topic().equals(a)));

        // 时间范围 + 序列号范围组合
        List<MessageView> combined = service.query(a, 1002L, 1003L, 0L, 100L);
        assertEquals(List.of(2L, 3L), combined.stream().map(MessageView::seq).toList());

        // 只按时间下界
        List<MessageView> fromTs = service.query(b, 1002L, null, null, null);
        assertEquals(List.of(2L, 3L), fromTs.stream().map(MessageView::seq).toList());

        // 不命中
        assertEquals(List.of(), service.query(a, 9000L, 9999L, null, null));

        // 非法范围
        assertThrows(IllegalArgumentException.class, () -> service.query(a, 2000L, 1000L, null, null));
    }

    @Test
    void segmentTimeBoundsUseMinMaxWhenTimestampsNotMonotonic() {
        // 题干只要求序列号单调，纳秒时间戳可乱序：段0 三条时间戳 3000,1000,2000，
        // 段边界必须按 min/max 维护，否则分段裁剪会漏消息。
        String topic = uniqueTopic();
        write(topic, 0L, 3000L, "p0", "b0");
        write(topic, 1L, 1000L, "p1", "b1");
        write(topic, 2L, 2000L, "p2", "b2");
        write(topic, 3L, 500L, "p3", "b3");

        SegmentView seg0 = service.segments(topic).get(0);
        assertEquals(1000L, seg0.startNs());
        assertEquals(3000L, seg0.endNs());
        assertEquals(0L, seg0.startSeq());
        assertEquals(2L, seg0.endSeq());

        // 高时间窗只命中段0 内的 seq0
        assertEquals(List.of(0L),
                service.query(topic, 2500L, 4000L, null, null).stream()
                        .map(MessageView::seq).toList());
        // 低时间窗只命中段1
        assertEquals(List.of(3L),
                service.query(topic, 500L, 500L, null, null).stream()
                        .map(MessageView::seq).toList());
        // 跨越两段的时间窗按序列号原序返回
        assertEquals(List.of(1L, 2L, 3L),
                service.query(topic, 500L, 2000L, null, null).stream()
                        .map(MessageView::seq).toList());
    }

    // ---- 写入中断恢复 ----------------------------------------------------

    @Test
    void recoverKeepsVerifiedPrefixQuarantinesTailAndRebuildsIndex() {
        String topic = uniqueTopic();
        for (long seq = 0; seq < 5; seq++) {
            write(topic, seq);
        }
        // 段0: 0..2(SEALED)，段1: 3..4(OPEN)。向最新段注入 2 条未链入尾消息 5,6
        service.corrupt(new CorruptSegmentRequest(topic, 1, FaultMode.INCOMPLETE_TAIL, 2));

        List<SegmentView> damaged = service.segments(topic);
        assertEquals(SegmentStatus.DAMAGED, damaged.get(1).status());
        // DAMAGED 段不参与查询：段1 的 3,4 暂时不可见
        assertEquals(List.of(0L, 1L, 2L),
                service.query(topic, null, null, null, null).stream().map(MessageView::seq).toList());

        // 受损期间新写入被拒绝，必须先恢复；但既有批次仍可幂等重试
        assertThrows(ConflictException.class, () -> write(topic, 7L, 1007L, "p7", "b7"));
        WriteResult retryOfSeq0 = write(topic, 0L, 1000L, "payload-0", "batch-" + topic + "-0");
        assertTrue(retryOfSeq0.duplicate());

        RecoveryResult result = service.recover();
        assertEquals(1, result.segments().size());
        SegmentRecoveryReport report = result.segments().get(0);
        assertEquals(1, report.segIndex());
        assertEquals(2, report.keptCount());
        assertEquals(2, report.quarantinedCount());
        assertEquals(2, report.rebuiltIndexCount());
        assertTrue(report.prefixVerified());
        // 段1原有 3,4 共 2 条，kept=2 < maxSize=3，恢复后回到 OPEN
        assertEquals("OPEN", report.finalStatus());

        // 段边界按完整前缀重写
        SegmentView recovered = service.segments(topic).get(1);
        assertEquals(SegmentStatus.OPEN, recovered.status());
        assertEquals(2L, recovered.messageCount());
        assertEquals(3L, recovered.startSeq());
        assertEquals(4L, recovered.endSeq());
        assertEquals(1003L, recovered.startNs());
        assertEquals(1004L, recovered.endNs());
        assertNotNull(recovered.checksum());

        // 尾消息被物理隔离并保留现场
        assertEquals(2L, quarantineRepository.countBySegmentId(recovered.id()));
        var quarantined = quarantineRepository.findAll().stream()
                .filter(q -> q.topic().equals(topic)).map(q -> q.seq()).sorted().toList();
        assertEquals(List.of(5L, 6L), quarantined);

        // 索引重建后查询结果与正常索引一致：0..4 原序
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L),
                service.query(topic, null, null, null, null).stream().map(MessageView::seq).toList());
        assertEquals(5L, jdbc.queryForObject("SELECT COUNT(*) FROM bag_msg_index WHERE topic = :t",
                new MapSqlParameterSource("t", topic), Long.class));

        // 被隔离的序列号可重新写入，且写入后进入下一新段
        WriteResult rewritten = write(topic, 5L, 1005L, "payload-5", "batch-rewrite-5");
        assertFalse(rewritten.duplicate());
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L),
                service.query(topic, null, null, null, null).stream().map(MessageView::seq).toList());

        // 恢复幂等：再次恢复无受损段，不报错、不产生副作用
        RecoveryResult second = service.recover();
        assertTrue(second.segments().isEmpty());
    }

    @Test
    void recoverRebuildsDroppedIndex() {
        String topic = uniqueTopic();
        write(topic, 0L);
        write(topic, 1L);
        service.corrupt(new CorruptSegmentRequest(topic, 0, FaultMode.DROP_INDEX, null));

        assertEquals(0L, service.query(topic, null, null, null, null).size());
        RecoveryResult result = service.recover();
        assertEquals(2, result.segments().get(0).keptCount());
        assertEquals(0, result.segments().get(0).quarantinedCount());
        assertEquals(2, result.segments().get(0).rebuiltIndexCount());

        assertEquals(List.of(0L, 1L),
                service.query(topic, null, null, null, null).stream().map(MessageView::seq).toList());
    }

    @Test
    void recoverDetectsTamperedPayloadChecksumAndTruncatesPrefix() {
        String topic = uniqueTopic();
        for (long seq = 0; seq < 3; seq++) {
            write(topic, seq);
        }
        // 篡改段内第 2 条消息（seq=1）的负载校验值，模拟内容损坏；再标记段待恢复
        jdbc.update("UPDATE bag_message SET payload_checksum = 'deadbeef' WHERE topic = :t AND seq = 1",
                new MapSqlParameterSource("t", topic));
        service.corrupt(new CorruptSegmentRequest(topic, 0, FaultMode.MARK_DAMAGED, null));

        SegmentRecoveryReport report = service.recover().segments().get(0);
        // 链在 seq=1 处断裂：只保留 seq=0 一条可验证前缀，其余隔离
        assertEquals(1, report.keptCount());
        assertEquals(2, report.quarantinedCount());
        assertTrue(report.prefixVerified());

        SegmentView seg = service.segments(topic).get(0);
        assertEquals(1L, seg.messageCount());
        assertEquals(0L, seg.startSeq());
        assertEquals(0L, seg.endSeq());
        assertEquals(List.of(0L),
                service.query(topic, null, null, null, null).stream().map(MessageView::seq).toList());
    }

    @Test
    void corruptOnlyAllowedOnLatestSegment() {
        String topic = uniqueTopic();
        for (long seq = 0; seq < 4; seq++) {
            write(topic, seq);
        }
        // 段0 已封段，不允许注入故障
        assertThrows(ConflictException.class,
                () -> service.corrupt(new CorruptSegmentRequest(topic, 0, FaultMode.MARK_DAMAGED, null)));
        assertThrows(java.util.NoSuchElementException.class,
                () -> service.corrupt(new CorruptSegmentRequest(topic, 9, FaultMode.MARK_DAMAGED, null)));
    }

    // ---- 重复回放 --------------------------------------------------------

    @Test
    void replayIsOrderedChecksummedAndIdempotent() {
        String topic = uniqueTopic();
        for (long seq = 0; seq < 5; seq++) {
            write(topic, seq);
        }

        PlayRequest request = new PlayRequest("play-key-1", topic, null, null, 1L, 4L);
        PlayResult first = service.play(request);
        assertFalse(first.duplicated());
        assertEquals(4, first.messageCount());
        assertEquals(List.of(1L, 2L, 3L, 4L),
                first.messages().stream().map(MessageView::seq).toList());
        assertNotNull(first.replayChecksum());
        assertFalse(first.replayChecksum().isEmpty());

        // 手工按相同链式规则计算，校验回放顺序与内容
        String expected = "";
        for (long seq = 1; seq <= 4; seq++) {
            MessageView v = first.messages().get((int) (seq - 1));
            expected = Checksums.chain(expected, topic, seq, v.timestampNs(),
                    v.typeId(), v.payloadChecksum());
        }
        assertEquals(expected, first.replayChecksum());

        // 相同 playKey 重复请求：不重复创建记录，但消息仍按原序返回
        PlayResult second = service.play(request);
        assertTrue(second.duplicated());
        assertEquals(first.playId(), second.playId());
        assertEquals(first.replayChecksum(), second.replayChecksum());
        assertEquals(first.messages().stream().map(MessageView::seq).toList(),
                second.messages().stream().map(MessageView::seq).toList());

        assertEquals(1L, countPlay("play-key-1"));

        // 不同 playKey 创建独立记录
        PlayResult other = service.play(new PlayRequest("play-key-2", topic, null, null, null, null));
        assertFalse(other.duplicated());
        assertEquals(5, other.messageCount());
    }

    @Test
    void repeatedReplayIsImmutableAgainstLaterWritesAndConflictingParams() {
        String topic = uniqueTopic();
        for (long seq = 0; seq < 4; seq++) {
            write(topic, seq);
        }

        PlayRequest request = new PlayRequest("play-freeze", topic, null, null, 1L, 2L);
        PlayResult first = service.play(request);
        assertEquals(List.of(1L, 2L), first.messages().stream().map(MessageView::seq).toList());
        assertEquals(2, first.messageCount());

        // 首次回放后继续追加消息
        for (long seq = 4; seq < 8; seq++) {
            write(topic, seq);
        }

        // 相同 playKey 重复回放：仍返回首次快照的 2 条，而非当前范围命中的 1,2,5..
        PlayResult second = service.play(request);
        assertTrue(second.duplicated());
        assertEquals(first.playId(), second.playId());
        assertEquals(2, second.messages().size());
        assertEquals(List.of(1L, 2L), second.messages().stream().map(MessageView::seq).toList());
        assertEquals(first.replayChecksum(), second.replayChecksum());

        // 相同 playKey 但查询参数不一致 → 拒绝
        assertThrows(ConflictException.class,
                () -> service.play(new PlayRequest("play-freeze", topic, null, null, 1L, 3L)));
    }

    private long countPlay(String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM bag_play WHERE play_key = :k",
                new MapSqlParameterSource("k", key), Long.class);
    }

    // ---- 真实并发边界 ----------------------------------------------------

    @Test
    void concurrentWritesToDifferentTopicsAreIsolated() throws Exception {
        // 序号由客户端按序分配，同一 Topic 必须按序提交；跨 Topic 无此约束，
        // 该用例验证多 Topic 并发写入互不干扰、计数与分段正确。
        int threads = 8;
        int perTopic = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int t = 0; t < threads; t++) {
                final String topic = "concurrent-topic-" + t + "-" + UUID.randomUUID();
                futures.add(pool.submit(() -> {
                    start.await();
                    for (long seq = 0; seq < perTopic; seq++) {
                        WriteResult r = write(topic, seq, 1000L + seq, "p" + seq,
                                "batch-" + topic + "-" + seq);
                        assertFalse(r.duplicate());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                assertDoesNotThrow(() -> f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bag_message WHERE topic LIKE 'concurrent-topic-%'",
                new MapSqlParameterSource(), Long.class);
        assertEquals((long) threads * perTopic, total);
    }

    @Test
    void concurrentSameSeqOnOneTopicSucceedsExactlyOnce() throws Exception {
        // 同一 Topic、同一下一序号被多线程并发抢占：只能有一个成功，
        // 其余必须收到序列号冲突异常，数据库不得出现重复消息。
        String topic = uniqueTopic();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int t = 0; t < threads; t++) {
                final int idx = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        WriteResult r = write(topic, 0L, 1000L, "race", "race-batch-" + idx);
                        assertFalse(r.duplicate());
                        success.incrementAndGet();
                    } catch (ConflictException e) {
                        conflict.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                assertDoesNotThrow(() -> f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }

        assertEquals(1, success.get());
        assertEquals(threads - 1, conflict.get());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM bag_message WHERE topic = :t",
                new MapSqlParameterSource("t", topic), Long.class));
    }

    @Test
    void concurrentSameBatchRetryWritesExactlyOnce() throws Exception {
        String topic = uniqueTopic();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger duplicated = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    WriteResult r = service.write(new WriteMessageRequest(
                            topic, 1000L, 0L, "test/Type", b64("same"), "same-batch"));
                    if (r.duplicate()) {
                        duplicated.incrementAndGet();
                    } else {
                        created.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                assertDoesNotThrow(() -> f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }

        assertEquals(1, created.get());
        assertEquals(threads - 1, duplicated.get());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM bag_message WHERE topic = :t",
                new MapSqlParameterSource("t", topic), Long.class));
    }

    @Test
    void emptyPayloadRoundTripsAsEmptyBytes() {
        String topic = uniqueTopic();
        WriteResult r = service.write(new WriteMessageRequest(
                topic, 1000L, 0L, "test/Type", null, "batch-empty"));
        MessageView view = service.query(topic, null, null, null, null).get(0);
        assertEquals("", view.payloadBase64());
        assertEquals(Checksums.sha256(new byte[0]), view.payloadChecksum());
        assertEquals(r.messageId(), view.id());
    }
}
