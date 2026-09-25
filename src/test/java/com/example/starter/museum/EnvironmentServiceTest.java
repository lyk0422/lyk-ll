package com.example.starter.museum;

import com.example.starter.museum.api.ContractResponse;
import com.example.starter.museum.api.ReadingResponse;
import com.example.starter.museum.api.WorkOrderResponse;
import com.example.starter.museum.domain.ContractStatus;
import com.example.starter.museum.domain.HandoverType;
import com.example.starter.museum.domain.Party;
import com.example.starter.museum.domain.WorkOrderStatus;
import com.example.starter.museum.service.EnvironmentService;
import com.example.starter.museum.service.LoanService;
import com.example.starter.museum.support.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 微环境判异与处置：连续 3 点越限（恰 3 触发）、乱序归位、展柜+时刻去重、
 * 24 小时处置时限恰满升级紧急并冻结、异常挂起与双方复核恢复、结案后迟到读数。
 */
class EnvironmentServiceTest extends MuseumTestBase {

    private static final BigDecimal TEMP_OK = new BigDecimal("20.00");
    private static final BigDecimal TEMP_BAD = new BigDecimal("30.00");
    private static final BigDecimal HUM_OK = new BigDecimal("45.00");

    @Autowired
    private EnvironmentService environmentService;
    @Autowired
    private LoanService loanService;

    @Test
    void threeConsecutiveOutOfRangeReadingsTriggerAtExactlyThird() {
        ContractResponse contract = inExhibitionLoan();
        Instant t0 = Instant.parse("2026-03-01T00:00:00Z");

        fixClock("2026-03-01T00:05:00Z");
        ReadingResponse r1 = ingest("S001", t0, TEMP_BAD);
        assertThat(r1.outOfRange()).isTrue();
        assertThat(r1.alertTriggered()).isFalse();

        fixClock("2026-03-01T01:05:00Z");
        ReadingResponse r2 = ingest("S001", t0.plus(1, ChronoUnit.HOURS), TEMP_BAD);
        assertThat(r2.alertTriggered()).isFalse();

        fixClock("2026-03-01T02:05:00Z");
        ReadingResponse r3 = ingest("S001", t0.plus(2, ChronoUnit.HOURS), TEMP_BAD);
        assertThat(r3.alertTriggered()).as("恰第 3 个连续越限点触发").isTrue();
        assertThat(r3.alertNo()).startsWith("AL-");
        assertThat(r3.workOrderNo()).startsWith("WO-");

        // 异常期间合同自动挂起
        assertThat(loanService.getContract(contract.contractNo()).status())
                .isEqualTo(ContractStatus.SUSPENDED);

        // 已存在未关闭异常，后续越限读数不再重复触发
        fixClock("2026-03-01T03:05:00Z");
        ReadingResponse r4 = ingest("S001", t0.plus(3, ChronoUnit.HOURS), TEMP_BAD);
        assertThat(r4.alertTriggered()).isFalse();
    }

