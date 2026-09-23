package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.adapter.SyntheticUpstreamAdapter;
import com.example.starter.config.ForwarderProperties;
import com.example.starter.dto.IngestResult;
import com.example.starter.dto.MessageRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.starter.testsupport.TestClockConfiguration;

/**
 * 并发与幂等边界测试：同一 dedupKey 并发写入只保留一条；
 * 多线程并发补发借助行锁领取，消息不重不漏。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
class ForwarderConcurrencyTest {

    @Autowired
    private ForwarderService forwarderService;
    @Autowired
    private SyntheticUpstreamAdapter upstreamAdapter;
    @Autowired
    private ForwarderProperties properties;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM audit_record");
        jdbcTemplate.execute("DELETE FROM device_message");
        jdbcTemplate.execute("DELETE FROM topic_policy");
        upstreamAdapter.setOnline(true);
        upstreamAdapter.setAutoAck(false);
        upstreamAdapter.clearDeliveryLog();
        properties.setQueueCapacity(0);
    }

    @Test
    void concurrentIngestOfSameDedupKeyKeepsExactlyOneRecord() throws Exception {
        upstreamAdapter.setOnline(false);
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IngestResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return forwarderService.ingest(
                            message("dev1", "t-conc", 1, 0, "same-key"));
                }));
            }
            start.countDown();

            int accepted = 0;
            int duplicate = 0;
            for (Future<IngestResult> future : futures) {
                IngestResult result = future.get(30, TimeUnit.SECONDS);
                if (result.outcome().equals(IngestResult.ACCEPTED)) {
                    accepted++;
                } else if (result.outcome().equals(IngestResult.DUPLICATE)) {
                    duplicate++;
                }
            }
            assertThat(accepted).isEqualTo(1);
            assertThat(duplicate).isEqualTo(threads - 1);
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM device_message WHERE message_id = 'same-key'",
                    Integer.class);
            assertThat(count).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDispatchDeliversEveryMessageExactlyOnce() throws Exception {
        upstreamAdapter.setOnline(false);
        int total = 150;
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < total; i++) {
            String key = String.format("key-%03d", i);
            keys.add(key);
            forwarderService.ingest(message("dev1", "t-burst", i, i % 3, key));
        }

        upstreamAdapter.setOnline(true);
        int dispatchers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(dispatchers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < dispatchers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    // 反复补发直到本轮没有消息可投递。
                    while (forwarderService.dispatchOnce() > 0) {
                        // 继续下一轮。
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }

            List<String> delivered = upstreamAdapter.getDeliveredMessageIds();
            assertThat(delivered).hasSize(total);
            assertThat(new HashSet<>(delivered)).containsExactlyInAnyOrderElementsOf(keys);
            assertThat(forwarderService.snapshotPending()).isEmpty();
            Integer inflight = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM device_message WHERE status = 'INFLIGHT'",
                    Integer.class);
            assertThat(inflight).isEqualTo(total);
        } finally {
            pool.shutdownNow();
        }
    }

    private MessageRequest message(String deviceId, String topic, long seq, int priority, String key) {
        MessageRequest request = new MessageRequest();
        request.setDeviceId(deviceId);
        request.setTopic(topic);
        request.setSeq(seq);
        request.setPriority(priority);
        request.setDedupKey(key);
        request.setPayload("payload-" + key);
        return request;
    }
}
