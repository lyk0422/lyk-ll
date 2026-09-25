package com.example.starter.rebate;

import com.example.starter.rebate.api.BatchResponse;
import com.example.starter.rebate.api.DocumentResponse;
import com.example.starter.rebate.api.DoubtResponse;
import com.example.starter.rebate.api.LateEventResponse;
import com.example.starter.rebate.api.RateVersionResponse;
import com.example.starter.rebate.api.RefundResponse;
import com.example.starter.rebate.repo.BatchRow;
import com.example.starter.rebate.repo.DocumentRow;
import com.example.starter.rebate.repo.RebateJdbcRepository;
import com.example.starter.rebate.service.RebateService;
import com.example.starter.rebate.support.BusinessConflictException;
import com.example.starter.rebate.support.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 出口退税主流程与失败分支测试（H2 真实库验证约束与事务边界）。
 */
class RebateServiceTest extends RebateServiceTestBase {

    private static final String HS = "1234567890";
    private static final LocalDate DAY_BEFORE_NEW_VERSION = LocalDate.parse("2026-06-30");
    private static final LocalDate NEW_VERSION_DAY = LocalDate.parse("2026-07-01");

    @Autowired
    private RebateService service;
    @Autowired
    private RebateJdbcRepository repo;

    private void seedRates() {
        service.saveRate("rate-1", HS, LocalDate.parse("2026-01-01"), new BigDecimal("0.13"));
        service.saveRate("rate-2", HS, LocalDate.parse("2026-07-01"), new BigDecimal("0.10"));
    }

    private long collectDoc(String reqId, String decl, String inv, String rmb, String usd) {
        DocumentResponse doc = service.collect(reqId, decl, inv, HS, new BigDecimal(rmb), new BigDecimal(usd));
        return doc.id();
    }

    // ---------------- 文库版本按申报日（UTC 日界）取值 ----------------

    @Test
    void rateVersion_picksLatestEffectiveByDeclareDate() {
        seedRates();
        RateVersionResponse before = service.effectiveRate(HS, DAY_BEFORE_NEW_VERSION);
        RateVersionResponse onDay = service.effectiveRate(HS, NEW_VERSION_DAY);
        RateVersionResponse later = service.effectiveRate(HS, LocalDate.parse("2026-12-31"));

        assertThat(before.effectiveDate()).isEqualTo(LocalDate.parse("2026-01-01"));
        assertThat(before.rate()).isEqualByComparingTo("0.13");
        assertThat(onDay.effectiveDate()).isEqualTo(NEW_VERSION_DAY);
        assertThat(onDay.rate()).isEqualByComparingTo("0.10");
        assertThat(later.rate()).isEqualByComparingTo("0.10");
    }

    // ---------------- 换汇成本恰端点合法 ----------------

    @Test
    void cost_exactEndpointsAreValid_andTaxBaseFormula() {
        seedRates();
        // FOB=1000 元，退税率 0.13 → 计税依据 130；130/26=5.0（恰下端点）
        long lowerDoc = collectDoc("d-lower", "DECL-L", "INV-L", "1000", "26");
        BatchResponse lowerBatch = service.createBatch("b-lower", "B-L", NEW_VERSION_DAY.minusDays(1),
                List.of(lowerDoc));
        assertThat(lowerBatch.details()).hasSize(1);
        assertThat(lowerBatch.details().get(0).taxBase()).isEqualByComparingTo("130.0000");
        assertThat(lowerBatch.details().get(0).cost()).isEqualByComparingTo("5.0000000000");
        assertThat(lowerBatch.details().get(0).result()).isEqualTo("PASS");
        assertThat(lowerBatch.doubts()).isEmpty();

        // 130/16.25=8.0（恰上端点），仍合法
        long upperDoc = collectDoc("d-upper", "DECL-U", "INV-U", "1000", "16.25");
        BatchResponse upperBatch = service.createBatch("b-upper", "B-U", NEW_VERSION_DAY.minusDays(1),
                List.of(upperDoc));
        assertThat(upperBatch.details().get(0).cost()).isEqualByComparingTo("8.0000000000");
        assertThat(upperBatch.details().get(0).result()).isEqualTo("PASS");
    }

