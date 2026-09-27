package com.example.starter.rt.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.rt.dto.DeviceCapabilityRequest;
import com.example.starter.rt.dto.DeviceResponse;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.dto.RejectionBody;
import com.example.starter.rt.dto.ScheduleCancelRequest;
import com.example.starter.rt.dto.ScheduleItemRequest;
import com.example.starter.rt.dto.SchedulePublishRequest;
import com.example.starter.rt.dto.SchedulePublishResponse;
import com.example.starter.rt.dto.ScheduleResponse;
import com.example.starter.rt.repository.SchedulingRepository;

/**
 * 分次设备时长排程门禁：主流程、400/404/422 失败分支、窗口与占用规则的 H2 集成测试。
 */
class ScheduleLifecycleTest extends SchedulingTestBase {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 10, 1, 2, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private SchedulingRepository scheduleRepo;

    private ScheduleItemRequest item(int fractionNo, int level, OffsetDateTime start, int minutes) {
        return new ScheduleItemRequest(fractionNo, DEVICE, level, start, start.plusMinutes(minutes));
    }

    private SchedulePublishRequest publishReq(String requestId, List<ScheduleItemRequest> items) {
        return new SchedulePublishRequest(PATIENT, PLAN, 1, requestId, items);
    }

    private SchedulePublishResponse publishOk(String requestId, List<ScheduleItemRequest> items) {
        RecordedResponse result = scheduling.publish(publishReq(requestId, items));
        assert200(result);
        return (SchedulePublishResponse) result.body();
    }

    // ============================ 设备 ============================

