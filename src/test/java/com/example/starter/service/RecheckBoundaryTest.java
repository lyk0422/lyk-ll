package com.example.starter.service;

import com.example.starter.StarterApplication;
import com.example.starter.support.AbstractIntegrationTest;
import com.example.starter.support.TestClockConfig;
import com.example.starter.web.LotView;
import com.example.starter.web.RecheckView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复检 15 个自然日（UTC 日界）窗口、复检覆盖首检且首检记录保留、复检仅一次的 H2 测试。
 */
@SpringBootTest(classes = {StarterApplication.class, TestClockConfig.class})
class RecheckBoundaryTest extends AbstractIntegrationTest {

    @Autowired
    private CertificationService service;

    /**
     * 构造首检不合格批次：田间 98.00%（低于良种门槛），室内 90.00%（达标），
     * 双检齐回形成不合格结论，不合格时间固定为基准时间 2026-03-10T12:00Z。
     */
    private LotView prepareUnqualifiedLot(String lotNo, String idPrefix) {
        service.declare(declareRequest(idPrefix + "-D", lotNo, 2000));
        service.submitField(fieldRequest(idPrefix + "-F", lotNo, "98.00"));
        LotView result = service.submitLab(labRequest(idPrefix + "-L", lotNo, "90.00"));
        assertEquals("UNQUALIFIED", result.status());
        return result;
    }

    @Test
    @DisplayName("恰在第 15 个自然日（UTC 日界，含当日）提交复检受理，当天最晚时刻仍受理")
    void recheckAcceptedExactlyOnDay15() {
        prepareUnqualifiedLot("LOT-3001", "R1");

        // 不合格日 2026-03-10，截止日（含）= 2026-03-25。
        clock.setInstant(Instant.parse("2026-03-25T23:59:59Z"));
        RecheckView view = service.applyRecheck(recheckRequest("R1-RECHECK", "LOT-3001"));
        assertEquals("ACCEPTED", view.status());
        assertEquals("2026-03-25", view.deadlineAt().toString());

        LotView lot = service.getLot("LOT-3001");
        assertEquals("RECHECKING", lot.status());
        assertEquals(1, lot.recheckCount());

        // 同键同参重放返回首次受理结果，不重复受理。
        RecheckView replay = service.applyRecheck(recheckRequest("R1-RECHECK", "LOT-3001"));
        assertEquals(view.appliedAt(), replay.appliedAt());
        assertEquals(1, count("recheck_application",
                "lot_id = (SELECT id FROM seed_lot WHERE lot_no = 'LOT-3001')"));
    }

    @Test
    @DisplayName("第 16 个自然日提交复检逾期拒绝")
    void recheckRejectedOnDay16() {
        prepareUnqualifiedLot("LOT-3002", "R2");

        clock.setInstant(Instant.parse("2026-03-26T00:00:00Z"));
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.applyRecheck(recheckRequest("R2-RECHECK", "LOT-3002")));
        assertTrue(ex.getMessage().contains("逾期"));
        assertEquals(0, count("recheck_application",
                "lot_id = (SELECT id FROM seed_lot WHERE lot_no = 'LOT-3002')"));
        assertEquals("UNQUALIFIED", service.getLot("LOT-3002").status());
    }

    @Test
    @DisplayName("复检合格为最终结论并覆盖首检不合格：发原种证，但首检记录保留可查")
    void recheckConclusionOverridesFirstButRecordsRetained() {
        prepareUnqualifiedLot("LOT-3003", "R3");

        clock.setInstant(Instant.parse("2026-03-20T09:00:00Z"));
        service.applyRecheck(recheckRequest("R3-RECHECK", "LOT-3003"));

        // 第 2 轮仅复检不合格的田间项且达原种门槛；首检室内项合格并沿用，最终结论立即形成。
        LotView qualified = service.submitField(fieldRequest("R3-F2", "LOT-3003", "99.95"));
        assertEquals("QUALIFIED", qualified.status());
        assertEquals("ORIGINAL", qualified.determinedGrade());

        // 首检（round=1）与复检（round=2）记录均保留可查；室内仅首检一条并被复检结论沿用。
        assertEquals(2, qualified.fieldInspections().size());
        assertEquals(1, qualified.labInspections().size());
        assertEquals(1, qualified.fieldInspections().get(0).round());
        assertEquals(2, qualified.fieldInspections().get(1).round());
        assertEquals(1, qualified.labInspections().get(0).round());

        LotView certified = service.issueCertificate("LOT-3003");
        assertEquals("CERTIFIED", certified.status());
        assertNotNull(certified.certificate());
        assertEquals("ORIGINAL", certified.certificate().grade());
        assertEquals(2000, certified.certificate().labelCount());
        assertEquals(0, certified.availableLabels(), "发证同事务核减全部标签");
    }

    @Test
    @DisplayName("复检不合格为最终不合格结论，仍不得发证")
    void recheckFailureIsFinalUnqualified() {
        prepareUnqualifiedLot("LOT-3004", "R4");
        clock.setInstant(Instant.parse("2026-03-20T09:00:00Z"));
        service.applyRecheck(recheckRequest("R4-RECHECK", "LOT-3004"));

        // 第 2 轮田间复检仍低于良种门槛；首检室内项沿用，最终不合格结论立即形成。
        LotView result = service.submitField(fieldRequest("R4-F2", "LOT-3004", "97.50"));
        assertEquals("UNQUALIFIED", result.status());
        assertThrows(BusinessRuleException.class, () -> service.issueCertificate("LOT-3004"));

        // 复检仅限一次：再次申请（新 requestId）拒绝。
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.applyRecheck(recheckRequest("R4-RECHECK-2", "LOT-3004")));
        assertTrue(ex.getMessage().contains("仅限复检一次"));
    }

    @Test
    @DisplayName("合格批次或未形成不合格结论时申请复检被拒绝")
    void recheckRejectedForQualifiedLot() {
        service.declare(declareRequest("R5-D", "LOT-3005", 10));
        service.submitField(fieldRequest("R5-F", "LOT-3005", "99.95"));
        service.submitLab(labRequest("R5-L", "LOT-3005", "90.00"));

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.applyRecheck(recheckRequest("R5-RECHECK", "LOT-3005")));
        assertTrue(ex.getMessage().contains("首检不合格"));
    }
}
