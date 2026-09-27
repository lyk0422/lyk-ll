package com.example.starter.rt.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.rt.AbstractIntegrationTest;
import com.example.starter.rt.domain.PlanStatus;
import com.example.starter.rt.domain.ScheduleBatchStatus;
import com.example.starter.rt.domain.ScheduleItemStatus;
import com.example.starter.rt.dto.DeviceCapabilityRequest;
import com.example.starter.rt.dto.DeviceRegisterRequest;
import com.example.starter.rt.dto.DeviceResponse;
import com.example.starter.rt.dto.OccupancyItemView;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.dto.RejectionBody;
import com.example.starter.rt.dto.ScheduleCancelResponse;
import com.example.starter.rt.dto.ScheduleFractionItem;
import com.example.starter.rt.dto.SchedulePublishRequest;
import com.example.starter.rt.dto.SchedulePublishResponse;
import com.example.starter.rt.dto.TreatmentRequest;
import com.example.starter.rt.dto.TreatmentResponse;
import com.example.starter.rt.repository.SchedulingRepository;
import com.example.starter.rt.service.SchedulingService;
import com.example.starter.rt.service.WorkflowService;
import com.example.starter.rt.web.ApiException;

/**
 * 设备与连续分次批量排程主流程及失败分支 H2 集成测试（合成规则）。
 */
class SchedulingLifecycleTest extends AbstractIntegrationTest {

    private static final String PATIENT = "PS1";
    private static final String PLAN = "PLAN_S";
    private static final String TPL = "TPLS";
    private static final String DEVICE = "DV1";
    private static final Instant FUTURE = Instant.parse("2026-12-01T00:00:00Z");

    @Autowired
    private SchedulingService scheduling;

    @Autowired
    private WorkflowService workflow;

    @Autowired
    private SchedulingRepository schedulingRepository;

    // ---------------- 公共准备 ----------------

    /** 准备已批准且首次验证合格的当前计划（25 次分次），返回版本ID。 */
    private long setupVerifiedPlan() {
        patient(PATIENT, "排程患者");
        pelvisTemplate(TPL, 4500L, new BigDecimal("40.00"));
        long versionId = createPlanVersion(PATIENT, PLAN, 1, TPL, 5000L, 200L, 25,
                4400L, new BigDecimal("35.00"));
        assertThat(workflow.audit(PATIENT, PLAN, 1, "S-AUD").httpStatus()).isEqualTo(200);
        assertThat(workflow.approve(PATIENT, PLAN, 1, "S-APV").httpStatus()).isEqualTo(200);
        assertThat(workflow.verify(PATIENT, PLAN, 1, 200L, 200L, "S-VER").httpStatus())
                .isEqualTo(200);
        return versionId;
    }

    private void device(String deviceId, int level) {
        RecordedResponse result = scheduling.registerDevice(
                new DeviceRegisterRequest(deviceId, "合成设备" + deviceId, level, "REG-" + deviceId));
        assertThat(result.httpStatus()).isEqualTo(201);
    }