    @Test
    @DisplayName("设备登记版本从1开始；重复登记409")
    void registerDeviceVersionStartsAtOne() {
        registerDevice(3);
        List<DeviceResponse> devices = scheduling.devices();
        assertThat(devices).hasSize(1);
        assertThat(devices.get(0).version()).isEqualTo(1);
        assertThat(devices.get(0).capabilityLevel()).isEqualTo(3);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.example.starter.rt.web.ApiException.class,
                () -> scheduling.registerDevice(
                        new com.example.starter.rt.dto.DeviceRegisterRequest(DEVICE, "重复", 2)));
    }

    @Test
    @DisplayName("能力下调成功版本加1；非下调422；版本不符409；不存在404")
    void downgradeCapabilityRules() {
        registerDevice(3);

        RecordedResponse ok = scheduling.downgradeCapability(
                new DeviceCapabilityRequest(DEVICE, 1, 2, "DC-1"));
        assert200(ok);
        DeviceResponse after = scheduling.devices().get(0);
        assertThat(after.capabilityLevel()).isEqualTo(2);
        assertThat(after.version()).isEqualTo(2);

        // 非下调（同级或上调）422
        RecordedResponse sameLevel = scheduling.downgradeCapability(
                new DeviceCapabilityRequest(DEVICE, 2, 2, "DC-2"));
        assertThat(sameLevel.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) sameLevel.body()).code()).isEqualTo("DEVICE_DOWNGRADE_ONLY");

        // expectedVersion 过期 409
        RecordedResponse stale = scheduling.downgradeCapability(
                new DeviceCapabilityRequest(DEVICE, 1, 1, "DC-3"));
        assertThat(stale.httpStatus()).isEqualTo(409);
        assertThat(((RejectionBody) stale.body()).code()).isEqualTo("DEVICE_VERSION_CONFLICT");

        // 设备不存在 404
        org.junit.jupiter.api.Assertions.assertThrows(
                com.example.starter.rt.web.ApiException.class,
                () -> scheduling.downgradeCapability(
                        new DeviceCapabilityRequest("NO-DEV", 1, 1, "DC-4")));
    }

    // ============================ 发布主流程 ============================

    @Test
    @DisplayName("批量发布3个连续分次：冻结版本/等级/设备/窗口，时长10/20/30，占用可查")
    void publishBatchFreezesAndOccupies() {
        approvedVerifiedPlan();
        registerDevice(3);

        List<ScheduleItemRequest> items = List.of(
                item(1, 1, T0, 10),
                item(2, 2, T0.plusMinutes(10), 20),
                item(3, 3, T0.plusMinutes(30), 30));
        SchedulePublishResponse resp = publishOk("PUB-1", items);

        assertThat(resp.schedules()).hasSize(3);
        assertThat(resp.requestId()).isEqualTo("PUB-1");
        List<ScheduleResponse> rows = resp.schedules();
        assertThat(rows).extracting(ScheduleResponse::fractionNo).containsExactly(1, 2, 3);
        assertThat(rows).extracting(ScheduleResponse::requiredMinutes).containsExactly(10, 20, 30);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo("BOOKED");
            assertThat(r.version()).isEqualTo(1);
            assertThat(r.deviceId()).isEqualTo(DEVICE);
            assertThat(r.planVersionId()).isNotNull();
        });

        // 守卫表 3 行活跃占用；设备占用查询命中
        assertThat(scheduleRepo.countOccupancies()).isEqualTo(3);
        List<ScheduleResponse> occ = scheduling.deviceOccupancies(DEVICE,
                T0.minusMinutes(5), T0.plusMinutes(60));
        assertThat(occ).hasSize(3);
    }

    @Test
    @DisplayName("端点相接半开区间可共存（end=next start）")
    void adjacentWindowsCoexist() {
        approvedVerifiedPlan();
        registerDevice(2);
        // 同一设备两个20分钟窗口端点相接
        publishOk("PUB-A", List.of(item(1, 2, T0, 20)));
        SchedulePublishResponse second = publishOk("PUB-B", List.of(item(2, 2, T0.plusMinutes(20), 20)));
        assertThat(second.schedules()).hasSize(1);
        assertThat(scheduleRepo.countOccupancies()).isEqualTo(2);
    }

    @Test
    @DisplayName("同设备窗口重叠：422拒绝，整批零占用")
    void overlappingWindowRejectsWholeBatch() {
        approvedVerifiedPlan();
        registerDevice(3);
        publishOk("PUB-O1", List.of(item(1, 1, T0, 10)));

        // 新批次含两个分次，其中分次3窗口与已占用窗口重叠1秒 → 整批零占用
        RecordedResponse result = scheduling.publish(publishReq("PUB-O2", List.of(
                item(2, 1, T0.plusMinutes(20), 10),
                item(3, 1, T0.plusMinutes(30), 10),
                item(4, 1, T0.plusMinutes(9).plusSeconds(59), 10))));
        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) result.body()).code()).isEqualTo("DEVICE_WINDOW_CONFLICT");

        // 整批回滚：分次2/3/4 均无排程、无占用
        assertThat(scheduling.planSchedules(PATIENT, PLAN, "BOOKED")).hasSize(1);
        assertThat(scheduleRepo.countOccupancies()).isEqualTo(1);
    }

    @Test
    @DisplayName("同批内窗口重叠或分次重复：422/400 且零占用")
    void intraBatchConflictsReject() {
        approvedVerifiedPlan();
        registerDevice(3);

        RecordedResponse overlap = scheduling.publish(publishReq("PUB-I1", List.of(
                item(1, 1, T0, 10),
                item(2, 1, T0.plusMinutes(5), 10))));
        assertThat(overlap.httpStatus()).isEqualTo(422);

        com.example.starter.rt.web.ApiException duplicate = org.junit.jupiter.api.Assertions.assertThrows(
                com.example.starter.rt.web.ApiException.class,
                () -> scheduling.publish(publishReq("PUB-I2", List.of(
                        item(1, 1, T0, 10),
                        item(1, 1, T0.plusMinutes(20), 10)))));
        assertThat(duplicate.getHttpStatus()).isEqualTo(400);
        assertThat(scheduleRepo.countOccupancies()).isZero();
    }

    @Test
    @DisplayName("分次不连续、批次超10条：400")
    void nonContinuousOrTooManyFractionsReject() {
        approvedVerifiedPlan();
        registerDevice(3);

        com.example.starter.rt.web.ApiException gap = org.junit.jupiter.api.Assertions.assertThrows(
                com.example.starter.rt.web.ApiException.class,
                () -> scheduling.publish(publishReq("PUB-G", List.of(
                        item(1, 1, T0, 10),
                        item(3, 1, T0.plusMinutes(10), 10)))));
        assertThat(gap.getHttpStatus()).isEqualTo(400);
        assertThat(gap.getCode()).isEqualTo("SCHEDULE_FRACTIONS_NOT_CONTINUOUS");

        List<ScheduleItemRequest> eleven = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(n -> item(n, 1, T0.plusMinutes(10L * (n - 1)), 10)).toList();
        com.example.starter.rt.web.ApiException tooMany = org.junit.jupiter.api.Assertions.assertThrows(
                com.example.starter.rt.web.ApiException.class,
                () -> scheduling.publish(publishReq("PUB-M", eleven)));
        assertThat(tooMany.getHttpStatus()).isEqualTo(400);
        assertThat(tooMany.getCode()).isEqualTo("SCHEDULE_BATCH_TOO_LARGE");
    }

    @Test
    @DisplayName("窗口长度不符等级分钟数：400")
    void wrongWindowLengthRejects() {
        approvedVerifiedPlan();
        registerDevice(3);
        // 等级2应20分钟，给10分钟
        ScheduleItemRequest bad = new ScheduleItemRequest(1, DEVICE, 2, T0, T0.plusMinutes(10));
        com.example.starter.rt.web.ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.example.starter.rt.web.ApiException.class,
                () -> scheduling.publish(publishReq("PUB-W", List.of(bad))));
        assertThat(ex.getHttpStatus()).isEqualTo(400);
        assertThat(ex.getCode()).isEqualTo("SCHEDULE_WINDOW_LENGTH_INVALID");
    }

    @Test
    @DisplayName("同分次双占、已执行分次再排、超处方次数、能力不足：422")
    void businessRejections() {
        approvedVerifiedPlan();
        registerDevice(2);
        publishOk("PUB-B1", List.of(item(1, 2, T0, 20)));

        // 同分次双占
        RecordedResponse doubleBook = scheduling.publish(
                publishReq("PUB-B2", List.of(item(1, 2, T0.plusMinutes(30), 20))));
        assertThat(doubleBook.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) doubleBook.body()).code()).isEqualTo("FRACTION_ALREADY_SCHEDULED");

        // 能力不足：设备等级2，请求等级3
        RecordedResponse cap = scheduling.publish(
                publishReq("PUB-B3", List.of(item(2, 3, T0.plusMinutes(60), 30))));
        assertThat(cap.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) cap.body()).code()).isEqualTo("DEVICE_CAPABILITY_INSUFFICIENT");

        // 超出处方分次总数25
        RecordedResponse outOfRange = scheduling.publish(
                publishReq("PUB-B4", List.of(item(26, 1, T0.plusMinutes(90), 10))));
        assertThat(outOfRange.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) outOfRange.body()).code()).isEqualTo("FRACTION_OUT_OF_PLAN_RANGE");

        // 已执行分次不能再排：先在窗口内执行分次1
        setClockUtc(LocalDateTime.of(2026, 10, 1, 2, 5, 0));
        assert200(workflow.recordFraction(PATIENT, PLAN, 1, 200L, "FR-1"));
        // 排程1已EXECUTED释放，再次尝试发布分次1
        setClockUtc(LocalDateTime.of(2026, 10, 1, 0, 0, 0));
        RecordedResponse executed = scheduling.publish(
                publishReq("PUB-B5", List.of(item(1, 1, T0.plusMinutes(120), 10))));
        assertThat(executed.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) executed.body()).code()).isEqualTo("FRACTION_ALREADY_EXECUTED");
    }

    @Test
    @DisplayName("未验证计划不能发布排程：422")
    void unverifiedPlanCannotSchedule() {
        baseData();
        registerDevice(3);
        assert200(workflow.audit(PATIENT, PLAN, 1, "U-AUD"));
        assert200(workflow.approve(PATIENT, PLAN, 1, "U-APV"));
        // 未做首次验证（状态 APPROVED）
        RecordedResponse result = scheduling.publish(
                publishReq("PUB-U", List.of(item(1, 1, T0, 10))));
        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) result.body()).code()).isEqualTo("FIRST_VERIFICATION_REQUIRED");
    }

    @Test
    @DisplayName("计划版本不存在/设备不存在：404")
    void notFoundCases() {
        approvedVerifiedPlan();
        // 设备不存在
        ScheduleItemRequest noDevice = new ScheduleItemRequest(
                1, "GHOST", 1, T0, T0.plusMinutes(10));
        org.junit.jupiter.api.Assertions.assertThrows(
                com.example.starter.rt.web.ApiException.class,
                () -> scheduling.publish(publishReq("PUB-ND", List.of(noDevice))));

        // 版本不存在：404 以异常抛出
        org.junit.jupiter.api.Assertions.assertThrows(
                com.example.starter.rt.web.ApiException.class,
                () -> scheduling.publish(new SchedulePublishRequest(
                        PATIENT, PLAN, 9, "PUB-NV", List.of(item(1, 1, T0, 10)))));
    }

    // ============================ 取消 ============================

    @Test
    @DisplayName("取消BOOKED排程：版本加1并释放占用；终态再取消422；版本不符409")
    void cancelReleasesOccupancy() {
        approvedVerifiedPlan();
        registerDevice(2);
        ScheduleResponse s1 = publishOk("PUB-C", List.of(item(1, 2, T0, 20))).schedules().get(0);
        assertThat(scheduleRepo.countOccupancies()).isEqualTo(1);

        // expectedVersion 错误 409
        RecordedResponse stale = scheduling.cancel(
                new ScheduleCancelRequest(s1.id(), 9, "CAN-BAD"));
        assertThat(stale.httpStatus()).isEqualTo(409);

        // 正确取消
        RecordedResponse ok = scheduling.cancel(
                new ScheduleCancelRequest(s1.id(), 1, "CAN-1"));
        assert200(ok);
        assertThat(scheduleRepo.countOccupancies()).isZero();
        ScheduleResponse after = scheduling.planSchedules(PATIENT, PLAN, null).get(0);
        assertThat(after.status()).isEqualTo("CANCELLED");
        assertThat(after.version()).isEqualTo(2);
        assertThat(after.cancelRequestId()).isEqualTo("CAN-1");

        // 终态再取消 422
        RecordedResponse again = scheduling.cancel(
                new ScheduleCancelRequest(s1.id(), 2, "CAN-2"));
        assertThat(again.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) again.body()).code()).isEqualTo("SCHEDULE_NOT_BOOKED");
    }

    // ============================ 受控执行 ============================

    @Test
    @DisplayName("受控执行：窗口内成功，原子释放占用，排程EXECUTED冻结实绩")
    void controlledExecutionWithinWindow() {
        approvedVerifiedPlan();
        registerDevice(2);
        ScheduleResponse s1 = publishOk("PUB-E", List.of(item(1, 2, T0, 20))).schedules().get(0);

        // 窗口外（早1分钟）拒绝
        setClockUtc(LocalDateTime.of(2026, 10, 1, 1, 59, 0));
        RecordedResponse early = workflow.recordFraction(PATIENT, PLAN, 1, 200L, "FR-EARLY");
        assertThat(early.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) early.body()).code()).isEqualTo("OUT_OF_SCHEDULE_WINDOW");
        assertThat(scheduleRepo.countOccupancies()).isEqualTo(1);

        // 窗口终点边界排除：恰在 end 时刻拒绝（半开）
        setClockUtc(LocalDateTime.of(2026, 10, 1, 2, 20, 0));
        RecordedResponse atEnd = workflow.recordFraction(PATIENT, PLAN, 1, 200L, "FR-END");
        assertThat(atEnd.httpStatus()).isEqualTo(422);

        // 窗口内执行成功
        setClockUtc(LocalDateTime.of(2026, 10, 1, 2, 10, 0));
        RecordedResponse ok = workflow.recordFraction(PATIENT, PLAN, 1, 200L, "FR-OK");
        assert200(ok);
        assertThat(scheduleRepo.countOccupancies()).isZero();

        ScheduleResponse done = scheduling.planSchedules(PATIENT, PLAN, null).get(0);
        assertThat(done.status()).isEqualTo("EXECUTED");
        assertThat(done.version()).isEqualTo(2);
        assertThat(done.executedFractionId()).isNotNull();
        assertThat(done.planVersionId()).isEqualTo(s1.planVersionId());
    }

    @Test
    @DisplayName("启用排程后无BOOKED排程的分次拒绝治疗；无排程历史计划保持兼容")
    void gateRequiresScheduleButLegacyPlanCompatible() {
        approvedVerifiedPlan();
        registerDevice(2);
        // 已启用排程控制：仅发布分次1
        publishOk("PUB-G1", List.of(item(1, 2, T0, 20)));
        setClockUtc(LocalDateTime.of(2026, 10, 1, 2, 5, 0));
        assert200(workflow.recordFraction(PATIENT, PLAN, 1, 200L, "FR-1"));
        // 分次2未排程：SCHEDULE_REQUIRED 422（顺序分次规则满足，但门禁缺排程）
        RecordedResponse missing = workflow.recordFraction(PATIENT, PLAN, 2, 200L, "FR-MISS");
        assertThat(missing.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) missing.body()).code()).isEqualTo("SCHEDULE_REQUIRED");

        // 另一患者计划从未发布排程：不受门禁，验证后直接登记分次成功
        String p2 = "PLEG";
        patient(p2, "历史兼容患者");
        createPlanVersion(p2, "PLAN_L", 1, TPL, 5000L, 200L, 25,
                4500L, new BigDecimal("40.00"));
        assert200(workflow.audit(p2, "PLAN_L", 1, "L-AUD"));
        assert200(workflow.approve(p2, "PLAN_L", 1, "L-APV"));
        assert200(workflow.verify(p2, "PLAN_L", 1, 200L, 200L, "L-VER"));
        assert200(workflow.recordFraction(p2, "PLAN_L", 1, 200L, "L-FR"));
        assertThat(workflow.fractionLedger(p2, "PLAN_L")).hasSize(1);
    }

    @Test
    @DisplayName("设备能力下调阻断已排程未开始项，但旧等级实绩不改变")
    void downgradeBlocksPendingNotCompleted() {
        approvedVerifiedPlan();
        registerDevice(3);
        // 分次1用等级1先执行完成
        publishOk("PUB-D1", List.of(
                item(1, 1, T0, 10),
                item(2, 3, T0.plusMinutes(10), 30)));
        setClockUtc(LocalDateTime.of(2026, 10, 1, 2, 5, 0));
        assert200(workflow.recordFraction(PATIENT, PLAN, 1, 200L, "FR-D1"));

        // 设备等级3下调到1
        assert200(scheduling.downgradeCapability(
                new DeviceCapabilityRequest(DEVICE, 1, 1, "DC-DOWN")));

        // 分次2冻结等级3 > 新能力1：执行被阻断，排程仍 BOOKED
        setClockUtc(LocalDateTime.of(2026, 10, 1, 2, 20, 0));
        RecordedResponse blocked = workflow.recordFraction(PATIENT, PLAN, 2, 200L, "FR-D2");
        assertThat(blocked.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) blocked.body()).code()).isEqualTo("DEVICE_CAPABILITY_INSUFFICIENT");
        assertThat(scheduling.planSchedules(PATIENT, PLAN, "BOOKED")).hasSize(1);

        // 已完成的分次1实绩不被移动/改变
        ScheduleResponse done = scheduling.planSchedules(PATIENT, PLAN, "EXECUTED").get(0);
        assertThat(done.fractionNo()).isEqualTo(1);
        assertThat(done.complexityLevel()).isEqualTo(1);
        assertThat(workflow.fractionLedger(PATIENT, PLAN)).hasSize(1);
    }
}
