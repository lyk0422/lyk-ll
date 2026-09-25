package com.example.starter.damage;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.api.Responsibility;
import com.example.starter.damage.api.dto.EstimateRequest;
import com.example.starter.damage.api.dto.RegisterRequest;
import com.example.starter.damage.api.dto.SurveyItemDto;
import com.example.starter.damage.api.dto.SurveyRequest;
import com.example.starter.damage.service.DamageCaseService;

/** 真实并发：同箱同时刻唯一受理、requestId 幂等首请求仲裁，均由数据库约束裁决。 */
@SpringBootTest
class DamageConcurrencyTest {

    private static final long D1 = LocalDate.of(2026, 9, 1)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();

    @Autowired
    private DamageCaseService service;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService pool;

    @BeforeEach
    void cleanTables() {
        for (String table : List.of("case_event", "repair_cycle", "estimate_item",
                "estimate_version", "damage_item", "survey", "idempotent_request",
                "damage_case")) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void shutdownPool() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("同箱同时刻并发登记：恰一个受理，另一个409，在办案件唯一")
    void concurrentRegistrationOfSameContainerAcceptsExactlyOne() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();

        for (int i = 1; i <= 2; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    service.register(new RegisterRequest(
                            "REQ-CONC-REG-" + idx, "CSQCONC0001",
                            new BigDecimal("1000.00"), D1));
                    success.incrementAndGet();
                } catch (ApiException e) {
                    if ("ACTIVE_CASE_EXISTS".equals(e.getCode())) {
                        conflict.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // 屏障/中断等非业务异常不计入，断言会失败暴露问题
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM damage_case WHERE active_flag = 'CSQCONC0001'",
                Integer.class);
        assertThat(active).isEqualTo(1);
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM damage_case WHERE container_no = 'CSQCONC0001'",
                Integer.class);
        assertThat(total).isEqualTo(1);
    }

    @Test
    @DisplayName("同 requestId 同参并发估价：两者返回同一版本，估价版本唯一")
    void concurrentSameKeyEstimateProducesSingleVersion() throws Exception {
        String caseNo = service.register(new RegisterRequest(
                "REQ-CONC-EST-REG", "CSQCONC0002", new BigDecimal("1000.00"), D1)).caseNo();
        service.survey(new SurveyRequest("REQ-CONC-EST-SUR", caseNo, "检验员-李", D1,
                List.of(new SurveyItemDto("IICL-4010", "角柱", Responsibility.OWNER, 1))));

        CyclicBarrier barrier = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        List<Long> versionIds = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();

        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    versionIds.add(service.estimate(
                            new EstimateRequest("REQ-CONC-EST-SAME", caseNo))
                            .currentEstimate().estimateId());
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();
        assertThat(versionIds).hasSize(2);
        assertThat(versionIds.get(0)).isEqualTo(versionIds.get(1));
        Integer versions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM estimate_version ev JOIN damage_case dc ON dc.id = ev.case_id "
                        + "WHERE dc.case_no = ?", Integer.class, caseNo);
        assertThat(versions).isEqualTo(1);
        Integer idempotentRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'REQ-CONC-EST-SAME'",
                Integer.class);
        assertThat(idempotentRows).isEqualTo(1);
    }
}
