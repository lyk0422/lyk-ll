package com.example.starter.service;

import com.example.starter.StarterApplication;
import com.example.starter.support.AbstractIntegrationTest;
import com.example.starter.support.TestClockConfig;
import com.example.starter.web.CancelRequest;
import com.example.starter.web.FieldInspectionRequest;
import com.example.starter.web.LotView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主流程、边界分级、幂等与越级发证拒绝的 H2 集成测试。
 */
@SpringBootTest(classes = {StarterApplication.class, TestClockConfig.class})
class CertificationFlowTest extends AbstractIntegrationTest {

    @Autowired
    private CertificationService service;

    @Test
    @DisplayName("申报幂等：同键同参返回首次结果且只建一个批次；同键异参 409 且不新增批次")
    void declareIdempotentSameKeyDifferentParamsConflict() {
        LotView first = service.declare(declareRequest("REQ-DECLARE-1", "LOT-1001", 5000));
        assertEquals("DECLARED", first.status());

        LotView replay = service.declare(declareRequest("REQ-DECLARE-1", "LOT-1001", 5000));
        assertEquals(first.createdAt(), replay.createdAt());
        assertEquals(1, count("seed_lot", "lot_no = ?", "LOT-1001"));

        var differentParams = new com.example.starter.web.DeclareLotRequest(
                "REQ-DECLARE-1", "LOT-1001", "另一家申报主体", "郑单 958（合成）",
                "T-0001（合成地块）", new BigDecimal("120.50"), 5000);
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.declare(differentParams));
        assertTrue(ex.getMessage().contains("requestId"));
        assertEquals(1, count("seed_lot", "lot_no = ?", "LOT-1001"));
    }

    @Test
    @DisplayName("纯度 99.90 与发芽率 90.00 恰好达原种门槛 → 原种分级")
    void exactOriginalThresholdsGradeOriginal() {
        service.declare(declareRequest("D-1", "LOT-2001", 3000));

        LotView afterField = service.submitField(fieldRequest("F-1", "LOT-2001", "99.90"));
        assertEquals("DECLARED", afterField.status(), "仅田间一项合格回时，批次仍等待室内检测");
        assertEquals(1, afterField.fieldInspections().size());

        LotView afterLab = service.submitLab(labRequest("L-1", "LOT-2001", "90.00"));
        assertEquals("QUALIFIED", afterLab.status());
        assertEquals("ORIGINAL", afterLab.determinedGrade());
    }

    @Test
    @DisplayName("一项达原种一项 85.00 恰好良种门槛 → 良种，不得越级为原种")
    void exactBreederThresholdGradesBreeder() {
        service.declare(declareRequest("D-2", "LOT-2002", 3000));
        service.submitField(fieldRequest("F-2", "LOT-2002", "99.95"));
        LotView result = service.submitLab(labRequest("L-2", "LOT-2002", "85.00"));
        assertEquals("QUALIFIED", result.status());
        assertEquals("BREEDER", result.determinedGrade());
    }

    @Test
    @DisplayName("一项不合格一项未回时批次保持待定；此时发证被拒绝（不得越级）")
    void oneFailedOnePendingStaysPendingAndIssueRejected() {
        service.declare(declareRequest("D-3", "LOT-2003", 3000));
        LotView afterField = service.submitField(fieldRequest("F-3", "LOT-2003", "98.00"));
        assertEquals("PENDING", afterField.status(), "田间 98.00% 不合格而室内未回，应保持待定");
        assertNull(afterField.determinedGrade());

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.issueCertificate("LOT-2003"));
        assertTrue(ex.getMessage().contains("不得越级发证"));
        assertEquals(0, count("certificate", "lot_id = (SELECT id FROM seed_lot WHERE lot_no = 'LOT-2003')"));
    }

    @Test
    @DisplayName("双检齐回但任一项不达标 → 不合格不可发证")
    void bothReturnedButOneFailedIsUnqualified() {
        service.declare(declareRequest("D-4", "LOT-2004", 3000));
        service.submitField(fieldRequest("F-4", "LOT-2004", "99.95"));
        LotView result = service.submitLab(labRequest("L-4", "LOT-2004", "84.99"));
        assertEquals("UNQUALIFIED", result.status());
        assertNull(result.determinedGrade());
        assertNotNull(result.unqualifiedAt());

        assertThrows(BusinessRuleException.class, () -> service.issueCertificate("LOT-2004"));
    }

    @Test
    @DisplayName("检验提交幂等：同键同参返回首次结果且不产生新检验记录；同键异参 409 且无新记录")
    void inspectionIdempotentAndConflictNoNewRecord() {
        service.declare(declareRequest("D-5", "LOT-2005", 100));
        LotView first = service.submitField(fieldRequest("F-5", "LOT-2005", "99.95"));
        LotView replay = service.submitField(fieldRequest("F-5", "LOT-2005", "99.95"));
        assertEquals(first.updatedAt(), replay.updatedAt());
        assertEquals(1, count("field_inspection",
                "lot_id = (SELECT id FROM seed_lot WHERE lot_no = 'LOT-2005')"));

        FieldInspectionRequest changed = new FieldInspectionRequest("F-5", "LOT-2005",
                new BigDecimal("99.10"), "田间检验员甲", null);
        assertThrows(ConflictException.class, () -> service.submitField(changed));
        assertEquals(1, count("field_inspection",
                "lot_id = (SELECT id FROM seed_lot WHERE lot_no = 'LOT-2005')"));
    }

    @Test
    @DisplayName("分级发证：生成唯一证书号、同事务核减标签；重复发证 409")
    void issueCertificateDeductsLabelsAndRejectsDuplicate() {
        service.declare(declareRequest("D-6", "LOT-2006", 4200));
        service.submitField(fieldRequest("F-6", "LOT-2006", "99.90"));
        service.submitLab(labRequest("L-6", "LOT-2006", "90.00"));

        LotView certified = service.issueCertificate("LOT-2006");
        assertEquals("CERTIFIED", certified.status());
        assertEquals(0, certified.availableLabels(), "发证同事务核减全部可用标签");
        assertNotNull(certified.certificate());
        assertEquals("VALID", certified.certificate().status());
        assertEquals(4200, certified.certificate().labelCount());
        assertTrue(certified.certificate().certNo().startsWith("CERT-"));
        assertEquals(1, count("certificate",
                "lot_id = (SELECT id FROM seed_lot WHERE lot_no = 'LOT-2006')"));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.issueCertificate("LOT-2006"));
        assertTrue(ex.getMessage().contains("已发证"));
    }

    @Test
    @DisplayName("注销终态：已发证书同步作废留痕，重复注销幂等成功，注销后检验/复检/发证拒绝")
    void cancelIsTerminalAndVoidsCertificate() {
        service.declare(declareRequest("D-7", "LOT-2007", 800));
        service.submitField(fieldRequest("F-7", "LOT-2007", "99.90"));
        service.submitLab(labRequest("L-7", "LOT-2007", "90.00"));
        service.issueCertificate("LOT-2007");

        LotView cancelled = service.cancel(new CancelRequest("LOT-2007"));
        assertEquals("CANCELLED", cancelled.status());
        assertNotNull(cancelled.certificate());
        assertEquals("VOID", cancelled.certificate().status());
        assertNotNull(cancelled.certificate().voidedAt());
        assertEquals("批次注销，证书同步作废", cancelled.certificate().voidReason());

        LotView replayCancel = service.cancel(new CancelRequest("LOT-2007"));
        assertEquals("CANCELLED", replayCancel.status());

        BusinessRuleException fieldRejected = assertThrows(BusinessRuleException.class,
                () -> service.submitField(fieldRequest("F-7B", "LOT-2007", "99.99")));
        assertTrue(fieldRejected.getMessage().contains("注销"));
        assertThrows(BusinessRuleException.class,
                () -> service.applyRecheck(recheckRequest("R-7", "LOT-2007")));
        assertThrows(BusinessRuleException.class, () -> service.issueCertificate("LOT-2007"));

        assertEquals(1, count("certificate",
                "status = 'VOID' AND lot_id = (SELECT id FROM seed_lot WHERE lot_no = 'LOT-2007')"));
        assertTrue(count("lot_event",
                "event_type = 'CERT_VOID' AND lot_id = (SELECT id FROM seed_lot WHERE lot_no = 'LOT-2007')") >= 1);
    }

    @Test
    @DisplayName("标签数量为 0 时发证原子失败：不产生证书，批次保持合格待发证，标签不被扣成负数")
    void issueWithZeroLabelsFailsAtomically() {
        service.declare(declareRequest("D-9", "LOT-2009", 0));
        service.submitField(fieldRequest("F-9", "LOT-2009", "99.90"));
        service.submitLab(labRequest("L-9", "LOT-2009", "90.00"));

        LotView qualified = service.getLot("LOT-2009");
        assertEquals("QUALIFIED", qualified.status());
        assertEquals(0, qualified.availableLabels());

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.issueCertificate("LOT-2009"));
        assertTrue(ex.getMessage().contains("标签"));

        LotView unchanged = service.getLot("LOT-2009");
        assertEquals("QUALIFIED", unchanged.status(), "发证失败后状态保持 QUALIFIED");
        assertEquals(0, unchanged.availableLabels());
        assertEquals(0, count("certificate",
                "lot_id = (SELECT id FROM seed_lot WHERE lot_no = 'LOT-2009')"));
    }

    @Test
    @DisplayName("不存在批次返回 404")
    void unknownLotReturnsNotFound() {
        assertThrows(NotFoundException.class, () -> service.getLot("LOT-NOPE"));
        assertThrows(NotFoundException.class, () -> service.issueCertificate("LOT-NOPE"));
    }

    @Test
    @DisplayName("同批次号再次申报（不同 requestId）返回 409")
    void duplicateLotNoConflict() {
        service.declare(declareRequest("D-8", "LOT-2008", 10));
        assertThrows(ConflictException.class,
                () -> service.declare(declareRequest("D-8-OTHER", "LOT-2008", 10)));
    }
}
