package com.example.starter.schedule;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.example.starter.schedule.ScheduleRequests.CreateSchedule;
import com.example.starter.schedule.ScheduleRequests.ItemSpec;
import com.example.starter.schedule.ScheduleRequests.RollWindowSpec;
import com.example.starter.schedule.ScheduleResponses.ScheduleView;
import com.example.starter.schedule.ScheduleResponses.Violation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 排产规则校验测试：宽度跳档边界、烫辊前 5 块、10 公里恰达、半开冲突、违例整单拒绝。
 */
class ScheduleValidationTest extends BaseScheduleTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 9, 25, 8, 0);

    private ItemSpec item(int seq, int widthMm, String mileageKm, boolean warmup) {
        LocalDateTime start = DAY.plusHours(seq - 1L);
        return new ItemSpec(seq, "C" + seq, widthMm, new BigDecimal(mileageKm), warmup,
                start, start.plusHours(1));
    }

    private ScheduleView createDraft(String requestId, String unitNo, List<ItemSpec> items,
                                     List<RollWindowSpec> windows) {
        return scheduleService.create(new CreateSchedule(requestId, "L1", unitNo, items, windows));
    }

    private List<Violation> violationsOf(long scheduleId) {
        try {
            scheduleService.validate(scheduleId);
            return List.of();
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            return e.violations();
        }
    }

    @Test
    void widthJumpExactlyOneGradeIsLegal() {
        ScheduleView draft = createDraft("r-jump-ok", "U-JUMP-OK", List.of(
                item(1, 1500, "1", false),
                item(2, 1400, "1", false),
                item(3, 1400, "1", false),
                item(4, 1300, "1", false)), List.of());
        var result = scheduleService.validate(draft.scheduleId());
        assertThat(result.status()).isEqualTo("VALIDATED");
        assertThat(scheduleService.getView(draft.scheduleId()).status()).isEqualTo("VALIDATED");
    }

    @Test
    void widthJumpTwoGradesRejected() {
        ScheduleView draft = createDraft("r-jump-2", "U-JUMP-2", List.of(
                item(1, 1500, "1", false),
                item(2, 1300, "1", false)), List.of());
        List<Violation> violations = violationsOf(draft.scheduleId());
        assertThat(violations).extracting(Violation::rule).contains("WIDTH_JUMP");
        assertThat(scheduleService.getView(draft.scheduleId()).status()).isEqualTo("DRAFT");
    }

    @Test
    void widthIncreaseRejected() {
        ScheduleView draft = createDraft("r-order", "U-ORDER", List.of(
                item(1, 1400, "1", false),
                item(2, 1500, "1", false)), List.of());
        List<Violation> violations = violationsOf(draft.scheduleId());
        assertThat(violations).extracting(Violation::rule).contains("WIDTH_ORDER");
    }

    @Test
    void mileageExactly10kmIsLegal() {
        ScheduleView draft = createDraft("r-10km", "U-10KM", List.of(
                item(1, 1500, "6", false),
                item(2, 1500, "4", false)), List.of());
        var result = scheduleService.validate(draft.scheduleId());
        assertThat(result.status()).isEqualTo("VALIDATED");
    }

    @Test
    void mileageOver10kmRejected() {
        ScheduleView draft = createDraft("r-11km", "U-11KM", List.of(
                item(1, 1500, "6", false),
                item(2, 1500, "5", false)), List.of());
        List<Violation> violations = violationsOf(draft.scheduleId());
        assertThat(violations).extracting(Violation::rule).contains("MILEAGE_LIMIT");
    }

    @Test
    void mileageResetsAfterRollChangeWindow() {
        // 同宽度 8km + 换辊窗口 + 8km：窗口落在作业间隙，两个周期各不超 10km
        List<ItemSpec> items = List.of(
                item(1, 1500, "8", false),
                new ItemSpec(2, "C2", 1500, new BigDecimal("8"), false,
                        DAY.plusHours(1).plusMinutes(30), DAY.plusHours(2).plusMinutes(30)));
        List<RollWindowSpec> windows = List.of(new RollWindowSpec(
                DAY.plusHours(1), DAY.plusHours(1).plusMinutes(30)));
        ScheduleView draft = createDraft("r-cycle", "U-CYCLE", items, windows);
        var result = scheduleService.validate(draft.scheduleId());
        assertThat(result.status()).isEqualTo("VALIDATED");
    }

    @Test
    void warmupAtFifthPositionIsLegal() {
        List<ItemSpec> items = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            items.add(item(i, 1500, "1", false));
        }
        items.add(item(5, 1500, "1", true));
        ScheduleView draft = createDraft("r-warm5", "U-WARM5", items, List.of());
        var result = scheduleService.validate(draft.scheduleId());
        assertThat(result.status()).isEqualTo("VALIDATED");
    }

    @Test
    void warmupAtSixthPositionRejected() {
        List<ItemSpec> items = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            items.add(item(i, 1500, "1", false));
        }
        items.add(item(6, 1500, "1", true));
        ScheduleView draft = createDraft("r-warm6", "U-WARM6", items, List.of());
        List<Violation> violations = violationsOf(draft.scheduleId());
        assertThat(violations).extracting(Violation::rule).contains("WARMUP_POSITION");
    }

    @Test
    void windowTouchingEndpointIsNotConflict() {
        // 半开区间：作业 [08:00, 09:00) 与窗口 [09:00, 09:30) 端点相接不冲突
        List<ItemSpec> items = List.of(item(1, 1500, "1", false));
        List<RollWindowSpec> windows = List.of(new RollWindowSpec(
                DAY.plusHours(1), DAY.plusHours(1).plusMinutes(30)));
        ScheduleView draft = createDraft("r-touch", "U-TOUCH", items, windows);
        var result = scheduleService.validate(draft.scheduleId());
        assertThat(result.status()).isEqualTo("VALIDATED");
    }

    @Test
    void windowInternalOverlapIsConflict() {
        // 作业 [08:00, 09:00) 与窗口 [08:30, 09:30) 内部重叠冲突
        List<ItemSpec> items = List.of(item(1, 1500, "1", false));
        List<RollWindowSpec> windows = List.of(new RollWindowSpec(
                DAY.plusMinutes(30), DAY.plusHours(1).plusMinutes(30)));
        ScheduleView draft = createDraft("r-overlap", "U-OVERLAP", items, windows);
        List<Violation> violations = violationsOf(draft.scheduleId());
        assertThat(violations).extracting(Violation::rule).contains("WINDOW_CONFLICT");
    }

    @Test
    void multipleViolationsAllReportedAndWholeOrderRejected() {
        // 同时触发跳档与超里程：全部明细返回，状态保持 DRAFT，不产生部分落程
        ScheduleView draft = createDraft("r-multi", "U-MULTI", List.of(
                item(1, 1500, "6", false),
                item(2, 1300, "6", false),
                item(3, 1300, "6", false)), List.of());
        assertThatThrownBy(() -> scheduleService.validate(draft.scheduleId()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.violations()).extracting(Violation::rule)
                            .contains("WIDTH_JUMP", "MILEAGE_LIMIT");
                });
        ScheduleView after = scheduleService.getView(draft.scheduleId());
        assertThat(after.status()).isEqualTo("DRAFT");
        assertThat(after.effective()).isFalse();
        // 未下达、未产生任何实绩
        Integer actuals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM actual_record WHERE schedule_id = ?",
                Integer.class, draft.scheduleId());
        assertThat(actuals).isZero();
    }
}
