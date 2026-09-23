package com.example.starter.rosbridge.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.rosbridge.testsupport.TestDatasources;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 直接对 H2(MODE=MySQL) 验证关键数据库边界：游标并发分配唯一且单调、
 * Service/Action CAS 只完成一次、引用计数宽限、ACK 单调与留存裁剪。
 * 不使用 mock 或内存 Map 代替数据库。
 */
class GatewayRepositoryH2Test {

    private DataSource ds;
    private JdbcTemplate jdbc;
    private GatewayRepository repo;

    @BeforeEach
    void setUp() {
        ds = TestDatasources.newH2("repo_test_" + System.nanoTime());
        jdbc = TestDatasources.jdbc(ds);
        repo = new GatewayRepository(jdbc);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (ds instanceof java.io.Closeable c) {
            c.close();
        }
    }

    @Test
    void sharedSubscriptionIsUniqueByTopicTypeQos() {
        SharedSubscriptionRow a = repo.insertShared("/t", "std_msgs/String",
                new com.example.starter.rosbridge.ros.Qos("RELIABLE", "VOLATILE", 10).key(), 1L);
        assertThat(a).isNotNull();
        // 相同键再次插入触发唯一约束
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DuplicateKeyException.class,
                () -> repo.insertShared("/t", "std_msgs/String",
                        new com.example.starter.rosbridge.ros.Qos(
                                "reliable", "volatile", 10).key(), 2L));
        // 不同 QoS 可共存
        SharedSubscriptionRow b = repo.insertShared("/t", "std_msgs/String",
                new com.example.starter.rosbridge.ros.Qos("BEST_EFFORT", "VOLATILE", 10).key(), 2L);
        assertThat(b.id()).isNotEqualTo(a.id());
    }

    @Test
    void concurrentCursorAllocationIsUniqueAndMonotonic() throws Exception {
        SharedSubscriptionRow shared = repo.insertShared(
                "/c", "T", "K", 1L);
        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Long> allocated = Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < perThread; i++) {
                    allocated.add(repo.allocateCursor(shared.id()));
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(allocated).hasSize(threads * perThread);
        Set<Long> unique = new HashSet<>(allocated);
        assertThat(unique).hasSize(threads * perThread); // 无重复游标
        assertThat(unique).allSatisfy(c -> assertThat(c).isBetween(1L, (long) threads * perThread));
        assertThat(repo.findSharedById(shared.id()).nextCursor())
                .isEqualTo((long) threads * perThread + 1);
    }

    @Test
    void serviceCallCompletesExactlyOnceBetweenTimeoutAndLateResult() {
        repo.insertServiceCallIfAbsent("c1", "r1", "/svc", 100L, 1L);
        // 超时方先完成
        assertThat(repo.completeServiceCall("c1", "r1", ServiceCallRow.TIMEOUT, null, 101L))
                .isTrue();
        // 迟到结果 CAS 失败
        assertThat(repo.completeServiceCall("c1", "r1", ServiceCallRow.OK, "{}", 120L))
                .isFalse();
        ServiceCallRow row = repo.getServiceCall("c1", "r1");
        assertThat(row.status()).isEqualTo(ServiceCallRow.TIMEOUT);
    }

    @Test
    void duplicateServiceRequestIsIdempotent() {
        ServiceCallRow first = repo.insertServiceCallIfAbsent("c", "r", "/s", 100L, 1L);
        ServiceCallRow second = repo.insertServiceCallIfAbsent("c", "r", "/s", 200L, 2L);
        assertThat(second.deadline()).isEqualTo(first.deadline());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_call WHERE client_id='c' AND request_id='r'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void goalCasAllowsOnlyOneTerminalState() {
        repo.insertGoalIfAbsent("c", "g", "/a", 1L);
        assertThat(repo.casFinishGoal("c", "g", ActionGoalRow.CANCELED, null, 2L)).isTrue();
        assertThat(repo.casFinishGoal("c", "g", ActionGoalRow.SUCCEEDED, "{\"x\":1}", 3L))
                .isFalse();
        assertThat(repo.getGoal("c", "g").status()).isEqualTo(ActionGoalRow.CANCELED);
        // 重复取消也不产生第二条终态
        assertThat(repo.casFinishGoal("c", "g", ActionGoalRow.CANCELED, null, 4L)).isFalse();
    }

    @Test
    void referenceCountingAndGraceRelease() {
        SharedSubscriptionRow shared = repo.insertShared("/t", "T", "K", 1L);
        long id = shared.id();
        repo.insertClientSub("a", id, 1L);
        repo.insertClientSub("b", id, 1L);
        repo.acquireReference(id);
        repo.acquireReference(id);
        assertThat(repo.findSharedById(id).refCount()).isEqualTo(2);

        // 客户端 a 释放，进入宽限
        SharedSubscriptionRow afterA = repo.releaseReference(id, 100L);
        assertThat(afterA.refCount()).isEqualTo(1);
        assertThat(afterA.graceUntil()).isNull();
        // 宽限未到/仍有引用，不能释放上游
        assertThat(repo.tryReleaseUpstreamAfterGrace(id, 50L)).isFalse();

        // 最后一个客户端 b 释放，进入宽限
        SharedSubscriptionRow afterB = repo.releaseReference(id, 100L);
        assertThat(afterB.refCount()).isZero();
        assertThat(afterB.graceUntil()).isEqualTo(100L);
        // 宽限期内（now<grace_until）不能释放
        assertThat(repo.tryReleaseUpstreamAfterGrace(id, 99L)).isFalse();
        // 宽限期结束后可释放
        assertThat(repo.tryReleaseUpstreamAfterGrace(id, 100L)).isTrue();
        assertThat(repo.findSharedById(id).upstreamActive()).isFalse();
        // 重复释放幂等失败
        assertThat(repo.tryReleaseUpstreamAfterGrace(id, 200L)).isFalse();
    }

    @Test
    void reacquireDuringGracePreventsRelease() {
        SharedSubscriptionRow shared = repo.insertShared("/t", "T", "K", 1L);
        long id = shared.id();
        repo.insertClientSub("a", id, 1L);
        repo.acquireReference(id);
        repo.releaseReference(id, 100L);
        // 宽限内重新获取引用
        repo.acquireReference(id);
        assertThat(repo.findSharedById(id).graceUntil()).isNull();
        assertThat(repo.tryReleaseUpstreamAfterGrace(id, 1000L)).isFalse();
    }

    @Test
    void ackCursorIsMonotonic() {
        SharedSubscriptionRow shared = repo.insertShared("/t", "T", "K", 1L);
        long id = shared.id();
        repo.insertClientSub("c", id, 1L);
        assertThat(repo.updateAck("c", id, 10L)).isTrue();
        // 回退/重复被拒
        assertThat(repo.updateAck("c", id, 5L)).isFalse();
        assertThat(repo.updateAck("c", id, 10L)).isFalse();
        assertThat(repo.findClientSub("c", id).lastAckCursor()).isEqualTo(10L);
        assertThat(repo.updateAck("c", id, 11L)).isTrue();
        assertThat(repo.findClientSub("c", id).lastAckCursor()).isEqualTo(11L);
    }

    @Test
    void messageRetentionAndReplayQuery() {
        SharedSubscriptionRow shared = repo.insertShared("/t", "T", "K", 1L);
        long id = shared.id();
        for (long c = 1; c <= 10; c++) {
            long allocated = repo.allocateCursor(id);
            repo.insertMessage(id, allocated, "{\"n\":" + c + "}", c);
        }
        repo.pruneMessages(id, 3);
        List<TopicMessageRow> kept = repo.messagesAfter(id, 0L);
        assertThat(kept).extracting(TopicMessageRow::cursor).containsExactly(8L, 9L, 10L);
        // 严格大于 ACK 游标
        assertThat(repo.messagesAfter(id, 8L)).extracting(TopicMessageRow::cursor)
                .containsExactly(9L, 10L);
    }

    @Test
    void gracingSubsCleanupOnlyAfterRelease() {
        SharedSubscriptionRow shared = repo.insertShared("/t", "T", "K", 1L);
        long id = shared.id();
        repo.insertClientSub("a", id, 1L);
        repo.acquireReference(id);
        repo.releaseReference(id, 100L);
        repo.markClientSubsGracing("a", 100L);
        assertThat(repo.deleteGracingSubsForShared(id)).isEqualTo(1);
        assertThat(repo.findClientSub("a", id)).isNull();
    }

    @Test
    void concurrentAcquireReleaseKeepsNonNegativeCount() throws Exception {
        SharedSubscriptionRow shared = repo.insertShared("/t", "T", "K", 1L);
        long id = shared.id();
        int threads = 6;
        int rounds = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (int t = 0; t < threads; t++) {
            final String client = "c" + t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int r = 0; r < rounds; r++) {
                        repo.insertClientSub(client, id, 1L);
                        repo.acquireReference(id);
                        repo.releaseReference(id, Long.MAX_VALUE);
                        repo.deleteClientSub(client, id);
                    }
                } catch (Throwable th) {
                    errors.add(th);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();
        assertThat(repo.findSharedById(id).refCount()).isZero();
    }
}
