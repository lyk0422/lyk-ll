package com.example.starter.pest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.example.starter.common.ApiException;
import com.example.starter.pest.api.Requests.ConfirmRequest;
import com.example.starter.pest.api.Requests.ReportRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 处置链唯一性：同库区同时刻只允许一条进行中处置链。
 */
class ActiveChainUniquenessTest extends BaseIntegrationTest {

    @Test
    void 同库区已有进行中处置链时再次确认被拒绝() {
        reportAndConfirm("A", "u1");
        var second = pestReportService.report(new ReportRequest("u2-r", "A", "再次发现虫迹", "张三"));

        assertThatThrownBy(() -> pestReportService.confirm(second.id(),
                new ConfirmRequest("u2-c", "李四")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(count("active_chain")).isEqualTo(1);
        assertThat(count("treatment_chain")).isEqualTo(1);
    }

    @Test
    void 并发确认同库区仅一条处置链生效() throws Exception {
        var r1 = pestReportService.report(new ReportRequest("uc1-r", "A", "虫迹一", "张三"));
        var r2 = pestReportService.report(new ReportRequest("uc2-r", "A", "虫迹二", "张三"));

        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        List<Object> outcomes = new CopyOnWriteArrayList<>();
        try {
            var tasks = List.of(
                    (Runnable) () -> confirmConcurrently(r1.id(), "uc1-c", ready, start, outcomes),
                    (Runnable) () -> confirmConcurrently(r2.id(), "uc2-c", ready, start, outcomes));
            tasks.forEach(pool::submit);
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发就绪信号超时");
            }
            start.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发确认执行超时");
            }
        } finally {
            pool.shutdownNow();
        }

        long successes = outcomes.stream().filter(o -> !(o instanceof ApiException)).count();
        long conflicts = outcomes.stream()
                .filter(o -> o instanceof ApiException e && e.getStatus() == HttpStatus.CONFLICT)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        assertThat(count("active_chain")).isEqualTo(1);
        assertThat(count("treatment_chain")).isEqualTo(1);
    }

    private void confirmConcurrently(long reportId, String requestId, CountDownLatch ready,
                                     CountDownLatch start, List<Object> outcomes) {
        try {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发起始信号超时");
            }
            outcomes.add(pestReportService.confirm(reportId, new ConfirmRequest(requestId, "李四")));
        } catch (ApiException e) {
            outcomes.add(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发确认被中断", e);
        }
    }
}
