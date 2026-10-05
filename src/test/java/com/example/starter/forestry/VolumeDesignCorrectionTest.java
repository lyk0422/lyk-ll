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
 * 蓄积设计值复核与一次补正：设计值与合成蓄积表复核口径（单株蓄积×株数）一致才受理；
 * 不一致不予受理并挂起待补正；补正一致受理，补正后仍不一致按撤回处理、不生成证照；
 * 补正按 requestId 幂等；归档后迟到补正只记迟到事件。
 * 合成数据：SP-001 马尾松林龄 30，单株 0.50 立方米/株；SP-007 杉木林龄 22 无蓄积表档位。
 */
class VolumeDesignCorrectionTest extends AbstractForestryTest {

    private ApplyRequest applyReq(String requestId, String subplotId, String species,
                                  int treeCount, String designVolume) {
        return new ApplyRequest(requestId, "OP-V", subplotId, species, "MAIN",
                treeCount, new BigDecimal(designVolume));
    }

    @Test
    void consistentDesignIsAcceptedWithCheckedVolume() {
        // 40 株 × 0.50 = 20.00，设计值与复核口径一致：直接受理
        var app = service.apply(applyReq("v-ok", "SP-001", "PINUS_MASSONIANA", 40, "20.00"));

        assertThat(app.status()).isEqualTo("SUBMITTED");
        assertThat(app.checkedVolume()).isEqualByComparingTo("20.00");
        assertThat(app.designVolume()).isEqualByComparingTo("20.00");
        assertThat(app.treeCount()).isEqualTo(40);
    }

    @Test
    void inconsistentDesignIsNotAcceptedAndCannotBeReviewed() {
        // 40 株复核 20.00，填报 19.00：口径不一致，不予受理
        var app = service.apply(applyReq("v-mismatch", "SP-001", "PINUS_MASSONIANA", 40, "19.00"));

        assertThat(app.status()).isEqualTo("VOLUME_CORRECTION_PENDING");
        assertThat(app.checkedVolume()).isEqualByComparingTo("20.00");

        // 未受理：审核与发证均不允许
        assertThatThrownBy(() -> service.review(app.id(), new ReviewRequest(true, "OK")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getErrorCode()).isEqualTo("INVALID_STATUS");
                });
        assertThatThrownBy(() -> service.issue(new IssueRequest("v-mismatch-issue", app.id())))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo("INVALID_STATUS"));
    }

    @Test
    void correctionMatchingCheckedVolumeIsAcceptedAndCanBeLicensed() {
        var app = service.apply(applyReq("v-corr-ok", "SP-001", "PINUS_MASSONIANA", 40, "19.00"));
        assertThat(app.status()).isEqualTo("VOLUME_CORRECTION_PENDING");

        // 补正为复核口径 20.00：受理
        var corrected = service.correct(app.id(), new CorrectionRequest("v-corr-ok-1",
                new BigDecimal("20.00")));
        assertThat(corrected.status()).isEqualTo("SUBMITTED");
        assertThat(corrected.designVolume()).isEqualByComparingTo("20.00");

        // 补正受理后可走完整流程发证
        service.review(app.id(), new ReviewRequest(true, "OK"));
        var license = service.issue(new IssueRequest("v-corr-ok-issue", app.id()));
        assertThat(license.status()).isEqualTo("ISSUED");
        assertThat(license.treeCount()).isEqualTo(40);
        assertThat(license.volume()).isEqualByComparingTo("20.00");
    }

    @Test
    void correctionStillInconsistentWithdrawsWithoutLicense() {
        var app = service.apply(applyReq("v-corr-bad", "SP-001", "PINUS_MASSONIANA", 40, "19.00"));

        // 补正后仍不一致：按撤回处理
        var withdrawn = service.correct(app.id(), new CorrectionRequest("v-corr-bad-1",
                new BigDecimal("18.00")));
        assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");

        // 撤回后：不能再补正、不能审核、不能发证，不产生证照
        assertThatThrownBy(() -> service.correct(app.id(), new CorrectionRequest("v-corr-bad-2",
                new BigDecimal("20.00"))))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo("INVALID_STATUS"));
        assertThatThrownBy(() -> service.review(app.id(), new ReviewRequest(true, "OK")))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo("INVALID_STATUS"));
        assertThatThrownBy(() -> service.issue(new IssueRequest("v-corr-bad-issue", app.id())))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo("INVALID_STATUS"));
        Integer licenses = jdbc.queryForObject(
                "SELECT COUNT(*) FROM harvest_license WHERE application_id=?", Integer.class, app.id());
        assertThat(licenses).isZero();
    }

    @Test
    void correctionIsIdempotentByRequestId() {
        var app = service.apply(applyReq("v-corr-idem", "SP-001", "PINUS_MASSONIANA", 40, "19.00"));

        var first = service.correct(app.id(), new CorrectionRequest("v-corr-idem-1",
                new BigDecimal("20.00")));
        // 同键同参：返回首次结果
        var replay = service.correct(app.id(), new CorrectionRequest("v-corr-idem-1",
                new BigDecimal("20.00")));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.status()).isEqualTo("SUBMITTED");
        // 补正机会只被消耗一次
        Integer used = jdbc.queryForObject(
                "SELECT COUNT(*) FROM harvest_application WHERE id=? AND correction_used=1",
                Integer.class, app.id());
        assertThat(used).isOne();

        // 同键异参：409
        assertThatThrownBy(() -> service.correct(app.id(), new CorrectionRequest("v-corr-idem-1",
                new BigDecimal("21.00"))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getErrorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                });
    }

    @Test
    void missingVolumeTableTierRejectsApply() {
        // SP-007 杉木林龄 22 在合成蓄积表中无档位：无法复核，拒绝受理
        assertThatThrownBy(() -> service.apply(
                applyReq("v-no-table", "SP-007", "CUNNINGHAMIA", 10, "5.00")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("VOLUME_TABLE_MISSING");
                });
    }

    @Test
    void lateCorrectionAfterArchiveOnlyRecordsEvent() {
        var app = service.apply(applyReq("v-corr-late", "SP-001", "PINUS_MASSONIANA", 40, "19.00"));
        service.archive(app.id());

        assertThatThrownBy(() -> service.correct(app.id(), new CorrectionRequest("v-corr-late-1",
                new BigDecimal("20.00"))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getErrorCode()).isEqualTo("APPLICATION_ARCHIVED");
                });

        Integer events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_event WHERE application_id=? AND event_type='CORRECTION'",
                Integer.class, app.id());
        assertThat(events).isOne();
        // 归档终态不被迟到补正驱动
        assertThat(service.getApplication(app.id()).status()).isEqualTo("ARCHIVED");
    }
}
