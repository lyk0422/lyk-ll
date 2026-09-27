package com.example.starter;

import java.util.ArrayList;
import java.util.List;

import com.example.starter.web.dto.BatchView;
import com.example.starter.web.dto.JudgmentRequest;
import com.example.starter.web.dto.JudgmentResponse;
import com.example.starter.web.dto.ReceiptRequest;
import com.example.starter.web.dto.ReleaseRequest;
import com.example.starter.web.dto.RiskBindRequest;
import com.example.starter.web.dto.RiskChangeRequest;
import com.example.starter.web.dto.RiskChecklistResponse;
import com.example.starter.web.dto.RiskResultRequest;
import com.example.starter.web.dto.RiskResultResponse;
import com.example.starter.web.dto.RiskSummaryView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 风险抽检主流程与失败分支：比例冻结、逐只结果、阳性专项隔离、原子扩充、补充清单、
 * 出厂门禁、版本乐观锁、requestId 幂等与核销后迟到告警。整类可重复执行。
 */
class RiskFlowTest extends BaseIntegrationTest {

    private static List<String> heads(int n) {
        List<String> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(String.format("H%02d", i));
        }
        return list;
    }

    private void ready(String batchNo, int n) throws Exception {
        declareOk("d-" + batchNo, batchNo, heads(n));
        preQuarantineOk(batchNo, "pq-" + batchNo);
    }

    private void inspectAndJudgeAll(String batchNo, int n, String category) throws Exception {
        for (int i = 1; i <= n; i++) {
            inspectOk(batchNo, "i-" + batchNo + "-" + i, String.format("H%02d", i));
        }
        JudgmentRequest.JudgmentItem[] items = heads(n).stream()
                .map(h -> new JudgmentRequest.JudgmentItem(h, category, null, null))
                .toArray(JudgmentRequest.JudgmentItem[]::new);
        JudgmentResponse judged = postOk("/api/batches/" + batchNo + "/judgments",
                new JudgmentRequest("j-" + batchNo, List.of(items)), JudgmentResponse.class);
        // 四类判定完成：普通态为 JUDGED；已因阳性专项隔离的批次保留 SPECIAL_QUARANTINE。
        assertTrue("JUDGED".equals(judged.status()) || "SPECIAL_QUARANTINE".equals(judged.status()),
                "判定后状态应为 JUDGED 或 SPECIAL_QUARANTINE，实际：" + judged.status());
    }

    // ---------- 冻结与比例 ----------

    @Test
    @DisplayName("普通10%/加严30%：向上取整且至少1只，按编号字典序取前N只并冻结规则版本与申报集合")
    void samplingRatioCeilAtLeastOneAndFrozen() throws Exception {
        ready("B-N10", 10);
        RiskChecklistResponse bound = bindRisk("rb-1", "B-N10", "NORMAL", "RULE-1", heads(10), 0);
        assertEquals(1, bound.version());
        assertEquals(1L, bound.sequenceNo());
        assertEquals("INITIAL", bound.kind());
        assertEquals(List.of("H01"), bound.sampledHeadNos());
        assertFalse(bound.samplingCompleted());
        assertFalse(bound.quarantineActive());
        assertFalse(bound.late());

        ready("B-T7", 7);
        RiskChecklistResponse tight = bindRisk("rb-2", "B-T7", "TIGHTENED", "RULE-1", heads(7), 0);
        // ceil(7×30%)=ceil(2.1)=3
        assertEquals(List.of("H01", "H02", "H03"), tight.sampledHeadNos());

        RiskSummaryView summary = getRisk("B-T7");
        assertTrue(summary.controlled());
        assertEquals(1, summary.version());
        assertEquals("TIGHTENED", summary.currentLevel());
        assertEquals("RULE-1", summary.currentRuleVersion());
        RiskSummaryView.ChecklistView checklist = summary.checklists().get(0);
        assertEquals(heads(7), checklist.declaredHeadNos());
        assertEquals(List.of("H01", "H02", "H03"), checklist.samples().stream()
                .map(RiskSummaryView.SampleView::headNo).toList());
        checklist.samples().forEach(s -> assertNull(s.result()));
        assertFalse(summary.samplingCompleted());
        assertTrue(summary.reasons().isEmpty());
    }

    @Test
    @DisplayName("全部样本阴性完成抽检后，受控批次满足原同步检验与回执方可核销")
    void allNegativeCompletesGateAndRelease() throws Exception {
        ready("B-OK", 10);
        bindRisk("rb-1", "B-OK", "NORMAL", "RULE-1", heads(10), 0);

        // 抽检未完成：即使同步检验、判定、回执齐全，门禁仍拒绝出厂
        inspectAndJudgeAll("B-OK", 10, "QUALIFIED");
        postJson("/api/batches/B-OK/release", new ReleaseRequest("rel-0", "核销员甲"))
                .andExpect(status().isConflict());
        assertEquals("JUDGED", getView("B-OK").status());

        RiskResultResponse result = riskResult("rr-1", "B-OK", "H01", "NEGATIVE", 1);
        assertEquals(2, result.version());
        assertTrue(result.checklistCompleted());
        assertTrue(result.samplingCompleted());
        assertTrue(result.allNegative());
        assertFalse(result.quarantineActive());

        ReleaseResponseHolder released = releaseOk("B-OK", "rel-1");
        assertEquals("RELEASED", released.response.status());
        assertEquals("COMPLETED", getRisk("B-OK").checklists().get(0).status());
    }

    private record ReleaseResponseHolder(com.example.starter.web.dto.ReleaseResponse response) {
    }

    private ReleaseResponseHolder releaseOk(String batchNo, String requestId) throws Exception {
        return new ReleaseResponseHolder(postOk("/api/batches/" + batchNo + "/release",
                new ReleaseRequest(requestId, "核销员甲"),
                com.example.starter.web.dto.ReleaseResponse.class));
    }

    // ---------- 阳性专项隔离 ----------

    @Test
    @DisplayName("任一阳性整批专项隔离：四类判定保留、回执勾稽通过仍拒绝受控出厂")
    void positiveLocksBatchInSpecialQuarantine() throws Exception {
        ready("B-POS", 10);
        bindRisk("rb-1", "B-POS", "TIGHTENED", "RULE-1", heads(10), 0);

        RiskResultResponse h01 = riskResult("rr-1", "B-POS", "H01", "NEGATIVE", 1);
        assertEquals(2, h01.version());
        assertFalse(h01.quarantineActive());
        RiskResultResponse positive = riskResult("rr-2", "B-POS", "H02", "POSITIVE", 2);
        assertEquals(3, positive.version());
        assertTrue(positive.quarantineActive());
        assertFalse(positive.allNegative());
        assertEquals("SPECIAL_QUARANTINE", getView("B-POS").status());

        // 四类判定在专项隔离态仍可完成并保留
        inspectAndJudgeAll("B-POS", 10, "RENDERING");
        postOk("/api/batches/B-POS/receipts",
                new ReceiptRequest("rc-1", "RC-1", "RENDERING", 10, "合成熟处理厂"),
                com.example.starter.web.dto.ReceiptResponse.class);

        postJson("/api/batches/B-POS/release", new ReleaseRequest("rel-1", "核销员甲"))
                .andExpect(status().isConflict());
        BatchView view = getView("B-POS");
        assertEquals("SPECIAL_QUARANTINE", view.status());
        assertNotNull(view.judgedAt());
        assertEquals(10, view.judgments().size());

        RiskSummaryView summary = getRisk("B-POS");
        assertTrue(summary.quarantineActive());
        assertFalse(summary.allNegative());
        assertEquals(1, summary.reasons().size());
        RiskSummaryView.QuarantineReasonView reason = summary.reasons().get(0);
        assertEquals("H02", reason.headNo());
        assertEquals("POSITIVE", reason.result());
        assertEquals("TIGHTENED", reason.level());
        assertEquals("RULE-1", reason.ruleVersion());
    }

    @Test
    @DisplayName("判定完成后才出阳性：JUDGED 转为专项隔离，判定保留且受控出厂拒绝")
    void positiveAfterJudgedMovesToQuarantineAndBlocksRelease() throws Exception {
        ready("B-POST", 10);
        bindRisk("rb-1", "B-POST", "NORMAL", "RULE-1", heads(10), 0);
        inspectAndJudgeAll("B-POST", 10, "QUALIFIED");
        assertEquals("JUDGED", getView("B-POST").status());

        // 抽检在判定之后才出阳性：JUDGED→SPECIAL_QUARANTINE
        RiskResultResponse positive = riskResult("rr-1", "B-POST", "H01", "POSITIVE", 1);
        assertTrue(positive.quarantineActive());
        assertEquals("SPECIAL_QUARANTINE", getView("B-POST").status());

        postJson("/api/batches/B-POST/release", new ReleaseRequest("rel-1", "核销员甲"))
                .andExpect(status().isConflict());
        assertNotNull(getView("B-POST").judgedAt(), "四类判定结果保留");
    }

    // ---------- 结果失败分支与幂等 ----------

    @Test
    @DisplayName("清单外结果拒绝、异结果冲突、同结果重复无新记录且版本不加")
    void outOfListRejectedConflictAndDuplicate() throws Exception {
        ready("B-RES", 10);
        bindRisk("rb-1", "B-RES", "TIGHTENED", "RULE-1", heads(10), 0);

        // 清单外（合法头只但不在冻结集合）：422
        postJson("/api/batches/B-RES/risk/results",
                new RiskResultRequest("rr-x", "H09", "NEGATIVE", 1, clock.instant().toString()))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, countRows("risk_sample"));

        // 失败不占键：同一 requestId 改用合法参数后受理成功
        RiskResultResponse first = riskResult("rr-x", "B-RES", "H01", "NEGATIVE", 1);
        assertFalse(first.duplicated());
        assertEquals(2, first.version());

        // 异结果冲突：409，结果不被覆盖
        postJson("/api/batches/B-RES/risk/results",
                new RiskResultRequest("rr-2", "H01", "POSITIVE", 2, clock.instant().toString()))
                .andExpect(status().isConflict());
        assertEquals("NEGATIVE", getRisk("B-RES").checklists().get(0).samples().get(0).result());

        // 同结果重复（新 requestId）：无新记录、版本不增加
        RiskResultResponse duplicate = riskResult("rr-3", "B-RES", "H01", "NEGATIVE", 2);
        assertTrue(duplicate.duplicated());
        assertEquals(2, duplicate.version());
        assertEquals(1, countRows("risk_sample"));
    }

    @Test
    @DisplayName("同键同参重放首次冻结结果，异参409；合法文本含分隔符指纹可区分")
    void idempotentReplayFingerprintAndDelimiter() throws Exception {
        ready("B-IDEM", 10);
        RiskBindRequest request = new RiskBindRequest("rb-same", "NORMAL", "RULE-1",
                heads(10), 0, clock.instant().toString());
        String first = postJson("/api/batches/B-IDEM/risk/bind", request)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String replayed = postJson("/api/batches/B-IDEM/risk/bind", request)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(first, replayed);
        assertEquals(1, countRows("risk_checklist"));
        assertEquals(1, countRows("risk_binding"));

        // 同键异参（规则版本不同）：409
        postJson("/api/batches/B-IDEM/risk/bind",
                new RiskBindRequest("rb-same", "NORMAL", "RULE-2", heads(10), 0,
                        clock.instant().toString()))
                .andExpect(status().isConflict());

        // 分隔符碰撞：["H01","H02"] 与 ["H01,H02"] 拼接字符串相同，结构化 JSON 指纹必须区分
        ready("B-DELIM", 2);
        RiskBindRequest split = new RiskBindRequest("rb-d", "NORMAL", "RULE-1",
                List.of("H01", "H02"), 0, clock.instant().toString());
        postJson("/api/batches/B-DELIM/risk/bind", split).andExpect(status().isOk());
        postJson("/api/batches/B-DELIM/risk/bind",
                new RiskBindRequest("rb-d", "NORMAL", "RULE-1",
                        List.of("H01,H02"), 0, clock.instant().toString()))
                .andExpect(status().isConflict());
    }

    // ---------- 风险变更 ----------

    @Test
    @DisplayName("未完成清单提高等级原子扩充：保留既有结果，版本加1，降低与同级拒绝")
    void expandOpenChecklistAtomically() throws Exception {
        ready("B-EXP", 20);
        bindRisk("rb-1", "B-EXP", "NORMAL", "RULE-1", heads(20), 0);
        // NORMAL 20 只 → 2 只；先出 1 只结果，清单仍 OPEN
        riskResult("rr-1", "B-EXP", "H01", "NEGATIVE", 1);

        RiskChecklistResponse expanded = changeRisk("rc-1", "B-EXP", "TIGHTENED", "RULE-2",
                heads(20), 2);
        assertEquals(3, expanded.version());
        assertEquals("EXPANDED", expanded.kind());
        assertEquals(1L, expanded.sequenceNo(), "原子扩充沿用原清单序号");
        // ceil(20×30%)=6
        assertEquals(heads(6), expanded.sampledHeadNos());
        assertEquals("RULE-2", expanded.ruleVersion());
        assertEquals("OPEN", expanded.status());

        RiskSummaryView.ChecklistView checklist = getRisk("B-EXP").checklists().get(0);
        assertEquals(6, checklist.samples().size());
        RiskSummaryView.SampleView h01 = checklist.samples().stream()
                .filter(s -> s.headNo().equals("H01")).findFirst().orElseThrow();
        assertEquals("NEGATIVE", h01.result(), "既有结果须保留");
        assertEquals(1, countRows("risk_sample"), "扩充不得复制既有结果");

        // 版本不匹配：409；降低/同级：422；申报集合不一致：422
        postJson("/api/batches/B-EXP/risk/changes",
                new RiskChangeRequest("rc-bad-v", "TIGHTENED", "RULE-3", heads(20), 2,
                        clock.instant().toString()))
                .andExpect(status().isConflict());
        postJson("/api/batches/B-EXP/risk/changes",
                new RiskChangeRequest("rc-down", "NORMAL", "RULE-3", heads(20), 3,
                        clock.instant().toString()))
                .andExpect(status().isUnprocessableEntity());
        postJson("/api/batches/B-EXP/risk/changes",
                new RiskChangeRequest("rc-same", "TIGHTENED", "RULE-3", heads(20), 3,
                        clock.instant().toString()))
                .andExpect(status().isUnprocessableEntity());
        postJson("/api/batches/B-EXP/risk/changes",
                new RiskChangeRequest("rc-set", "TIGHTENED", "RULE-3", heads(19), 3,
                        clock.instant().toString()))
                .andExpect(status().isUnprocessableEntity());

        // 既有结果同结果重复仍不增加版本
        RiskResultResponse duplicate = riskResult("rr-2", "B-EXP", "H01", "NEGATIVE", 3);
        assertTrue(duplicate.duplicated());
        assertEquals(3, duplicate.version());
    }

    @Test
    @DisplayName("已完成清单不可改：提高等级另开补充清单并再次锁出厂")
    void completedChecklistOpensSupplementaryAndRelocks() throws Exception {
        ready("B-SUP", 10);
        bindRisk("rb-1", "B-SUP", "NORMAL", "RULE-1", heads(10), 0);
        riskResult("rr-1", "B-SUP", "H01", "NEGATIVE", 1);
        assertEquals("COMPLETED", getRisk("B-SUP").checklists().get(0).status());

        RiskChecklistResponse supplementary = changeRisk("rc-1", "B-SUP", "TIGHTENED", "RULE-2",
                heads(10), 2);
        assertEquals(3, supplementary.version());
        assertEquals(2L, supplementary.sequenceNo());
        assertEquals("SUPPLEMENTARY", supplementary.kind());
        assertEquals(List.of("H01", "H02", "H03"), supplementary.sampledHeadNos());
        assertFalse(supplementary.samplingCompleted(), "补充清单未完成，再次锁出厂");

        RiskSummaryView summary = getRisk("B-SUP");
        assertEquals(2, summary.checklists().size());
        assertEquals("COMPLETED", summary.checklists().get(0).status());
        assertEquals("OPEN", summary.checklists().get(1).status());
        assertEquals(2L, summary.currentSequenceNo());

        // 原同步检验与判定齐全，但补充抽检未完成，仍拒绝出厂
        inspectAndJudgeAll("B-SUP", 10, "QUALIFIED");
        postJson("/api/batches/B-SUP/release", new ReleaseRequest("rel-0", "核销员甲"))
                .andExpect(status().isConflict());

        // 补充清单全部阴性后门禁解除，给予核销
        riskResult("rr-2", "B-SUP", "H01", "NEGATIVE", 3);
        riskResult("rr-3", "B-SUP", "H02", "NEGATIVE", 4);
        RiskResultResponse last = riskResult("rr-4", "B-SUP", "H03", "NEGATIVE", 5);
        assertTrue(last.samplingCompleted());
        assertTrue(last.allNegative());
        assertEquals("RELEASED", releaseOk("B-SUP", "rel-1").response.status());
    }

    // ---------- 绑定失败分支与参数非法 ----------

    @Test
    @DisplayName("宰前检疫前绑定422、重复绑定409、expectedVersion非法409、参数非法400")
    void bindFailuresAndBadRequests() throws Exception {
        declareOk("d-1", "B-EARLY", heads(3));
        postJson("/api/batches/B-EARLY/risk/bind",
                new RiskBindRequest("rb-1", "NORMAL", "RULE-1", heads(3), 0,
                        clock.instant().toString()))
                .andExpect(status().isUnprocessableEntity());

        preQuarantineOk("B-EARLY", "pq-1");
        postJson("/api/batches/B-EARLY/risk/bind",
                new RiskBindRequest("rb-1", "NORMAL", "RULE-1", heads(3), 1,
                        clock.instant().toString()))
                .andExpect(status().isConflict());
        bindRisk("rb-ok", "B-EARLY", "NORMAL", "RULE-1", heads(3), 0);
        postJson("/api/batches/B-EARLY/risk/bind",
                new RiskBindRequest("rb-2", "NORMAL", "RULE-1", heads(3), 0,
                        clock.instant().toString()))
                .andExpect(status().isConflict());

        // 非法等级 / 非法时刻 / 集合重复：400
        postJson("/api/batches/B-EARLY/risk/results",
                new RiskResultRequest("rr-bad-level", "H01", "NEG", 1, clock.instant().toString()))
                .andExpect(status().isBadRequest());
        postJson("/api/batches/B-EARLY/risk/results",
                new RiskResultRequest("rr-bad-time", "H01", "NEGATIVE", 1, "not-a-time"))
                .andExpect(status().isBadRequest());
        postJson("/api/batches/B-EARLY/risk/bind",
                new RiskBindRequest("rb-dup", "HIGH", "RULE-1", List.of("H01", "H01"), 0,
                        clock.instant().toString()))
                .andExpect(status().isBadRequest());

        // 畸形 JSON 请求体：400 而非 500
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/batches/B-EARLY/risk/results")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{ not-json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("风险对象不存在404；未绑定批次查询 controlled=false")
    void notFoundAndUnboundView() throws Exception {
        declareOk("d-1", "B-NOBIND", heads(2));
        RiskSummaryView summary = getRisk("B-NOBIND");
        assertFalse(summary.controlled());
        assertNull(summary.version());
        assertTrue(summary.checklists().isEmpty());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/batches/B-MISSING/risk"))
                .andExpect(status().isNotFound());
    }

    // ---------- 核销后迟到 ----------

    @Test
    @DisplayName("核销后风险绑定/变更/结果只记迟到告警，不追改冻结出厂证据")
    void lateRiskEventsAfterReleaseDoNotChangeEvidence() throws Exception {
        ready("B-LATE", 2);
        inspectAndJudgeAll("B-LATE", 2, "QUALIFIED");
        assertEquals("RELEASED", releaseOk("B-LATE", "rel-1").response.status());

        RiskChecklistResponse lateBind = postOk("/api/batches/B-LATE/risk/bind",
                new RiskBindRequest("rb-late", "TIGHTENED", "RULE-9", heads(2), 0,
                        clock.instant().toString()),
                RiskChecklistResponse.class);
        assertTrue(lateBind.late());
        assertEquals("LATE", lateBind.kind());

        RiskResultResponse lateResult = postOk("/api/batches/B-LATE/risk/results",
                new RiskResultRequest("rr-late", "H01", "POSITIVE", 1, clock.instant().toString()),
                RiskResultResponse.class);
        assertTrue(lateResult.late());

        RiskChecklistResponse lateChange = postOk("/api/batches/B-LATE/risk/changes",
                new RiskChangeRequest("rc-late", "TIGHTENED", "RULE-9", heads(2), 1,
                        clock.instant().toString()),
                RiskChecklistResponse.class);
        assertTrue(lateChange.late());

        assertEquals(0, countRows("risk_binding"));
        assertEquals(0, countRows("risk_checklist"));
        assertEquals(0, countRows("risk_sample"));
        assertEquals("RELEASED", getView("B-LATE").status());
        BatchView view = getView("B-LATE");
        assertEquals(3, view.lateEvents().size());
        assertEquals(List.of("LATE_RISK_BIND", "LATE_RISK_RESULT", "LATE_RISK_CHANGE"),
                view.lateEvents().stream().map(BatchView.LateEventView::eventType).toList());
    }
}
