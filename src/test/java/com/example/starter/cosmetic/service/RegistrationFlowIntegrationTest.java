package com.example.starter.cosmetic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.example.starter.cosmetic.api.IngredientPayload;
import com.example.starter.cosmetic.api.ObjectionResponse;
import com.example.starter.cosmetic.api.RegistrationResponse;
import com.example.starter.cosmetic.api.ReportPayload;
import com.example.starter.cosmetic.api.SubmitChangeRequest;
import com.example.starter.cosmetic.api.SubmitRegistrationRequest;
import com.example.starter.cosmetic.repo.RegistrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 备案全流程集成测试（H2 MySQL 兼容模式）：公示期满口径、异议竞态、实质/简易变更、
 * 版本切换原子性、幂等重放与并发、注销终态。
 */
@SpringBootTest
class RegistrationFlowIntegrationTest {

    /** 备案提交时刻（UTC），提交日为 2026-01-01。 */
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    /** 公示期满时刻：第10日24:00，即 2026-01-11T00:00:00Z。 */
    private static final Instant DEADLINE = Instant.parse("2026-01-11T00:00:00Z");

    @Autowired
    private RegistrationService service;

    @Autowired
    private RegistrationRepository registrationRepository;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.sql("DELETE FROM objection").update();
        jdbc.sql("DELETE FROM review_record").update();
        jdbc.sql("DELETE FROM test_report").update();
        jdbc.sql("DELETE FROM registration_claim").update();
        jdbc.sql("DELETE FROM registration_ingredient").update();
        jdbc.sql("DELETE FROM registration").update();
        jdbc.sql("DELETE FROM idempotency_record").update();
        jdbc.sql("DELETE FROM product").update();
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private long newProduct() {
        return service.createProduct("合成产品-" + rid(), "合成备案人", T0).id();
    }

    private static SubmitRegistrationRequest normalSubmit(String requestId, long productId) {
        return new SubmitRegistrationRequest(requestId, productId, "合成包装文字",
                List.of("MOISTURIZING"),
                List.of(new IngredientPayload("GLYCERIN", new BigDecimal("5.0"))),
                List.of());
    }

    private long submitAndFinalize(long productId) {
        RegistrationResponse submit = service.submitInitial(normalSubmit(rid(), productId), T0);
        assertTrue(service.finalizeIfDue(submit.registrationId(), DEADLINE));
        return submit.registrationId();
    }

    @Test
    void normalCosmeticPassesReviewAndEntersPublicity() {
        long productId = newProduct();
        RegistrationResponse response = service.submitInitial(normalSubmit(rid(), productId), T0);
        assertEquals("PUBLICITY", response.status());
        assertTrue(response.reviewPassed());
        assertEquals(LocalDate.of(2026, 1, 1), response.publicityStartDate());
        assertEquals(DEADLINE, response.publicityDeadline());
        assertEquals("REG-%06d-%03d".formatted(productId, 1), response.registrationNo());
        assertThat(service.getReviewRecords(response.registrationId()))
                .allMatch(record -> "PASS".equals(record.result()));
    }

    @Test
    void specialClaimWithExactlyThreeYearOldReportPasses() {
        // 报告恰满3年当日有效：提交日2026-01-01，报告出具日2023-01-01
        long productId = newProduct();
        var request = new SubmitRegistrationRequest(rid(), productId, "合成包装文字",
                List.of("SUNSCREEN"),
                List.of(new IngredientPayload("HOMOSALATE", new BigDecimal("10.0"))),
                List.of(new ReportPayload("SUNSCREEN", "SUPPORT", LocalDate.of(2023, 1, 1), "合成检验机构")));
        RegistrationResponse response = service.submitInitial(request, T0);
        assertEquals("PUBLICITY", response.status());
        assertTrue(response.reviewPassed());
    }

    @Test
    void specialClaimWithoutReportIsRejectedAndRecorded() {
        long productId = newProduct();
        var request = new SubmitRegistrationRequest(rid(), productId, "合成包装文字",
                List.of("SUNSCREEN"),
                List.of(new IngredientPayload("GLYCERIN", new BigDecimal("5.0"))),
                List.of());
        RegistrationResponse response = service.submitInitial(request, T0);
        assertEquals("REJECTED", response.status());
        assertFalse(response.reviewPassed());
        assertThat(service.getReviewRecords(response.registrationId()))
                .anyMatch(record -> "FAIL".equals(record.result()) && record.item().equals("功效评价报告"));
        assertThat(service.getDetail(response.registrationId()).rejectReason()).contains("功效评价试验报告");
    }

