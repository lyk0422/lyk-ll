package com.example.starter.forwarder.service;

import com.example.starter.forwarder.config.ForwarderProperties;
import com.example.starter.forwarder.model.AuditEventType;
import com.example.starter.forwarder.model.DeviceMessage;
import com.example.starter.forwarder.repo.AuditRepository;
import com.example.starter.forwarder.repo.MessageRepository;
import com.example.starter.forwarder.support.Clock;
import com.example.starter.forwarder.testsupport.MutableClock;
import com.example.starter.forwarder.testsupport.RecordingUpstreamAdapter;
import com.example.starter.forwarder.upstream.UpstreamAdapter;
import com.example.starter.forwarder.web.AckRequest;
import com.example.starter.forwarder.web.IngestRequest;
import com.example.starter.forwarder.web.IngestResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基于嵌入式 H2 文件库（MySQL 兼容模式，独立临时目录）的核心业务测试，
 * 覆盖断网策略、去重、补发顺序、重复/乱序 ACK、超时重试、容量耗尽与驱逐审计、并发边界。
 */
@SpringBootTest
class ForwarderServiceH2Test {

    static Path tempDir;

    @DynamicPropertySource
    static void registerH2Dir(DynamicPropertyRegistry registry) throws IOException {
        tempDir = Files.createTempDirectory("forwarder-h2-main-");
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:file:" + tempDir.resolve("db")
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        Clock mutableClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        UpstreamAdapter recordingUpstream() {
            return new RecordingUpstreamAdapter();
        }
    }

    @Autowired
    private ForwarderService service;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private AuditRepository auditRepository;
    @Autowired
    private ForwarderProperties properties;
    @Autowired
    private MutableClock clock;
    @Autowired
    private RecordingUpstreamAdapter upstream;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DataSource dataSource;

    private int savedCapacity;

    @BeforeEach
    void cleanAndOffline() throws IOException {
        jdbcTemplate.update("DELETE FROM device_message");
        jdbcTemplate.update("DELETE FROM audit_log");
        upstream.reset();
        savedCapacity = properties.getCapacity();
        properties.setCapacity(10000);
        service.setOnline(false);
    }

    @AfterEach
    void restoreCapacity() {
        properties.setCapacity(savedCapacity);
        service.setOnline(false);
    }

    private IngestRequest msg(String deviceId, String topic, long seq, int priority, String dedupKey) {
        IngestRequest request = new IngestRequest();
        request.setDeviceId(deviceId);
        request.setTopic(topic);
        request.setSeq(seq);
        request.setPriority(priority);
        request.setDedupKey(dedupKey);
        request.setPayload("payload-" + dedupKey);
        return request;
    }

    @Test
    void offlinePoliciesDropLatestFifoBounded() {
        // DROP：离线直接拒绝且不入库，但必须留审计
        IngestResponse dropped = service.ingest(msg("dev1", "/sensor/drop", 1, 5, "k-drop"));
        assertFalse(dropped.accepted());
        assertEquals(0, messageRepository.activeCount());
        assertEquals(1, auditRepository.countByType(AuditEventType.DROP_POLICY));

        // LATEST：两条只保留最新一条，旧的写覆盖审计
        service.ingest(msg("dev1", "/sensor/latest", 1, 5, "k-latest-1"));
        service.ingest(msg("dev1", "/sensor/latest", 2, 5, "k-latest-2"));
        List<DeviceMessage> latest = messageRepository.findPendingOrdered(10);
        assertEquals(1, latest.size());
        assertEquals("k-latest-2", latest.get(0).getDedupKey());
        assertEquals(1, auditRepository.countByType(AuditEventType.OVERWRITE_LATEST));
        assertEquals("k-latest-1",
                auditRepository.findDedupKeysByType(AuditEventType.OVERWRITE_LATEST).get(0));

        // FIFO：完整顺序保留
        service.ingest(msg("dev1", "/sensor/fifo", 1, 5, "k-fifo-1"));
        service.ingest(msg("dev1", "/sensor/fifo", 2, 5, "k-fifo-2"));
        service.ingest(msg("dev1", "/sensor/fifo", 3, 5, "k-fifo-3"));
        assertEquals(3, messageRepository.countPendingByTopic("/sensor/fifo"));

        // BOUNDED(3)：第四条挤出最旧一条
        for (int i = 1; i <= 4; i++) {
            service.ingest(msg("dev1", "/sensor/bounded", i, 5, "k-bounded-" + i));
        }
        List<DeviceMessage> bounded = messageRepository.findPendingOrdered(10).stream()
                .filter(m -> "/sensor/bounded".equals(m.getTopic())).toList();
        assertEquals(3, bounded.size());
        assertEquals(List.of("k-bounded-2", "k-bounded-3", "k-bounded-4"),
                bounded.stream().map(DeviceMessage::getDedupKey).toList());
        assertEquals(1, auditRepository.countByType(AuditEventType.EVICT_BOUNDED));
    }

