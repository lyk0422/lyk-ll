package com.example.starter.rt.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.example.starter.rt.AbstractIntegrationTest;
import com.example.starter.rt.domain.PlanStatus;
import com.example.starter.rt.dto.DeviceResponse;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.dto.RejectionBody;
import com.example.starter.rt.dto.ScheduleCancelRequest;
import com.example.starter.rt.dto.ScheduleItemRequest;
import com.example.starter.rt.dto.ScheduleItemView;
import com.example.starter.rt.dto.SchedulePublishRequest;
import com.example.starter.rt.dto.ScheduleResponse;
import com.example.starter.rt.dto.TreatmentRequest;
import com.example.starter.rt.dto.TreatmentResponse;
import com.example.starter.rt.dto.DeviceRegisterRequest;
import com.example.starter.rt.dto.DeviceUpdateRequest;
import com.example.starter.rt.repository.SchedulingRepository;
import com.example.starter.rt.service.SchedulingService;
import com.example.starter.rt.service.WorkflowService;
import com.example.starter.rt.support.MutableClock;
import com.example.starter.rt.web.ApiException;
import com.example.starter.rt.web.ClockConfig;

/**
 * 排程门禁主流程与失败分支 H2 集成测试：真实事务、真实 H2（MODE=MySQL）、可控时钟。
 */
class SchedulingLifecycleTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-27T01:00:00Z");
    private static final MutableClock MUTABLE = new MutableClock(T0, ClockConfig.BUSINESS_ZONE);

    private static final String PATIENT = "PS1";
    private static final String PLAN = "PLAN_S";
    private static final String TPL = "TPLS";
    private static final String DEVICE = "DEV_S1";

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return MUTABLE;
        }
    }

    @Autowired
    private SchedulingService scheduling;

    @Autowired
    private WorkflowService workflow;

    @Autowired
    private SchedulingRepository schedulingRepository;

    @BeforeEach
    void resetClock() {
        MUTABLE.setInstant(T0);
    }

    // ---------------- 测试准备辅助 ----------------

    /** 患者 + 模板 + 计划 v1 审核/批准/验证通过（VERIFIED）。 */
    private void setupVerifiedPlan() {
        patient(PATIENT, "排程患者");
        pelvisTemplate(TPL, 4500L, new BigDecimal("40.00"));
        createPlanVersion(PATIENT, PLAN, 1, TPL, 5000L, 200L, 25, 4400L, new BigDecimal("35.00"));
        assertThat(workflow.audit(PATIENT, PLAN, 1, "S-AUD-1").httpStatus()).isEqualTo(200);
        assertThat(workflow.approve(PATIENT, PLAN, 1, "S-APV-1").httpStatus()).isEqualTo(200);
        assertThat(workflow.verify(PATIENT, PLAN, 1, 200L, 200L, "S-VER-1").httpStatus())
                .isEqualTo(200);
    }

    private void registerDevice(String deviceId, int maxLevel) {
        RecordedResponse result = scheduling.registerDevice(
                new DeviceRegisterRequest(deviceId, "合成设备-" + deviceId, maxLevel,
                        "S-DEV-" + deviceId));
        assertThat(result.httpStatus()).isEqualTo(201);
    }

    /** 构造单分次窗口项：startIso(UTC) 起 minutes 分钟。 */
    private ScheduleItemRequest item(int fractionNo, String startIso, int minutes) {
        OffsetDateTime start = OffsetDateTime.parse(startIso);
        return new ScheduleItemRequest(fractionNo, start, start.plusMinutes(minutes));
    }

    private SchedulePublishRequest publishRequest(String requestId, int level, String deviceId,
                                                  List<ScheduleItemRequest> items) {
        return new SchedulePublishRequest(PATIENT, PLAN, level, deviceId, items, requestId);
    }

    /** 等级1（10分钟）连续窗口：从 startIso 起依次为 fractionFrom..fractionFrom+count-1。 */
    private List<ScheduleItemRequest> consecutiveItems(String startIso, int fractionFrom,
                                                       int count, int minutesEach) {
        List<ScheduleItemRequest> items = new ArrayList<>();
        OffsetDateTime start = OffsetDateTime.parse(startIso);
        for (int i = 0; i < count; i++) {
            items.add(new ScheduleItemRequest(fractionFrom + i,
                    start.plusMinutes((long) i * minutesEach),
                    start.plusMinutes((long) (i + 1) * minutesEach)));
        }
        return items;
    }

    private ScheduleResponse publishOk(String requestId, int level, List<ScheduleItemRequest> items) {
        RecordedResponse result = scheduling.publish(publishRequest(requestId, level, DEVICE, items));
        assertThat(result.httpStatus()).isEqualTo(201);
        return (ScheduleResponse) result.body();
    }

    private RecordedResponse treat(int fractionNo, String requestId) {
        return scheduling.treat(new TreatmentRequest(PATIENT, PLAN, fractionNo, 200L, requestId));
    }

    private static String code(RecordedResponse result) {
        return ((RejectionBody) result.body()).code();
    }

    // ---------------- 主流程 ----------------

    @Test
    @DisplayName("主流程：登记设备→发布排程→窗口内受控治疗→完成释放占用并冻结实绩")
    void publishAndTreatMainFlow() {
        setupVerifiedPlan();
        registerDevice(DEVICE, 3);

        ScheduleResponse published = publishOk("S-PUB-1", 1,
                consecutiveItems("2026-09-27T01:00:00Z", 1, 2, 10));
        assertThat(published.version()).isEqualTo(1);
        assertThat(published.status()).isEqualTo("ACTIVE");
        assertThat(published.level()).isEqualTo(1);
        assertThat(published.deviceId()).isEqualTo(DEVICE);
        assertThat(published.items()).hasSize(2);
        assertThat(published.items()).allSatisfy(
                item -> assertThat(item.status()).isEqualTo("SCHEDULED"));

        // 占用查询：2 项占用中
        assertThat(scheduling.occupancy(DEVICE)).hasSize(2);

        // 窗口 [01:00,01:10) 内（T0=01:00:00Z）治疗分次 1
        RecordedResponse t1 = treat(1, "S-TRT-1");
        assertThat(t1.httpStatus()).isEqualTo(200);
        TreatmentResponse treatment1 = (TreatmentResponse) t1.body();
        assertThat(treatment1.fractionNo()).isEqualTo(1);
        assertThat(treatment1.deliveredDoseCgy()).isEqualTo(200L);
        assertThat(treatment1.executedAtUtc()).isEqualTo(T0.atOffset(ZoneOffset.UTC));

        // 台账 1 条；排程项 1 完成并冻结实绩；占用剩 1
        assertThat(workflow.fractionLedger(PATIENT, PLAN)).hasSize(1);
        ScheduleItemView completed = scheduling.schedules(PATIENT, PLAN).get(0).items().get(0);
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.deliveredDoseCgy()).isEqualTo(200L);
        assertThat(completed.executedAtUtc()).isEqualTo(T0.atOffset(ZoneOffset.UTC));
        assertThat(scheduling.occupancy(DEVICE)).hasSize(1);

        // 推进时钟到窗口 2 内，治疗分次 2
        MUTABLE.advance(Duration.ofMinutes(10));
        assertThat(treat(2, "S-TRT-2").httpStatus()).isEqualTo(200);
        assertThat(workflow.fractionLedger(PATIENT, PLAN)).hasSize(2);
        assertThat(scheduling.occupancy(DEVICE)).isEmpty();
        assertThat(planRepository.findVersion(PATIENT, PLAN, 1).orElseThrow().status())
                .isEqualTo(PlanStatus.EXECUTING.name());
    }

    // ---------------- 发布参数与业务校验 ----------------

    @Test
    @DisplayName("窗口长度不等于等级分钟数、分次不连续：400 参数非法")
    void publishRejectsInvalidParams() {
        setupVerifiedPlan();
        registerDevice(DEVICE, 3);

        // 等级1 须 10 分钟，给 15 分钟 → 400
        SchedulePublishRequest badLength = publishRequest("S-PUB-BAD1", 1, DEVICE,
                List.of(item(1, "2026-09-27T01:00:00Z", 15)));
        assertThatThrownBy(() -> scheduling.publish(badLength))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getHttpStatus()).isEqualTo(400));

        // 分次 1、3 不连续 → 400
        SchedulePublishRequest nonConsecutive = publishRequest("S-PUB-BAD2", 1, DEVICE,
                List.of(item(1, "2026-09-27T01:00:00Z", 10),
                        item(3, "2026-09-27T01:10:00Z", 10)));
        assertThatThrownBy(() -> scheduling.publish(nonConsecutive))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getHttpStatus()).isEqualTo(400));

        // 结束不晚于开始 → 400
        OffsetDateTime start = OffsetDateTime.parse("2026-09-27T01:00:00Z");
        SchedulePublishRequest inverted = publishRequest("S-PUB-BAD3", 1, DEVICE,
                List.of(new ScheduleItemRequest(1, start, start)));
        assertThatThrownBy(() -> scheduling.publish(inverted))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getHttpStatus()).isEqualTo(400));
    }

    @Test
    @DisplayName("未验证合格的计划不能发布排程：422 PLAN_NOT_VERIFIED")
    void publishRequiresVerifiedPlan() {
        patient(PATIENT, "排程患者");
        pelvisTemplate(TPL, 4500L, new BigDecimal("40.00"));
        createPlanVersion(PATIENT, PLAN, 1, TPL, 5000L, 200L, 25, 4400L, new BigDecimal("35.00"));
        workflow.audit(PATIENT, PLAN, 1, "S-AUD-NV");
        workflow.approve(PATIENT, PLAN, 1, "S-APV-NV");
        registerDevice(DEVICE, 3);

        RecordedResponse result = scheduling.publish(publishRequest("S-PUB-NV", 1, DEVICE,
                consecutiveItems("2026-09-27T01:00:00Z", 1, 1, 10)));
        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(code(result)).isEqualTo("PLAN_NOT_VERIFIED");
    }

    @Test
    @DisplayName("同设备重叠整批拒绝零占用；端点相接可共存")
    void publishRejectsOverlapButAllowsTouching() {
        setupVerifiedPlan();
        registerDevice(DEVICE, 3);
        publishOk("S-PUB-A", 1, List.of(item(1, "2026-09-27T01:00:00Z", 10)));

        // 重叠 [01:05,01:15) → 422，整批零占用
        RecordedResponse overlap = scheduling.publish(publishRequest("S-PUB-B", 1, DEVICE,
                List.of(item(2, "2026-09-27T01:05:00Z", 10))));
        assertThat(overlap.httpStatus()).isEqualTo(422);
        assertThat(code(overlap)).isEqualTo("DEVICE_WINDOW_OVERLAP");
        assertThat(scheduling.occupancy(DEVICE)).hasSize(1);
        assertThat(scheduling.schedules(PATIENT, PLAN)).hasSize(1);

        // 端点相接 [01:10,01:20) → 201 共存
        ScheduleResponse touching = publishOk("S-PUB-C", 1,
                List.of(item(2, "2026-09-27T01:10:00Z", 10)));
        assertThat(touching.items()).hasSize(1);
        assertThat(scheduling.occupancy(DEVICE)).hasSize(2);
    }

    @Test
    @DisplayName("同分次双占拒绝；已执行分次不能再排程")
    void publishRejectsDoubleBookingAndExecutedFraction() {
        setupVerifiedPlan();
        registerDevice(DEVICE, 3);
        publishOk("S-PUB-D1", 1, consecutiveItems("2026-09-27T01:00:00Z", 1, 1, 10));

        // 分次 1 已有生效排程 → 422 双占
        RecordedResponse doubleBooked = scheduling.publish(publishRequest("S-PUB-D2", 1, DEVICE,
                List.of(item(1, "2026-09-27T02:00:00Z", 10))));
        assertThat(doubleBooked.httpStatus()).isEqualTo(422);
        assertThat(code(doubleBooked)).isEqualTo("FRACTION_ALREADY_SCHEDULED");

        // 执行分次 1 后，分次 1 不能再排程
        assertThat(treat(1, "S-TRT-D1").httpStatus()).isEqualTo(200);
        RecordedResponse executed = scheduling.publish(publishRequest("S-PUB-D3", 1, DEVICE,
                List.of(item(1, "2026-09-27T03:00:00Z", 10))));
        assertThat(executed.httpStatus()).isEqualTo(422);
        assertThat(code(executed)).isEqualTo("FRACTION_ALREADY_EXECUTED");
    }

    @Test
    @DisplayName("排程等级超过设备能力上限：422 拒绝")
    void publishRejectsInsufficientDeviceCapability() {
        setupVerifiedPlan();
        registerDevice(DEVICE, 1);

        RecordedResponse result = scheduling.publish(publishRequest("S-PUB-CAP", 2, DEVICE,
                List.of(item(1, "2026-09-27T01:00:00Z", 20))));
        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(code(result)).isEqualTo("DEVICE_CAPABILITY_INSUFFICIENT");
        assertThat(scheduling.occupancy(DEVICE)).isEmpty();
    }

    // ---------------- 幂等 ----------------

    @Test
    @DisplayName("发布幂等：同键同参重放首次冻结结果；同键异参409；失败不占键")
    void publishIdempotency() {
        setupVerifiedPlan();
        registerDevice(DEVICE, 3);
        ScheduleResponse first = publishOk("S-PUB-IDEM", 1,
                consecutiveItems("2026-09-27T01:00:00Z", 1, 2, 10));

        // 同键同参 → 重放：同 scheduleId、replayed=true，不产生新排程
        RecordedResponse replay = scheduling.publish(publishRequest("S-PUB-IDEM", 1, DEVICE,
                consecutiveItems("2026-09-27T01:00:00Z", 1, 2, 10)));
        assertThat(replay.httpStatus()).isEqualTo(201);
        @SuppressWarnings("unchecked")
        Map<String, Object> replayBody = (Map<String, Object>) replay.body();
        assertThat(replayBody.get("replayed")).isEqualTo(Boolean.TRUE);
        assertThat(((Number) replayBody.get("scheduleId")).longValue())
                .isEqualTo(first.scheduleId());
        assertThat(scheduling.schedules(PATIENT, PLAN)).hasSize(1);

        // 同键异参（窗口时刻不同）→ 409 指纹冲突
        List<ScheduleItemRequest> differentParams =
                consecutiveItems("2026-09-27T05:00:00Z", 1, 2, 10);
        assertThatThrownBy(() -> scheduling.publish(
                publishRequest("S-PUB-IDEM", 1, DEVICE, differentParams)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getHttpStatus()).isEqualTo(409);
                    assertThat(((ApiException) e).getCode())
                            .isEqualTo("IDEMPOTENCY_FINGERPRINT_CONFLICT");
                });

        // 失败不占键：先用 "S-PUB-FAIL" 发一个重叠批次（422），同键修正参数后成功
        RecordedResponse failed = scheduling.publish(publishRequest("S-PUB-FAIL", 1, DEVICE,
                List.of(item(3, "2026-09-27T01:05:00Z", 10))));
        assertThat(failed.httpStatus()).isEqualTo(422);
        RecordedResponse retried = scheduling.publish(publishRequest("S-PUB-FAIL", 1, DEVICE,
                List.of(item(3, "2026-09-27T03:00:00Z", 10))));
        assertThat(retried.httpStatus()).isEqualTo(201);
    }

    // ---------------- 取消 ----------------

    @Test
    @DisplayName("排程取消：版本冲突409；取消释放占用且版本加1；重复取消422；取消后不能治疗")
    void cancelFlow() {
        setupVerifiedPlan();
        registerDevice(DEVICE, 3);
        ScheduleResponse published = publishOk("S-PUB-CXL", 1,
                consecutiveItems("2026-09-27T01:00:00Z", 1, 2, 10));

        // expectedVersion 不匹配 → 409
        RecordedResponse versionConflict = scheduling.cancel(published.scheduleId(),
                new ScheduleCancelRequest(9, "S-CXL-BAD"));
        assertThat(versionConflict.httpStatus()).isEqualTo(409);
        assertThat(code(versionConflict)).isEqualTo("VERSION_CONFLICT");

        // 取消成功：版本 1→2，项全部 CANCELLED，占用释放
        RecordedResponse cancelled = scheduling.cancel(published.scheduleId(),
                new ScheduleCancelRequest(1, "S-CXL-1"));
        assertThat(cancelled.httpStatus()).isEqualTo(200);
        ScheduleResponse cancelledBody = (ScheduleResponse) cancelled.body();
        assertThat(cancelledBody.version()).isEqualTo(2);
        assertThat(cancelledBody.status()).isEqualTo("CANCELLED");
        assertThat(cancelledBody.items()).allSatisfy(
                item -> assertThat(item.status()).isEqualTo("CANCELLED"));
        assertThat(scheduling.occupancy(DEVICE)).isEmpty();

        // 重复取消 → 422
        RecordedResponse again = scheduling.cancel(published.scheduleId(),
                new ScheduleCancelRequest(2, "S-CXL-2"));
        assertThat(again.httpStatus()).isEqualTo(422);
        assertThat(code(again)).isEqualTo("SCHEDULE_ALREADY_CANCELLED");

        // 取消后治疗 → 422 无生效排程
        RecordedResponse treatAfterCancel = treat(1, "S-TRT-CXL");
        assertThat(treatAfterCancel.httpStatus()).isEqualTo(422);
        assertThat(code(treatAfterCancel)).isEqualTo("SCHEDULE_NOT_FOUND");
    }

    // ---------------- 受控治疗门禁 ----------------

    @Test
    @DisplayName("治疗门禁：无排程422；窗口外422；跳次409告警；顺序执行成功")
    void treatmentGates() {
        setupVerifiedPlan();
        registerDevice(DEVICE, 3);

        // 无排程 → 422
        RecordedResponse noSchedule = treat(1, "S-TRT-NONE");
        assertThat(noSchedule.httpStatus()).isEqualTo(422);
        assertThat(code(noSchedule)).isEqualTo("SCHEDULE_NOT_FOUND");

        // 窗口在未来（02:00-02:10），当前 T0=01:00 → 422 窗口外
        publishOk("S-PUB-FUT", 1, consecutiveItems("2026-09-27T02:00:00Z", 1, 1, 10));
        RecordedResponse outside = treat(1, "S-TRT-OUT");
        assertThat(outside.httpStatus()).isEqualTo(422);
        assertThat(code(outside)).isEqualTo("OUTSIDE_SCHEDULE_WINDOW");

        // 分次 2 排在另一台设备同时段窗口（同设备窗口不可重叠）
        registerDevice("DEV_S2", 3);
        RecordedResponse pub2 = scheduling.publish(new SchedulePublishRequest(PATIENT, PLAN,
                1, "DEV_S2", consecutiveItems("2026-09-27T02:00:00Z", 2, 1, 10), "S-PUB-FUT2"));
        assertThat(pub2.httpStatus()).isEqualTo(201);

        // 推进到窗口内，跳次（先治 2）→ 409 告警
        MUTABLE.setInstant(Instant.parse("2026-09-27T02:00:00Z"));
        RecordedResponse skip = treat(2, "S-TRT-SKIP");
        assertThat(skip.httpStatus()).isEqualTo(409);
        assertThat(code(skip)).isEqualTo("FRACTION_SKIP_DETECTED");
        assertThat(((RejectionBody) skip.body()).alert()).isTrue();

        // 顺序执行分次 1 成功
        assertThat(treat(1, "S-TRT-OK1").httpStatus()).isEqualTo(200);
        assertThat(workflow.fractionLedger(PATIENT, PLAN)).hasSize(1);
    }

    // ---------------- 设备能力变更 ----------------

    @Test
    @DisplayName("设备能力下调：只阻断尚未开始项，已完成实绩保留；版本冲突409")
    void deviceDowngradeBlocksOnlyNotStarted() {
        setupVerifiedPlan();
        registerDevice(DEVICE, 3);
        publishOk("S-PUB-LV", 2, consecutiveItems("2026-09-27T01:00:00Z", 1, 2, 20));

        // 分次 1 在窗口内完成（等级2，20分钟窗口）
        assertThat(treat(1, "S-TRT-LV1").httpStatus()).isEqualTo(200);

        // 版本冲突 → 409
        RecordedResponse conflict = scheduling.updateDevice(DEVICE,
                new DeviceUpdateRequest(1, 5, "S-DEVUP-BAD"));
        assertThat(conflict.httpStatus()).isEqualTo(409);
        assertThat(code(conflict)).isEqualTo("VERSION_CONFLICT");

        // 下调 3→1 成功，版本 1→2
        RecordedResponse updated = scheduling.updateDevice(DEVICE,
                new DeviceUpdateRequest(1, 1, "S-DEVUP-1"));
        assertThat(updated.httpStatus()).isEqualTo(200);
        assertThat(((DeviceResponse) updated.body()).version()).isEqualTo(2);
        assertThat(((DeviceResponse) updated.body()).maxLevel()).isEqualTo(1);

        // 尚未开始的分次 2（等级2）被阻断
        MUTABLE.advance(Duration.ofMinutes(20));
        RecordedResponse blocked = treat(2, "S-TRT-LV2");
        assertThat(blocked.httpStatus()).isEqualTo(422);
        assertThat(code(blocked)).isEqualTo("DEVICE_CAPABILITY_INSUFFICIENT");

        // 已完成分次 1 实绩保留，未被改写；分次 2 仍为 SCHEDULED（未移作完成证据）
        List<ScheduleItemView> items = scheduling.schedules(PATIENT, PLAN).get(0).items();
        assertThat(items.get(0).status()).isEqualTo("COMPLETED");
        assertThat(items.get(0).deliveredDoseCgy()).isEqualTo(200L);
        assertThat(items.get(1).status()).isEqualTo("SCHEDULED");
        assertThat(items.get(1).deliveredDoseCgy()).isNull();
        assertThat(workflow.fractionLedger(PATIENT, PLAN)).hasSize(1);
    }

    // ---------------- 计划改版 ----------------

    @Test
    @DisplayName("计划改版：旧版本未开始排程同事务取消释放，已执行实绩不变，失效版本不能治疗")
    void revisionCancelsNotStartedSchedules() {
        setupVerifiedPlan();
        registerDevice(DEVICE, 3);
        ScheduleResponse published = publishOk("S-PUB-REV", 1,
                consecutiveItems("2026-09-27T01:00:00Z", 1, 2, 10));

        // 分次 1 执行完成（实绩冻结）
        assertThat(treat(1, "S-TRT-REV1").httpStatus()).isEqualTo(200);

        // 改版：v2 设计→审核→批准→验证，v1 失效
        createPlanVersion(PATIENT, PLAN, 2, TPL, 5000L, 200L, 25, 4300L, new BigDecimal("30.00"));
        assertThat(workflow.audit(PATIENT, PLAN, 2, "S-AUD-2").httpStatus()).isEqualTo(200);
        assertThat(workflow.approve(PATIENT, PLAN, 2, "S-APV-2").httpStatus()).isEqualTo(200);
        assertThat(workflow.verify(PATIENT, PLAN, 2, 200L, 200L, "S-VER-2").httpStatus())
                .isEqualTo(200);

        // 旧版本排程批次取消（版本+1），未开始项 CANCELLED，已完成项实绩保留
        ScheduleResponse schedule = scheduling.schedules(PATIENT, PLAN).get(0);
        assertThat(schedule.scheduleId()).isEqualTo(published.scheduleId());
        assertThat(schedule.status()).isEqualTo("CANCELLED");
        assertThat(schedule.version()).isEqualTo(2);
        assertThat(schedule.items().get(0).status()).isEqualTo("COMPLETED");
        assertThat(schedule.items().get(0).deliveredDoseCgy()).isEqualTo(200L);
        assertThat(schedule.items().get(1).status()).isEqualTo("CANCELLED");
        assertThat(scheduling.occupancy(DEVICE)).isEmpty();

        // 台账实绩不变
        assertThat(workflow.fractionLedger(PATIENT, PLAN)).hasSize(1);
        assertThat(workflow.fractionLedger(PATIENT, PLAN).get(0).deliveredDoseCgy()).isEqualTo(200L);

        // 失效版本的排程不能治疗
        RecordedResponse treatOld = treat(2, "S-TRT-REV2");
        assertThat(treatOld.httpStatus()).isEqualTo(422);
        assertThat(code(treatOld)).isEqualTo("SCHEDULE_NOT_FOUND");
    }

    // ---------------- 历史兼容 ----------------

    @Test
    @DisplayName("未启用排程控制的历史入口兼容：旧分次入口仍可登记")
    void legacyFractionEntryRemainsCompatible() {
        setupVerifiedPlan();
        RecordedResponse legacy = workflow.recordFraction(PATIENT, PLAN, 1, 200L, "S-LEG-1");
        assertThat(legacy.httpStatus()).isEqualTo(200);
        assertThat(workflow.fractionLedger(PATIENT, PLAN)).hasSize(1);
    }
}
