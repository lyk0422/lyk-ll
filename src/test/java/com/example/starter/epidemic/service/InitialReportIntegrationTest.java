package com.example.starter.epidemic.service;

import com.example.starter.epidemic.AbstractIntegrationTest;
import com.example.starter.epidemic.Requests;
import com.example.starter.epidemic.api.ActionResult;
import com.example.starter.epidemic.api.CardView;
import com.example.starter.epidemic.api.InitialReportRequest;
import com.example.starter.epidemic.api.SubmitResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 初报主流程、重复卡去重与撤回释放去重位。
 */
class InitialReportIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EpidemicCardService service;

    @Test
    void first_report_creates_pending_card() {
        SubmitResult r = service.submitInitial(Requests.initial(
                Requests.rid(), "P1", "PLAGUE", "CONFIRMED", Requests.utc(2026, 9, 25, 7, 0), null));
        assertThat(r.duplicated()).isFalse();
        assertThat(r.replayed()).isFalse();
        assertThat(r.status()).isEqualTo("PENDING_REVIEW");
        assertThat(r.versionNo()).isEqualTo(1);
        assertThat(r.cardNumber()).startsWith("C");
    }

    @Test
    void duplicate_same_patient_disease_onset_returns_existing_card_without_new_one() {
        String rid1 = Requests.rid();
        SubmitResult first = service.submitInitial(Requests.initial(
                rid1, "PDUP", "MEASLES", "SUSPECTED", Requests.utc(2026, 9, 25, 7, 0), null));
        SubmitResult second = service.submitInitial(Requests.initial(
                Requests.rid(), "PDUP", "MEASLES", "SUSPECTED", Requests.utc(2026, 9, 25, 7, 5), null));

        assertThat(second.duplicated()).isTrue();
        assertThat(second.cardNumber()).isEqualTo(first.cardNumber());
        assertThat(second.existingCardNumber()).isEqualTo(first.cardNumber());
        assertThat(second.versionNo()).isEqualTo(1);
        assertThat(service.listCards()).hasSize(1);
    }

    @Test
    void different_disease_is_not_duplicate() {
        SubmitResult a = service.submitInitial(Requests.initial(
                Requests.rid(), "PDIFF", "MEASLES", "CONFIRMED", Requests.utc(2026, 9, 25, 7, 0), null));
        SubmitResult b = service.submitInitial(Requests.initial(
                Requests.rid(), "PDIFF", "TYPHOID", "CONFIRMED", Requests.utc(2026, 9, 25, 7, 0), null));
        assertThat(b.duplicated()).isFalse();
        assertThat(b.cardNumber()).isNotEqualTo(a.cardNumber());
    }

    @Test
    void same_request_id_same_params_replays_first_result() {
        String rid = Requests.rid();
        InitialReportRequest req = Requests.initial(
                rid, "PREPLAY", "PLAGUE", "CONFIRMED", Requests.utc(2026, 9, 25, 7, 0), null);
        SubmitResult first = service.submitInitial(req);
        SubmitResult replay = service.submitInitial(req);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.cardNumber()).isEqualTo(first.cardNumber());
        assertThat(replay.versionNo()).isEqualTo(1);
        assertThat(service.listCards()).hasSize(1);
    }

    @Test
    void same_request_id_different_params_returns_409_and_no_new_version() {
        String rid = Requests.rid();
        service.submitInitial(Requests.initial(
                rid, "PCONF", "PLAGUE", "CONFIRMED", Requests.utc(2026, 9, 25, 7, 0), null));
        InitialReportRequest changed = Requests.initial(
                rid, "PCONF", "PLAGUE", "CONFIRMED", Requests.utc(2026, 9, 25, 6, 30), null);

        assertThatThrownBy(() -> service.submitInitial(changed))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getErrorCode()).isEqualTo("IDEMPOTENT_PARAM_MISMATCH");
                });
        CardView view = service.getCard(service.listCards().get(0).cardNumber());
        assertThat(view.versions()).hasSize(1);
    }

    @Test
    void withdrawn_card_releases_dedup_slot_allowing_new_report() {
        SubmitResult first = service.submitInitial(Requests.initial(
                Requests.rid(), "PWD", "MEASLES", "CONFIRMED", Requests.utc(2026, 9, 25, 7, 0), null));
        ActionResult withdrawn = service.withdraw(first.cardNumber(), "王操作", "信息错误");
        assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");

        SubmitResult again = service.submitInitial(Requests.initial(
                Requests.rid(), "PWD", "MEASLES", "CONFIRMED", Requests.utc(2026, 9, 25, 7, 10), null));
        assertThat(again.duplicated()).isFalse();
        assertThat(again.cardNumber()).isNotEqualTo(first.cardNumber());
    }

    @Test
    void withdrawn_card_is_terminal_and_review_is_rejected() {
        SubmitResult r = service.submitInitial(Requests.initial(
                Requests.rid(), "PTERM", "MEASLES", "CONFIRMED", Requests.utc(2026, 9, 25, 7, 0), null));
        service.withdraw(r.cardNumber(), "王操作", null);
        assertThatThrownBy(() -> service.review(r.cardNumber(), "审1", null))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo("CARD_WITHDRAWN");
    }

    @Test
    void unknown_disease_or_facility_is_rejected() {
        assertThatThrownBy(() -> service.submitInitial(Requests.initial(
                Requests.rid(), "PX", "NOPE", "CONFIRMED", Requests.utc(2026, 9, 25, 7, 0), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo("DISEASE_NOT_FOUND");
    }
}
