package com.example.starter.pest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.common.ApiException;
import com.example.starter.pest.api.Requests.ReportRequest;
import com.example.starter.pest.api.Results.ReportResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 幂等：同键同参返回首次结果（含并发重放），同键异参 409。
 */
class IdempotencyTest extends BaseIntegrationTest {

    @Test
    void 同键同参顺序重放返回首次结果且不重复落库() {
        var request = new ReportRequest("idem-seq", "A", "墙角发现虫迹", "张三");

        ReportResult first = pestReportService.report(request);
        ReportResult replayed = pestReportService.report(request);

        assertThat(replayed).isEqualTo(first);
        assertThat(count("pest_report")).isEqualTo(1);
    }

    @Test
    void 同键异参返回409() {
        pestReportService.report(new ReportRequest("idem-conflict", "A", "墙角发现虫迹", "张三"));

        assertThatThrownBy(() -> pestReportService.report(
                new ReportRequest("idem-conflict", "A", "不同的虫情描述", "张三")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(count("pest_report")).isEqualTo(1);
    }

    @Test
    void 同键同参并发提交仅执行一次且全部返回首次结果() throws Exception {
        int threads = 8;
        var request = new ReportRequest("idem-concurrent", "A", "墙角发现虫迹", "张三");
        var pool = Executors.newFixedThreadPool(threads);
        var ready = new CountDownLatch(threads);
        var start = new CountDownLatch(1);
        List<Future<ReportResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("并发起始信号超时");
                    }
                    return pestReportService.report(request);
                }));
            }
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发就绪信号超时");
            }
            start.countDown();

            List<ReportResult> results = new ArrayList<>();
            for (Future<ReportResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).hasSize(threads);
            assertThat(results).allMatch(r -> r.equals(results.get(0)));
            assertThat(count("pest_report")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
