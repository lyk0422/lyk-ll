package com.example.starter.schedule;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.example.starter.schedule.ScheduleRequests.ActualItem;
import com.example.starter.schedule.ScheduleRequests.CreateSchedule;
import com.example.starter.schedule.ScheduleRequests.ItemSpec;
import com.example.starter.schedule.ScheduleRequests.RegisterRollChange;
import com.example.starter.schedule.ScheduleRequests.ReportActuals;
import com.example.starter.schedule.ScheduleRequests.ReviseSchedule;
import com.example.starter.schedule.ScheduleResponses.ActualsResult;
import com.example.starter.schedule.ScheduleResponses.FreezeResult;
import com.example.starter.schedule.ScheduleResponses.ScheduleView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 排产流转测试：版本冻结、实绩去重归位、换辊触发、日冻结终态、同键重放。
 */
class ScheduleFlowTest extends BaseScheduleTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 9, 25, 8, 0);

    private ItemSpec item(int seq, int widthMm, String mileageKm) {
        LocalDateTime start = DAY.plusHours(seq - 1L);
        return new ItemSpec(seq, "C" + seq, widthMm, new BigDecimal(mileageKm), false,
                start, start.plusHours(1));
    }

    private CreateSchedule createReq(String requestId, String unitNo) {
        return new CreateSchedule(requestId, "L1", unitNo, List.of(
                item(1, 1500, "2"),
                item(2, 1400, "2")), List.of());
    }

    private ScheduleView releasedSchedule(String requestId, String unitNo) {
        ScheduleView draft = scheduleService.create(createReq(requestId, unitNo));
        scheduleService.validate(draft.scheduleId());
        return scheduleService.release(draft.scheduleId());
    }

    private ReportActuals actualsReq(String requestId, ActualItem... items) {
        return new ReportActuals(requestId, List.of(items));
    }

    private ActualItem actual(String contractNo, int passNo, String mileageKm) {
        return new ActualItem(contractNo, passNo, new BigDecimal(mileageKm), DAY.plusHours(1));
    }

    @Test
    void releaseRequiresValidated() {
        ScheduleView draft = scheduleService.create(createReq("r-rel-0", "U-REL-0"));
        assertThatThrownBy(() -> scheduleService.release(draft.scheduleId()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void releasedVersionFrozenAndReviseCreatesNewVersion() {
        ScheduleView v1 = releasedSchedule("r-v1", "U-VER");
        assertThat(v1.status()).isEqualTo("RELEASED");
        assertThat(v1.effective()).isTrue();
        assertThat(v1.version()).isEqualTo(1);

        // 下达后修改生成新版本
        ScheduleView v2 = scheduleService.revise(v1.scheduleId(), new ReviseSchedule(
                "r-v2", List.of(item(1, 1500, "3"), item(2, 1400, "3")), List.of()));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.status()).isEqualTo("DRAFT");
        assertThat(v2.effective()).isFalse();

        // 旧版本仍生效，直到新版本下达
        assertThat(scheduleService.getView(v1.scheduleId()).effective()).isTrue();
        scheduleService.validate(v2.scheduleId());
        ScheduleView v2Released = scheduleService.release(v2.scheduleId());
        assertThat(v2Released.effective()).isTrue();
        assertThat(scheduleService.getView(v1.scheduleId()).effective()).isFalse();
    }

    @Test
    void releaseDifferentUnitOnSameLineConflict() {
        releasedSchedule("r-a", "U-A");
        ScheduleView draftB = scheduleService.create(createReq("r-b", "U-B"));
        scheduleService.validate(draftB.scheduleId());
        assertThatThrownBy(() -> scheduleService.release(draftB.scheduleId()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void actualsDeduplicatedByContractAndPass() {
        ScheduleView schedule = releasedSchedule("r-dedup", "U-DEDUP");
        ActualsResult result = scheduleService.reportActuals(schedule.scheduleId(),
                actualsReq("ra-dedup",
                        actual("C1", 1, "4"),
                        actual("C1", 1, "4"),
                        actual("C1", 2, "3")));
        assertThat(result.accepted()).isEqualTo(2);
        assertThat(result.duplicates()).isEqualTo(1);
        assertThat(result.actualMileageKm()).isEqualByComparingTo("7");
        assertThat(result.status()).isEqualTo("EXECUTING");
        // 重复道次归位后不入库
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM actual_record WHERE schedule_id = ?",
                Integer.class, schedule.scheduleId());
        assertThat(rows).isEqualTo(2);
    }

    @Test
    void actualsBelowThresholdDoNotLock() {
        ScheduleView schedule = releasedSchedule("r-below", "U-BELOW");
        ActualsResult result = scheduleService.reportActuals(schedule.scheduleId(),
                actualsReq("ra-below", actual("C1", 1, "9.999")));
        assertThat(result.locked()).isFalse();
        assertThat(result.rollChangeRequired()).isFalse();
    }

    @Test
    void actualsReachingThresholdLockAndRequireRollChange() {
        ScheduleView schedule = releasedSchedule("r-lock", "U-LOCK");
        ActualsResult first = scheduleService.reportActuals(schedule.scheduleId(),
                actualsReq("ra-lock-1", actual("C1", 1, "6")));
        assertThat(first.locked()).isFalse();

        // 恰达 10 公里阈值：自动锁定后续排程并提示换辊
        ActualsResult second = scheduleService.reportActuals(schedule.scheduleId(),
                actualsReq("ra-lock-2", actual("C1", 2, "4")));
        assertThat(second.actualMileageKm()).isEqualByComparingTo("10");
        assertThat(second.locked()).isTrue();
        assertThat(second.rollChangeRequired()).isTrue();

        // 锁定后不得静默放行超限轧制
        assertThatThrownBy(() -> scheduleService.reportActuals(schedule.scheduleId(),
                actualsReq("ra-lock-3", actual("C1", 3, "1"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));

        // 登记换辊后解除锁定、里程清零，可继续回传
        ScheduleView unlocked = scheduleService.registerRollChange(schedule.scheduleId(),
                new RegisterRollChange(DAY.plusHours(3), DAY.plusHours(3).plusMinutes(30)));
        assertThat(unlocked.locked()).isFalse();
        assertThat(unlocked.rollChangeRequired()).isFalse();
        assertThat(unlocked.actualMileageKm()).isEqualByComparingTo("0");

        ActualsResult after = scheduleService.reportActuals(schedule.scheduleId(),
                actualsReq("ra-lock-4", actual("C1", 3, "1")));
        assertThat(after.accepted()).isEqualTo(1);
        assertThat(after.locked()).isFalse();
    }

    @Test
    void freezeIsTerminalAndLateActualsOnlyRecorded() {
        ScheduleView schedule = releasedSchedule("r-frz", "U-FRZ");
        scheduleService.reportActuals(schedule.scheduleId(),
                actualsReq("ra-frz", actual("C1", 1, "4")));

        FreezeResult frozen = scheduleService.freeze(schedule.scheduleId());
        assertThat(frozen.status()).isEqualTo("FROZEN");
        assertThat(frozen.actualMileageKm()).isEqualByComparingTo("4");

        // 重复冻结：幂等成功，结论不变
        FreezeResult again = scheduleService.freeze(schedule.scheduleId());
        assertThat(again.status()).isEqualTo("FROZEN");
        assertThat(again.actualMileageKm()).isEqualByComparingTo("4");

        // 迟到实绩只记事件，不改已冻结里程与换辊结论
        ActualsResult late = scheduleService.reportActuals(schedule.scheduleId(),
                actualsReq("ra-late", actual("C1", 2, "3")));
        assertThat(late.late()).isEqualTo(1);
        assertThat(late.accepted()).isZero();
        assertThat(late.actualMileageKm()).isEqualByComparingTo("4");
        assertThat(repository.countLateEvents(schedule.scheduleId())).isEqualTo(1);
        assertThat(scheduleService.getView(schedule.scheduleId()).actualMileageKm())
                .isEqualByComparingTo("4");

        // 冻结为终态：不允许再下达/修订
        assertThatThrownBy(() -> scheduleService.release(schedule.scheduleId()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void sameRequestIdSameParamsReplaysFirstResult() {
        CreateSchedule request = createReq("r-idem", "U-IDEM");
        IdempotencyService.Outcome first = idempotencyService.run("r-idem",
                Fingerprint.ofSchedule(request.requestId(), request.lineCode(), request.unitNo(),
                        request.items(), request.rollChangeWindows()),
                201, () -> scheduleService.create(request));
        IdempotencyService.Outcome replay = idempotencyService.run("r-idem",
                Fingerprint.ofSchedule(request.requestId(), request.lineCode(), request.unitNo(),
                        request.items(), request.rollChangeWindows()),
                201, () -> scheduleService.create(request));

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.bodyJson()).isEqualTo(first.bodyJson());
        Integer schedules = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule WHERE unit_no = 'U-IDEM'", Integer.class);
        assertThat(schedules).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentParamsReturns409() {
        CreateSchedule request = createReq("r-idem-x", "U-IDEM-X");
        idempotencyService.run("r-idem-x",
                Fingerprint.ofSchedule(request.requestId(), request.lineCode(), request.unitNo(),
                        request.items(), request.rollChangeWindows()),
                201, () -> scheduleService.create(request));

        CreateSchedule changed = new CreateSchedule("r-idem-x", "L1", "U-IDEM-X",
                List.of(item(1, 1500, "9")), List.of());
        String changedFingerprint = Fingerprint.ofSchedule(changed.requestId(), changed.lineCode(),
                changed.unitNo(), changed.items(), changed.rollChangeWindows());
        assertThatThrownBy(() -> idempotencyService.run("r-idem-x", changedFingerprint,
                201, () -> scheduleService.create(changed)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                });
        // 不产生新版本/新单据
        Integer schedules = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule WHERE unit_no = 'U-IDEM-X'", Integer.class);
        assertThat(schedules).isEqualTo(1);
    }

    @Test
    void actualsReplayDoesNotDoubleCountMileage() {
        ScheduleView schedule = releasedSchedule("r-rep", "U-REP");
        ReportActuals request = actualsReq("ra-rep", actual("C1", 1, "4"));
        String fingerprint = Fingerprint.ofActuals("ra-rep", schedule.scheduleId(), request.actuals());

        IdempotencyService.Outcome first = idempotencyService.run("ra-rep", fingerprint, 200,
                () -> scheduleService.reportActuals(schedule.scheduleId(), request));
        IdempotencyService.Outcome replay = idempotencyService.run("ra-rep", fingerprint, 200,
                () -> scheduleService.reportActuals(schedule.scheduleId(), request));

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(scheduleService.getView(schedule.scheduleId()).actualMileageKm())
                .isEqualByComparingTo("4");
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM actual_record WHERE schedule_id = ?",
                Integer.class, schedule.scheduleId());
        assertThat(rows).isEqualTo(1);
    }
}
