package com.example.starter.service;

import com.example.starter.domain.Detection;
import com.example.starter.domain.VersionConflictException;
import com.example.starter.testsupport.TestDataCleaner;
import com.example.starter.web.dto.AssociateResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发显式关联的结果明细验证：同一起始版本的并发请求恰好一个成功，
 * 其余得到明确的版本冲突，冲突检测不得登记为已处理或产生任何写入。
 */
@SpringBootTest
class DiagnosticConcurrencyTest {

    @Autowired
    private TrackLifecycleService service;

    @Autowired
    private TestDataCleaner cleaner;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanData() {
        cleaner.clean();
    }

    @Test
    void exactlyOneAssociatedAndOthersConflictWithoutSideEffects() throws Exception {
        service.associate(new Detection("d0", 1000L, "cam-1", "car",
                0.0, 0.0, 0.0, 0.9), null, null, null);

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 1; i <= threads; i++) {
            long ts = 2000L + i;
            double x = 0.1 * i;
            Callable<String> task = () -> {
                start.await();
                try {
                    AssociateResponse r = service.associate(
                            new Detection("d-" + ts, ts, "cam-1", "car", x, 0.0, 0.0, 0.9),
                            "T-000001", 2L, 5.0);
                    return "OK:" + r.outcome() + ":" + r.version();
                } catch (VersionConflictException e) {
                    return "CONFLICT";
                }
            };
            futures.add(pool.submit(task));
        }
        start.countDown();

        int associated = 0;
        int conflicts = 0;
        for (Future<String> f : futures) {
            String result = f.get(30, TimeUnit.SECONDS);
            if (result.startsWith("OK:ASSOCIATED:3")) {
                associated++;
            } else if ("CONFLICT".equals(result)) {
                conflicts++;
            }
        }
        pool.shutdownNow();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(associated).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
        assertThat(service.getTrack("T-000001").version()).isEqualTo(3L);
        // 赢家 1 条 + 建轨 d0 共 2 条已处理检测；冲突请求全部回滚不留痕。
        Integer processed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_detection", Integer.class);
        assertThat(processed).isEqualTo(2);
    }
}