    @Test
    void duplicateDedupKeyIsIgnored() {
        service.ingest(msg("dev1", "/sensor/fifo", 1, 5, "k-dup"));
        IngestResponse second = service.ingest(msg("dev1", "/sensor/fifo", 2, 5, "k-dup"));
        assertTrue(second.accepted());
        assertTrue(second.duplicated());
        assertEquals(1, messageRepository.activeCount());
        assertEquals(1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM device_message WHERE status='PENDING'", Long.class).intValue());
    }

    @Test
    void dispatchOrderIsPriorityDescThenDeviceSeqAsc() {
        service.ingest(msg("devA", "/t", 10, 1, "a10"));
        service.ingest(msg("devA", "/t", 11, 9, "a11"));
        service.ingest(msg("devB", "/t", 1, 5, "b1"));
        service.ingest(msg("devB", "/t", 2, 5, "b2"));

        service.setOnline(true);
        int sent = service.dispatchOnce();
        assertEquals(4, sent);
        assertEquals(List.of("a11", "b1", "b2", "a10"), upstream.getSentKeys());
    }

    @Test
    void duplicateAndOutOfOrderAckDoNotChangeCounts() {
        service.ingest(msg("devA", "/t", 1, 5, "ack1"));
        service.ingest(msg("devA", "/t", 2, 5, "ack2"));
        service.ingest(msg("devA", "/t", 3, 5, "ack3"));
        service.setOnline(true);
        service.dispatchOnce();

        // 先确认较大序号 ack3，不得误删较小序号
        assertTrue(service.acknowledge(ack("ack3")).matched());
        // 重复 ACK 两次：不改变计数
        assertFalse(service.acknowledge(ack("ack3")).matched());
        assertFalse(service.acknowledge(ack("ack3")).matched());
        // 未发送过的 dedupKey 的 ACK 视为乱序/无效
        assertFalse(service.acknowledge(ack("never-sent")).matched());

        assertEquals(1, auditRepository.countByType(AuditEventType.ACK));
        assertEquals(3, auditRepository.countByType(AuditEventType.ACK_DUPLICATE));

        DeviceMessage acked = messageRepository.findByDedupKey("ack3");
        assertEquals(com.example.starter.forwarder.model.MessageStatus.ACKNOWLEDGED, acked.getStatus());
        assertEquals(com.example.starter.forwarder.model.MessageStatus.IN_FLIGHT,
                messageRepository.findByDedupKey("ack1").getStatus());
        assertEquals(com.example.starter.forwarder.model.MessageStatus.IN_FLIGHT,
                messageRepository.findByDedupKey("ack2").getStatus());
        // 已确认记录不再占用容量，且较小序号仍在
        assertEquals(2, messageRepository.activeCount());

        // 补发一轮不会重发已确认消息
        int sentAgain = service.dispatchOnce();
        assertEquals(0, sentAgain);
    }

    @Test
    void timedOutInFlightGoesBackToPendingAndRetries() {
        service.ingest(msg("devA", "/t", 1, 5, "timeout1"));
        service.setOnline(true);
        service.dispatchOnce();
        assertEquals(1, upstream.sendCountOf("timeout1"));

        // 未到超时：仍在途，不会重发
        clock.advance(properties.getAckTimeoutMs() - 10);
        service.dispatchOnce();
        assertEquals(1, upstream.sendCountOf("timeout1"));

        // 超过 ACK 超时：重置待发并再次补发
        clock.advance(20);
        service.dispatchOnce();
        assertEquals(2, upstream.sendCountOf("timeout1"));
        assertEquals(1, auditRepository.countByType(AuditEventType.TIMEOUT_RETRY));
    }

    @Test
    void capacityEvictionPrefersLowestPriorityOldestNonInFlight() {
        properties.setCapacity(2);
        service.ingest(msg("devA", "/t", 1, 1, "low"));
        service.ingest(msg("devA", "/t", 2, 9, "high"));
        service.ingest(msg("devA", "/t", 3, 5, "mid"));

        // 最低优先级最旧的 low 被驱逐，high/mid 保留
        assertEquals(2, messageRepository.activeCount());
        assertEquals(List.of("low"),
                auditRepository.findDedupKeysByType(AuditEventType.EVICT_CAPACITY));

        // 占满且全部在途时无法驱逐，拒绝新消息并记录原因
        jdbcTemplate.update("DELETE FROM device_message");
        jdbcTemplate.update("DELETE FROM audit_log");
        service.ingest(msg("devA", "/t", 1, 5, "f1"));
        service.ingest(msg("devA", "/t", 2, 5, "f2"));
        service.setOnline(true);
        service.dispatchOnce();
        IngestResponse rejected = service.ingest(msg("devA", "/t", 3, 5, "f3"));
        assertFalse(rejected.accepted());
        assertEquals(2, messageRepository.activeCount());
        assertEquals(1, auditRepository.countByType(AuditEventType.REJECT_CAPACITY));
    }

    @Test
    void uniqueConstraintTransactionRollsBackOnDuplicate() {
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        long before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM device_message", Long.class);

        boolean rolledBack;
        try {
            tx.executeWithoutResult(status -> {
                jdbcTemplate.update("INSERT INTO device_message (message_key, device_id, topic, seq, "
                        + "priority, dedup_key, payload, status, received_at, dispatched_count) "
                        + "VALUES ('m1','d','/t',1,1,'uk','p','PENDING',1,0)");
                jdbcTemplate.update("INSERT INTO device_message (message_key, device_id, topic, seq, "
                        + "priority, dedup_key, payload, status, received_at, dispatched_count) "
                        + "VALUES ('m2','d','/t',2,1,'uk','p','PENDING',1,0)");
            });
            rolledBack = false;
        } catch (DuplicateKeyException e) {
            rolledBack = true;
        }
        assertTrue(rolledBack, "重复 dedup_key 必须触发唯一约束异常");
        long after = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM device_message", Long.class);
        assertEquals(before, after, "事务必须整体回滚，第一条插入不得残留");
    }

    @Test
    void concurrentDispatchSendsEachMessageExactlyOnce() throws Exception {
        int total = 50;
        for (int i = 0; i < total; i++) {
            service.ingest(msg("dev" + (i % 4), "/t", i, i % 7, "c" + i));
        }
        service.setOnline(true);

        int threads = 8;
        ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    service.dispatchOnce();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            }));
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertEquals(total, upstream.getSentKeys().size(), "每条消息只能被发送一次");
        assertEquals(total,
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM device_message WHERE status='IN_FLIGHT'",
                        Long.class).intValue());
    }

    @Test
    void concurrentIngestSameDedupKeyKeepsExactlyOne() throws Exception {
        int threads = 12;
        String key = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<IngestResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            long seq = i;
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.ingest(msg("devX", "/sensor/fifo", seq, 5, key));
            }));
        }
        int accepted = 0;
        int duplicated = 0;
        for (Future<IngestResponse> f : futures) {
            IngestResponse response = f.get(30, TimeUnit.SECONDS);
            if (response.duplicated()) {
                duplicated++;
            } else {
                accepted++;
            }
        }
        pool.shutdownNow();
        assertEquals(1, accepted);
        assertEquals(threads - 1, duplicated);
        assertEquals(1,
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM device_message WHERE dedup_key=?",
                        Long.class, key).intValue());
    }

    private AckRequest ack(String dedupKey) {
        AckRequest request = new AckRequest();
        request.setDedupKey(dedupKey);
        return request;
    }
}
