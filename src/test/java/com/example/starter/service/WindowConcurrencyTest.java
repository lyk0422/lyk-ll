package com.example.starter.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.domain.WindowStatus;
import com.example.starter.error.BusinessException;
import com.example.starter.support.AbstractWindowTest;
import com.example.starter.support.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发测试：真实多线程并发提交与审批，验证幂等唯一成功与冲突串行化。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class WindowConcurrencyTest extends AbstractWindowTest {

    @Test
    void 并发同键同参只允许一次成功且响应一致() throws Exception {
        var command = cmd("REQ-RACE", berthA, at("2026-10-01T10:00:00Z"), at("2026-10-01T12:00:00Z"));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<WindowService.CreateResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return service.create(command);
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        List<WindowService.CreateResult> results = new ArrayList<>();
        for (Future<WindowService.CreateResult> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        long createdCount = results.stream().filter(WindowService.CreateResult::created).count();
        assertThat(createdCount).isEqualTo(1);
        long firstId = results.get(0).window().id();
        assertThat(results).allSatisfy(r -> {
            assertThat(r.window().id()).isEqualTo(firstId);
            assertThat(r.window().status()).isEqualTo(WindowStatus.PENDING);
            assertThat(r.window().requestId()).isEqualTo("REQ-RACE");
        });
        assertThat(countWindows()).isEqualTo(1);
    }

    @Test
    void 并发审批同泊位重叠窗口仅一个成功() throws Exception {
        var w1 = service.create(cmd("REQ-1", berthA, at("2026-10-01T10:00:00Z"), at("2026-10-01T12:00:00Z")));
        var w2 = service.create(cmd("REQ-2", berthA, at("2026-10-01T11:00:00Z"), at("2026-10-01T13:00:00Z")));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<?> f1 = pool.submit(() -> {
            ready.countDown();
            go.await(5, TimeUnit.SECONDS);
            service.approve(w1.window().id());
            return null;
        });
        Future<?> f2 = pool.submit(() -> {
            ready.countDown();
            go.await(5, TimeUnit.SECONDS);
            service.approve(w2.window().id());
            return null;
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        List<Throwable> failures = new ArrayList<>();
        for (Future<?> f : List.of(f1, f2)) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                failures.add(e.getCause() instanceof BusinessException be ? be : e);
            }
        }
        pool.shutdownNow();

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo("BERTH_WINDOW_CONFLICT"));

        long approvedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM berth_window WHERE status IN ('APPROVED','ACTIVE')",
                Long.class);
        assertThat(approvedCount).isEqualTo(1);
    }
}