    @Test
    void outOfOrderReadingsAreAlignedBySampleTimeAndStillTrigger() {
        inExhibitionLoan();
        Instant t1 = Instant.parse("2026-03-01T01:00:00Z");
        Instant t2 = Instant.parse("2026-03-01T02:00:00Z");
        Instant t3 = Instant.parse("2026-03-01T03:00:00Z");
        fixClock("2026-03-01T03:10:00Z");

        // 乱序：先 t3，再 t1，再 t2；序列在 t2 入库时补齐为连续 3 点
        assertThat(ingest("S001", t3, TEMP_BAD).alertTriggered()).isFalse();
        assertThat(ingest("S001", t1, TEMP_BAD).alertTriggered()).isFalse();
        ReadingResponse aligned = ingest("S001", t2, TEMP_BAD);
        assertThat(aligned.alertTriggered()).isTrue();
        java.sql.Timestamp stored = jdbc.queryForObject(
                "SELECT trigger_time FROM environment_alert WHERE alert_no = ?",
                java.sql.Timestamp.class, aligned.alertNo());
        Instant triggerTime = stored.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC);
        // 触发点按采样时刻归位为最晚的 t3
        assertThat(triggerTime).isEqualTo(t3);
    }

    @Test
    void normalReadingBreaksTheConsecutiveWindow() {
        inExhibitionLoan();
        Instant t0 = Instant.parse("2026-03-01T00:00:00Z");
        fixClock("2026-03-01T00:05:00Z");
        ingest("S001", t0, TEMP_BAD);
        ingest("S001", t0.plusSeconds(3600), TEMP_OK);
        ReadingResponse third = ingest("S001", t0.plusSeconds(7200), TEMP_BAD);
        assertThat(third.alertTriggered()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM environment_alert", Integer.class)).isZero();
    }

    @Test
    void duplicateShowcaseAndSampleTimeIsDeduplicated() {
        fixClock("2026-03-01T00:05:00Z");
        Instant t = Instant.parse("2026-03-01T00:00:00Z");
        ingest("S001", t, TEMP_OK);
        ReadingResponse dup = ingest("S001", t, TEMP_BAD);
        assertThat(dup.duplicate()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM environment_reading", Integer.class)).isEqualTo(1);
    }

    @Test
    void exactlyTwentyFourHoursWithoutConfirmationEscalatesAndFreezesShowcase() {
        ContractResponse contract = inExhibitionLoan();
        Instant t0 = Instant.parse("2026-03-01T00:00:00Z");
        fixClock("2026-03-01T02:05:00Z");
        ingest("S001", t0, TEMP_BAD);
        ingest("S001", t0.plusSeconds(3600), TEMP_BAD);
        ReadingResponse trigger = ingest("S001", t0.plusSeconds(7200), TEMP_BAD);
        Instant triggerTime = t0.plusSeconds(7200);

        // 差 1 秒到 24h：未升级
        fixClock(triggerTime.plus(24, ChronoUnit.HOURS).minusSeconds(1).toString());
        assertThat(environmentService.escalateDueWorkOrders()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM environment_alert", String.class))
                .isEqualTo("OPEN");

        // 恰满 24h：升级 URGENT 并冻结展柜新入藏
        fixClock(triggerTime.plus(24, ChronoUnit.HOURS).toString());
        assertThat(environmentService.escalateDueWorkOrders()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM environment_alert", String.class))
                .isEqualTo("URGENT");
        assertThat(jdbc.queryForObject("SELECT frozen FROM showcase WHERE showcase_no = 'S001'",
                Boolean.class)).isTrue();

        // 冻结期间新入藏（新借展指定该柜）被拒
        assertThatThrownBy(() -> loanService.apply("A003", "迟到的展方",
                LocalDate.of(2026, 3, 2), LocalDate.of(2026, 6, 2), "S001"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("冻结");

        // 逾期后确认处置：工单完成、冻结解除
        fixClock(triggerTime.plus(25, ChronoUnit.HOURS).toString());
        WorkOrderResponse confirmed = environmentService.confirmWorkOrder(
                trigger.workOrderNo(), "保护员甲", "已启动应急除湿");
        assertThat(confirmed.status()).isEqualTo(WorkOrderStatus.CONFIRMED);
        assertThat(confirmed.showcaseFrozen()).isFalse();
        assertThat(contract.contractNo()).isNotNull();
    }

    @Test
    void confirmationWithinTwentyFourHoursStaysOpenAndDoesNotFreeze() {
        inExhibitionLoan();
        Instant t0 = Instant.parse("2026-03-01T00:00:00Z");
        fixClock("2026-03-01T02:05:00Z");
        ingest("S001", t0, TEMP_BAD);
        ingest("S001", t0.plusSeconds(3600), TEMP_BAD);
        ReadingResponse trigger = ingest("S001", t0.plusSeconds(7200), TEMP_BAD);

        fixClock(t0.plusSeconds(7200).plus(23, ChronoUnit.HOURS).toString());
        WorkOrderResponse confirmed = environmentService.confirmWorkOrder(
                trigger.workOrderNo(), "保护员乙", "23 小时内处置");
        assertThat(confirmed.status()).isEqualTo(WorkOrderStatus.CONFIRMED);
        assertThat(confirmed.showcaseFrozen()).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM environment_alert", String.class)).isEqualTo("OPEN");

        // 到 24h 后再扫描，已确认工单不再升级
        fixClock(t0.plusSeconds(7200).plus(25, ChronoUnit.HOURS).toString());
        assertThat(environmentService.escalateDueWorkOrders()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM environment_alert", String.class)).isEqualTo("OPEN");
    }

    @Test
    void suspensionRecoveryRequiresEnvironmentRecoveryWorkOrderAndBothPartyReviews() {
        ContractResponse contract = inExhibitionLoan();
        Instant t0 = Instant.parse("2026-03-01T00:00:00Z");
        fixClock("2026-03-01T02:05:00Z");
        ingest("S001", t0, TEMP_BAD);
        ingest("S001", t0.plusSeconds(3600), TEMP_BAD);
        ReadingResponse trigger = ingest("S001", t0.plusSeconds(7200), TEMP_BAD);
        assertThat(loanService.getContract(contract.contractNo()).status())
                .isEqualTo(ContractStatus.SUSPENDED);

        // 挂起期间不能归还点交、不能续展
        assertThatThrownBy(() -> loanService.confirmHandover(
                contract.contractNo(), HandoverType.RETURN, Party.LENDER))
                .isInstanceOf(ApiException.class);

        // 未恢复正常前不能复核
        assertThatThrownBy(() -> environmentService.reviewResume(contract.contractNo(), Party.LENDER))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("恢复");

        // 正常读数已到，但工单尚未确认处置：仍不进入待复核，合同继续挂起
        fixClock("2026-03-01T03:05:00Z");
        ReadingResponse recovered = ingest("S001", t0.plusSeconds(3 * 3600), TEMP_OK);
        assertThat(recovered.alertTriggered()).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM environment_alert", String.class))
                .isEqualTo("OPEN");
        assertThatThrownBy(() -> environmentService.reviewResume(contract.contractNo(), Party.LENDER))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("恢复");

        // 24h 内确认工单：读数此前已正常，确认即推动异常进入待双方复核
        environmentService.confirmWorkOrder(trigger.workOrderNo(), "保护员甲", "处置完成");
        assertThat(jdbc.queryForObject("SELECT status FROM environment_alert", String.class))
                .isEqualTo("AWAIT_REVIEW");

        // 单方复核不生效
        ContractResponse oneSide = environmentService.reviewResume(contract.contractNo(), Party.LENDER);
        assertThat(oneSide.status()).isEqualTo(ContractStatus.SUSPENDED);
        assertThat(jdbc.queryForObject("SELECT status FROM environment_alert", String.class))
                .isEqualTo("AWAIT_REVIEW");

        // 双方齐备：恢复在展、异常关闭
        ContractResponse resumed = environmentService.reviewResume(contract.contractNo(), Party.BORROWER);
        assertThat(resumed.status()).isEqualTo(ContractStatus.IN_EXHIBITION);
        assertThat(resumed.suspendedAt()).isNotNull();
        assertThat(resumed.resumedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM environment_alert", String.class))
                .isEqualTo("RESOLVED");

        // 恢复后可正常归还
        loanService.confirmHandover(contract.contractNo(), HandoverType.RETURN, Party.LENDER);
        loanService.confirmHandover(contract.contractNo(), HandoverType.RETURN, Party.BORROWER);
        assertThat(loanService.getContract(contract.contractNo()).status())
                .isEqualTo(ContractStatus.RETURNED);
    }

    @Test
    void lateReadingAfterClosureOnlyRecordsEvent() {
        ContractResponse contract = inExhibitionLoan();
        fixClock("2026-03-01T00:05:00Z");
        ingest("S001", Instant.parse("2026-03-01T00:00:00Z"), TEMP_OK);

        loanService.confirmHandover(contract.contractNo(), HandoverType.RETURN, Party.LENDER);
        loanService.confirmHandover(contract.contractNo(), HandoverType.RETURN, Party.BORROWER);
        loanService.close(contract.contractNo());

        ReadingResponse late = environmentService.ingestReading(
                "S001", Instant.parse("2026-04-01T00:00:00Z"), TEMP_BAD, HUM_OK);
        assertThat(late.late()).isTrue();
        assertThat(late.alertTriggered()).isFalse();
        Integer lateEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM loan_event WHERE type = 'LATE_READING'", Integer.class);
        assertThat(lateEvents).isEqualTo(1);
        Integer readings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM environment_reading WHERE late = FALSE", Integer.class);
        assertThat(readings).isEqualTo(1);
    }

    @Test
    void healthyReadingsWithoutWorkOrderConfirmationStillEscalateThenCanRecover() {
        ContractResponse contract = inExhibitionLoan();
        Instant t0 = Instant.parse("2026-03-01T00:00:00Z");
        Instant triggerTime = t0.plusSeconds(7200);
        fixClock("2026-03-01T02:05:00Z");
        ingest("S001", t0, TEMP_BAD);
        ingest("S001", t0.plusSeconds(3600), TEMP_BAD);
        ReadingResponse trigger = ingest("S001", triggerTime, TEMP_BAD);
        // 读数很快恢复正常，但无人确认处置工单
        fixClock("2026-03-01T03:05:00Z");
        ingest("S001", t0.plusSeconds(3 * 3600), TEMP_OK);
        assertThat(jdbc.queryForObject("SELECT status FROM environment_alert", String.class))
                .isEqualTo("OPEN");

        // 恰满 24h 未确认：照样升级紧急并冻结
        fixClock(triggerTime.plus(24, ChronoUnit.HOURS).toString());
        assertThat(environmentService.escalateDueWorkOrders()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM environment_alert", String.class))
                .isEqualTo("URGENT");
        assertThat(jdbc.queryForObject("SELECT frozen FROM showcase WHERE showcase_no='S001'",
                Boolean.class)).isTrue();

        // 逾期确认处置：读数此前已正常 -> 进入待复核；双方复核后复展
        WorkOrderResponse confirmed = environmentService.confirmWorkOrder(
                trigger.workOrderNo(), "保护员丙", "逾期处置完成");
        assertThat(confirmed.status()).isEqualTo(WorkOrderStatus.CONFIRMED);
        assertThat(jdbc.queryForObject("SELECT status FROM environment_alert", String.class))
                .isEqualTo("AWAIT_REVIEW");
        environmentService.reviewResume(contract.contractNo(), Party.LENDER);
        ContractResponse resumed = environmentService.reviewResume(contract.contractNo(), Party.BORROWER);
        assertThat(resumed.status()).isEqualTo(ContractStatus.IN_EXHIBITION);
        assertThat(jdbc.queryForObject("SELECT status FROM environment_alert", String.class))
                .isEqualTo("RESOLVED");
    }

    private ContractResponse inExhibitionLoan() {
        ContractResponse c = loanService.apply("A001", "省博物院",
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), "S001");
        loanService.confirmHandover(c.contractNo(), HandoverType.OUTBOUND, Party.LENDER);
        loanService.confirmHandover(c.contractNo(), HandoverType.OUTBOUND, Party.BORROWER);
        return loanService.getContract(c.contractNo());
    }

    private ReadingResponse ingest(String showcaseNo, Instant sampleTime, BigDecimal temp) {
        return environmentService.ingestReading(showcaseNo, sampleTime, temp, HUM_OK);
    }
}
