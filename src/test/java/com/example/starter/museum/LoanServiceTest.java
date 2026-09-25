package com.example.starter.museum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 借展合同业务规则测试：唯一有效合同、3 年期限、双方点交、续展窗口与结案终态。
 */
class LoanServiceTest extends MuseumTestSupport {

    @Test
    void applyCreatesContractInAppliedStatus() {
        Map<String, Object> view = loanService.apply(newRequestId(), ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER,
                "某省博物馆", LocalDate.of(2026, 2, 1), LocalDate.of(2027, 2, 1));
        assertEquals(LoanService.STATUS_APPLIED, view.get("status"));
        assertEquals(ARTIFACT_LEVEL_ONE, ((Number) view.get("artifactId")).longValue());
        assertEquals(1, count("loan_contract"));
        assertEquals(1, count("artifact_active_loan"));
    }

    @Test
    void applyAcceptsExactlyThreeYears() {
        // 恰满 3 年合法
        Map<String, Object> view = loanService.apply(newRequestId(), ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER,
                "某省博物馆", LocalDate.of(2026, 1, 1), LocalDate.of(2029, 1, 1));
        assertEquals(LoanService.STATUS_APPLIED, view.get("status"));
    }

    @Test
    void applyRejectsPeriodOverThreeYears() {
        ApiException ex = assertThrows(ApiException.class, () -> loanService.apply(newRequestId(),
                ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER, "某省博物馆",
                LocalDate.of(2026, 1, 1), LocalDate.of(2029, 1, 2)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals(0, count("loan_contract"));
    }

    @Test
    void applyRejectsSecondActiveContractForSameLevelOneArtifact() {
        loanService.apply(newRequestId(), ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER, "甲馆",
                LocalDate.of(2026, 2, 1), LocalDate.of(2027, 2, 1));
        ApiException ex = assertThrows(ApiException.class, () -> loanService.apply(newRequestId(),
                ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER, "乙馆",
                LocalDate.of(2026, 3, 1), LocalDate.of(2027, 3, 1)));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        // 事务回滚：失败的申请不留下合同记录
        assertEquals(1, count("loan_contract"));
        assertEquals(1, count("artifact_active_loan"));
    }

    @Test
    void deliveryHandoverTakesEffectOnlyAfterBothPartiesConfirm() {
        long contractId = ((Number) loanService.apply(newRequestId(), ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER,
                "某省博物馆", LocalDate.of(2026, 2, 1), LocalDate.of(2027, 2, 1)).get("contractId")).longValue();
        Map<String, Object> first = loanService.confirmHandover(contractId, "DELIVERY", LoanService.PARTY_MUSEUM);
        assertFalse((Boolean) first.get("effective"));
        assertEquals(LoanService.STATUS_APPLIED, contractStatus(contractId));
        // 同一方重复确认不生效
        Map<String, Object> repeat = loanService.confirmHandover(contractId, "DELIVERY", LoanService.PARTY_MUSEUM);
        assertFalse((Boolean) repeat.get("effective"));
        assertEquals(LoanService.STATUS_APPLIED, contractStatus(contractId));
        Map<String, Object> second = loanService.confirmHandover(contractId, "DELIVERY", LoanService.PARTY_BORROWER);
        assertTrue((Boolean) second.get("effective"));
        assertEquals(LoanService.STATUS_ON_DISPLAY, contractStatus(contractId));
    }

    @Test
    void returnHandoverTakesEffectOnlyAfterBothPartiesConfirm() {
        long contractId = applyAndDisplay(ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER,
                LocalDate.of(2026, 2, 1), LocalDate.of(2027, 2, 1));
        Map<String, Object> first = loanService.confirmHandover(contractId, "RETURN", LoanService.PARTY_BORROWER);
        assertFalse((Boolean) first.get("effective"));
        assertEquals(LoanService.STATUS_ON_DISPLAY, contractStatus(contractId));
        Map<String, Object> second = loanService.confirmHandover(contractId, "RETURN", LoanService.PARTY_MUSEUM);
        assertTrue((Boolean) second.get("effective"));
        assertEquals(LoanService.STATUS_RETURNED, contractStatus(contractId));
    }

    @Test
    void extendRejectedBeforeSixtyDayWindow() {
        long contractId = applyAndDisplay(ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        // 届满前第 61 日：拒绝
        clock.setInstant(java.time.Instant.parse("2026-10-31T00:00:00Z"));
        ApiException ex = assertThrows(ApiException.class,
                () -> loanService.extend(contractId, LocalDate.of(2027, 3, 1)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
    }

    @Test
    void extendAcceptedExactlyAtSixtyDaysBeforeExpiry() {
        long contractId = applyAndDisplay(ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        // 恰届满前第 60 日：受理
        clock.setInstant(java.time.Instant.parse("2026-11-01T00:00:00Z"));
        Map<String, Object> view = loanService.extend(contractId, LocalDate.of(2027, 3, 1));
        assertEquals(true, view.get("extended"));
        assertEquals("2027-03-01", view.get("endDate"));
    }

    @Test
    void extendLimitedToHalfOfOriginalPeriod() {
        // 原期限 2026-01-01 ~ 2027-01-01 共 365 天，一半为 182 天
        long contractId = applyAndDisplay(ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER,
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
        clock.setInstant(java.time.Instant.parse("2026-12-01T00:00:00Z"));
        ApiException ex = assertThrows(ApiException.class,
                () -> loanService.extend(contractId, LocalDate.of(2027, 1, 1).plusDays(183)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        // 恰一半合法
        Map<String, Object> view = loanService.extend(contractId, LocalDate.of(2027, 1, 1).plusDays(182));
        assertEquals(true, view.get("extended"));
    }

    @Test
    void extendAllowedOnlyOnce() {
        long contractId = applyAndDisplay(ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        clock.setInstant(java.time.Instant.parse("2026-11-01T00:00:00Z"));
        loanService.extend(contractId, LocalDate.of(2027, 2, 1));
        ApiException ex = assertThrows(ApiException.class,
                () -> loanService.extend(contractId, LocalDate.of(2027, 3, 1)));
        assertEquals(HttpStatus.CONFLICT, ex.status());
    }

    @Test
    void closeRequiresCompletedReturnHandover() {
        long contractId = applyAndDisplay(ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER,
                LocalDate.of(2026, 2, 1), LocalDate.of(2027, 2, 1));
        ApiException ex = assertThrows(ApiException.class, () -> loanService.close(contractId));
        assertEquals(HttpStatus.CONFLICT, ex.status());
    }

    @Test
    void closeIsTerminalAndRepeatCloseReturnsIdempotentSuccess() {
        long contractId = applyAndDisplay(ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER,
                LocalDate.of(2026, 2, 1), LocalDate.of(2027, 2, 1));
        loanService.confirmHandover(contractId, "RETURN", LoanService.PARTY_MUSEUM);
        loanService.confirmHandover(contractId, "RETURN", LoanService.PARTY_BORROWER);
        Map<String, Object> closed = loanService.close(contractId);
        assertEquals(LoanService.STATUS_CLOSED, closed.get("status"));
        assertEquals(0, count("artifact_active_loan"));
        // 重复结案：幂等成功
        Map<String, Object> replay = loanService.close(contractId);
        assertEquals(LoanService.STATUS_CLOSED, replay.get("status"));
        assertEquals(true, replay.get("idempotentReplay"));
        // 结案后同一文物可再次借展
        Map<String, Object> again = loanService.apply(newRequestId(), ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER,
                "另一博物馆", LocalDate.of(2027, 3, 1), LocalDate.of(2028, 3, 1));
        assertEquals(LoanService.STATUS_APPLIED, again.get("status"));
    }

    @Test
    void applyRejectsFrozenShowcase() {
        jdbc.update("UPDATE showcase SET frozen = TRUE WHERE id = ?", SHOWCASE_PAPER);
        ApiException ex = assertThrows(ApiException.class, () -> loanService.apply(newRequestId(),
                ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER, "某省博物馆",
                LocalDate.of(2026, 2, 1), LocalDate.of(2027, 2, 1)));
        assertEquals(HttpStatus.CONFLICT, ex.status());
    }
}
