package com.example.starter.rosbridge.persistence;

import com.example.starter.rosbridge.persistence.GatewayRepository.AcquireResult;
import com.example.starter.rosbridge.persistence.GatewayRepository.ActionGoalRow;
import com.example.starter.rosbridge.persistence.GatewayRepository.ClientSubRow;
import com.example.starter.rosbridge.persistence.GatewayRepository.ExpireResult;
import com.example.starter.rosbridge.persistence.GatewayRepository.MessageRow;
import com.example.starter.rosbridge.persistence.GatewayRepository.ReleaseResult;
import com.example.starter.rosbridge.persistence.GatewayRepository.ServiceCallInsert;
import com.example.starter.rosbridge.persistence.GatewayRepository.ServiceCallRow;
import com.example.starter.rosbridge.persistence.GatewayRepository.UpstreamRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GatewayRepository} 的真实 H2（MODE=MySQL）事务测试：
 * 覆盖共享订阅引用计数、并发 acquire 不丢更新、单调游标、
 * Service 完成/超时 CAS 幂等、Action 取消/结果条件更新。
 * 不使用 mock 或内存 Map 替代数据库边界。
 */
class GatewayRepositoryH2Test {

    private DriverManagerDataSource dataSource;
    private GatewayRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        // 每个测试类使用独立命名内存库，避免用例间状态串扰。
        String dbName = "rb_test_" + UUID.randomUUID().toString().replace("-", "");
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + dbName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        ScriptUtils.executeSqlScript(dataSource.getConnection(),
                new org.springframework.core.io.ClassPathResource("schema.sql"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        repository = new GatewayRepository(jdbcTemplate, txManager);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dataSource != null) {
            dataSource.getConnection().close();
        }
    }

    @Test
    void sameTopicTypeAndQosShareUpstreamAndRefCount() {
        AcquireResult a = repository.acquireSubscription("c1", "/t", "std_msgs/msg/String",
                "h1", "{}", 1L);
        AcquireResult b = repository.acquireSubscription("c2", "/t", "std_msgs/msg/String",
                "h1", "{}", 2L);
        assertEquals(a.upstreamId(), b.upstreamId(), "topic/类型/QoS 相同必须复用同一上游");
        assertTrue(a.upstreamCreated());
        assertFalse(b.upstreamCreated());
        UpstreamRow upstream = repository.findUpstream(a.upstreamId());
        assertEquals(2, upstream.refCount());
    }

    @Test
    void differentQosProducesDifferentUpstream() {
        AcquireResult a = repository.acquireSubscription("c1", "/t", "std_msgs/msg/String",
                "h1", "{}", 1L);
        AcquireResult b = repository.acquireSubscription("c2", "/t", "std_msgs/msg/String",
                "h2", "{\"depth\":10}", 2L);
        assertFalse(a.upstreamId() == b.upstreamId(), "QoS 不同不得复用上游");
    }

    @Test
    void duplicateSubscribeIsIdempotentAndDoesNotInflateRefCount() {
        repository.acquireSubscription("c1", "/t", "T", "h", "{}", 1L);
        AcquireResult again = repository.acquireSubscription("c1", "/t", "T", "h", "{}", 2L);
        assertFalse(again.subscriptionActivated(), "重复订阅不应再次激活");
        assertEquals(1, repository.findUpstream(again.upstreamId()).refCount());
    }

    @Test
    void onlyLastUnsubscribeReleasesUpstream() {
        AcquireResult a = repository.acquireSubscription("c1", "/t", "T", "h", "{}", 1L);
        repository.acquireSubscription("c2", "/t", "T", "h", "{}", 2L);
        long id = a.upstreamId();

        ReleaseResult first = repository.releaseSubscription("c1", id);
        assertTrue(first.deactivated());
        assertEquals(1, first.refCountAfter());
        assertFalse(first.released(), "仍有一个客户端订阅，不得释放上游");

        ReleaseResult second = repository.releaseSubscription("c2", id);
        assertEquals(0, second.refCountAfter());
        assertTrue(second.released(), "最后一个客户端取消后才可释放");

        ReleaseResult duplicate = repository.releaseSubscription("c2", id);
        assertFalse(duplicate.deactivated(), "重复取消必须幂等");
    }

    @Test
    void concurrentAcquireByDistinctClientsNeverLosesRefCount() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        List<AcquireResult> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String clientId = "cc" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    AcquireResult r = repository.acquireSubscription(
                            clientId, "/concurrent", "T", "h", "{}", 1L);
                    synchronized (results) {
                        results.add(r);
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, failures.get());

