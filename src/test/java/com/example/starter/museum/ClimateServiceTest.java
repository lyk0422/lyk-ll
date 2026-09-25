package com.example.starter.museum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 展柜微环境监测测试：连续 3 点越限触发、乱序归位、去重、24 小时处置边界、
 * 挂起恢复复核与结案后迟到读数。
 */
class ClimateServiceTest extends MuseumTestSupport {

    private static final Instant T0 = TestClockConfig.BASE;
    /** 越限温度：纸质展柜上限 22℃。 */
    private static final BigDecimal BREACH_TEMP = new BigDecimal("25.00");
    /** 区间内温度。 */
    private static final BigDecimal NORMAL_TEMP = new BigDecimal("20.00");
    /** 区间内湿度。 */
    private static final BigDecimal NORMAL_HUMIDITY = new BigDecimal("55.00");

    private long displayContract() {
        return applyAndDisplay(ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER,
                LocalDate.of(2026, 2, 1), LocalDate.of(2027, 2, 1));
    }

    private Map<String, Object> breachAt(Instant sampledAt) {
        return climateService.ingest(SHOWCASE_PAPER, sampledAt, BREACH_TEMP, NORMAL_HUMIDITY);
    }

    @Test
    void exactlyThreeConsecutiveBreachesTriggerOrderAndSuspend() {
        long contractId = displayContract();
        Map<String, Object> first = breachAt(T0);
        assertEquals(1, first.get("consecutiveBreaches"));
        assertEquals(0, count("protection_order"));
        Map<String, Object> second = breachAt(T0.plusSeconds(3600));
        assertEquals(2, second.get("consecutiveBreaches"));
        assertEquals(0, count("protection_order"));
        // 恰第 3 个连续越限点：触发工单并挂起合同
        Map<String, Object> third = breachAt(T0.plusSeconds(7200));
        assertEquals(3, third.get("consecutiveBreaches"));
        assertEquals(1, count("protection_order"));
        assertEquals(LoanService.STATUS_SUSPENDED, contractStatus(contractId));
        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM protection_order");
        assertEquals("OPEN", order.get("status"));
        assertEquals(contractId, ((Number) order.get("contract_id")).longValue());
        // 处置时限 = 生成时间 + 24 小时
        assertEquals(T0.plus(Duration.ofHours(24)).toString(),
                ((java.sql.Timestamp) order.get("deadline_at")).toInstant().toString());
        // 处置确认后越限序列复位：区间内读数后重新累计
        long orderId = ((Number) order.get("id")).longValue();
        climateService.confirmOrder(orderId, "文保员甲");
        climateService.ingest(SHOWCASE_PAPER, T0.plusSeconds(10800), NORMAL_TEMP, NORMAL_HUMIDITY);
        Map<String, Object> fourth = breachAt(T0.plusSeconds(14400));
        assertEquals(1, fourth.get("consecutiveBreaches"));
        assertEquals(1, count("protection_order"));
        // 复位后再次连续 3 点越限：生成新工单
        breachAt(T0.plusSeconds(18000));
        breachAt(T0.plusSeconds(21600));
        assertEquals(2, count("protection_order"));
    }

    @Test
    void outOfOrderReadingsAreRepositionedBySampleTime() {
        displayContract();
        breachAt(T0);
        Map<String, Object> second = breachAt(T0.plusSeconds(7200));
        assertEquals(2, second.get("consecutiveBreaches"));
        assertEquals(0, count("protection_order"));
        // 乱序到达的中间采样点按时刻归位后，连续越限凑满 3 点触发
        Map<String, Object> middle = breachAt(T0.plusSeconds(3600));
        assertEquals(3, middle.get("consecutiveBreaches"));
        assertEquals(1, count("protection_order"));
    }

    @Test
    void readingsAreDeduplicatedByShowcaseAndSampleTime() {
        displayContract();
        breachAt(T0);
        Map<String, Object> duplicate = breachAt(T0);
        assertEquals(true, duplicate.get("deduplicated"));
        assertEquals(1, count("climate_reading"));
    }

