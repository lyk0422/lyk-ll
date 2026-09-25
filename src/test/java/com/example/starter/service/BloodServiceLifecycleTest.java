package com.example.starter.service;

import com.example.starter.AbstractBloodIntegrationTest;
import com.example.starter.domain.BloodUnit;
import com.example.starter.domain.ComponentType;
import com.example.starter.domain.TestResultCode;
import com.example.starter.domain.TestType;
import com.example.starter.domain.UnitStatus;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import com.example.starter.web.dto.IntakeRequest;
import com.example.starter.web.dto.TestResultRequest;
import com.example.starter.web.dto.UnitResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入库与检测结果回传：乱序回传、隔离、直接报废、终态不复活、效期优先。
 */
class BloodServiceLifecycleTest extends AbstractBloodIntegrationTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-09-30T15:59:59Z");

    @Autowired
    private BloodService bloodService;

    private UnitResponse intake(String barcode) {
        return bloodService.intake(new IntakeRequest(barcode, ComponentType.RED_CELL, EXPIRES_AT));
    }

    private TestResultRequest result(String barcode, TestType type, TestResultCode code) {
        return new TestResultRequest(barcode, type, code, null);
    }

    @Test
    void intake_createsPendingUnitWithUniqueBarcode() {
        UnitResponse response = intake("B0001");

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.componentType()).isEqualTo("RED_CELL");
        assertThat(response.initialResult()).isNull();
        assertThat(response.repeatResult()).isNull();
        assertThat(response.expiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(response.version()).isZero();

        assertThatThrownBy(() -> intake("B0001"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void intake_unknownBarcodeReported_returns404() {
        assertThatThrownBy(() -> bloodService.reportTestResult(result("MISSING", TestType.INITIAL, TestResultCode.PASS)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void singlePass_keepsQuarantined() {
        intake("B0002");

        UnitResponse afterInitial = bloodService.reportTestResult(result("B0002", TestType.INITIAL, TestResultCode.PASS));

        assertThat(afterInitial.status()).isEqualTo("QUARANTINED");
        assertThat(afterInitial.initialResult()).isEqualTo("PASS");
        assertThat(afterInitial.repeatResult()).isNull();
    }

    @Test
    void bothPass_inOrder_becomesReleasable() {
        intake("B0003");

        bloodService.reportTestResult(result("B0003", TestType.INITIAL, TestResultCode.PASS));
        UnitResponse done = bloodService.reportTestResult(result("B0003", TestType.REPEAT, TestResultCode.PASS));

        assertThat(done.status()).isEqualTo("RELEASABLE");
        assertThat(done.initialResult()).isEqualTo("PASS");
        assertThat(done.repeatResult()).isEqualTo("PASS");
    }

    @Test
    void bothPass_outOfOrder_becomesReleasable() {
        // 复检先回传、初检后回传（乱序）
        intake("B0004");

        UnitResponse first = bloodService.reportTestResult(result("B0004", TestType.REPEAT, TestResultCode.PASS));
        assertThat(first.status()).isEqualTo("QUARANTINED");
        assertThat(first.repeatResult()).isEqualTo("PASS");
        assertThat(first.initialResult()).isNull();

        UnitResponse second = bloodService.reportTestResult(result("B0004", TestType.INITIAL, TestResultCode.PASS));
        assertThat(second.status()).isEqualTo("RELEASABLE");
        assertThat(second.initialResult()).isEqualTo("PASS");
    }

    @Test
    void anyFail_scrapsImmediately_evenIfOtherTestNotReported() {
        intake("B0005");

        UnitResponse failed = bloodService.reportTestResult(result("B0005", TestType.REPEAT, TestResultCode.FAIL));

        assertThat(failed.status()).isEqualTo("SCRAPPED");
        assertThat(failed.scrapReason()).isEqualTo("TEST_FAILED");
    }

    @Test
    void failAfterOnePass_stillScraps() {
        intake("B0006");
        bloodService.reportTestResult(result("B0006", TestType.INITIAL, TestResultCode.PASS));
        assertThat(bloodService.reportTestResult(result("B0006", TestType.REPEAT, TestResultCode.FAIL)).status())
                .isEqualTo("SCRAPPED");
    }

    @Test
    void passAfterScrap_isLateEvent_andDoesNotReviveUnit() {
        intake("B0007");
        bloodService.reportTestResult(result("B0007", TestType.INITIAL, TestResultCode.FAIL));

        // 报废终态后，另一项合格结果回传不得复活
        UnitResponse afterLatePass = bloodService.reportTestResult(result("B0007", TestType.REPEAT, TestResultCode.PASS));

        BloodUnit reloaded = bloodService.getUnit("B0007");
        assertThat(reloaded.getStatus()).isEqualTo(UnitStatus.SCRAPPED);
        assertThat(afterLatePass.status()).isEqualTo("SCRAPPED");
        assertThat(afterLatePass.repeatResult()).isNull();
        // 迟到事件已落库但未采纳
        assertThat(eventRepository.countByBarcodeAndAccepted("B0007", false)).isEqualTo(1);
        assertThat(eventRepository.countByBarcodeAndAccepted("B0007", true)).isEqualTo(1);
    }

    @Test
    void duplicateReportForSameSlot_isIgnoredAsLateEvent() {
        intake("B0008");
        bloodService.reportTestResult(result("B0008", TestType.INITIAL, TestResultCode.PASS));

        UnitResponse duplicate = bloodService.reportTestResult(result("B0008", TestType.INITIAL, TestResultCode.PASS));

        assertThat(duplicate.status()).isEqualTo("QUARANTINED");
        assertThat(eventRepository.countByBarcodeAndAccepted("B0008", false)).isEqualTo(1);
    }

    @Test
    void resultReceivedAfterExpiry_marksExpiredEvenWhenPass() {
        intake("B0009");
        Instant late = EXPIRES_AT.plusSeconds(1);

        UnitResponse response = bloodService.reportTestResult(
                new TestResultRequest("B0009", TestType.INITIAL, TestResultCode.PASS, late));

        assertThat(response.status()).isEqualTo("EXPIRED");
        assertThat(response.scrapReason()).isEqualTo("EXPIRED");
        assertThat(response.initialResult()).isNull();
        assertThat(response.expiredAt()).isEqualTo(late);
    }

    @Test
    void resultReceivedExactlyAtExpiryBoundary_stillAccepted() {
        intake("B0010");

        UnitResponse response = bloodService.reportTestResult(
                new TestResultRequest("B0010", TestType.INITIAL, TestResultCode.PASS, EXPIRES_AT));

        assertThat(response.status()).isEqualTo("QUARANTINED");
        assertThat(response.initialResult()).isEqualTo("PASS");
    }

    @Test
    void manualScrap_isTerminalAndIdempotent() {
        intake("B0011");
        assertThat(bloodService.scrap("B0011").status()).isEqualTo("SCRAPPED");
        // 终态后再次报废不改变状态
        assertThat(bloodService.scrap("B0011").status()).isEqualTo("SCRAPPED");
        assertThat(bloodService.reportTestResult(result("B0011", TestType.INITIAL, TestResultCode.PASS)).status())
                .isEqualTo("SCRAPPED");
    }
}
