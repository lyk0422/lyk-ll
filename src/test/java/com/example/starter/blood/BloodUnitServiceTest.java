package com.example.starter.blood;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import com.example.starter.blood.domain.TestOutcome;
import com.example.starter.blood.domain.TestType;
import com.example.starter.blood.domain.UnitStatus;
import com.example.starter.blood.repo.LateEventRepository;
import com.example.starter.blood.service.ApiException;
import com.example.starter.blood.service.BloodUnitService;
import com.example.starter.blood.service.TestResultAck;

/**
 * 成分血状态机测试：乱序回传、隔离、报废终态与迟到事件。
 */
class BloodUnitServiceTest extends BloodServiceTestBase {

    @Autowired
    private BloodUnitService unitService;

    @Autowired
    private LateEventRepository lateEventRepository;

    @Test
    void checkInStartsPendingAndRejectsDuplicateBarcode() {
        var unit = unitService.checkIn("BC-001", "红细胞", LocalDate.of(2026, 10, 1));
        assertThat(unit.status()).isEqualTo(UnitStatus.PENDING);
        // 效期当日 23:59:59 UTC 为可放行截止边界
        assertThat(unit.expireAt().toString()).isEqualTo("2026-10-01T23:59:59Z");

        assertThatThrownBy(() -> unitService.checkIn("BC-001", "血浆", LocalDate.of(2026, 10, 2)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void outOfOrderResultsPassThroughIsolatedThenReleasable() {
        unitService.checkIn("BC-002", "血小板", LocalDate.of(2026, 10, 1));

        // 复检先回传：一项合格一项未回，保持隔离
        TestResultAck first = unitService.applyTestResult("BC-002", TestType.RECHECK, TestOutcome.QUALIFIED);
        assertThat(first.status()).isEqualTo(UnitStatus.ISOLATED);
        assertThat(first.late()).isFalse();

        // 初检后回传：两项均合格，进入可放行
        TestResultAck second = unitService.applyTestResult("BC-002", TestType.INITIAL, TestOutcome.QUALIFIED);
        assertThat(second.status()).isEqualTo(UnitStatus.RELEASABLE);
    }

    @Test
    void anyUnqualifiedDiscardsImmediately() {
        unitService.checkIn("BC-003", "红细胞", LocalDate.of(2026, 10, 1));
        TestResultAck ack = unitService.applyTestResult("BC-003", TestType.INITIAL, TestOutcome.UNQUALIFIED);
        assertThat(ack.status()).isEqualTo(UnitStatus.DISCARDED);
    }

    @Test
    void terminalStateIsNotRevivedByLateQualifiedResult() {
        unitService.checkIn("BC-004", "红细胞", LocalDate.of(2026, 10, 1));
        unitService.applyTestResult("BC-004", TestType.RECHECK, TestOutcome.UNQUALIFIED);
        assertThat(unitService.getUnit("BC-004").status()).isEqualTo(UnitStatus.DISCARDED);

        // 报废终态后合格结果回传：不复活，仅记录迟到事件
        TestResultAck late = unitService.applyTestResult("BC-004", TestType.INITIAL, TestOutcome.QUALIFIED);
        assertThat(late.late()).isTrue();
        assertThat(late.status()).isEqualTo(UnitStatus.DISCARDED);
        assertThat(unitService.getUnit("BC-004").status()).isEqualTo(UnitStatus.DISCARDED);
        assertThat(lateEventRepository.countByBarcode("BC-004")).isEqualTo(1);
    }

    @Test
    void unknownBarcodeReturnsNotFound() {
        assertThatThrownBy(() -> unitService.applyTestResult("BC-404", TestType.INITIAL, TestOutcome.QUALIFIED))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
