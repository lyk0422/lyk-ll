package com.example.starter.spectrum;

import com.example.starter.spectrum.api.LeaseResponse;
import com.example.starter.spectrum.api.LeaseUpsertRequest;
import com.example.starter.spectrum.domain.LeaseMode;
import com.example.starter.spectrum.service.LeaseConflictException;
import com.example.starter.spectrum.service.LeaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发协调测试：真实多线程并发创建，验证共享功率上限不会被并发越过，
 * 且并发下同键请求保持幂等。所有线程通过门闩同时放行，设置超时防止悬挂。
 */
class ConcurrentLeaseTest extends AbstractSpectrumIntegrationTest {

    @Autowired
    private LeaseService leaseService;

    private static LeaseUpsertRequest upsert(String requestId, double eirp) {
        return new LeaseUpsertRequest(requestId, null, T0, T0 + 1000, 100.0, 200.0,
                LeaseMode.SHARED, eirp, new TreeSet<>(List.of("CELL-A")));
    }

    @Test
    void concurrentSharedCreatesNeverExceedLimit() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            String requestId = "req-conc-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                    leaseService.create(upsert(requestId, 600.0));
                    successes.incrementAndGet();
                } catch (LeaseConflictException e) {
                    conflicts.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "workers not ready in time");
        go.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // 上限 1000mW：每条 600mW，串行化后恰好一条成功，其余全部因超限被拒
        assertEquals(1, successes.get(), "exactly one create must succeed");
        assertEquals(threads - 1, conflicts.get(), "all others must be conflict-rejected");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM lease", Integer.class));
        assertEquals(1, leaseService.activeSnapshot(T0 + 500).size());
    }

    @Test
    void concurrentSameRequestIdCreatesSingleLease() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                    LeaseResponse response = leaseService.create(
                            upsert("req-conc-same", 500.0));
                    successes.incrementAndGet();
                    return response.leaseId();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "workers not ready in time");
        go.countDown();
        List<Long> leaseIds = new ArrayList<>();
        for (Future<?> future : futures) {
            @SuppressWarnings("unchecked")
            Future<Long> typed = (Future<Long>) future;
            leaseIds.add(typed.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // 同键同参并发：全部成功且返回同一租约，库中只有一条
        assertEquals(threads, successes.get());
        assertEquals(1, leaseIds.stream().distinct().count());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM lease", Integer.class));
    }

    @Test
    void concurrentCancelAndModifyAreSerializable() throws Exception {
        // 同一租约上并发取消与修改：串行化后恰好一个成功，
        // 最终状态一致（要么已取消且版本 1，要么活动且版本 2）。
        com.example.starter.spectrum.api.LeaseResponse created = leaseService.create(
                new LeaseUpsertRequest("req-cc-0", null, T0, T0 + 1000, 100.0, 200.0,
                        LeaseMode.SHARED, 500.0, new TreeSet<>(List.of("CELL-A"))));
        long leaseId = created.leaseId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger cancelOk = new AtomicInteger();
        AtomicInteger modifyOk = new AtomicInteger();

        Future<?> cancelFuture = pool.submit(() -> {
            ready.countDown();
            try {
                go.await(5, TimeUnit.SECONDS);
                leaseService.cancel(leaseId,
                        new com.example.starter.spectrum.api.LeaseCancelRequest("req-cc-1", 1));
                cancelOk.incrementAndGet();
            } catch (com.example.starter.spectrum.service.LeaseServiceException expected) {
                // 串行化后取消/修改必有一方失败
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Future<?> modifyFuture = pool.submit(() -> {
            ready.countDown();
            try {
                go.await(5, TimeUnit.SECONDS);
                leaseService.modify(leaseId,
                        new LeaseUpsertRequest("req-cc-2", 1, T0, T0 + 2000, 100.0, 200.0,
                                LeaseMode.SHARED, 500.0, new TreeSet<>(List.of("CELL-A"))));
                modifyOk.incrementAndGet();
            } catch (com.example.starter.spectrum.service.LeaseServiceException expected) {
                // 串行化后取消/修改必有一方失败
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTrue(ready.await(10, TimeUnit.SECONDS), "workers not ready in time");
        go.countDown();
        cancelFuture.get(30, TimeUnit.SECONDS);
        modifyFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, cancelOk.get() + modifyOk.get(),
                "exactly one of cancel/modify must succeed");
        com.example.starter.spectrum.api.LeaseResponse current = leaseService.getLease(leaseId);
        if (cancelOk.get() == 1) {
            assertEquals(com.example.starter.spectrum.domain.LeaseStatus.CANCELLED,
                    current.status());
            assertEquals(1, current.version());
        } else {
            assertEquals(com.example.starter.spectrum.domain.LeaseStatus.ACTIVE,
                    current.status());
            assertEquals(2, current.version());
        }
    }
}