    @Test
    void publicityFinalizesExactlyAtTenthDayMidnight() {
        long productId = newProduct();
        RegistrationResponse submit = service.submitInitial(normalSubmit(rid(), productId), T0);
        // 期满前一秒仍未生效
        assertFalse(service.finalizeIfDue(submit.registrationId(), DEADLINE.minusSeconds(1)));
        assertEquals("PUBLICITY", service.getDetail(submit.registrationId()).status());
        // 恰第10日24:00期满自动生效
        assertTrue(service.finalizeIfDue(submit.registrationId(), DEADLINE));
        var detail = service.getDetail(submit.registrationId());
        assertEquals("EFFECTIVE", detail.status());
        assertEquals(DEADLINE, detail.effectiveAt());
    }

    @Test
    void finalizeDueBatchProcessesOnlyDueRegistrations() {
        long productA = newProduct();
        long productB = newProduct();
        RegistrationResponse a = service.submitInitial(normalSubmit(rid(), productA), T0);
        RegistrationResponse b = service.submitInitial(normalSubmit(rid(), productB), T0);
        assertEquals(0, service.finalizeDue(DEADLINE.minusSeconds(1)));
        assertEquals(2, service.finalizeDue(DEADLINE));
        assertEquals("EFFECTIVE", service.getDetail(a.registrationId()).status());
        assertEquals("EFFECTIVE", service.getDetail(b.registrationId()).status());
    }

    @Test
    void objectionDuringPublicityMovesToObjectionReview() {
        long productId = newProduct();
        RegistrationResponse submit = service.submitInitial(normalSubmit(rid(), productId), T0);
        ObjectionResponse objection = service.submitObjection(
                submit.registrationId(), "合成异议：宣称依据不足", T0.plusSeconds(5L * 24 * 3600));
        assertEquals("OBJECTION_REVIEW", objection.registrationStatus());
        // 异议审查中期满不生效
        assertFalse(service.finalizeIfDue(submit.registrationId(), DEADLINE));
        assertEquals("OBJECTION_REVIEW", service.getDetail(submit.registrationId()).status());
        // 异议不成立且公示期已满 → 直接生效
        RegistrationResponse resolved = service.resolveObjection(
                submit.registrationId(), "DISMISSED", DEADLINE.plusSeconds(3600));
        assertEquals("EFFECTIVE", resolved.status());
    }

    @Test
    void objectionDismissedBeforeDeadlineReturnsToPublicity() {
        long productId = newProduct();
        RegistrationResponse submit = service.submitInitial(normalSubmit(rid(), productId), T0);
        service.submitObjection(submit.registrationId(), "合成异议", T0.plusSeconds(24 * 3600));
        RegistrationResponse resolved = service.resolveObjection(
                submit.registrationId(), "DISMISSED", T0.plusSeconds(2L * 24 * 3600));
        assertEquals("PUBLICITY", resolved.status());
        assertTrue(service.finalizeIfDue(submit.registrationId(), DEADLINE));
        assertEquals("EFFECTIVE", service.getDetail(submit.registrationId()).status());
    }

    @Test
    void objectionUpheldRejectsRegistration() {
        long productId = newProduct();
        RegistrationResponse submit = service.submitInitial(normalSubmit(rid(), productId), T0);
        service.submitObjection(submit.registrationId(), "合成异议", T0.plusSeconds(24 * 3600));
        RegistrationResponse resolved = service.resolveObjection(
                submit.registrationId(), "UPHELD", T0.plusSeconds(2L * 24 * 3600));
        assertEquals("REJECTED", resolved.status());
        assertFalse(service.finalizeIfDue(submit.registrationId(), DEADLINE));
    }

