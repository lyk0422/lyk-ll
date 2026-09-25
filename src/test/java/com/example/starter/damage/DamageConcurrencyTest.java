package com.example.starter.damage;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.domain.Responsibility;
import com.example.starter.damage.dto.Requests.DamageItemInput;
import com.example.starter.damage.dto.Requests.EstimateRequest;
import com.example.starter.damage.dto.Requests.RegisterRequest;
import com.example.starter.damage.dto.Requests.SurveyRequest;
import com.example.starter.damage.dto.Views.CaseView;
import com.example.starter.damage.dto.Views.EstimateView;
import com.example.starter.damage.service.DamageCaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发测试：真实 H2 约束与事务边界下，协调多线程同时提交，
 * 验证同一箱号并发登记唯一受理、同 requestId 并发重放不产生新估价版本。
 */
@SpringBootTest
class DamageConcurrencyTest {

    private static final int THREADS = 8;

    @Autowired
    private DamageCaseService service;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from case_event");
        jdbc.update("delete from reinspection");
        jdbc.update("delete from repair_completion");
        jdbc.update("delete from repair_approval");
        jdbc.update("delete from estimate");
        jdbc.update("delete from damage_item");
        jdbc.update("delete from damage_case");
        jdbc.update("delete from idempotency_record");
        pool = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private <T> List<T> runConcurrently(java.util.concurrent.Callable<T> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return task.call();
            }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    @Test
    void concurrentRegisterSameContainer_onlyOneAccepted() throws Exception {
        List<Object> results = runConcurrently(() -> {
            String requestId = "reg-conc-" + UUID.randomUUID();
            try {
                return service.register(new RegisterRequest(requestId, "CSQU3054383", new BigDecimal("10000.00")));
            } catch (ApiException e) {
                return e;
            }
        });

        long succeeded = results.stream().filter(CaseView.class::isInstance).count();
        long conflicted = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT)
                .count();
        assertThat(succeeded).isEqualTo(1);
        assertThat(conflicted).isEqualTo(THREADS - 1);

        Integer activeCases = jdbc.queryForObject(
                "select count(*) from damage_case where active_key is not null", Integer.class);
        assertThat(activeCases).isEqualTo(1);
        Integer totalCases = jdbc.queryForObject("select count(*) from damage_case", Integer.class);
        assertThat(totalCases).isEqualTo(1);
    }

    @Test
    void concurrentSameEstimateRequest_singleVersionAndSameResult() throws Exception {
        CaseView registered = service.register(new RegisterRequest(
                "reg-" + UUID.randomUUID(), "TEMU2000001", new BigDecimal("10000.00")));
        long caseId = registered.id();
        service.survey(caseId, new SurveyRequest("svy-" + UUID.randomUUID(), List.of(
                new DamageItemInput("DN", "侧板", "凹痕", Responsibility.OWNER, new BigDecimal("1000.00")))));

        List<EstimateView> results = runConcurrently(
                () -> service.estimate(caseId, new EstimateRequest("est-conc-1")));

        assertThat(results).allSatisfy(e -> {
            assertThat(e.version()).isEqualTo(1);
            assertThat(e.totalCost()).isEqualByComparingTo("1000.00");
        });
        assertThat(results).allSatisfy(e -> assertThat(e).isEqualTo(results.get(0)));
        Integer versions = jdbc.queryForObject(
                "select count(*) from estimate where case_id = ?", Integer.class, caseId);
        assertThat(versions).isEqualTo(1);
    }
}
