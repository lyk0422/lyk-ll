package com.example.starter.epidemic.service;

import com.example.starter.epidemic.AbstractIntegrationTest;
import com.example.starter.epidemic.Requests;
import com.example.starter.epidemic.api.ActionResult;
import com.example.starter.epidemic.api.CardView;
import com.example.starter.epidemic.api.ReviewView;
import com.example.starter.epidemic.api.SpecialCorrectionView;
import com.example.starter.epidemic.api.SubmitResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 终审归档终态与归档后双人特殊订正测试。
 */
class ArchiveAndSpecialIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EpidemicCardService service;

    private String approvedCard(String patientId) {
        SubmitResult r = service.submitInitial(Requests.initial(
                Requests.rid(), patientId, "MEASLES", "CONFIRMED",
                Requests.utc(2026, 9, 25, 7, 0), null));
        service.review(r.cardNumber(), "reviewer-a", null);
        return r.cardNumber();
    }

    @Test
    void archive_requires_approval_and_is_readonly_afterwards() {
        SubmitResult r = service.submitInitial(Requests.initial(
                Requests.rid(), "A1", "MEASLES", "CONFIRMED",
                Requests.utc(2026, 9, 25, 7, 0), null));
        assertThatThrownBy(() -> service.archive(r.cardNumber(), "f1", null))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo("CARD_NOT_APPROVED");

        service.review(r.cardNumber(), "reviewer-a", null);
        ActionResult archived = service.archive(r.cardNumber(), "final-1", null);
        assertThat(archived.status()).isEqualTo("ARCHIVED");
        assertThat(archived.idempotentHit()).isFalse();
    }

    @Test
    void duplicate_archive_returns_idempotent_success() {
        String card = approvedCard("A2");
        service.archive(card, "final-1", null);
        ActionResult again = service.archive(card, "final-2", "重复归档");
        assertThat(again.status()).isEqualTo("ARCHIVED");
        assertThat(again.idempotentHit()).isTrue();
        // 重复归档不新增终审轨迹
        CardView view = service.getCard(card);
        long finals = view.reviews().stream().filter(r -> r.stage().equals("FINAL")).count();
        assertThat(finals).isEqualTo(1);
    }

    @Test
    void normal_correction_after_archive_is_rejected_but_special_flow_works() {
        String card = approvedCard("A3");
        service.archive(card, "final-1", null);

        // 发起特殊订正（归档后唯一通道）
        String spRid = Requests.rid();
        SpecialCorrectionView created = service.submitSpecial(Requests.special(
                spRid, card, "TYPHOID", "CONFIRMED",
                Requests.utc(2026, 9, 25, 7, 0), null));
        assertThat(created.effective()).isFalse();
        assertThat(created.status()).isEqualTo("PENDING");

        // 单人确认不生效
        SpecialCorrectionView one = service.confirmSpecial(spRid, Requests.confirm("auditor-x"));
        assertThat(one.effective()).isFalse();
        assertThat(one.confirmer1()).isEqualTo("auditor-x");
        assertThat(service.getCard(card).versions()).hasSize(1);

        // 同一人再次确认仍不生效
        SpecialCorrectionView same = service.confirmSpecial(spRid, Requests.confirm("auditor-x"));
        assertThat(same.effective()).isFalse();
        assertThat(same.confirmer2()).isNull();

        // 第二名不同审核员确认后生效
        SpecialCorrectionView two = service.confirmSpecial(spRid, Requests.confirm("auditor-y"));
        assertThat(two.effective()).isTrue();
        assertThat(two.appliedVersionNo()).isEqualTo(2);

        CardView view = service.getCard(card);
        assertThat(view.status()).isEqualTo("ARCHIVED");
        assertThat(view.currentVersion()).isEqualTo(2);
        assertThat(view.current().diseaseCode()).isEqualTo("TYPHOID");
        assertThat(view.current().changeKind()).isEqualTo("SPECIAL");
        assertThat(view.versions()).hasSize(2);
        // 两条 SPECIAL 确认轨迹，审核员不同
        assertThat(view.reviews())
                .filteredOn(r -> r.stage().equals("SPECIAL"))
                .extracting(ReviewView::reviewer)
                .containsExactly("auditor-x", "auditor-y");
    }

    @Test
    void special_correction_requires_archived_card() {
        SubmitResult r = service.submitInitial(Requests.initial(
                Requests.rid(), "A4", "MEASLES", "CONFIRMED",
                Requests.utc(2026, 9, 25, 7, 0), null));
        service.review(r.cardNumber(), "reviewer-a", null);
        assertThatThrownBy(() -> service.submitSpecial(Requests.special(
                Requests.rid(), r.cardNumber(), "TYPHOID", "CONFIRMED",
                Requests.utc(2026, 9, 25, 7, 0), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo("SPECIAL_REQUIRES_ARCHIVED");
    }

    @Test
    void special_same_request_id_different_params_returns_409() {
        String card = approvedCard("A5");
        service.archive(card, "final-1", null);
        String rid = Requests.rid();
        service.submitSpecial(Requests.special(rid, card, "TYPHOID", "CONFIRMED",
                Requests.utc(2026, 9, 25, 7, 0), null));
        assertThatThrownBy(() -> service.submitSpecial(Requests.special(
                rid, card, "TB", "CONFIRMED",
                Requests.utc(2026, 9, 25, 7, 0), null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void special_replay_returns_first_result() {
        String card = approvedCard("A6");
        service.archive(card, "final-1", null);
        var req = Requests.special(Requests.rid(), card, "TB", "CONFIRMED",
                Requests.utc(2026, 9, 25, 7, 0), null);
        SpecialCorrectionView first = service.submitSpecial(req);
        SpecialCorrectionView replay = service.submitSpecial(req);
        assertThat(replay.requestId()).isEqualTo(first.requestId());
        assertThat(replay.status()).isEqualTo("PENDING");
    }
}