    @Test
    void cost_outOfRange_createsOpenDoubt() {
        seedRates();
        // 130/10=13 > 8 与 130/30≈4.33 < 5 均越界
        long highDoc = collectDoc("d-high", "DECL-H", "INV-H", "1000", "10");
        long lowDoc = collectDoc("d-low", "DECL-W", "INV-W", "1000", "30");
        BatchResponse batch = service.createBatch("b-doubt", "B-D", NEW_VERSION_DAY.minusDays(1),
                List.of(highDoc, lowDoc));

        assertThat(batch.details()).extracting(d -> d.result()).containsExactly("DOUBT", "DOUBT");
        assertThat(batch.doubts()).hasSize(2);
        assertThat(batch.doubts()).allMatch(d -> "OPEN".equals(d.status()));
        assertThat(batch.taxTotal()).isEqualByComparingTo("260.0000");
    }

    // ---------------- 单证去重与唯一批次 ----------------

    @Test
    void collect_dedupByDeclarationAndInvoice() {
        seedRates();
        DocumentResponse first = service.collect("dup-1", "DECL-D", "INV-D", HS,
                new BigDecimal("1000"), new BigDecimal("26"));
        DocumentResponse replay = service.collect("dup-1", "DECL-D", "INV-D", HS,
                new BigDecimal("1000"), new BigDecimal("26"));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.replayed()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM rebate_document", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameDeclaration_cannotJoinSecondActiveBatch_notEvenWithAnotherInvoice() {
        seedRates();
        long doc1 = collectDoc("one-1", "DECL-X", "INV-1", "1000", "26");
        service.createBatch("bx-1", "BX-1", NEW_VERSION_DAY.minusDays(1), List.of(doc1));

        // 同一报关单号换发票号：采集即被拒绝
        assertThatThrownBy(() -> collectDoc("one-2", "DECL-X", "INV-2", "1000", "26"))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("已参与有效申报批次");

        // 单证已挂批次后不能再关联进别的批次
        assertThatThrownBy(() -> service.createBatch("bx-2", "BX-2", NEW_VERSION_DAY.minusDays(1),
                List.of(doc1)))
                .isInstanceOf(BusinessConflictException.class);
    }

    // ---------------- 幂等：同键重放 / 同键异参 409 ----------------

