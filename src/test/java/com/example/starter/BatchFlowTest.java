package com.example.starter;

import java.time.Duration;
import java.util.List;

import com.example.starter.support.TestClockConfig;
import com.example.starter.web.dto.BatchView;
import com.example.starter.web.dto.DeclareRequest;
import com.example.starter.web.dto.DeclareResponse;
import com.example.starter.web.dto.JudgmentRequest;
import com.example.starter.web.dto.JudgmentResponse;
import com.example.starter.web.dto.PreQuarantineRequest;
import com.example.starter.web.dto.ReceiptRequest;
import com.example.starter.web.dto.ReceiptResponse;
import com.example.starter.web.dto.ReconciliationEntry;
import com.example.starter.web.dto.ReleaseRequest;
import com.example.starter.web.dto.ReleaseResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 屠宰检疫主流程与失败分支测试：6 小时窗口、漏检暂停、四类分流、有条件食用标注、
 * 回执勾稽、标识原子性、同键重放、核销终态与迟到回执。
 */
class BatchFlowTest extends BaseIntegrationTest {

    private static final Duration SIX_HOURS = Duration.ofHours(6);

    @Test
    @DisplayName("宰前检疫自申报起6小时内有效，恰满6小时须重新申报")
    void quarantineWindowExactlySixHours() throws Exception {
        // 恰满前 1 秒：检疫有效
        declareOk("d-1", "B-6H-1", List.of("H1"));
        clock.set(TestClockConfig.T0.plus(SIX_HOURS).minusSeconds(1));
        preQuarantineOk("B-6H-1", "pq-1");

        // 恰满 6 小时：检疫拒绝，须重新申报
        clock.set(TestClockConfig.T0);
        declareOk("d-2", "B-6H-2", List.of("H1"));
        clock.set(TestClockConfig.T0.plus(SIX_HOURS));
        postJson("/api/batches/B-6H-2/pre-quarantine", new PreQuarantineRequest("pq-2", "检疫员甲"))
                .andExpect(status().isUnprocessableEntity());

        // 有效期恰满后批次仍未重新申报前，窗口外的重复申报按重新申报受理并重置窗口
        DeclareResponse redeclared = postOk("/api/batches/declare",
                new DeclareRequest("d-3", "B-6H-2", "PIG", "合成养殖场", List.of("H1")),
                DeclareResponse.class);
        assertEquals(TestClockConfig.T0.plus(SIX_HOURS).toString(), redeclared.declaredAt());
        assertEquals("DECLARED", redeclared.status());

        // 重新申报后的新窗口内检疫通过
        preQuarantineOk("B-6H-2", "pq-3");
    }

