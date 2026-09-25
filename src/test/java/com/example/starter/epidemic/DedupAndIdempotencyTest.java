package com.example.starter.epidemic;

import com.example.starter.epidemic.api.CardResponse;
import com.example.starter.epidemic.api.SubmitCardRequest;
import com.example.starter.epidemic.service.EpidemicCardService;
import com.example.starter.epidemic.service.IdempotencyConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重复卡去重与 requestId 幂等：同指纹重复返回已存在编号；同键同参重放首响；同键异参 409。
 */
class DedupAndIdempotencyTest extends AbstractIntegrationTest {

    @Autowired
    private EpidemicCardService service;

    @Test
    void duplicateCard_samePatientDiseaseOnset_returnsExistingCardNo_andDoesNotCreate() {
        Instant diagnosis = BASE_TIME.minusSeconds(3600);
        SubmitCardRequest first = submitRequest("req-dup-1", "P-DUP", "B02", diagnosis);
        CardResponse r1 = service.submit(first);
        assertFalse(r1.duplicate());

        // 不同 requestId、不同报告电话，但同一患者＋病种＋发病日期：判重，不新建。
        SubmitCardRequest second = new SubmitCardRequest(
                "req-dup-2", first.patientId(), "另一个报告人姓名", first.age(), first.gender(),
                first.diseaseCode(), first.caseType(), first.onsetDate(), first.diagnosisTime(),
                first.institutionCode(), "李报告", first.address(), "13900000000", null);
        CardResponse r2 = service.submit(second);

        assertTrue(r2.duplicate(), "重复报告应标记 duplicate=true");
        assertEquals(r1.cardNo(), r2.cardNo(), "重复报告必须返回已存在卡片编号");
        assertEquals(1, countCards(), "重复报告不得新建卡片");
        assertEquals(1, countVersions(r1.cardNo()), "重复报告不得产生新版本");
    }

    @Test
    void sameRequestIdSameParams_replaysFirstResponse() {
        SubmitCardRequest req = submitRequest("req-replay", "P-REP", "A01",
                BASE_TIME.minusSeconds(600));
        CardResponse r1 = service.submit(req);
        CardResponse r2 = service.submit(req);
        assertEquals(r1.cardNo(), r2.cardNo());
        assertEquals(1, r2.versionNo());
        assertEquals(1, countCards());
    }

    @Test
    void sameRequestIdDifferentParams_returns409_andNoNewVersion() {
        SubmitCardRequest req1 = submitRequest("req-conflict", "P-CONF", "A01",
                BASE_TIME.minusSeconds(600));
        service.submit(req1);

        SubmitCardRequest req2 = new SubmitCardRequest(
                "req-conflict", req1.patientId(), req1.patientName(), req1.age(), req1.gender(),
                req1.diseaseCode(), req1.caseType(), req1.onsetDate(),
                req1.diagnosisTime(), req1.institutionCode(), req1.reporter(),
                "不同住址-参数冲突", req1.phone(), null);

        IdempotencyConflictException ex = assertThrows(IdempotencyConflictException.class,
                () -> service.submit(req2));
        assertTrue(ex.getMessage().contains("req-conflict"));
        assertEquals(1, countVersions(service.submit(req1).cardNo()), "409 不得产生新版本");
    }

    @Test
    void differentOnsetDate_isDistinctCard() {
        SubmitCardRequest r1 = submitRequest("req-o1", "P-ONS", "B02",
                BASE_TIME.minusSeconds(3600));
        CardResponse c1 = service.submit(r1);

        SubmitCardRequest r2 = new SubmitCardRequest(
                "req-o2", r1.patientId(), r1.patientName(), r1.age(), r1.gender(),
                r1.diseaseCode(), r1.caseType(),
                r1.onsetDate().plusDays(1), r1.diagnosisTime(), r1.institutionCode(),
                r1.reporter(), r1.address(), r1.phone(), null);
        CardResponse c2 = service.submit(r2);

        assertNotEquals(c1.cardNo(), c2.cardNo(), "发病日期不同应视为不同卡");
        assertEquals(2, countCards());
    }

    private int countCards() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM card", Integer.class);
    }

    private int countVersions(String cardNo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM card_version WHERE card_no = ?",
                Integer.class, cardNo);
    }
}
