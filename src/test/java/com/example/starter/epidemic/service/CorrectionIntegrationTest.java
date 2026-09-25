package com.example.starter.epidemic.service;

import com.example.starter.epidemic.AbstractIntegrationTest;
import com.example.starter.epidemic.Requests;
import com.example.starter.epidemic.api.ActionResult;
import com.example.starter.epidemic.api.CardView;
import com.example.starter.epidemic.api.CorrectionRequest;
import com.example.starter.epidemic.api.SubmitResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订正版本链与病种变更重起审核测试。
 */
class CorrectionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EpidemicCardService service;

    private String approvedCard(String patientId) {
        SubmitResult r = service.submitInitial(Requests.initial(
                Requests.rid(), patientId, "MEASLES", "SUSPECTED",
                Requests.utc(2026, 9, 25, 7, 0), null));
        service.review(r.cardNumber(), "reviewer-a", "初审核实");
        return r.cardNumber();
    }

    @Test
    void correction_before_approval_is_rejected() {
        SubmitResult r = service.submitInitial(Requests.initial(
                Requests.rid(), "C1", "MEASLES", "SUSPECTED",
                Requests.utc(2026, 9, 25, 7, 0), null));
        assertThatThrownBy(() -> service.submitCorrection(Requests.correction(
                Requests.rid(), r.cardNumber(), "MEASLES", "CONFIRMED",
                Requests.utc(2026, 9, 25, 7, 0), null, "疑似转确诊")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo("CARD_NOT_APPROVED");
    }

    @Test
    void disease_change_correction_restarts_review_and_voids_old_approval() {
        String card = approvedCard("C2");

        // 疑似麻疹 → 确诊麻疹：诊断类型变更视为病种变更类订正，重起审核
        SubmitResult corrected = service.submitCorrection(Requests.correction(
                Requests.rid(), card, "MEASLES", "CONFIRMED",
                Requests.utc(2026, 9, 25, 7, 0), null, "疑似转确诊"));
        assertThat(corrected.versionNo()).isEqualTo(2);
        assertThat(corrected.status()).isEqualTo("PENDING_REVIEW");

        CardView view = service.getCard(card);
        assertThat(view.versions()).hasSize(2);
        assertThat(view.currentVersion()).isEqualTo(2);
        assertThat(view.current().diagnosisType()).isEqualTo("CONFIRMED");
        assertThat(view.current().diseaseChanged()).isTrue();
        // 历史版本完整保留且为旧结论
        assertThat(view.versions().get(0).diagnosisType()).isEqualTo("SUSPECTED");
        // 原初审通过记录作废留痕
        assertThat(view.reviews())
                .anyMatch(r -> r.stage().equals("REVIEW") && r.versionNo() == 1 && r.voided());

        // 重起审核后再次通过
        ActionResult again = service.review(card, "reviewer-b", "复核确诊");
        assertThat(again.status()).isEqualTo("APPROVED");
    }

    @Test
    void disease_code_change_correction_restarts_review() {
        String card = approvedCard("C3");
        SubmitResult corrected = service.submitCorrection(Requests.correction(
                Requests.rid(), card, "TYPHOID", "CONFIRMED",
                Requests.utc(2026, 9, 25, 7, 0), null, "病种修正为伤寒"));
        assertThat(corrected.status()).isEqualTo("PENDING_REVIEW");
        CardView view = service.getCard(card);
        assertThat(view.current().diseaseCode()).isEqualTo("TYPHOID");
        assertThat(view.current().diseaseChanged()).isTrue();
    }

    @Test
    void non_disease_correction_keeps_approved_status() {
        String card = approvedCard("C4");
        CorrectionRequest req = Requests.correctionWithOnset(
                Requests.rid(), card, "MEASLES", "SUSPECTED",
                Requests.utc(2026, 9, 25, 7, 0),
                java.time.LocalDate.of(2026, 9, 23), null, "仅修正发病日期");
        SubmitResult corrected = service.submitCorrection(req);
        assertThat(corrected.versionNo()).isEqualTo(2);
        assertThat(corrected.status()).isEqualTo("APPROVED");
    }

    @Test
    void correction_same_request_id_replays_and_different_params_conflicts() {
        String card = approvedCard("C5");
        CorrectionRequest req = Requests.correction(
                Requests.rid(), card, "MEASLES", "CONFIRMED",
                Requests.utc(2026, 9, 25, 7, 0), null, "转确诊");
        SubmitResult first = service.submitCorrection(req);
        service.review(card, "reviewer-a", null);

        SubmitResult replay = service.submitCorrection(req);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.versionNo()).isEqualTo(first.versionNo());

        CorrectionRequest changed = Requests.correction(
                req.requestId(), card, "TYPHOID", "CONFIRMED",
                Requests.utc(2026, 9, 25, 7, 0), null, "改病种");
        assertThatThrownBy(() -> service.submitCorrection(changed))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
        // 409 不产生新版本
        assertThat(service.getCard(card).versions()).hasSize(2);
    }

    @Test
    void archived_card_rejects_normal_correction() {
        String card = approvedCard("C6");
        service.archive(card, "final-1", null);
        assertThatThrownBy(() -> service.submitCorrection(Requests.correction(
                Requests.rid(), card, "MEASLES", "CONFIRMED",
                Requests.utc(2026, 9, 25, 7, 0), null, "试图订正")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo("CARD_ARCHIVED");
    }
}