    @Test
    void boundaryValuesAreWithinRange() {
        displayContract();
        Map<String, Object> low = climateService.ingest(SHOWCASE_PAPER, T0,
                new BigDecimal("18.00"), new BigDecimal("50.00"));
        assertEquals(false, low.get("outOfRange"));
        Map<String, Object> high = climateService.ingest(SHOWCASE_PAPER, T0.plusSeconds(3600),
                new BigDecimal("22.00"), new BigDecimal("60.00"));
        assertEquals(false, high.get("outOfRange"));
        Map<String, Object> out = climateService.ingest(SHOWCASE_PAPER, T0.plusSeconds(7200),
                new BigDecimal("22.01"), new BigDecimal("55.00"));
        assertEquals(true, out.get("outOfRange"));
    }

    @Test
    void orderEscalatesExactlyAt24HoursAndFreezesShowcase() {
        displayContract();
        breachAt(T0);
        breachAt(T0.plusSeconds(3600));
        breachAt(T0.plusSeconds(7200));
        assertEquals(1, count("protection_order"));
        // 24 小时前 1 分钟：不升级
        clock.setInstant(T0.plus(Duration.ofHours(24)).minusSeconds(60));
        assertEquals(0, climateService.escalateDueOrders());
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM protection_order", String.class));
        // 恰满 24 小时：升级紧急并冻结展柜新入藏
        clock.setInstant(T0.plus(Duration.ofHours(24)));
        assertEquals(1, climateService.escalateDueOrders());
        assertEquals("ESCALATED", jdbc.queryForObject("SELECT status FROM protection_order", String.class));
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "SELECT frozen FROM showcase WHERE id = " + SHOWCASE_PAPER, Boolean.class));
        // 冻结期间新借展申请被拒绝
        ApiException ex = assertThrows(ApiException.class, () -> loanService.apply(newRequestId(),
                ARTIFACT_LEVEL_TWO, SHOWCASE_PAPER, "另一博物馆",
                LocalDate.of(2027, 3, 1), LocalDate.of(2028, 3, 1)));
        assertEquals(HttpStatus.CONFLICT, ex.status());
    }

    @Test
    void confirmOrderUnfreezesAndResumeRequiresBothParties() {
        long contractId = displayContract();
        breachAt(T0);
        breachAt(T0.plusSeconds(3600));
        breachAt(T0.plusSeconds(7200));
        long orderId = jdbc.queryForObject("SELECT id FROM protection_order", Long.class);
        // 工单未处置时恢复复核被拒绝
        ApiException blocked = assertThrows(ApiException.class,
                () -> loanService.resume(contractId, LoanService.PARTY_MUSEUM));
        assertEquals(HttpStatus.CONFLICT, blocked.getMessage() == null ? null : blocked.status());
        // 处置确认
        Map<String, Object> confirmed = climateService.confirmOrder(orderId, "文保员甲");
        assertEquals("CONFIRMED", confirmed.get("status"));
        // 单方复核不恢复
        Map<String, Object> oneSide = loanService.resume(contractId, LoanService.PARTY_MUSEUM);
        assertEquals(false, oneSide.get("resumed"));
        assertEquals(LoanService.STATUS_SUSPENDED, contractStatus(contractId));
        // 双方复核齐备后恢复在展
        Map<String, Object> bothSides = loanService.resume(contractId, LoanService.PARTY_BORROWER);
        assertEquals(true, bothSides.get("resumed"));
        assertEquals(LoanService.STATUS_ON_DISPLAY, contractStatus(contractId));
    }

    @Test
    void lateReadingAfterCloseOnlyRecordsEvent() {
        long contractId = displayContract();
        loanService.confirmHandover(contractId, "RETURN", LoanService.PARTY_MUSEUM);
        loanService.confirmHandover(contractId, "RETURN", LoanService.PARTY_BORROWER);
        loanService.close(contractId);
        // 结案后到达的越限读数：只记迟到事件，不触发工单
        Map<String, Object> late = climateService.ingest(SHOWCASE_PAPER, T0, BREACH_TEMP, NORMAL_HUMIDITY);
        assertEquals(true, late.get("late"));
        assertEquals(true, late.get("lateEventRecorded"));
        assertEquals(1, count("late_reading_event"));
        assertEquals(0, count("protection_order"));
    }
}
