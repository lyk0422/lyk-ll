package com.example.starter.rosbag;

import com.example.starter.rosbag.dto.*;
import com.example.starter.rosbag.exception.BatchConflictException;
import com.example.starter.rosbag.exception.SequenceRegressionException;
import com.example.starter.rosbag.service.RosbagService;
import com.example.starter.rosbag.util.Checksums;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * rosbag 存储主流程、失败分支、恢复与并发幂等边界的 H2 集成测试。
 */
@SpringBootTest
@Import(ControllableTimeSource.class)
class RosbagServiceTest {

    @Autowired
    private RosbagService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM rb_message_index");
        jdbc.update("DELETE FROM rb_message");
        jdbc.update("DELETE FROM rb_segment");
        jdbc.update("DELETE FROM rb_replay");
        jdbc.update("DELETE FROM rb_message_quarantine");
    }

    private WriteResult append(String topic, long seq, long ts, String type, String payload, String batch) {
        return service.append(new WriteMessageRequest(topic, seq, ts, type, payload, batch));
    }

    private List<MessageView> query(String topic, Long fromNs, Long toNs, Long fromSeq, Long toSeq) {
        return service.query(new QueryRequest(topic, fromNs, toNs, fromSeq, toSeq));
    }

    @Test
    void appendPersistsMessageAndIndex() {
        WriteResult result = append("/pose", 0, 1000, "Pose", "p0", "b-0");

        assertThat(result.deduplicated()).isFalse();
        assertThat(result.segmentNo()).isZero();
        assertThat(result.payloadChecksum()).isEqualTo(Checksums.crc32("p0"));

        List<MessageView> views = query("/pose", null, null, null, null);
        assertThat(views).hasSize(1);
        assertThat(views.get(0).seq()).isZero();
        assertThat(views.get(0).payload()).isEqualTo("p0");

        Long indexRows = jdbc.queryForObject("SELECT COUNT(*) FROM rb_message_index", Long.class);
        assertThat(indexRows).isEqualTo(1L);
    }

    @Test
    void sameBatchRetryIsIdempotentAndDoesNotDuplicate() {
        append("/pose", 0, 1000, "Pose", "p0", "batch-x");

        WriteResult retry = append("/pose", 0, 1000, "Pose", "p0", "batch-x");

        assertThat(retry.deduplicated()).isTrue();
        Long messages = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rb_message WHERE topic = '/pose'", Long.class);
        Long indexRows = jdbc.queryForObject("SELECT COUNT(*) FROM rb_message_index", Long.class);
        assertThat(messages).isEqualTo(1L);
        assertThat(indexRows).isEqualTo(1L);
    }

    @Test
    void sameBatchWithDifferentParamsIsRejected() {
        append("/pose", 0, 1000, "Pose", "p0", "batch-y");

        assertThatThrownBy(() -> append("/pose", 0, 1000, "Pose", "different", "batch-y"))
                .isInstanceOf(BatchConflictException.class);
        assertThatThrownBy(() -> append("/pose", 1, 2000, "Pose", "p1", "batch-y"))
                .isInstanceOf(BatchConflictException.class);
    }

    @Test
    void sequenceCannotRegress() {
        append("/pose", 5, 1000, "Pose", "p5", "b-5");
        assertThatThrownBy(() -> append("/pose", 5, 2000, "Pose", "p5dup", "b-5dup"))
                .isInstanceOf(SequenceRegressionException.class);
        assertThatThrownBy(() -> append("/pose", 4, 2000, "Pose", "p4", "b-4"))
                .isInstanceOf(SequenceRegressionException.class);
    }

    @Test
    void multiTopicRangeQueriesFilterAndOrderCorrectly() {
        // /pose: seq 0..9，时间戳 1000..10000
        for (int i = 0; i < 10; i++) {
            append("/pose", i, (i + 1) * 1000L, "Pose", "pose" + i, "pose-b" + i);
        }
        // /scan: seq 0..4，时间戳 1500..5500
        for (int i = 0; i < 5; i++) {
            append("/scan", i, 1500L + i * 1000L, "Scan", "scan" + i, "scan-b" + i);
        }

        // Topic 隔离
        assertThat(query("/pose", null, null, null, null)).hasSize(10);
        assertThat(query("/scan", null, null, null, null)).hasSize(5);

        // 时间闭区间
        List<MessageView> inTime = query("/pose", 3000L, 7000L, null, null);
        assertThat(inTime).extracting(MessageView::seq).containsExactly(2L, 3L, 4L, 5L, 6L);

        // 序列闭区间
        List<MessageView> inSeq = query("/pose", null, null, 7L, 9L);
        assertThat(inSeq).extracting(MessageView::seq).containsExactly(7L, 8L, 9L);

        // 时间 + 序列组合
        List<MessageView> combined = query("/pose", 2000L, 6000L, 1L, 100L);
        assertThat(combined).extracting(MessageView::seq).containsExactly(1L, 2L, 3L, 4L, 5L);

        // 无交集
        assertThat(query("/pose", 100_000L, 200_000L, null, null)).isEmpty();
        assertThat(query("/pose", null, null, 100L, 200L)).isEmpty();

        // 原顺序：按 (timestamp_ns, seq)
        List<MessageView> ordered = query("/scan", null, null, null, null);
        assertThat(ordered).extracting(MessageView::timestampNs).isSorted();
    }

    @Test
    void corruptedMessageIsQuarantinedAndPrefixSurvivesRecovery() {
        for (int i = 0; i < 10; i++) {
            append("/imu", i, (i + 1) * 1000L, "Imu", "imu" + i, "imu-b" + i);
        }

        // 故障注入：seq=5 的消息负载被破坏（模拟写入中断的 torn write）
        assertThat(service.simulateCorruptedMessage("/imu", 5)).isTrue();

        // 恢复：保留 0..4 完整前缀，隔离 5..9
        RecoveryResult result = service.recover("/imu");
        assertThat(result.prefixMessagesKept()).isEqualTo(5L);
        assertThat(result.incompleteMessagesQuarantined()).isEqualTo(5L);
        assertThat(result.indexRowsRebuilt()).isEqualTo(5L);

        List<MessageView> survivors = query("/imu", null, null, null, null);
        assertThat(survivors).extracting(MessageView::seq).containsExactly(0L, 1L, 2L, 3L, 4L);

        // 隔离表留有现场
        Long quarantinedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rb_message_quarantine WHERE topic = '/imu'", Long.class);
        assertThat(quarantinedRows).isEqualTo(5L);
        List<Long> quarantinedSeqs = jdbc.queryForList(
                "SELECT seq FROM rb_message_quarantine WHERE topic = '/imu' ORDER BY seq",
                Long.class);
        assertThat(quarantinedSeqs).containsExactly(5L, 6L, 7L, 8L, 9L);

        // 主表只剩 5 条有效消息
        Long validRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rb_message WHERE topic = '/imu' AND valid = TRUE", Long.class);
        assertThat(validRows).isEqualTo(5L);

        // 释放的 seq 与批次号可重新写入，且查询与正常索引一致
        append("/imu", 5, 6000, "Imu", "imu5-repaired", "imu-b5");
        List<MessageView> afterRewrite = query("/imu", null, null, 4L, 5L);
        assertThat(afterRewrite).extracting(MessageView::seq).containsExactly(4L, 5L);
        assertThat(afterRewrite.get(1).payload()).isEqualTo("imu5-repaired");
    }

    @Test
    void corruptedIndexIsRebuiltAndQueryMatchesNormalState() {
        for (int i = 0; i < 10; i++) {
            append("/lidar", i, (i + 1) * 1000L, "Lidar", "l" + i, "lidar-b" + i);
        }
        assertThat(query("/lidar", null, null, null, null)).hasSize(10);

        // 故障注入：删除 seq=3 的索引行，查询漏数据
        assertThat(service.simulateCorruptedIndex("/lidar", 3)).isEqualTo(1);
        assertThat(query("/lidar", null, null, null, null)).hasSize(9);

        RecoveryResult result = service.recover("/lidar");
        assertThat(result.incompleteMessagesQuarantined()).isZero();
        assertThat(result.indexRowsRebuilt()).isEqualTo(10L);

        List<MessageView> restored = query("/lidar", null, null, null, null);
        assertThat(restored).hasSize(10);
        assertThat(restored).extracting(MessageView::seq).containsExactly(
                0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
    }

    @Test
    void segmentChecksumIsMaintainedAndRecomputedAfterRecovery() {
        long expected = 0L;
        for (int i = 0; i < 10; i++) {
            String payload = "c" + i;
            append("/chk", i, (i + 1) * 1000L, "Chk", payload, "chk-b" + i);
            expected = Checksums.foldMessage(expected, "/chk", i, (i + 1) * 1000L,
                    "Chk", Checksums.crc32(payload));
        }
        List<SegmentView> before = service.segments("/chk");
        assertThat(before).hasSize(1);
        assertThat(before.get(0).segmentChecksum()).isEqualTo(expected);
        assertThat(before.get(0).messageCount()).isEqualTo(10L);

        // 破坏 seq=7 后恢复，段校验应按前缀 0..6 重算
        service.simulateCorruptedMessage("/chk", 7);
        service.recover("/chk");

        long prefixChecksum = 0L;
        for (int i = 0; i < 7; i++) {
            prefixChecksum = Checksums.foldMessage(prefixChecksum, "/chk", i, (i + 1) * 1000L,
                    "Chk", Checksums.crc32("c" + i));
        }
        List<SegmentView> after = service.segments("/chk");
        SegmentView openSegment = after.stream()
                .filter(s -> "OPEN".equals(s.status()))
                .findFirst()
                .orElseThrow();
        assertThat(openSegment.messageCount()).isEqualTo(7L);
        assertThat(openSegment.segmentChecksum()).isEqualTo(prefixChecksum);
        assertThat(openSegment.startSeq()).isZero();
        assertThat(openSegment.endSeq()).isEqualTo(6L);
        assertThat(openSegment.startNs()).isEqualTo(1000L);
        assertThat(openSegment.endNs()).isEqualTo(7000L);
    }

    @Test
    void replayIsIdempotentAndReturnsMessagesInOriginalOrder() {
        for (int i = 0; i < 6; i++) {
            append("/rpl", i, (i + 1) * 1000L, "Rpl", "r" + i, "rpl-b" + i);
        }

        ReplayRequest request = new ReplayRequest("replay-1", "/rpl", 2000L, 4000L);
        ReplayResult first = service.replay(request);
        ReplayResult second = service.replay(request);

        assertThat(first.deduplicated()).isFalse();
        assertThat(first.messageCount()).isEqualTo(3L);
        assertThat(first.messages()).extracting(MessageView::seq).containsExactly(1L, 2L, 3L);

        assertThat(second.deduplicated()).isTrue();
        assertThat(second.messageCount()).isEqualTo(3L);
        assertThat(second.messages()).extracting(MessageView::seq).containsExactly(1L, 2L, 3L);

        Long replayRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rb_replay WHERE replay_key = 'replay-1'", Long.class);
        assertThat(replayRows).isEqualTo(1L);
    }

    @Test
    void concurrentAppendsToDistinctTopicsAllPersist() throws Exception {
        int threads = 8;
        int perThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int idx = t;
            futures.add(pool.submit(() -> {
                start.await();
                String topic = "/concurrent-" + idx;
                for (int i = 0; i < perThread; i++) {
                    append(topic, i, (i + 1) * 1000L, "M", "m" + i, topic + "-b" + i);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        for (int t = 0; t < threads; t++) {
            assertThat(query("/concurrent-" + t, null, null, null, null)).hasSize(perThread);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rb_message WHERE topic LIKE '/concurrent-%'", Long.class);
        assertThat(total).isEqualTo((long) threads * perThread);
    }

    @Test
    void concurrentSameBatchWritesExactlyOneMessage() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                return append("/idem", 0, 1000, "M", "same", "only-batch").deduplicated();
            }));
        }
        start.countDown();
        long dedupWins = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(30, TimeUnit.SECONDS)) {
                dedupWins++;
            }
        }
        pool.shutdown();

        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rb_message WHERE topic = '/idem' AND batch_no = 'only-batch'",
                Long.class);
        assertThat(rows).isEqualTo(1L);
        assertThat(dedupWins).isEqualTo(threads - 1L);
    }
}