    @Test
    void objectionAfterDeadlineIsRejected() {
        long productId = newProduct();
        RegistrationResponse submit = service.submitInitial(normalSubmit(rid(), productId), T0);
        assertTrue(service.finalizeIfDue(submit.registrationId(), DEADLINE));
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.submitObjection(submit.registrationId(), "迟到异议", DEADLINE.plusSeconds(1)));
        assertEquals(409, exception.status());
        assertEquals("EFFECTIVE", service.getDetail(submit.registrationId()).status());
    }

    @Test
    void objectionAndFinalizeRaceYieldsUniqueFinalState() throws Exception {
        for (int round = 0; round < 20; round++) {
            long productId = newProduct();
            RegistrationResponse submit = service.submitInitial(normalSubmit(rid(), productId), T0);
            long registrationId = submit.registrationId();

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Object> objectionResult = new AtomicReference<>();
            AtomicReference<Boolean> finalizeResult = new AtomicReference<>();
            Future<?> objectionFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    objectionResult.set(service.submitObjection(registrationId, "竞态异议", DEADLINE.minusSeconds(1)));
                } catch (BusinessException e) {
                    objectionResult.set(e);
                }
                return null;
            });
            Future<?> finalizeFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                finalizeResult.set(service.finalizeIfDue(registrationId, DEADLINE));
                return null;
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            objectionFuture.get(30, TimeUnit.SECONDS);
            finalizeFuture.get(30, TimeUnit.SECONDS);
            executor.shutdown();

            boolean objectionAccepted = objectionResult.get() instanceof ObjectionResponse;
            boolean finalized = Boolean.TRUE.equals(finalizeResult.get());
            // 不得既生效又受理异议：两者恰有一个成立
            assertNotEquals(objectionAccepted, finalized,
                    "第" + round + "轮：公示生效与异议受理必须形成唯一终态");
            String finalStatus = service.getDetail(registrationId).status();
            if (objectionAccepted) {
                assertEquals("OBJECTION_REVIEW", finalStatus);
            } else {
                assertInstanceOf(BusinessException.class, objectionResult.get());
                assertEquals("EFFECTIVE", finalStatus);
            }
        }
    }

    @Test
    void substantialChangeRereviewsAndSwitchesVersionAtomically() {
        long productId = newProduct();
        long v1Id = submitAndFinalize(productId);
        String v1No = service.getDetail(v1Id).registrationNo();

        Instant changeAt = Instant.parse("2026-01-12T00:00:00Z");
        var changeRequest = new SubmitChangeRequest(rid(), "SUBSTANTIAL", "实质变更新包装",
                List.of("SUNSCREEN"),
                List.of(new IngredientPayload("HOMOSALATE", new BigDecimal("10.0"))),
                List.of(new ReportPayload("SUNSCREEN", "SUPPORT", LocalDate.of(2024, 6, 1), "合成检验机构")));
        RegistrationResponse v2 = service.submitChange(v1Id, changeRequest, changeAt);
        // 实质变更重新审查并生成新备案编号
        assertEquals("PUBLICITY", v2.status());
        assertEquals(2, v2.version());
        assertNotEquals(v1No, v2.registrationNo());
        // 公示期间旧版本仍生效
        assertEquals("EFFECTIVE", service.getDetail(v1Id).status());

        // 新版本期满生效，旧版本同事务失效
        Instant v2Deadline = Instant.parse("2026-01-22T00:00:00Z");
        assertTrue(service.finalizeIfDue(v2.registrationId(), v2Deadline));
        assertEquals("EFFECTIVE", service.getDetail(v2.registrationId()).status());
        assertEquals("SUPERSEDED", service.getDetail(v1Id).status());
        assertEquals(1, registrationRepository.countEffectiveByProduct(productId));

        var chain = service.getChangeChain(productId);
        assertEquals(2, chain.size());
        assertEquals(v1Id, chain.get(1).previousId());
    }

    @Test
    void substantialChangeFailingReviewKeepsOldVersionEffective() {
        long productId = newProduct();
        long v1Id = submitAndFinalize(productId);
        var changeRequest = new SubmitChangeRequest(rid(), "SUBSTANTIAL", "实质变更新包装",
                List.of("MOISTURIZING"),
                List.of(new IngredientPayload("SALICYLIC_ACID", new BigDecimal("2.1"))),
                List.of());
        RegistrationResponse v2 = service.submitChange(v1Id, changeRequest, Instant.parse("2026-01-12T00:00:00Z"));
        assertEquals("REJECTED", v2.status());
        assertEquals("EFFECTIVE", service.getDetail(v1Id).status());
        assertEquals(1, registrationRepository.countEffectiveByProduct(productId));
    }

    @Test
    void simpleChangeReusesOriginalNumberAndSupersedesAtomically() {
        long productId = newProduct();
        long v1Id = submitAndFinalize(productId);
        String v1No = service.getDetail(v1Id).registrationNo();

        RegistrationResponse v2 = service.submitChange(v1Id,
                new SubmitChangeRequest(rid(), "SIMPLE", "仅包装文字调整", null, null, null),
                Instant.parse("2026-01-12T00:00:00Z"));
        // 简易变更引用原编号并立即生效，旧版本同事务失效
        assertEquals("EFFECTIVE", v2.status());
        assertEquals(v1No, v2.registrationNo());
        assertEquals(2, v2.version());
        assertEquals("SUPERSEDED", service.getDetail(v1Id).status());
        assertEquals(1, registrationRepository.countEffectiveByProduct(productId));
        // 宣称与配方自上一版本继承
        assertEquals(List.of("MOISTURIZING"), service.getDetail(v2.registrationId()).claims());
    }

    @Test
    void changeOnNonEffectiveRegistrationIsRejected() {
        long productId = newProduct();
        RegistrationResponse submit = service.submitInitial(normalSubmit(rid(), productId), T0);
        BusinessException exception = assertThrows(BusinessException.class, () ->
                service.submitChange(submit.registrationId(),
                        new SubmitChangeRequest(rid(), "SIMPLE", "新包装", null, null, null), T0));
        assertEquals(409, exception.status());
    }

    @Test
    void sameKeySameParamsReturnsFirstResult() {
        long productId = newProduct();
        var request = normalSubmit(rid(), productId);
        RegistrationResponse first = service.submitInitial(request, T0);
        RegistrationResponse replayed = service.submitInitial(request, T0.plusSeconds(600));
        assertEquals(first.registrationId(), replayed.registrationId());
        assertEquals(first.registrationNo(), replayed.registrationNo());
        assertThat(registrationRepository.findByProductId(productId)).hasSize(1);
    }

    @Test
    void sameKeyDifferentParamsReturns409() {
        long productId = newProduct();
        String requestId = rid();
        service.submitInitial(normalSubmit(requestId, productId), T0);
        var tampered = new SubmitRegistrationRequest(requestId, productId, "篡改后的包装文字",
                List.of("MOISTURIZING"),
                List.of(new IngredientPayload("GLYCERIN", new BigDecimal("5.0"))),
                List.of());
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.submitInitial(tampered, T0.plusSeconds(1)));
        assertEquals(409, exception.status());
    }

    @Test
    void changeSubmissionIsAlsoIdempotent() {
        long productId = newProduct();
        long v1Id = submitAndFinalize(productId);
        var changeRequest = new SubmitChangeRequest(rid(), "SIMPLE", "仅包装文字调整", null, null, null);
        Instant changeAt = Instant.parse("2026-01-12T00:00:00Z");
        RegistrationResponse first = service.submitChange(v1Id, changeRequest, changeAt);
        RegistrationResponse replayed = service.submitChange(v1Id, changeRequest, changeAt.plusSeconds(60));
        assertEquals(first.registrationId(), replayed.registrationId());
        assertThat(registrationRepository.findByProductId(productId)).hasSize(2);
    }

    @Test
    void concurrentSameKeySubmitYieldsSingleRegistration() throws Exception {
        long productId = newProduct();
        var request = normalSubmit(rid(), productId);
        int threads = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RegistrationResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                return service.submitInitial(request, T0);
            }));
        }
        start.countDown();
        List<RegistrationResponse> responses = new ArrayList<>();
        for (Future<RegistrationResponse> future : futures) {
            responses.add(future.get(30, TimeUnit.SECONDS));
        }
        executor.shutdown();
        long firstId = responses.get(0).registrationId();
        assertThat(responses).allMatch(response -> response.registrationId() == firstId);
        assertThat(registrationRepository.findByProductId(productId)).hasSize(1);
    }

    @Test
    void cancellationIsTerminalAndIdempotent() {
        long productId = newProduct();
        long registrationId = submitAndFinalize(productId);

        RegistrationResponse cancel1 = service.cancel(registrationId, DEADLINE.plusSeconds(3600));
        assertEquals("CANCELLED", cancel1.status());
        // 重复注销返回幂等成功
        RegistrationResponse cancel2 = service.cancel(registrationId, DEADLINE.plusSeconds(7200));
        assertEquals("CANCELLED", cancel2.status());
        assertEquals(cancel1.registrationId(), cancel2.registrationId());
        // 注销后新宣称审查一律拒绝
        BusinessException submitException = assertThrows(BusinessException.class,
                () -> service.submitInitial(normalSubmit(rid(), productId), DEADLINE.plusSeconds(7300)));
        assertEquals(422, submitException.status());
        BusinessException changeException = assertThrows(BusinessException.class, () ->
                service.submitChange(registrationId,
                        new SubmitChangeRequest(rid(), "SIMPLE", "新包装", null, null, null),
                        DEADLINE.plusSeconds(7400)));
        assertEquals(422, changeException.status());
        // 历史备案可查
        assertEquals("CANCELLED", service.getDetail(registrationId).status());
        assertThat(service.getChangeChain(productId)).hasSize(1);
    }

    @Test
    void cancelNonEffectiveRegistrationIsRejected() {
        long productId = newProduct();
        RegistrationResponse submit = service.submitInitial(normalSubmit(rid(), productId), T0);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.cancel(submit.registrationId(), T0.plusSeconds(3600)));
        assertEquals(409, exception.status());
    }

    @Test
    void secondInitialSubmitWhileActiveIsRejected() {
        long productId = newProduct();
        service.submitInitial(normalSubmit(rid(), productId), T0);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.submitInitial(normalSubmit(rid(), productId), T0.plusSeconds(1)));
        assertEquals(409, exception.status());
    }
}