    @Test
    void idempotent_sameKeySameParamsReplays_differentParamsConflict409() {
        seedRates();
        BatchResponse first = service.createBatch("idem-key", "B-IDEM", NEW_VERSION_DAY.minusDays(1),
                List.of(collectDoc("idem-doc", "DECL-I", "INV-I", "1000", "26")));
        BatchResponse replay = service.createBatch("idem-key", "B-IDEM", NEW_VERSION_DAY.minusDays(1),
                List.of(1L));
        assertThat(replay.id()).isEqualTo(first.id());

        assertThatThrownBy(() -> service.createBatch("idem-key", "B-OTHER", NEW_VERSION_DAY.minusDays(1),
                List.of(1L)))
                .isInstanceOf(BusinessConflictException.class);

        // 状态与占用未被异参请求改变
        BatchRow row = repo.findBatchById(first.id());
        assertThat(row.status()).isEqualTo("DRAFT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM rebate_batch", Integer.class)).isEqualTo(1);
    }

    // ---------------- 疑点复核原子性 ----------------

    @Test
    void declare_withUnclearedDoubt_failsAndLeavesBatchDraft() {
        seedRates();
        long doc = collectDoc("at-doc", "DECL-A", "INV-A", "1000", "10"); // 成本 13，疑点
        BatchResponse batch = service.createBatch("at-batch", "B-A", NEW_VERSION_DAY.minusDays(1),
                List.of(doc));
        long doubtId = batch.doubts().get(0).id();

        // 未说明直接申报 → 拒绝
        assertThatThrownBy(() -> service.declare(batch.id(), "dec-fail-1", List.of(doubtId)))
                .isInstanceOf(BusinessRuleException.class);
        // 完全不复核直接申报 → 拒绝
        assertThatThrownBy(() -> service.declare(batch.id(), "dec-fail-2", List.of()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("疑点");

        assertThat(repo.findBatchById(batch.id()).status()).isEqualTo("DRAFT");
        assertThat(repo.findDoubtById(doubtId).status()).isEqualTo("OPEN");
        assertThat(repo.findBatchById(batch.id()).declaredAt()).isNull();
    }

    @Test
    void declare_reviewAndFreezeAtomically_partialReviewRollsBack() {
        seedRates();
        long d1 = collectDoc("pa-1", "DECL-P1", "INV-P1", "1000", "10"); // 疑点
        long d2 = collectDoc("pa-2", "DECL-P2", "INV-P2", "1000", "30"); // 疑点
        BatchResponse batch = service.createBatch("pa-batch", "B-PA", NEW_VERSION_DAY.minusDays(1),
                List.of(d1, d2));
        long doubt1 = batch.doubts().get(0).id();
        long doubt2 = batch.doubts().get(1).id();

        // 两个疑点都说明
        service.explain(doubt1, "exp-1", "高换汇成本说明：定价异常");
        service.explain(doubt2, "exp-2", "低换汇成本说明：促销订单");
        assertThat(repo.findDoubtById(doubt1).status()).isEqualTo("EXPLAINED");

        // 只复核其中一个 → 申报失败，已做的复核必须随事务回滚为 EXPLAINED
        assertThatThrownBy(() -> service.declare(batch.id(), "dec-partial", List.of(doubt1)))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(repo.findBatchById(batch.id()).status()).isEqualTo("DRAFT");
        assertThat(repo.findDoubtById(doubt1).status()).isEqualTo("EXPLAINED");
        assertThat(repo.findDoubtById(doubt2).status()).isEqualTo("EXPLAINED");

        // 两个一并复核 → 同事务内解除疑点并冻结批次
        BatchResponse declared = service.declare(batch.id(), "dec-ok", List.of(doubt1, doubt2));
        assertThat(declared.status()).isEqualTo("DECLARED");
        assertThat(declared.doubts()).allMatch(d -> "CLEARED".equals(d.status()));
        assertThat(declared.declaredAt()).isNotNull();

        // 冻结后重复申报被拒
        assertThatThrownBy(() -> service.declare(batch.id(), "dec-again", List.of()))
                .isInstanceOf(BusinessConflictException.class);
    }

    @Test
    void explain_idempotentAndStateGuards() {
        seedRates();
        long doc = collectDoc("ex-doc", "DECL-E", "INV-E", "1000", "10");
        BatchResponse batch = service.createBatch("ex-batch", "B-E", NEW_VERSION_DAY.minusDays(1),
                List.of(doc));
        long doubtId = batch.doubts().get(0).id();

        DoubtResponse first = service.explain(doubtId, "exp-key", "说明内容");
        DoubtResponse replay = service.explain(doubtId, "exp-key", "说明内容");
        assertThat(replay.id()).isEqualTo(first.id());
        // 同键异参 409
        assertThatThrownBy(() -> service.explain(doubtId, "exp-key", "不同的说明"))
                .isInstanceOf(BusinessConflictException.class);
        // 已说明不能再次说明
        assertThatThrownBy(() -> service.explain(doubtId, "exp-key-2", "再次说明"))
                .isInstanceOf(BusinessConflictException.class);
    }

    // ---------------- 撤批整体作废 ----------------

    @Test
    void voidBatch_releasesAllDocumentsAndRegistration() {
        seedRates();
        long d1 = collectDoc("v-1", "DECL-V1", "INV-V1", "1000", "26");
        long d2 = collectDoc("v-2", "DECL-V2", "INV-V2", "1000", "26");
        BatchResponse batch = service.createBatch("v-batch", "B-V", NEW_VERSION_DAY.minusDays(1),
                List.of(d1, d2));

        BatchResponse voided = service.voidBatch(batch.id());
        assertThat(voided.status()).isEqualTo("VOID");
        assertThat(voided.voidedAt()).isNotNull();

        // 全部单证整体释放，无一残留
        for (DocumentRow doc : repo.findDocumentsByBatch(batch.id())) {
            assertThat(doc.batchId()).isNull();
        }
        assertThat(repo.findDocumentById(d1).status()).isEqualTo("RELEASED");
        assertThat(repo.findDocumentById(d1).batchId()).isNull();
        assertThat(repo.findDocumentById(d2).batchId()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM declaration_registration", Integer.class)).isZero();

        // 释放后报关单号可重新采集并组新批次
        long d3 = collectDoc("v-3", "DECL-V1", "INV-V3", "1000", "26");
        BatchResponse batch2 = service.createBatch("v-batch-2", "B-V2", NEW_VERSION_DAY.minusDays(1),
                List.of(d3));
        assertThat(batch2.status()).isEqualTo("DRAFT");
    }

    @Test
    void voidBatch_afterDeclare_isRejected() {
        seedRates();
        long doc = collectDoc("vd-1", "DECL-VD", "INV-VD", "1000", "26");
        BatchResponse batch = service.createBatch("vd-batch", "B-VD", NEW_VERSION_DAY.minusDays(1),
                List.of(doc));
        service.declare(batch.id(), "vd-dec", List.of());
        assertThatThrownBy(() -> service.voidBatch(batch.id()))
                .isInstanceOf(BusinessConflictException.class);
        assertThat(repo.findBatchById(batch.id()).status()).isEqualTo("DECLARED");
    }

    // ---------------- 退库核销：差额恰 0.5% 一致 / 超出转人工 / 唯一登记 ----------------

    private long approvedBatchWithTax(String batchNo, String reqPrefix, String rmb, String usd) {
        seedRates();
        long doc = collectDoc(reqPrefix + "-doc", "DECL-" + reqPrefix, "INV-" + reqPrefix, rmb, usd);
        BatchResponse batch = service.createBatch(reqPrefix + "-batch", batchNo,
                NEW_VERSION_DAY.minusDays(1), List.of(doc));
        service.declare(batch.id(), reqPrefix + "-declare", List.of());
        service.approve(batch.id());
        return batch.id();
    }

    @Test
    void refund_exactHalfPercentIsConsistent() {
        long batchId = approvedBatchWithTax("B-R1", "r1", "1000", "26"); // 税额 130
        // 130 × 0.5% = 0.65，到账 130.65 恰在容忍端点
        RefundResponse refund = service.refund(batchId, "rf-1", "TS-001", new BigDecimal("130.65"));
        assertThat(refund.status()).isEqualTo("CONSISTENT");
        assertThat(refund.diffRatio()).isEqualByComparingTo("0.0050000000");
        assertThat(refund.replayed()).isFalse();
        assertThat(repo.findBatchById(batchId).status()).isEqualTo("REFUNDED");

        // 同批次+同国库流水号重放
        RefundResponse replay = service.refund(batchId, "rf-1", "TS-001", new BigDecimal("130.65"));
        assertThat(replay.id()).isEqualTo(refund.id());
        assertThat(replay.replayed()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM refund_registration", Integer.class)).isEqualTo(1);
    }

    @Test
    void refund_aboveHalfPercent_registersDiscrepancyAndStaysApproved() {
        long batchId = approvedBatchWithTax("B-R2", "r2", "1000", "26"); // 税额 130
        // 0.67/130 ≈ 0.515% > 0.5%
        RefundResponse refund = service.refund(batchId, "rf-2", "TS-002", new BigDecimal("130.67"));
        assertThat(refund.status()).isEqualTo("DISCREPANCY");
        assertThat(refund.diffRatio()).isGreaterThan(new BigDecimal("0.005"));
        // 差异登记留痕，但批次保持审核通过、转人工
        assertThat(repo.findBatchById(batchId).status()).isEqualTo("APPROVED");

        // 另一笔流水号金额一致后可正常退库
        RefundResponse fixed = service.refund(batchId, "rf-2b", "TS-003", new BigDecimal("130.00"));
        assertThat(fixed.status()).isEqualTo("CONSISTENT");
        assertThat(repo.findBatchById(batchId).status()).isEqualTo("REFUNDED");
    }

    @Test
    void refund_sameKeyDifferentParams_conflict() {
        long batchId = approvedBatchWithTax("B-R3", "r3", "1000", "26");
        service.refund(batchId, "rf-key", "TS-004", new BigDecimal("130.00"));
        assertThatThrownBy(() -> service.refund(batchId, "rf-key", "TS-004", new BigDecimal("129.00")))
                .isInstanceOf(BusinessConflictException.class);
    }

    // ---------------- 结案终态与迟到单证 ----------------

    @Test
    void close_isTerminal_idempotent_andLateDocumentsOnlyRecorded() {
        long batchId = approvedBatchWithTax("B-C1", "c1", "1000", "26");
        service.refund(batchId, "rf-c1", "TS-C1", new BigDecimal("130.00"));

        BatchResponse closed = service.close(batchId, "close-key");
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.closedAt()).isNotNull();

        // 重复结案（同键重放幂等成功）
        BatchResponse replay = service.close(batchId, "close-key");
        assertThat(replay.status()).isEqualTo("CLOSED");
        // 换新键重复结案同样幂等成功
        BatchResponse again = service.close(batchId, "close-key-2");
        assertThat(again.status()).isEqualTo("CLOSED");

        // 结案后撤批被拒
        assertThatThrownBy(() -> service.voidBatch(batchId))
                .isInstanceOf(BusinessConflictException.class);

        // 迟到单证只登记事件
        LateEventResponse late = service.registerLate(batchId, "late-1",
                "DECL-LATE", "INV-LATE", "{\"fob\":1000}");
        assertThat(late.replayed()).isFalse();
        LateEventResponse lateReplay = service.registerLate(batchId, "late-1",
                "DECL-LATE", "INV-LATE", "{\"fob\":1000}");
        assertThat(lateReplay.id()).isEqualTo(late.id());
        assertThat(repo.findLateEventsByBatch(batchId)).hasSize(1);
        // 终态批次不被迟到事件改动
        assertThat(repo.findBatchById(batchId).status()).isEqualTo("CLOSED");
        assertThat(repo.findBatchById(batchId).closedAt()).isEqualTo(closed.closedAt());
    }

    @Test
    void lateDocument_beforeClose_rejected() {
        long batchId = approvedBatchWithTax("B-C2", "c2", "1000", "26");
        // 尚未退库/结案
        assertThatThrownBy(() -> service.registerLate(batchId, "late-x",
                "DECL-X", "INV-X", null))
                .isInstanceOf(BusinessConflictException.class);
    }

    // ---------------- 文库版本体现在批次复算 ----------------

    @Test
    void batchValidation_usesRateVersionOfDeclareDate() {
        seedRates();
        long doc = collectDoc("rv-doc", "DECL-RV", "INV-RV", "1000", "16.25"); // 130/16.25=8
        // 申报日在新税率生效后：计税依据=1000×0.10=100，成本=100/16.25≈6.15 仍 PASS
        BatchResponse batch = service.createBatch("rv-batch", "B-RV", NEW_VERSION_DAY, List.of(doc));
        assertThat(batch.details().get(0).rate()).isEqualByComparingTo("0.10");
        assertThat(batch.details().get(0).rateEffectiveDate()).isEqualTo(NEW_VERSION_DAY);
        assertThat(batch.details().get(0).taxBase()).isEqualByComparingTo("100.0000");
        assertThat(batch.taxTotal()).isEqualByComparingTo("100.0000");
    }
}