    @Test
    @DisplayName("6小时有效期内重复申报拒绝受理")
    void redeclareWithinWindowRejected() throws Exception {
        declareOk("d-1", "B-RED", List.of("H1"));
        clock.set(TestClockConfig.T0.plus(Duration.ofHours(3)));
        postJson("/api/batches/declare",
                new DeclareRequest("d-2", "B-RED", "PIG", "合成养殖场", List.of("H1")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("漏检头只整批暂停流转，补检后可判定")
    void missingInspectionPausesWholeBatch() throws Exception {
        declareOk("d-1", "B-MISS", List.of("H1", "H2", "H3"));
        preQuarantineOk("B-MISS", "pq-1");
        inspectOk("B-MISS", "i-1", "H1");
        inspectOk("B-MISS", "i-2", "H2");

        // H3 漏检：判定分流被拒绝且整批暂停
        postJson("/api/batches/B-MISS/judgments", judgmentRequest("j-1",
                item("H1", "QUALIFIED"), item("H2", "QUALIFIED"), item("H3", "QUALIFIED")))
                .andExpect(status().isConflict());
        assertEquals("PAUSED", getView("B-MISS").status());

        // 暂停状态下补检漏检头只，随后判定放行
        inspectOk("B-MISS", "i-3", "H3");
        JudgmentResponse judged = postOk("/api/batches/B-MISS/judgments", judgmentRequest("j-2",
                item("H1", "QUALIFIED"), item("H2", "QUALIFIED"), item("H3", "QUALIFIED")),
                JudgmentResponse.class);
        assertEquals("JUDGED", judged.status());
        assertEquals(3, judged.judgedCount());
    }

    @Test
    @DisplayName("四类判定分流与有条件食用高温/流通区域标注")
    void fourWayDiversionAndConditionalMarking() throws Exception {
        declareOk("d-1", "B-4WAY", List.of("H1", "H2", "H3", "H4"));
        preQuarantineOk("B-4WAY", "pq-1");
        for (int i = 1; i <= 4; i++) {
            inspectOk("B-4WAY", "i-" + i, "H" + i);
        }
        JudgmentResponse judged = postOk("/api/batches/B-4WAY/judgments", judgmentRequest("j-1",
                item("H1", "QUALIFIED"),
                new JudgmentRequest.JudgmentItem("H2", "CONDITIONAL", true, "华东一区"),
                item("H3", "RENDERING"),
                item("H4", "DESTRUCTION")), JudgmentResponse.class);
        assertEquals(4, judged.judgedCount());
        assertEquals(1, judged.categoryCounts().get("QUALIFIED"));
        assertEquals(1, judged.categoryCounts().get("CONDITIONAL"));
        assertEquals(1, judged.categoryCounts().get("RENDERING"));
        assertEquals(1, judged.categoryCounts().get("DESTRUCTION"));

        BatchView view = getView("B-4WAY");
        BatchView.HeadView conditional = view.heads().stream()
                .filter(h -> h.headNo().equals("H2")).findFirst().orElseThrow();
        assertEquals("CONDITIONAL", conditional.judgmentCategory());
        assertEquals(Boolean.TRUE, conditional.highTemp());
        assertEquals("华东一区", conditional.circulationArea());
    }

    @Test
    @DisplayName("有条件食用缺高温或流通区域标注拒绝且无任何落库")
    void conditionalWithoutMarkingRejected() throws Exception {
        declareOk("d-1", "B-COND", List.of("H1"));
        preQuarantineOk("B-COND", "pq-1");
        inspectOk("B-COND", "i-1", "H1");

        postJson("/api/batches/B-COND/judgments", judgmentRequest("j-1",
                new JudgmentRequest.JudgmentItem("H1", "CONDITIONAL", null, null)))
                .andExpect(status().isUnprocessableEntity());

        BatchView view = getView("B-COND");
        assertEquals("INSPECTING", view.status());
        assertTrue(view.judgments().isEmpty());
        assertEquals(0, countRows("judgment_detail"));
    }

    @Test
    @DisplayName("判定分流与库存标识原子完成：合格头只同步标识，非法判定整体回滚")
    void judgmentAndInventoryMarkingAtomic() throws Exception {
        declareOk("d-1", "B-ATOM", List.of("H1", "H2"));
        preQuarantineOk("B-ATOM", "pq-1");
        inspectOk("B-ATOM", "i-1", "H1");
        inspectOk("B-ATOM", "i-2", "H2");

        // 含批次外头只的非法判定：整单回滚，不允许部分落库
        postJson("/api/batches/B-ATOM/judgments", judgmentRequest("j-1",
                item("H1", "QUALIFIED"), item("H9", "QUALIFIED")))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, countRows("judgment_detail"));
        BatchView afterFailure = getView("B-ATOM");
        assertEquals("INSPECTING", afterFailure.status());
        assertTrue(afterFailure.heads().stream().noneMatch(BatchView.HeadView::inventoryMarked));
        assertTrue(afterFailure.heads().stream().allMatch(h -> h.judgmentCategory() == null));

        // 合法判定：合格头只库存标识同步置位，化制头只不标识
        postOk("/api/batches/B-ATOM/judgments", judgmentRequest("j-2",
                item("H1", "QUALIFIED"), item("H2", "RENDERING")), JudgmentResponse.class);
        BatchView judged = getView("B-ATOM");
        BatchView.HeadView h1 = head(judged, "H1");
        BatchView.HeadView h2 = head(judged, "H2");
        assertTrue(h1.inventoryMarked());
        assertFalse(h2.inventoryMarked());
        assertEquals("JUDGED", judged.status());
    }

    @Test
    @DisplayName("回执勾稽恰等受理、不等拒绝出厂")
    void receiptReconciliationExactMatch() throws Exception {
        declareOk("d-1", "B-REC", List.of("H1", "H2", "H3"));
        preQuarantineOk("B-REC", "pq-1");
        inspectOk("B-REC", "i-1", "H1");
        inspectOk("B-REC", "i-2", "H2");
        inspectOk("B-REC", "i-3", "H3");
        postOk("/api/batches/B-REC/judgments", judgmentRequest("j-1",
                item("H1", "RENDERING"), item("H2", "RENDERING"), item("H3", "DESTRUCTION")),
                JudgmentResponse.class);

        // 化制判定 2 头，回执仅 1 头：勾稽不等，拒绝出厂
        ReceiptResponse r1 = postOk("/api/batches/B-REC/receipts",
                new ReceiptRequest("r-1", "RC-1", "RENDERING", 1, "合成熟处理厂"),
                ReceiptResponse.class);
        assertFalse(entry(r1.reconciliation(), "RENDERING").matched());
        postJson("/api/batches/B-REC/release", new ReleaseRequest("rel-1", "核销员甲"))
                .andExpect(status().isConflict());

        // 化制回执补足至恰等、销毁回执恰等：受理核销
        postOk("/api/batches/B-REC/receipts",
                new ReceiptRequest("r-2", "RC-2", "RENDERING", 1, "合成熟处理厂"),
                ReceiptResponse.class);
        postOk("/api/batches/B-REC/receipts",
                new ReceiptRequest("r-3", "RC-3", "DESTRUCTION", 1, "合成销毁场"),
                ReceiptResponse.class);
        ReleaseResponse released = postOk("/api/batches/B-REC/release",
                new ReleaseRequest("rel-2", "核销员甲"), ReleaseResponse.class);
        assertEquals("RELEASED", released.status());
        assertTrue(released.reconciliation().stream().allMatch(ReconciliationEntry::matched));

        // 回执头只数超过判定数：同样勾稽不等，拒绝出厂
        declareOk("d-2", "B-OVER", List.of("H1"));
        preQuarantineOk("B-OVER", "pq-2");
        inspectOk("B-OVER", "i-9", "H1");
        postOk("/api/batches/B-OVER/judgments", judgmentRequest("j-9", item("H1", "RENDERING")),
                JudgmentResponse.class);
        postOk("/api/batches/B-OVER/receipts",
                new ReceiptRequest("r-9", "RC-9", "RENDERING", 2, "合成熟处理厂"),
                ReceiptResponse.class);
        postJson("/api/batches/B-OVER/release", new ReleaseRequest("rel-9", "核销员甲"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("同键同参重放返回首次结果，同键异参返回409且不改变批次状态")
    void idempotentReplayAndConflict() throws Exception {
        DeclareRequest request = new DeclareRequest("rid-1", "B-IDEM", "PIG", "合成养殖场",
                List.of("H1", "H2"));
        String first = postJson("/api/batches/declare", request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replayed = postJson("/api/batches/declare", request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, replayed);
        assertEquals(1, countRows("slaughter_batch"));
        assertEquals(2, countRows("head_ledger"));

        // 同键异参：409，批次状态与台账不变
        postJson("/api/batches/declare",
                new DeclareRequest("rid-1", "B-IDEM", "PIG", "合成养殖场", List.of("H1")))
                .andExpect(status().isConflict());
        BatchView view = getView("B-IDEM");
        assertEquals("DECLARED", view.status());
        assertEquals(2, view.heads().size());
    }

    @Test
    @DisplayName("回执登记同键重放不重复入账")
    void receiptIdempotentReplay() throws Exception {
        declareOk("d-1", "B-RIDEM", List.of("H1"));
        preQuarantineOk("B-RIDEM", "pq-1");
        inspectOk("B-RIDEM", "i-1", "H1");
        postOk("/api/batches/B-RIDEM/judgments", judgmentRequest("j-1", item("H1", "RENDERING")),
                JudgmentResponse.class);

        ReceiptRequest receipt = new ReceiptRequest("r-1", "RC-1", "RENDERING", 1, "合成熟处理厂");
        String first = postJson("/api/batches/B-RIDEM/receipts", receipt)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replayed = postJson("/api/batches/B-RIDEM/receipts", receipt)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, replayed);
        assertEquals(1, countRows("harmless_receipt"));

        // 同键异参：409 且不新增回执
        postJson("/api/batches/B-RIDEM/receipts",
                new ReceiptRequest("r-1", "RC-1", "RENDERING", 5, "合成熟处理厂"))
                .andExpect(status().isConflict());
        assertEquals(1, countRows("harmless_receipt"));
    }

    @Test
    @DisplayName("出厂核销为终态：重复核销幂等成功，迟到回执只记事件并告警")
    void releaseTerminalAndLateReceipt() throws Exception {
        declareOk("d-1", "B-REL", List.of("H1"));
        preQuarantineOk("B-REL", "pq-1");
        inspectOk("B-REL", "i-1", "H1");
        postOk("/api/batches/B-REL/judgments", judgmentRequest("j-1", item("H1", "RENDERING")),
                JudgmentResponse.class);
        postOk("/api/batches/B-REL/receipts",
                new ReceiptRequest("r-1", "RC-1", "RENDERING", 1, "合成熟处理厂"),
                ReceiptResponse.class);
        ReleaseResponse released = postOk("/api/batches/B-REL/release",
                new ReleaseRequest("rel-1", "核销员甲"), ReleaseResponse.class);
        assertEquals("RELEASED", released.status());

        // 重复核销（新 requestId）：幂等成功，核销时间不变
        ReleaseResponse again = postOk("/api/batches/B-REL/release",
                new ReleaseRequest("rel-2", "核销员乙"), ReleaseResponse.class);
        assertEquals("RELEASED", again.status());
        assertEquals(released.releasedAt(), again.releasedAt());

        // 核销后迟到回执：受理登记但只记迟到事件，不改变批次状态与勾稽
        ReceiptResponse late = postOk("/api/batches/B-REL/receipts",
                new ReceiptRequest("r-2", "RC-2", "RENDERING", 1, "合成熟处理厂"),
                ReceiptResponse.class);
        assertTrue(late.late());
        BatchView view = getView("B-REL");
        assertEquals("RELEASED", view.status());
        assertEquals(1, view.lateEvents().size());
        assertEquals("LATE_RECEIPT", view.lateEvents().get(0).eventType());
        assertEquals(2, view.receipts().size());
        assertTrue(view.receipts().stream().filter(BatchView.ReceiptView::late).count() == 1);
        ReconciliationEntry rendering = entry(view.reconciliation(), "RENDERING");
        assertEquals(1, rendering.judgedCount());
        assertEquals(1, rendering.receivedCount());
        assertTrue(rendering.matched());
    }

    @Test
    @DisplayName("头只台账、判定明细与回执勾稽均可由视图复算")
    void viewRecomputable() throws Exception {
        declareOk("d-1", "B-VIEW", List.of("H1", "H2"));
        preQuarantineOk("B-VIEW", "pq-1");
        inspectOk("B-VIEW", "i-1", "H1");
        inspectOk("B-VIEW", "i-2", "H2");
        postOk("/api/batches/B-VIEW/judgments", judgmentRequest("j-1",
                item("H1", "QUALIFIED"), item("H2", "DESTRUCTION")), JudgmentResponse.class);
        postOk("/api/batches/B-VIEW/receipts",
                new ReceiptRequest("r-1", "RC-1", "DESTRUCTION", 1, "合成销毁场"),
                ReceiptResponse.class);

        BatchView view = getView("B-VIEW");
        assertEquals(2, view.heads().size());
        assertEquals(2, view.judgments().size());
        assertEquals(1, view.receipts().size());
        assertEquals(1, entry(view.reconciliation(), "DESTRUCTION").judgedCount());
        assertEquals(1, entry(view.reconciliation(), "DESTRUCTION").receivedCount());
        assertEquals(0, entry(view.reconciliation(), "RENDERING").judgedCount());
        assertNotEquals(null, view.declaredAt());
    }

    // ---------- 辅助 ----------

    private static JudgmentRequest judgmentRequest(String requestId,
                                                   JudgmentRequest.JudgmentItem... items) {
        return new JudgmentRequest(requestId, List.of(items));
    }

    private static JudgmentRequest.JudgmentItem item(String headNo, String category) {
        return new JudgmentRequest.JudgmentItem(headNo, category, null, null);
    }

    private static BatchView.HeadView head(BatchView view, String headNo) {
        return view.heads().stream().filter(h -> h.headNo().equals(headNo))
                .findFirst().orElseThrow();
    }

    private static ReconciliationEntry entry(List<ReconciliationEntry> entries, String category) {
        return entries.stream().filter(e -> e.category().equals(category))
                .findFirst().orElseThrow();
    }
}
