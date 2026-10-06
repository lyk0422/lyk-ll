package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.api.Requests.PermitApplyRequest;
import com.example.starter.api.Requests.ReportSubmitRequest;
import com.example.starter.api.Requests.SegmentRegisterRequest;
import com.example.starter.api.Requests.TreeRegisterRequest;
import com.example.starter.api.Responses.LedgerEntry;
import com.example.starter.api.Responses.PermitResponse;
import com.example.starter.api.Responses.TreeRegisterResponse;
import com.example.starter.domain.WorkType;
import com.example.starter.service.LedgerService;
import com.example.starter.service.PermitService;
import com.example.starter.service.SegmentService;
import com.example.starter.service.ServiceResult;
import com.example.starter.service.TreeService;

/**
 * 台账：各路段风险分布与修剪闭环率，可复算、重复查询稳定。
 */
class LedgerIT extends BaseIntegrationTest {

    @Autowired
    private SegmentService segmentService;
    @Autowired
    private TreeService treeService;
    @Autowired
    private PermitService permitService;
    @Autowired
    private LedgerService ledgerService;

    private LedgerEntry entryOf(List<LedgerEntry> entries, String segmentCode) {
        return entries.stream().filter(e -> e.segmentCode().equals(segmentCode))
                .findFirst().orElseThrow();
    }

    @Test
    void ledgerComputesRiskDistributionAndClosureRate() {
        segmentService.register(new SegmentRegisterRequest("seg-l1", "SEG-L1", "台账路1"));
        segmentService.register(new SegmentRegisterRequest("seg-l2", "SEG-L2", "台账路2"));
        // SEG-L1：一级+二级各一棵；SEG-L2：三级一棵（无计划）
        ServiceResult tree1 = treeService.register(new TreeRegisterRequest(
                "tree-l1", "SEG-L1", "T-L1", 30.0, 2, 40, 2));
        treeService.register(new TreeRegisterRequest("tree-l2", "SEG-L1", "T-L2", 10.0, 1, 20, 1));
        treeService.register(new TreeRegisterRequest("tree-l3", "SEG-L2", "T-L3", 5.0, 1, 10, 0));

        // 闭环 SEG-L1 的一级风险计划
        long planId = ((TreeRegisterResponse) tree1.body()).planId();
        ServiceResult permit = permitService.apply(new PermitApplyRequest(
                "permit-l1", "SEG-L1", planId, WorkType.HIGH_ALTITUDE));
        String permitNo = ((PermitResponse) permit.body()).permitNo();
        permitService.submitReport(new ReportSubmitRequest(
                "report-l1", permitNo, "photo-b", "photo-a", "弃枝1车已清运"));

        List<LedgerEntry> entries = ledgerService.ledger();
        LedgerEntry l1 = entryOf(entries, "SEG-L1");
        assertEquals(1, l1.level1Count());
        assertEquals(1, l1.level2Count());
        assertEquals(0, l1.level3Count());
        assertEquals(1, l1.totalPlans());
        assertEquals(1, l1.closedPlans());
        assertEquals(0, l1.escalatedPlans());
        assertEquals(1.0, l1.closureRate());

        LedgerEntry l2 = entryOf(entries, "SEG-L2");
        assertEquals(1, l2.level3Count());
        assertEquals(0, l2.totalPlans());
        assertNull(l2.closureRate());
    }

    @Test
    void ledgerIsStableAcrossRepeatedQueries() {
        segmentService.register(new SegmentRegisterRequest("seg-l3", "SEG-L3", "台账路3"));
        treeService.register(new TreeRegisterRequest("tree-l4", "SEG-L3", "T-L4", 30.0, 2, 40, 2));
        List<LedgerEntry> first = ledgerService.ledger();
        List<LedgerEntry> second = ledgerService.ledger();
        assertEquals(first, second);
    }

    @Test
    void overduePlanAppearsAsEscalatedInLedger() {
        segmentService.register(new SegmentRegisterRequest("seg-l4", "SEG-L4", "台账路4"));
        treeService.register(new TreeRegisterRequest("tree-l5", "SEG-L4", "T-L5", 30.0, 2, 40, 2));
        clock.plus(Duration.ofDays(8));
        LedgerEntry entry = entryOf(ledgerService.ledger(), "SEG-L4");
        assertEquals(1, entry.escalatedPlans());
        assertEquals(0, entry.closedPlans());
        assertEquals(0.0, entry.closureRate());
    }
}
