package com.example.starter.epidemic;

import com.example.starter.epidemic.api.CardResponse;
import com.example.starter.epidemic.api.SubmitCardRequest;
import com.example.starter.epidemic.service.BusinessRuleException;
import com.example.starter.epidemic.service.EpidemicCardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告时限边界：甲类 2 小时、乙类 24 小时；恰在终点提交为及时，超时为迟报且强制原因。
 */
class DeadlineBoundaryTest extends AbstractIntegrationTest {

    @Autowired
    private EpidemicCardService service;

    @Test
    void categoryA_exactlyAtTwoHourDeadline_isTimely() {
        Instant diagnosis = BASE_TIME.minusSeconds(2 * 3600);
        CardResponse resp = service.submit(submitRequest(uid(), "P-A-ON", "A01", diagnosis));
        assertFalse(resp.late(), "恰在 2 小时时限终点提交应为及时");
        assertEquals(diagnosis.plusSeconds(2 * 3600), resp.deadlineAt());
        assertNull(resp.lateReason());
    }

    @Test
    void categoryA_oneSecondAfterTwoHourDeadline_isLate_andRequiresReason() {
        Instant diagnosis = BASE_TIME.minusSeconds(2 * 3600 + 1);
        SubmitCardRequest withoutReason = submitRequest(uid(), "P-A-LATE", "A01", diagnosis);
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.submit(withoutReason));
        assertTrue(ex.getMessage().contains("迟报"));

        SubmitCardRequest withReason = new SubmitCardRequest(
                withoutReason.requestId(), withoutReason.patientId(), withoutReason.patientName(),
                withoutReason.age(), withoutReason.gender(), withoutReason.diseaseCode(),
                withoutReason.caseType(), withoutReason.onsetDate(), withoutReason.diagnosisTime(),
                withoutReason.institutionCode(), withoutReason.reporter(), withoutReason.address(),
                withoutReason.phone(), "基层网络中断，补报时说明");
        CardResponse resp = service.submit(withReason);
        assertTrue(resp.late());
        assertEquals("基层网络中断，补报时说明", resp.lateReason());
    }

    @Test
    void categoryB_exactlyAtTwentyFourHourDeadline_isTimely() {
        Instant diagnosis = BASE_TIME.minusSeconds(24 * 3600);
        CardResponse resp = service.submit(submitRequest(uid(), "P-B-ON", "B02", diagnosis));
        assertFalse(resp.late(), "恰在 24 小时时限终点提交应为及时");
        assertEquals(diagnosis.plusSeconds(24 * 3600), resp.deadlineAt());
    }

    @Test
    void categoryB_oneSecondAfterTwentyFourHourDeadline_isLate_andRequiresReason() {
        Instant diagnosis = BASE_TIME.minusSeconds(24 * 3600 + 1);
        SubmitCardRequest withoutReason = submitRequest(uid(), "P-B-LATE", "B02", diagnosis);
        assertThrows(BusinessRuleException.class, () -> service.submit(withoutReason));

        SubmitCardRequest withReason = new SubmitCardRequest(
                withoutReason.requestId(), withoutReason.patientId(), withoutReason.patientName(),
                withoutReason.age(), withoutReason.gender(), withoutReason.diseaseCode(),
                withoutReason.caseType(), withoutReason.onsetDate(), withoutReason.diagnosisTime(),
                withoutReason.institutionCode(), withoutReason.reporter(), withoutReason.address(),
                withoutReason.phone(), "转诊延迟导致迟报");
        assertTrue(service.submit(withReason).late());
    }

    @Test
    void categoryA_oneSecondBeforeDeadline_isTimely() {
        Instant diagnosis = BASE_TIME.minusSeconds(2 * 3600 - 1);
        assertFalse(service.submit(submitRequest(uid(), "P-A-BEFORE", "A02", diagnosis)).late());
    }
}
