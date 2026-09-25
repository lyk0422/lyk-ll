package com.example.starter.epidemic.service;

import com.example.starter.epidemic.AbstractIntegrationTest;
import com.example.starter.epidemic.Requests;
import com.example.starter.epidemic.api.CardView;
import com.example.starter.epidemic.api.SubmitResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 时限边界与迟报原因的 H2 集成测试。固定时钟 now=2026-09-25T08:00:00Z。
 */
class TimelinessIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EpidemicCardService service;

    @Test
    void category_a_submitted_exactly_at_two_hour_deadline_is_timely() {
        // 诊断 06:00Z，时限终点 08:00Z，恰在终点提交 → 及时
        OffsetDateTime diagnosis = Requests.utc(2026, 9, 25, 6, 0);
        SubmitResult result = service.submitInitial(
                Requests.initial(Requests.rid(), "P-A-EXACT", "PLAGUE", "CONFIRMED", diagnosis, null));
        assertThat(result.timely()).isTrue();
        assertThat(result.duplicated()).isFalse();

        CardView view = service.getCard(result.cardNumber());
        assertThat(view.current().deadline()).isEqualTo(Requests.utc(2026, 9, 25, 8, 0));
        assertThat(view.current().timely()).isTrue();
    }

    @Test
    void category_a_one_second_over_two_hours_is_late_and_requires_reason() {
        // 诊断 05:59:59Z → 终点 07:59:59Z，提交 08:00:00Z → 迟报 1 秒
        OffsetDateTime diagnosis = Requests.utc(2026, 9, 25, 6, 0).minusSeconds(1);
        SubmitResult result = service.submitInitial(
                Requests.initial(Requests.rid(), "P-A-LATE", "CHOLERA", "CONFIRMED",
                        diagnosis, "基层网络中断导致迟报"));
        assertThat(result.timely()).isFalse();
        CardView view = service.getCard(result.cardNumber());
        assertThat(view.current().lateReason()).isEqualTo("基层网络中断导致迟报");
    }

    @Test
    void late_report_without_reason_is_rejected_400() {
        OffsetDateTime diagnosis = Requests.utc(2026, 9, 24, 0, 0);
        assertThatThrownBy(() -> service.submitInitial(
                Requests.initial(Requests.rid(), "P-NOREASON", "PLAGUE", "CONFIRMED", diagnosis, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo("LATE_REASON_REQUIRED");
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    void timely_report_with_late_reason_is_rejected_400() {
        OffsetDateTime diagnosis = Requests.utc(2026, 9, 25, 7, 0);
        assertThatThrownBy(() -> service.submitInitial(
                Requests.initial(Requests.rid(), "P-EXTRA", "PLAGUE", "CONFIRMED",
                        diagnosis, "不该填原因")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo("LATE_REASON_NOT_ALLOWED");
    }

    @Test
    void category_b_exactly_twenty_four_hours_is_timely_and_over_is_late() {
        // 乙类：诊断 2026-09-24T08:00Z，终点 2026-09-25T08:00Z，恰点及时
        SubmitResult timely = service.submitInitial(
                Requests.initial(Requests.rid(), "P-B-EXACT", "MEASLES", "CONFIRMED",
                        Requests.utc(2026, 9, 24, 8, 0), null));
        assertThat(timely.timely()).isTrue();

        // 诊断 07:59:59Z → 终点次日 07:59:59Z，提交 08:00:00Z → 迟报 1 秒
        SubmitResult late = service.submitInitial(
                Requests.initial(Requests.rid(), "P-B-LATE", "TYPHOID", "CONFIRMED",
                        Requests.utc(2026, 9, 24, 8, 0).minusSeconds(1), "转诊延迟"));
        assertThat(late.timely()).isFalse();
    }
}