        long upstreamId = results.get(0).upstreamId();
        assertTrue(results.stream().allMatch(r -> r.upstreamId() == upstreamId),
                "并发客户端必须共享同一上游");
        assertEquals(threads, repository.findUpstream(upstreamId).refCount(),
                "并发 acquire 引用计数不得丢失更新");
        assertEquals(1, results.stream().filter(AcquireResult::upstreamCreated).count(),
                "只能有一个线程真正创建上游");
    }

    @Test
    void cursorIsMonotonicAndReplayReturnsOnlyUnackedMessages() {
        AcquireResult a = repository.acquireSubscription("c1", "/t", "T", "h", "{}", 1L);
        long id = a.upstreamId();
        long c1 = repository.appendMessage(id, "/t", "T", "{\"v\":1}", 1L, 4096);
        long c2 = repository.appendMessage(id, "/t", "T", "{\"v\":2}", 2L, 4096);
        long c3 = repository.appendMessage(id, "/t", "T", "{\"v\":3}", 3L, 4096);
        assertEquals(1L, c1);
        assertEquals(2L, c2);
        assertEquals(3L, c3, "游标必须严格单调递增");

        List<MessageRow> afterAck1 = repository.findMessagesAfter(id, c1, 100);
        assertEquals(List.of(c2, c3), afterAck1.stream().map(MessageRow::cursor).toList(),
                "只能取到大于确认游标的消息");

        repository.advanceAck("c1", c2);
        ClientSubRow sub = repository.findActiveSubscriptions("c1").get(0);
        assertEquals(c2, sub.ackCursor());
        List<MessageRow> afterAck2 = repository.findMessagesAfter(id, sub.ackCursor(), 100);
        assertEquals(List.of(c3), afterAck2.stream().map(MessageRow::cursor).toList());
    }

    @Test
    void cursorIsGlobalMonotonicAcrossUpstreams() {
        AcquireResult a = repository.acquireSubscription("c1", "/t1", "T", "h1", "{}", 1L);
        AcquireResult b = repository.acquireSubscription("c2", "/t2", "T", "h2", "{}", 1L);
        long first = repository.appendMessage(a.upstreamId(), "/t1", "T", "{}", 1L, 4096);
        long second = repository.appendMessage(b.upstreamId(), "/t2", "T", "{}", 2L, 4096);
        assertTrue(second > first, "不同上游共享全局单调游标");
    }

    @Test
    void expireSubscriptionsDecrementsAndReportsReleased() {
        AcquireResult a = repository.acquireSubscription("c1", "/t", "T", "h", "{}", 1L);
        repository.acquireSubscription("c2", "/t", "T", "h", "{}", 2L);
        ExpireResult result = repository.expireClientSubscriptions("c1");
        assertEquals(List.of(a.upstreamId()), result.touchedUpstreamIds());
        assertTrue(result.releasedUpstreamIds().isEmpty(), "c2 仍订阅，上游不应释放");
        assertEquals(1, repository.findUpstream(a.upstreamId()).refCount());
    }

    @Test
    void serviceCompletionAndTimeoutAreIdempotentCas() {
        ServiceCallInsert insert = repository.insertServiceCallIfAbsent(
                "r1", "c1", "/svc", 1L);
        assertTrue(insert.inserted());

        // 迟到结果与超时竞争：只有一个 CAS 获胜。
        boolean finished = repository.completeServiceCall("r1", "OK", null, null,
                "{\"ok\":true}", 2L);
        boolean timedOut = repository.completeServiceCall("r1", "TIMED_OUT", "TIMEOUT",
                "Service 调用超时", null, 3L);
        assertTrue(finished);
        assertFalse(timedOut, "已完成的请求不得再被超时终结");

        ServiceCallRow row = repository.findServiceCall("r1");
        assertEquals("OK", row.status());
        assertEquals(2L, row.completedAt(), "迟到结果不得改写完成时间");

        // 同 requestId 重发不再插入新 PENDING。
        ServiceCallInsert duplicate = repository.insertServiceCallIfAbsent(
                "r1", "c1", "/svc", 4L);
        assertFalse(duplicate.inserted());
        assertEquals("OK", duplicate.row().status());
    }

    @Test
    void actionCancelAndFinalResultAreIdempotent() {
        repository.insertGoalIfAbsent("g1", "c1", "/act", 1L);
        assertTrue(repository.markGoalCanceling("g1", 2L));
        assertFalse(repository.markGoalCanceling("g1", 3L), "重复取消不得再次置 CANCELING");

        boolean firstResult = repository.completeGoal("g1", "CANCELED", "{}", 4L);
        boolean lateResult = repository.completeGoal("g1", "OK", "{\"v\":1}", 5L);
        assertTrue(firstResult);
        assertFalse(lateResult, "首个最终结果之后的迟到结果不得再次完成");

        ActionGoalRow row = repository.findGoal("g1");
        assertEquals("CANCELED", row.status());
        assertEquals(4L, row.completedAt());
    }

    @Test
    void actionOkResultWinsWhenNoCancel() {
        repository.insertGoalIfAbsent("g2", "c1", "/act", 1L);
        assertTrue(repository.completeGoal("g2", "OK", "{\"v\":9}", 2L));
        assertFalse(repository.markGoalCanceling("g2", 3L), "已终态 goal 不可取消");
        assertFalse(repository.completeGoal("g2", "CANCELED", "{}", 4L));
        assertEquals("OK", repository.findGoal("g2").status());
    }
}
