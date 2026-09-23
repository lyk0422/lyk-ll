package com.example.starter.lot;

import com.example.starter.lot.api.ConversionRequest;
import com.example.starter.lot.api.CreateLotRequest;
import com.example.starter.lot.api.QuarantineRequest;
import com.example.starter.lot.api.ConversionResponse;
import com.example.starter.lot.api.LotResponse;
import com.example.starter.lot.api.QuarantineResponse;
import com.example.starter.lot.service.BusinessException;
import com.example.starter.lot.service.LotGenealogyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发与竞态边界的 H2 真实事务测试：使用真实多线程协调启动，
 * 验证并发转换不超用、幂等键并发安全，以及转换/隔离的一致提交顺序。
 */
@SpringBootTest
class LotConcurrencyTest {

    @Autowired
    private LotGenealogyService service;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService pool;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM quarantine_source");
        jdbc.update("DELETE FROM quarantine_event");
        jdbc.update("DELETE FROM genealogy_edge");
        jdbc.update("DELETE FROM conversion_input");
        jdbc.update("DELETE FROM conversion_output");
        jdbc.update("DELETE FROM conversion");
        jdbc.update("DELETE FROM lot");
        jdbc.execute("ALTER TABLE conversion ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE quarantine_event ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE quarantine_source ALTER COLUMN id RESTART WITH 1");
        pool = Executors.newFixedThreadPool(12);
    }

    private void createLot(String id, String qty) {
        service.createLot(new CreateLotRequest(id, "KG", new BigDecimal(qty), null));
    }

    private ConversionRequest.InputItem in(String id, String qty, int version) {
        return new ConversionRequest.InputItem(id, new BigDecimal(qty), version);
    }

    private ConversionRequest.OutputItem out(String id, String qty) {
        return new ConversionRequest.OutputItem(id, new BigDecimal(qty));
    }

    /** 提交任务并在统一闸门放开后同时执行，返回 Future 列表。 */
    private List<Future<?>> submitParallel(int threads, Runnable task, CountDownLatch start) {
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await(30, TimeUnit.SECONDS);
                task.run();
                return null;
            }));
        }
        return futures;
    }

    @Test
    @DisplayName("并发转换：20 线程各抢 10（总量仅 100），成功恰为 10 笔且绝不超用")
    void concurrentConversionsNeverOveruse() throws Exception {
        createLot("A", "100.000");
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        List<Future<?>> futures = submitParallel(threads, () -> {
            String tag = UUID.randomUUID().toString().substring(0, 8);
            while (true) {
                LotResponse snapshot = service.getLot("A");
                if (snapshot.availableQty().compareTo(new BigDecimal("10.000")) < 0) {
                    rejected.incrementAndGet();
                    return;
                }
                try {
                    service.convert(new ConversionRequest(
                            "REQ-" + tag, "KG", BigDecimal.ZERO,
                            List.of(in("A", "10.000", snapshot.version())),
                            List.of(out("C-" + tag, "10.000"))));
                    success.incrementAndGet();
                    return;
                } catch (BusinessException e) {
                    // 版本竞争：重读新版本后重试；余额不足或最终 422 记为拒绝。
                    if (e.getCode().equals("INSUFFICIENT_BALANCE")) {
                        rejected.incrementAndGet();
                        return;
                    }
                    if (e.getCode().equals("VERSION_CONFLICT") || e.getCode().equals("CONCURRENT_CONVERSION")) {
                        continue;
                    }
                    throw e;
                }
            }
        }, start);

        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(success.get()).isEqualTo(10);
        assertThat(rejected.get()).isEqualTo(10);

        LotResponse finalLot = service.getLot("A");
        assertThat(finalLot.consumedQty()).isEqualByComparingTo("100.000");
        assertThat(finalLot.availableQty()).isEqualByComparingTo("0.000");
        assertThat(finalLot.version()).isEqualTo(10);

        Integer childCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot WHERE lot_id LIKE 'C-%'", Integer.class);
        assertThat(childCount).isEqualTo(10);
        // 数据库约束兜底：任何批次累计消费都不得超过初始量。
        Integer violated = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot WHERE consumed_qty > initial_qty", Integer.class);
        assertThat(violated).isZero();
    }

    @Test
    @DisplayName("幂等并发：同 requestId 同参并发 8 次，只落一笔台账且全部返回同一 conversionId")
    void concurrentSameRequestIdIsIdempotent() throws Exception {
        createLot("A", "100");
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await(30, TimeUnit.SECONDS);
                ConversionResponse response = service.convert(new ConversionRequest(
                        "REQ-SAME", "KG", BigDecimal.ZERO,
                        List.of(in("A", "5", 0)),
                        List.of(out("SAME-CHILD", "5"))));
                return response.conversionId();
            }));
        }
        start.countDown();
        long firstId = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<Long> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(firstId);
        }
        pool.shutdown();

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversion WHERE request_id = 'REQ-SAME'", Integer.class);
        assertThat(rows).isEqualTo(1);
        Integer inputRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversion_input WHERE lot_id = 'A'", Integer.class);
        assertThat(inputRows).isEqualTo(1);
        assertThat(service.getLot("A").consumedQty()).isEqualByComparingTo("5.000");
        assertThat(service.getLot("A").version()).isEqualTo(1);
    }

    @RepeatedTest(20)
    @DisplayName("转换/隔离竞态：无论谁先提交，新子批次都必须最终受先/后提交隔离影响")
    void conversionQuarantineRace_consistentOrdering() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String a = "A-" + suffix;
        String b = "B-" + suffix;
        String c = "C-" + suffix;
        createLot(a, "100");
        service.convert(new ConversionRequest(
                "SETUP-" + suffix, "KG", BigDecimal.ZERO,
                List.of(in(a, "50", 0)), List.of(out(b, "50"))));

        CountDownLatch start = new CountDownLatch(1);
        Future<ConversionResponse> conversionFuture = pool.submit(() -> {
            start.await(30, TimeUnit.SECONDS);
            return service.convert(new ConversionRequest(
                    "RACE-C-" + suffix, "KG", BigDecimal.ZERO,
                    List.of(in(b, "10", 0)), List.of(out(c, "10"))));
        });
        Future<QuarantineResponse> quarantineFuture = pool.submit(() -> {
            start.await(30, TimeUnit.SECONDS);
            return service.quarantine(new QuarantineRequest(
                    a, QuarantineRequest.Action.QUARANTINE, "race"));
        });
        start.countDown();

        conversionFuture.get(30, TimeUnit.SECONDS);
        quarantineFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // 两种提交顺序下 C 都必须是隔离状态，且生效来源集合包含祖先 A。
        LotResponse childC = service.getLot(c);
        assertThat(childC.status().name()).isEqualTo("QUARANTINED");
        assertThat(childC.activeQuarantineSources()).contains(a);
        // B 同样受 A 影响；台账只落一笔转换。
        assertThat(service.getLot(b).status().name()).isEqualTo("QUARANTINED");
        Integer conversionRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversion WHERE request_id = ?", Integer.class, "RACE-C-" + suffix);
        assertThat(conversionRows).isEqualTo(1);
    }
}
