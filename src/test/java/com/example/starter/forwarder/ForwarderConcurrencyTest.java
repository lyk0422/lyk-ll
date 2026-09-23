package com.example.starter.forwarder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.forwarder.TestSupport.MutableClock;
import com.example.starter.forwarder.TestSupport.RecordingUpstream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 并发与幂等边界测试：真实并发写入同一 dedupKey、并发重复 ACK。
 */
class ForwarderConcurrencyTest {

    private static final int THREADS = 8;

    @TempDir
    Path tempDir;

    private MessageRepository messages;
    private AuditRepository audit;
    private NetworkState network;
    private ForwarderService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = TestSupport.jdbcForFileDb(tempDir, "concurrency-test");
        messages = new MessageRepository(jdbc);
        audit = new AuditRepository(jdbc);
        MutableClock clock = new MutableClock(
                Instant.parse("2026-09-23T08:00:00Z"), ZoneId.of("Asia/Shanghai"));
        ForwarderProperties properties = TestSupport.defaultProperties();
        network = new NetworkState(properties);
        service = TestSupport.newService(jdbc, clock, new RecordingUpstream(), network, properties);
    }

    @Test
    void concurrentIngestWithSameDedupKeyKeepsExactlyOneRecord() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<IngestOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            final long seq = i + 1;
            futures.add(pool.submit(() -> {
                gate.await();
                return service.ingest(TestSupport.msg("dev-1", "scan", seq, 1, "same-key"));
            }));
        }
        gate.countDown();
        List<IngestOutcome> outcomes = new ArrayList<>();
        for (Future<IngestOutcome> future : futures) {
            outcomes.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, messages.countUnfinished());
        assertEquals(1, outcomes.stream().filter(o -> o == IngestOutcome.QUEUED).count());
        assertEquals(THREADS - 1, outcomes.stream().filter(o -> o == IngestOutcome.DUPLICATE).count());
    }

    @Test
    void concurrentDuplicateAcksConfirmExactlyOnce() throws Exception {
        service.ingest(TestSupport.msg("dev-1", "scan", 1, 1, "k1"));
        network.setOnline(true);
        service.drain();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<AckOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return service.ack("k1");
            }));
        }
        gate.countDown();
        List<AckOutcome> outcomes = new ArrayList<>();
        for (Future<AckOutcome> future : futures) {
            outcomes.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // 恰好一次首次确认，其余均为重复 ACK，计数不变
        assertEquals(1, outcomes.stream().filter(o -> o == AckOutcome.ACKED).count());
        assertEquals(THREADS - 1, outcomes.stream().filter(o -> o == AckOutcome.DUPLICATE).count());
        assertEquals(1, messages.countByStatus(MessageStatus.ACKED));
        assertEquals(1, audit.countByType(AuditEventType.ACKED));
    }
}