    /** 构造发布请求：从 firstFraction 起 count 个连续分次，窗口自 firstWindowStart 依次相接。 */
    private SchedulePublishRequest publishReq(String plan, int level, String deviceId,
                                              String reqId, int firstFraction, int count,
                                              Instant firstWindowStart) {
        int minutes = level * 10;
        List<ScheduleFractionItem> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Instant start = firstWindowStart.plus((long) i * minutes, ChronoUnit.MINUTES);
            items.add(new ScheduleFractionItem(firstFraction + i,
                    start.atOffset(ZoneOffset.UTC),
                    start.plus(minutes, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC)));
        }
        return new SchedulePublishRequest(PATIENT, plan, level, deviceId, items, reqId);
    }

    private SchedulePublishResponse publishOk(SchedulePublishRequest request) {
        RecordedResponse result = scheduling.publish(request);
        assertThat(result.httpStatus()).isEqualTo(201);
        return (SchedulePublishResponse) result.body();
    }

    private List<OccupancyItemView> occupancy(String deviceId) {
        return scheduling.occupancy(deviceId, null, null);
    }

    // ---------------- 设备登记与能力版本门禁 ----------------

    @Test
    @DisplayName("设备登记：版本从1开始；同ID重复登记409；同键重放返回首次结果")
    void deviceRegisterVersionAndReplay() {
        RecordedResponse first = scheduling.registerDevice(
                new DeviceRegisterRequest(DEVICE, "合成加速器", 3, "D-REG-1"));
        assertThat(first.httpStatus()).isEqualTo(201);
        DeviceResponse body = (DeviceResponse) first.body();
        assertThat(body.version()).isEqualTo(1);
        assertThat(body.maxComplexityLevel()).isEqualTo(3);
        assertThat(body.replayed()).isFalse();

        // 同键同参重放：返回首次冻结结果，replayed=true
        RecordedResponse replay = scheduling.registerDevice(
                new DeviceRegisterRequest(DEVICE, "合成加速器", 3, "D-REG-1"));
        assertThat(replay.httpStatus()).isEqualTo(201);
        assertThat(((Map<?, ?>) replay.body()).get("replayed")).isEqualTo(true);

        // 不同 requestId 重复登记同设备：409
        RecordedResponse dup = scheduling.registerDevice(
                new DeviceRegisterRequest(DEVICE, "合成加速器", 3, "D-REG-2"));
        assertThat(dup.httpStatus()).isEqualTo(409);
        assertThat(((RejectionBody) dup.body()).code()).isEqualTo("DEVICE_EXISTS");
    }

    @Test
    @DisplayName("设备能力调整：expectedVersion 匹配成功且版本+1；过期版本409；失败不占键可修正重发")
    void deviceCapabilityVersionGate() {
        device(DEVICE, 3);

        // 过期版本：409，且失败不占键
        RecordedResponse stale = scheduling.updateCapability(DEVICE,
                new DeviceCapabilityRequest(2, 5, "D-CAP-1"));
        assertThat(stale.httpStatus()).isEqualTo(409);
        assertThat(((RejectionBody) stale.body()).code()).isEqualTo("VERSION_CONFLICT");

        // 同键修正参数重发：因失败未占键，可成功（若占键则会指纹冲突409）
        RecordedResponse ok = scheduling.updateCapability(DEVICE,
                new DeviceCapabilityRequest(2, 1, "D-CAP-1"));
        assertThat(ok.httpStatus()).isEqualTo(200);
        DeviceResponse body = (DeviceResponse) ok.body();
        assertThat(body.maxComplexityLevel()).isEqualTo(2);
        assertThat(body.version()).isEqualTo(2);

        // 设备行版本确实加1
        assertThat(schedulingRepository.findDevice(DEVICE).orElseThrow().version()).isEqualTo(2);

        // 不存在的设备：404
        ApiException notFound = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> scheduling.updateCapability("DV_NONE",
                        new DeviceCapabilityRequest(1, 1, "D-CAP-X")));
        assertThat(notFound.getHttpStatus()).isEqualTo(404);
    }

    // ---------------- 排程发布 ----------------

    @Test
    @DisplayName("批量发布：冻结计划版本/等级/设备/窗口，端点相接窗口共存，占用可查")
    void publishFreezesAndAdjacentWindowsCoexist() {
        long versionId = setupVerifiedPlan();
        device(DEVICE, 3);

        SchedulePublishResponse first = publishOk(
                publishReq(PLAN, 2, DEVICE, "P-1", 1, 3, FUTURE));
        assertThat(first.planVersionId()).isEqualTo(versionId);
        assertThat(first.versionNo()).isEqualTo(1);
        assertThat(first.level()).isEqualTo(2);
        assertThat(first.deviceId()).isEqualTo(DEVICE);
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.items()).hasSize(3);
        assertThat(first.items()).allSatisfy(
                item -> assertThat(item.status()).isEqualTo(ScheduleItemStatus.SCHEDULED.name()));
        // 窗口长度恰为等级分钟数（20分钟），批内端点相接
        assertThat(first.items().get(0).windowEndUtc())
                .isEqualTo(first.items().get(0).windowStartUtc().plus(20, ChronoUnit.MINUTES));
        assertThat(first.items().get(0).windowEndUtc())
                .isEqualTo(first.items().get(1).windowStartUtc());

        // 另一批次：与已占用窗口端点相接（半开区间），可共存
        Instant adjacent = FUTURE.plus(3L * 20, ChronoUnit.MINUTES);
        SchedulePublishResponse second = publishOk(
                publishReq(PLAN, 2, DEVICE, "P-2", 4, 2, adjacent));
        assertThat(second.items()).hasSize(2);

        List<OccupancyItemView> occupancy = occupancy(DEVICE);
        assertThat(occupancy).hasSize(5);
        assertThat(occupancy).allSatisfy(
                item -> assertThat(item.status()).isEqualTo(ScheduleItemStatus.SCHEDULED.name()));
    }

    @Test
    @DisplayName("同设备窗口重叠：整批零占用（任一冲突全部不占用）")
    void overlappingBatchLeavesZeroOccupancy() {
        setupVerifiedPlan();
        device(DEVICE, 3);
        publishOk(publishReq(PLAN, 1, DEVICE, "P-1", 1, 2, FUTURE));

        // 新批 3 个分次，其中第 2 个窗口与已占用窗口重叠
        List<ScheduleFractionItem> items = new ArrayList<>();
        items.add(new ScheduleFractionItem(3,
                FUTURE.plus(30, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC),
                FUTURE.plus(40, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC)));
        items.add(new ScheduleFractionItem(4,
                FUTURE.plus(5, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC),
                FUTURE.plus(15, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC)));
        items.add(new ScheduleFractionItem(5,
                FUTURE.plus(50, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC),
                FUTURE.plus(60, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC)));
        RecordedResponse conflict = scheduling.publish(
                new SchedulePublishRequest(PATIENT, PLAN, 1, DEVICE, items, "P-OVERLAP"));

        assertThat(conflict.httpStatus()).isEqualTo(409);
        assertThat(((RejectionBody) conflict.body()).code()).isEqualTo("DEVICE_WINDOW_OVERLAP");
        // 整批零占用：仍只有首批 2 个占用项
        assertThat(occupancy(DEVICE)).hasSize(2);
        assertThat(schedulingRepository.findItemsByBatch(
                occupancy(DEVICE).get(0).scheduleId())).hasSize(2);
    }

    @Test
    @DisplayName("同分次双占拒绝：已排程分次再次发布 409")
    void sameFractionDoubleOccupancyRejected() {
        setupVerifiedPlan();
        device(DEVICE, 3);
        publishOk(publishReq(PLAN, 1, DEVICE, "P-1", 1, 2, FUTURE));

        device("DV2", 3);
        RecordedResponse result = scheduling.publish(
                publishReq(PLAN, 1, "DV2", "P-2", 2, 1, FUTURE.plus(2, ChronoUnit.HOURS)));
        assertThat(result.httpStatus()).isEqualTo(409);
        assertThat(((RejectionBody) result.body()).code()).isEqualTo("FRACTION_ALREADY_SCHEDULED");
        assertThat(occupancy("DV2")).isEmpty();
    }

    @Test
    @DisplayName("窗口长度不等于等级分钟数 / 分次不连续 / 批量超10：400 参数非法")
    void invalidWindowOrFractionParamsRejected400() {
        setupVerifiedPlan();
        device(DEVICE, 3);

        // 窗口 15 分钟 ≠ 等级1的10分钟
        SchedulePublishRequest badLength = new SchedulePublishRequest(PATIENT, PLAN, 1, DEVICE,
                List.of(new ScheduleFractionItem(1, FUTURE.atOffset(ZoneOffset.UTC),
                        FUTURE.plus(15, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC))), "P-BAD1");
        ApiException lengthEx = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> scheduling.publish(badLength));
        assertThat(lengthEx.getHttpStatus()).isEqualTo(400);

        // 分次号不连续（1 之后为 3）
        SchedulePublishRequest gap = new SchedulePublishRequest(PATIENT, PLAN, 1, DEVICE, List.of(
                new ScheduleFractionItem(1, FUTURE.atOffset(ZoneOffset.UTC),
                        FUTURE.plus(10, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC)),
                new ScheduleFractionItem(3,
                        FUTURE.plus(10, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC),
                        FUTURE.plus(20, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC))), "P-BAD2");
        ApiException gapEx = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> scheduling.publish(gap));
        assertThat(gapEx.getHttpStatus()).isEqualTo(400);

        // 批量 11 个分次 > 10
        ApiException sizeEx = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> scheduling.publish(publishReq(PLAN, 1, DEVICE, "P-BAD3", 1, 11, FUTURE)));
        assertThat(sizeEx.getHttpStatus()).isEqualTo(400);

        assertThat(occupancy(DEVICE)).isEmpty();
    }

    @Test
    @DisplayName("未验证合格计划 / 能力不足设备 / 已执行分次：422 业务拒绝")
    void publishBusinessRejections() {
        patient(PATIENT, "排程患者");
        pelvisTemplate(TPL, 4500L, new BigDecimal("40.00"));
        createPlanVersion(PATIENT, PLAN, 1, TPL, 5000L, 200L, 25, 4400L, new BigDecimal("35.00"));
        workflow.audit(PATIENT, PLAN, 1, "S-AUD");
        workflow.approve(PATIENT, PLAN, 1, "S-APV");
        device(DEVICE, 1);

        // 未通过首次模体验证：422
        RecordedResponse notVerified = scheduling.publish(
                publishReq(PLAN, 1, DEVICE, "P-NV", 1, 1, FUTURE));
        assertThat(notVerified.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) notVerified.body()).code()).isEqualTo("PLAN_NOT_VERIFIED");

        // 验证合格后：等级2超过设备能力上限1 → 422
        workflow.verify(PATIENT, PLAN, 1, 200L, 200L, "S-VER");
        RecordedResponse incapable = scheduling.publish(
                publishReq(PLAN, 2, DEVICE, "P-CAP", 1, 1, FUTURE));
        assertThat(incapable.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) incapable.body()).code())
                .isEqualTo("DEVICE_CAPABILITY_INSUFFICIENT");

        // 已执行分次不能再排程：422
        assertThat(workflow.recordFraction(PATIENT, PLAN, 1, 200L, "FR-DONE").httpStatus())
                .isEqualTo(200);
        RecordedResponse executed = scheduling.publish(
                publishReq(PLAN, 1, DEVICE, "P-EX", 1, 2, FUTURE));
        assertThat(executed.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) executed.body()).code()).isEqualTo("FRACTION_ALREADY_EXECUTED");

        assertThat(occupancy(DEVICE)).isEmpty();
    }

    @Test
    @DisplayName("发布幂等：同键同参重放不双占；同键异参409；业务失败不占键可修正重发")
    void publishIdempotencySemantics() {
        setupVerifiedPlan();
        device(DEVICE, 3);
        SchedulePublishRequest request = publishReq(PLAN, 1, DEVICE, "P-IDEM", 1, 2, FUTURE);
        publishOk(request);

        // 同键同参重放：201 且 replayed=true，不新增占用
        RecordedResponse replay = scheduling.publish(request);
        assertThat(replay.httpStatus()).isEqualTo(201);
        assertThat(((Map<?, ?>) replay.body()).get("replayed")).isEqualTo(true);
        assertThat(occupancy(DEVICE)).hasSize(2);

        // 同键异参：409 指纹冲突
        ApiException conflict = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> scheduling.publish(publishReq(PLAN, 1, DEVICE, "P-IDEM", 3, 1,
                        FUTURE.plus(2, ChronoUnit.HOURS))));
        assertThat(conflict.getHttpStatus()).isEqualTo(409);
        assertThat(conflict.getCode()).isEqualTo("IDEMPOTENCY_FINGERPRINT_CONFLICT");

        // 业务失败（重叠）不占键：同键修正窗口后重发成功
        RecordedResponse failed = scheduling.publish(
                publishReq(PLAN, 1, DEVICE, "P-RETRY", 3, 1, FUTURE));
        assertThat(failed.httpStatus()).isEqualTo(409);
        RecordedResponse retried = scheduling.publish(
                publishReq(PLAN, 1, DEVICE, "P-RETRY", 3, 1, FUTURE.plus(3, ChronoUnit.HOURS)));
        assertThat(retried.httpStatus()).isEqualTo(201);
        assertThat(occupancy(DEVICE)).hasSize(3);
    }

    // ---------------- 受控治疗记录 ----------------

    @Test
    @DisplayName("受控治疗：窗口内完成原子释放占用并冻结实绩，台账同步入账")
    void treatmentCompletesAndReleasesOccupancy() {
        setupVerifiedPlan();
        device(DEVICE, 3);
        Instant nowStart = clock.instant().minus(5, ChronoUnit.MINUTES);
        SchedulePublishResponse published = publishOk(
                publishReq(PLAN, 1, DEVICE, "P-1", 1, 1, nowStart));

        RecordedResponse treated = scheduling.treat(new TreatmentRequest(PATIENT, PLAN,
                published.scheduleId(), 1, 200L, "T-1"));
        assertThat(treated.httpStatus()).isEqualTo(200);
        TreatmentResponse body = (TreatmentResponse) treated.body();
        assertThat(body.fractionNo()).isEqualTo(1);
        assertThat(body.deliveredDoseCgy()).isEqualTo(200L);
        assertThat(body.executedAtUtc()).isNotNull();

        // 占用已释放、实绩已冻结
        List<OccupancyItemView> occupancy = occupancy(DEVICE);
        assertThat(occupancy).hasSize(1);
        assertThat(occupancy.get(0).status()).isEqualTo(ScheduleItemStatus.COMPLETED.name());
        assertThat(occupancy.get(0).deliveredDoseCgy()).isEqualTo(200L);
        // 分次台账入账，版本进入 EXECUTING
        assertThat(workflow.fractionLedger(PATIENT, PLAN)).hasSize(1);
        assertThat(planRepository.findVersion(PATIENT, PLAN, 1).orElseThrow().status())
                .isEqualTo(PlanStatus.EXECUTING.name());
        // 同键重放：200 replayed，台账不重复
        RecordedResponse replay = scheduling.treat(new TreatmentRequest(PATIENT, PLAN,
                published.scheduleId(), 1, 200L, "T-1"));
        assertThat(replay.httpStatus()).isEqualTo(200);
        assertThat(((Map<?, ?>) replay.body()).get("replayed")).isEqualTo(true);
        assertThat(workflow.fractionLedger(PATIENT, PLAN)).hasSize(1);
    }

    @Test
    @DisplayName("受控治疗：窗口外 422；跳次 409 告警；已取消项 422")
    void treatmentWindowSkipAndCancelledRejections() {
        setupVerifiedPlan();
        device(DEVICE, 3);

        // 窗口在过去：窗口外 422
        SchedulePublishResponse past = publishOk(publishReq(PLAN, 1, DEVICE, "P-PAST", 1, 1,
                Instant.parse("2026-01-01T00:00:00Z")));
        RecordedResponse outside = scheduling.treat(new TreatmentRequest(PATIENT, PLAN,
                past.scheduleId(), 1, 200L, "T-OUT"));
        assertThat(outside.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) outside.body()).code()).isEqualTo("OUTSIDE_SCHEDULE_WINDOW");

        // 分次2窗口包含当前时刻，但分次1未执行：跳次 409 且告警
        Instant nowStart = clock.instant().minus(5, ChronoUnit.MINUTES);
        SchedulePublishResponse batch = publishOk(
                publishReq(PLAN, 1, DEVICE, "P-SKIP", 2, 1, nowStart));
        RecordedResponse skip = scheduling.treat(new TreatmentRequest(PATIENT, PLAN,
                batch.scheduleId(), 2, 200L, "T-SKIP"));
        assertThat(skip.httpStatus()).isEqualTo(409);
        RejectionBody skipBody = (RejectionBody) skip.body();
        assertThat(skipBody.code()).isEqualTo("FRACTION_SKIP_DETECTED");
        assertThat(skipBody.alert()).isTrue();

        // 取消后治疗：422
        RecordedResponse cancel = scheduling.cancel(past.scheduleId(), 1, "C-1");
        assertThat(cancel.httpStatus()).isEqualTo(200);
        RecordedResponse cancelled = scheduling.treat(new TreatmentRequest(PATIENT, PLAN,
                past.scheduleId(), 1, 200L, "T-CXL"));
        assertThat(cancelled.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) cancelled.body()).code()).isEqualTo("SCHEDULE_ITEM_CANCELLED");
    }

    @Test
    @DisplayName("未启用排程控制的旧入口兼容：旧入口分次与受控治疗共用顺序台账")
    void legacyEntryRemainsCompatible() {
        setupVerifiedPlan();
        device(DEVICE, 3);

        // 旧入口（无排程控制）登记第 1 次
        assertThat(workflow.recordFraction(PATIENT, PLAN, 1, 200L, "FR-LEGACY").httpStatus())
                .isEqualTo(200);

        // 第 2 次走排程受控入口：顺序规则共享台账（下一分次为 2）
        Instant nowStart = clock.instant().minus(5, ChronoUnit.MINUTES);
        SchedulePublishResponse published = publishOk(
                publishReq(PLAN, 1, DEVICE, "P-1", 2, 1, nowStart));
        RecordedResponse treated = scheduling.treat(new TreatmentRequest(PATIENT, PLAN,
                published.scheduleId(), 2, 200L, "T-2"));
        assertThat(treated.httpStatus()).isEqualTo(200);
        assertThat(workflow.fractionLedger(PATIENT, PLAN)).hasSize(2);
    }

    // ---------------- 取消与占用释放 ----------------

    @Test
    @DisplayName("人工取消：释放未开始占用；版本门禁；取消后同窗口可再排")
    void cancelReleasesOccupancyWithVersionGate() {
        setupVerifiedPlan();
        device(DEVICE, 3);
        SchedulePublishResponse published = publishOk(
                publishReq(PLAN, 1, DEVICE, "P-1", 1, 2, FUTURE));

        // 期望版本不符：409
        RecordedResponse stale = scheduling.cancel(published.scheduleId(), 9, "C-STALE");
        assertThat(stale.httpStatus()).isEqualTo(409);
        assertThat(((RejectionBody) stale.body()).code()).isEqualTo("VERSION_CONFLICT");

        // 正确版本取消：释放 2 项占用，版本+1
        RecordedResponse cancelled = scheduling.cancel(published.scheduleId(), 1, "C-1");
        assertThat(cancelled.httpStatus()).isEqualTo(200);
        ScheduleCancelResponse body = (ScheduleCancelResponse) cancelled.body();
        assertThat(body.cancelledCount()).isEqualTo(2);
        assertThat(body.version()).isEqualTo(2);
        assertThat(body.status()).isEqualTo(ScheduleBatchStatus.CANCELLED.name());
        assertThat(occupancy(DEVICE)).allSatisfy(
                item -> assertThat(item.status()).isEqualTo(ScheduleItemStatus.CANCELLED.name()));

        // 已取消批次再取消：422
        RecordedResponse again = scheduling.cancel(published.scheduleId(), 2, "C-2");
        assertThat(again.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) again.body()).code()).isEqualTo("SCHEDULE_NOT_ACTIVE");

        // 占用已释放：同分次同窗口可重新发布
        publishOk(publishReq(PLAN, 1, DEVICE, "P-2", 1, 2, FUTURE));
        assertThat(occupancy(DEVICE)).hasSize(4);
    }

    // ---------------- 计划改版与设备下调 ----------------

    @Test
    @DisplayName("计划改版：未开始排程同事务取消并释放资源，不能用失效版本治疗")
    void revisionCancelsPendingSchedules() {
        setupVerifiedPlan();
        device(DEVICE, 3);
        SchedulePublishResponse published = publishOk(
                publishReq(PLAN, 1, DEVICE, "P-1", 1, 2, FUTURE));

        // 改版：v2 审核批准后，v1 失效
        createPlanVersion(PATIENT, PLAN, 2, TPL, 5000L, 200L, 25, 4300L, new BigDecimal("30.00"));
        assertThat(workflow.audit(PATIENT, PLAN, 2, "S-AUD-2").httpStatus()).isEqualTo(200);
        assertThat(workflow.approve(PATIENT, PLAN, 2, "S-APV-2").httpStatus()).isEqualTo(200);

        // 未开始排程已取消并释放占用
        assertThat(schedulingRepository.findBatch(published.scheduleId()).orElseThrow().status())
                .isEqualTo(ScheduleBatchStatus.CANCELLED.name());
        assertThat(schedulingRepository.findItemsByBatch(published.scheduleId())).allSatisfy(
                item -> {
                    assertThat(item.status()).isEqualTo(ScheduleItemStatus.CANCELLED.name());
                    assertThat(item.cancelReason()).isEqualTo("REVISION");
                });

        // 不能用失效版本治疗
        RecordedResponse treated = scheduling.treat(new TreatmentRequest(PATIENT, PLAN,
                published.scheduleId(), 1, 200L, "T-OLD"));
        assertThat(treated.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) treated.body()).code()).isEqualTo("SCHEDULE_ITEM_CANCELLED");

        // 占用已释放：新版本验证合格后同窗口可再排
        assertThat(workflow.verify(PATIENT, PLAN, 2, 200L, 200L, "S-VER-2").httpStatus())
                .isEqualTo(200);
        publishOk(publishReq(PLAN, 1, DEVICE, "P-2", 1, 2, FUTURE));
    }

    @Test
    @DisplayName("计划改版：已执行分次剂量与实绩不变，仅未开始项取消")
    void revisionKeepsExecutedActuals() {
        setupVerifiedPlan();
        device(DEVICE, 3);
        Instant nowStart = clock.instant().minus(5, ChronoUnit.MINUTES);
        // 分次1窗口包含当前时刻（可治疗），分次2窗口在未来
        List<ScheduleFractionItem> items = List.of(
                new ScheduleFractionItem(1, nowStart.atOffset(ZoneOffset.UTC),
                        nowStart.plus(10, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC)),
                new ScheduleFractionItem(2, FUTURE.atOffset(ZoneOffset.UTC),
                        FUTURE.plus(10, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC)));
        SchedulePublishResponse published = publishOk(new SchedulePublishRequest(
                PATIENT, PLAN, 1, DEVICE, items, "P-1"));
        assertThat(scheduling.treat(new TreatmentRequest(PATIENT, PLAN,
                published.scheduleId(), 1, 210L, "T-1")).httpStatus()).isEqualTo(200);

        // 改版
        createPlanVersion(PATIENT, PLAN, 2, TPL, 5000L, 200L, 25, 4300L, new BigDecimal("30.00"));
        workflow.audit(PATIENT, PLAN, 2, "S-AUD-2");
        assertThat(workflow.approve(PATIENT, PLAN, 2, "S-APV-2").httpStatus()).isEqualTo(200);

        // 已执行分次：实绩冻结不变；未开始项：已取消
        var batchItems = schedulingRepository.findItemsByBatch(published.scheduleId());
        assertThat(batchItems.get(0).status()).isEqualTo(ScheduleItemStatus.COMPLETED.name());
        assertThat(batchItems.get(0).deliveredDoseCgy()).isEqualTo(210L);
        assertThat(batchItems.get(0).executedAtUtc()).isNotNull();
        assertThat(batchItems.get(1).status()).isEqualTo(ScheduleItemStatus.CANCELLED.name());
        assertThat(workflow.fractionLedger(PATIENT, PLAN)).hasSize(1);
        assertThat(workflow.fractionLedger(PATIENT, PLAN).get(0).deliveredDoseCgy()).isEqualTo(210L);
    }

    @Test
    @DisplayName("设备能力下调：只阻断尚未开始项，已完成实绩保留旧等级证据")
    void downgradeBlocksOnlyPendingItems() {
        setupVerifiedPlan();
        device(DEVICE, 3);

        // 分次1（等级3）先完成治疗
        Instant nowStart = clock.instant().minus(15, ChronoUnit.MINUTES);
        SchedulePublishResponse first = publishOk(publishReq(PLAN, 3, DEVICE, "P-1", 1, 1, nowStart));
        assertThat(scheduling.treat(new TreatmentRequest(PATIENT, PLAN,
                first.scheduleId(), 1, 200L, "T-1")).httpStatus()).isEqualTo(200);

        // 分次2（等级3）排程未开始
        SchedulePublishResponse second = publishOk(
                publishReq(PLAN, 3, DEVICE, "P-2", 2, 1, nowStart));

        // 能力下调至等级1
        RecordedResponse downgrade = scheduling.updateCapability(DEVICE,
                new DeviceCapabilityRequest(1, 1, "D-CAP-1"));
        assertThat(downgrade.httpStatus()).isEqualTo(200);

        // 未开始项被阻断：422
        RecordedResponse blocked = scheduling.treat(new TreatmentRequest(PATIENT, PLAN,
                second.scheduleId(), 2, 200L, "T-2"));
        assertThat(blocked.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) blocked.body()).code())
                .isEqualTo("DEVICE_CAPABILITY_INSUFFICIENT");

        // 新发布等级3同样被阻断；等级1可发布
        RecordedResponse publishHigh = scheduling.publish(
                publishReq(PLAN, 3, DEVICE, "P-3", 3, 1, FUTURE));
        assertThat(publishHigh.httpStatus()).isEqualTo(422);
        publishOk(publishReq(PLAN, 1, DEVICE, "P-4", 3, 1, FUTURE));

        // 已完成实绩保留冻结等级3（未被移作其他证据）
        List<OccupancyItemView> occupancy = occupancy(DEVICE);
        OccupancyItemView completed = occupancy.stream()
                .filter(item -> item.status().equals(ScheduleItemStatus.COMPLETED.name()))
                .findFirst().orElseThrow();
        assertThat(completed.level()).isEqualTo(3);
        assertThat(completed.deliveredDoseCgy()).isEqualTo(200L);
    }

    @Test
    @DisplayName("对象不存在：患者/设备/排程批次/排程项 404")
    void notFoundSemantics() {
        setupVerifiedPlan();
        device(DEVICE, 3);

        ApiException noBatch = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> scheduling.cancel(99999L, 1, "C-X"));
        assertThat(noBatch.getHttpStatus()).isEqualTo(404);

        ApiException noItem = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> scheduling.treat(new TreatmentRequest(PATIENT, PLAN, 99999L, 1, 200L, "T-X")));
        assertThat(noItem.getHttpStatus()).isEqualTo(404);

        ApiException noDevice = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> scheduling.occupancy("DV_NONE", null, null));
        assertThat(noDevice.getHttpStatus()).isEqualTo(404);

        ApiException noPatient = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> scheduling.publish(new SchedulePublishRequest("P_NONE", PLAN, 1, DEVICE,
                        List.of(new ScheduleFractionItem(1, FUTURE.atOffset(ZoneOffset.UTC),
                                FUTURE.plus(10, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC))),
                        "P-NONE")));
        assertThat(noPatient.getHttpStatus()).isEqualTo(404);
    }
}
