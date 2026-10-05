package com.example.starter.forestry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.forestry.dto.ApplyRequest;
import com.example.starter.forestry.dto.CorrectionRequest;
import com.example.starter.forestry.dto.IssueRequest;
import com.example.starter.forestry.dto.ReviewRequest;
import com.example.starter.forestry.web.ApiException;

/**
 * 蓄积设计值复核与一次补正：设计值与合成蓄积表复核口径（株数 × 单株蓄积）不一致不予受理；
 * 允许一次补正，补正后仍不一致按撤回处理，不生成证照；补正写操作按 requestId 幂等。
 * 合成数据：马尾松单株蓄积 0.5 立方米/株。
 */
class VolumeRecheckTest extends AbstractForestryTest {

    @Test
    void consistentDesignAcceptedDirectly() {
        var app = service.apply(new ApplyRequest("vr-ok", "OP-V", "SP-001",
                "PINUS_MASSONIANA", "MAIN", 20, new BigDecimal("10.00")));

        assertThat(app.status()).isEqualTo("SUBMITTED");
        assertThat(app.treeCount()).isEqualTo(20);
        assertThat(app.volume()).isEqualByComparingTo("10.00");
        assertThat(app.designedVolume()).isEqualByComparingTo("10.00");
    }

    @Test
    void mismatchedDesignNotAcceptedAndCannotReviewOrIssue() {
        // 20 株复核蓄积 = 10.00，设计值 9.50 口径不一致：不予受理（待补正）
        var app = service.apply(new ApplyRequest("vr-mismatch", "OP-V", "SP-001",
                "PINUS_MASSONIANA", "MAIN", 20, new BigDecimal("9.50")));

        assertThat(app.status()).isEqualTo("CORRECTION_REQUIRED");
        assertThat(app.volume()).isEqualByComparingTo("10.00");
        assertThat(app.designedVolume()).isEqualByComparingTo("9.50");

        // 未受理的申请不能审核、不能发证
        assertThatThrownBy(() -> service.review(app.id(), new ReviewRequest(true, "OK")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getErrorCode()).isEqualTo("INVALID_STATUS");
                });
        assertThatThrownBy(() -> service.issue(new IssueRequest("vr-mismatch-issue", app.id())))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo("INVALID_STATUS"));
    }

    @Test
    void correctionWithConsistentDesignAcceptedAndIssuable() {
        var app = service.apply(new ApplyRequest("vr-fix", "OP-V", "SP-001",
                "PINUS_MASSONIANA", "MAIN", 20, new BigDecimal("9.50")));
        assertThat(app.status()).isEqualTo("CORRECTION_REQUIRED");

        // 补正：株数与设计值同时修正为一致口径（40 株 × 0.5 = 20.00）
        var corrected = service.correct(app.id(),
                new CorrectionRequest("vr-fix-c", 40, new BigDecimal("20.00")));
        assertThat(corrected.status()).isEqualTo("SUBMITTED");
        assertThat(corrected.treeCount()).isEqualTo(40);
        assertThat(corrected.volume()).isEqualByComparingTo("20.00");

        // 补正受理后可走既有审核-发证流程，台账按补正后口径记账
        service.review(app.id(), new ReviewRequest(true, "OK"));
        var license = service.issue(new IssueRequest("vr-fix-issue", app.id()));
        assertThat(license.status()).isEqualTo("ISSUED");
        assertThat(license.treeCount()).isEqualTo(40);
        assertThat(license.volume()).isEqualByComparingTo("20.00");
        Integer heldTrees = jdbc.queryForObject(
                "SELECT COALESCE(SUM(tree_count),0) FROM quota_ledger WHERE species_code='PINUS_MASSONIANA'",
                Integer.class);
        assertThat(heldTrees).isEqualTo(40);
    }

    @Test
    void correctionStillMismatchedWithdrawsWithoutLicense() {
        var app = service.apply(new ApplyRequest("vr-withdraw", "OP-V", "SP-001",
                "PINUS_MASSONIANA", "MAIN", 20, new BigDecimal("9.50")));

        // 补正后仍不一致（21 株复核 10.50 ≠ 设计 10.00）：按撤回处理
        var corrected = service.correct(app.id(),
                new CorrectionRequest("vr-withdraw-c", 21, new BigDecimal("10.00")));
        assertThat(corrected.status()).isEqualTo("WITHDRAWN");

        // 撤回后不能审核、不能发证、不能再补正，不生成证照
        assertThatThrownBy(() -> service.review(app.id(), new ReviewRequest(true, "OK")))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo("INVALID_STATUS"));
        assertThatThrownBy(() -> service.issue(new IssueRequest("vr-withdraw-issue", app.id())))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo("INVALID_STATUS"));
        assertThatThrownBy(() -> service.correct(app.id(),
                new CorrectionRequest("vr-withdraw-c2", 20, new BigDecimal("10.00"))))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo("INVALID_STATUS"));
        Integer licenses = jdbc.queryForObject(
                "SELECT COUNT(*) FROM harvest_license WHERE application_id=?", Integer.class, app.id());
        assertThat(licenses).isZero();
        // 未产生任何限额占用
        Integer holds = jdbc.queryForObject(
                "SELECT COUNT(*) FROM quota_ledger WHERE application_id=?", Integer.class, app.id());
        assertThat(holds).isZero();
    }

    @Test
    void correctionOnSubmittedApplicationRejected() {
        var app = service.apply(new ApplyRequest("vr-submitted", "OP-V", "SP-001",
                "PINUS_MASSONIANA", "MAIN", 20, new BigDecimal("10.00")));
        assertThat(app.status()).isEqualTo("SUBMITTED");

        // 仅待补正状态允许补正；已受理申请补正拒绝（只允许一次补正且须在待补正状态）
        assertThatThrownBy(() -> service.correct(app.id(),
                new CorrectionRequest("vr-submitted-c", 20, new BigDecimal("10.00"))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getErrorCode()).isEqualTo("INVALID_STATUS");
                });
    }

    @Test
    void correctionIsIdempotentByRequestId() {
        var app = service.apply(new ApplyRequest("vr-idem", "OP-V", "SP-001",
                "PINUS_MASSONIANA", "MAIN", 20, new BigDecimal("9.50")));

        var first = service.correct(app.id(),
                new CorrectionRequest("vr-idem-c", 20, new BigDecimal("10.00")));
        var replay = service.correct(app.id(),
                new CorrectionRequest("vr-idem-c", 20, new BigDecimal("10.00")));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.status()).isEqualTo("SUBMITTED");
        // 补正只落库一次
        Integer corrections = jdbc.queryForObject(
                "SELECT correction_count FROM harvest_application WHERE id=?", Integer.class, app.id());
        assertThat(corrections).isOne();

        // 同键异参 409，且不产生副作用
        assertThatThrownBy(() -> service.correct(app.id(),
                new CorrectionRequest("vr-idem-c", 22, new BigDecimal("11.00"))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getErrorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                });
        assertThat(service.getApplication(app.id()).status()).isEqualTo("SUBMITTED");
        assertThat(service.getApplication(app.id()).treeCount()).isEqualTo(20);
    }
}
