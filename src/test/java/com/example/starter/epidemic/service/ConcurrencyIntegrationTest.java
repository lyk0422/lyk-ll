package com.example.starter.epidemic.service;

import com.example.starter.epidemic.AbstractIntegrationTest;
import com.example.starter.epidemic.Requests;
import com.example.starter.epidemic.api.ActionResult;
import com.example.starter.epidemic.api.CardView;
import com.example.starter.epidemic.api.SubmitResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 真实并发测试：审核/撤回唯一终态、并发初报去重、同键并发重放。
 */
class ConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EpidemicCardService service;

    private String createPending(String patientId) {
        SubmitResult r = service.submitInitial(Requests.initial(
                Requests.rid(), patientId, "MEASLES", "CONFIRMED",
                Requests.utc(2026, 9, 25, 7, 0), null));
        return r.cardNumber();
    }

    @Test
    void concurrent_review_and_withdraw_always_ends_in_exactly_one_terminal_state() throws Exception {
        int iterations = 10;
        for (int i = 0; i < iterations; i++) {
            final int idx = i;
            String card = createPending("RACE-" + idx);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Boolean> review = pool.submit(() -> {
                    start.await();
                    try {
                        ActionResult result = service.review(card, "reviewer-" + idx, null);
                        return "APPROVED".equals(result.status());
                    } catch (BusinessException ex) {
                        return false;
                    }
                });
                Future<Boolean> withdraw = pool.submit(() -> {
                    start.await();
                    try {
                        ActionResult result = service.withdraw(card, "operator-" + idx, null);
                        return "WITHDRAWN".equals(result.status());
                    } catch (BusinessException ex) {
                        return false;
                    }
                });
                start.countDown();
                boolean reviewed = review.get(10, TimeUnit.SECONDS);
                boolean withdrawn = withdraw.get(10, TimeUnit.SECONDS);

                // 两个终态互斥，恰好一个生效
                assertThat(reviewed ^ withdrawn)
                        .as("第 %d 轮：审核与撤回必须恰好一个生效", idx)
                        .isTrue();

                CardView view = service.getCard(card);
                if (reviewed) {
                    assertThat(view.status()).isEqualTo("APPROVED");
                    assertThat(view.reviews())
                            .anyMatch(r -> r.stage().equals("REVIEW") && !r.voided());
                } else {
                    assertThat(view.status()).isEqualTo("WITHDRAWN");
                    assertThat(view.reviews())
                            .anyMatch(r -> r.stage().equals("WITHDRAWAL"));
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void concurrent_initial_reports_same_fingerprint_create_exactly_one_card() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<SubmitResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.submitInitial(Requests.initial(
                            Requests.rid(), "CDUP", "MEASLES", "CONFIRMED",
                            Requests.utc(2026, 9, 25, 7, 0), null));
                }));
            }
            start.countDown();

            List<SubmitResult> results = new ArrayList<>();
            for (Future<SubmitResult> f : futures) {
                results.add(f.get(15, TimeUnit.SECONDS));
            }
            long created = results.stream().filter(r -> !r.duplicated()).count();
            long duplicated = results.stream().filter(SubmitResult::duplicated).count();
            assertThat(created).isEqualTo(1);
            assertThat(duplicated).isEqualTo(threads - 1);
            assertThat(results.stream().map(SubmitResult::cardNumber).distinct()).hasSize(1);
            assertThat(service.listCards()).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrent_same_request_id_replays_first_result_without_duplicates() throws Exception {
        int threads = 6;
        String rid = Requests.rid();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<SubmitResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.submitInitial(Requests.initial(
                            rid, "RIDRACE", "PLAGUE", "CONFIRMED",
                            Requests.utc(2026, 9, 25, 7, 0), null));
                }));
            }
            start.countDown();

            List<SubmitResult> results = new ArrayList<>();
            for (Future<SubmitResult> f : futures) {
                results.add(f.get(15, TimeUnit.SECONDS));
            }
            assertThat(results.stream().map(SubmitResult::cardNumber).distinct()).hasSize(1);
            assertThat(service.listCards()).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
