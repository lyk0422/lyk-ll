package com.example.starter.rt.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.rt.AbstractIntegrationTest;
import com.example.starter.rt.dto.CreateDeviceRequest;
import com.example.starter.rt.dto.DeviceCapabilityRequest;
import com.example.starter.rt.dto.DeviceCapabilityResponse;
import com.example.starter.rt.dto.DeviceOccupancyItem;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.dto.ScheduleBatchResponse;
import com.example.starter.rt.dto.ScheduleCancelRequest;
import com.example.starter.rt.dto.ScheduleItemRequest;
import com.example.starter.rt.dto.ScheduleItemView;
import com.example.starter.rt.dto.SchedulePublishRequest;
import com.example.starter.rt.dto.TreatmentRequest;
import com.example.starter.rt.service.ScheduleService;
import com.example.starter.rt.service.WorkflowService;
import com.example.starter.rt.web.ApiException;

/**
 * 分次排程生命周期 H2 测试：设备登记/能力门禁、批量发布（整批原子占用）、
 * 受控治疗入口、取消与改版联动、版本与幂等边界。
 */
class ScheduleLifecycleTest extends AbstractIntegrationTest {

    private static final String TPL = "TPL_S";

    @Autowired
    private WorkflowService workflow;

    @Autowired
    private ScheduleService scheduleService;

    // ---------------- 辅助 ----------------

    private String rid() {
        return UUID.randomUUID().toString();
    }

    /** 推进到“已批准且首次验证合格”的当前计划（v1），返回版本ID。 */
    private long verifiedPlan(String patientId, String planCode, int fractionCount) {
        patient(patientId, "排程患者");
        pelvisTemplate(TPL, 4500L, new BigDecimal("40.00"));
        long versionId = createPlanVersion(patientId, planCode, 1, TPL,
                200L * fractionCount, 200L, fractionCount, 4400L, new BigDecimal("35.00"));
        workflow.audit(patientId, planCode, 1, rid());
        workflow.approve(patientId, planCode, 1, rid());
        workflow.verify(patientId, planCode, 1, 200L, 200L, rid());
        return versionId;
    }

    private void device(String deviceId, int maxLevel) {
        scheduleService.createDevice(new CreateDeviceRequest(deviceId, "合成加速器", maxLevel));
    }

    /** 构造发布请求：从 firstFraction 起 count 个连续分次，窗口自 firstStart 起连续相接。 */
    private SchedulePublishRequest publishRequest(String patientId, String planCode, int versionNo,
                                                  String deviceId, int level, String requestId,
                                                  int firstFraction, int count, Instant firstStart) {
        int minutes = level * 10;
        List<ScheduleItemRequest> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Instant start = firstStart.plus((long) i * minutes, ChronoUnit.MINUTES);
            items.add(new ScheduleItemRequest(firstFraction + i, start,
                    start.plus(minutes, ChronoUnit.MINUTES)));
        }
        return new SchedulePublishRequest(patientId, planCode, versionNo, deviceId, level,
                items, requestId);
    }

    private ScheduleBatchResponse batch(RecordedResponse response) {
        return (ScheduleBatchResponse) response.body();
    }

    @SuppressWarnings("unchecked")
    private boolean replayed(RecordedResponse response) {
        return Boolean.TRUE.equals(((Map<String, Object>) response.body()).get("replayed"));
    }

    // ---------------- 发布主流程 ----------------

    @Test
    @DisplayName("发布成功：冻结版本/等级/设备/窗口，占用查询可见，窗口长度等于等级分钟数")
    void publishSuccess() {
        verifiedPlan("PS1", "PLAN_S1", 5);
        device("DEV_S1", 3);
        Instant base = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        RecordedResponse result = scheduleService.publish(
                publishRequest("PS1", "PLAN_S1", 1, "DEV_S1", 2, "SCH-PUB-1", 1, 2, base));

        assertThat(result.httpStatus()).isEqualTo(200);
        ScheduleBatchResponse body = batch(result);
        assertThat(body.status()).isEqualTo("ACTIVE");
        assertThat(body.batchVersion()).isEqualTo(1);
        assertThat(body.level()).isEqualTo(2);
        assertThat(body.requiredMinutes()).isEqualTo(20);
        assertThat(body.versionNo()).isEqualTo(1);
        assertThat(body.items()).hasSize(2);
        ScheduleItemView first = body.items().get(0);
        assertThat(first.status()).isEqualTo("ACTIVE");
        assertThat(first.endAt()).isEqualTo(first.startAt().plus(20, ChronoUnit.MINUTES));

        List<DeviceOccupancyItem> occupancy = scheduleService.occupancy("DEV_S1", null, null);
        assertThat(occupancy).hasSize(2);
        assertThat(occupancy).allMatch(item -> "ACTIVE".equals(item.status()));
        assertThat(scheduleService.schedulesOfPlan("PS1", "PLAN_S1")).hasSize(1);
    }

    @Test
    @DisplayName("端点相接可共存：前批终点等于后批起点不视为重叠")
    void adjacentWindowsCoexist() {
        verifiedPlan("PS2", "PLAN_S2", 5);
        device("DEV_S2", 3);
        Instant base = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        assertThat(scheduleService.publish(
                publishRequest("PS2", "PLAN_S2", 1, "DEV_S2", 1, "SCH-ADJ-1", 1, 1, base))
                .httpStatus()).isEqualTo(200);
        RecordedResponse second = scheduleService.publish(
                publishRequest("PS2", "PLAN_S2", 1, "DEV_S2", 1, "SCH-ADJ-2", 2, 1,
                        base.plus(10, ChronoUnit.MINUTES)));
        assertThat(second.httpStatus()).isEqualTo(200);
        assertThat(scheduleService.occupancy("DEV_S2", null, null)).hasSize(2);
    }

    @Test
    @DisplayName("同设备窗口重叠：任一冲突整批零占用")
    void overlapRejectsWholeBatch() {
        verifiedPlan("PS3", "PLAN_S3", 9);
        device("DEV_S3", 3);
        Instant base = Instant.now().plus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        assertThat(scheduleService.publish(
                publishRequest("PS3", "PLAN_S3", 1, "DEV_S3", 1, "SCH-OVL-1", 1, 1, base))
                .httpStatus()).isEqualTo(200);

        // 第二批：分次2窗口不冲突、分次3窗口与第一批重叠 → 整批拒绝，分次2也不留占用
        List<ScheduleItemRequest> items = List.of(
                new ScheduleItemRequest(2, base.plus(20, ChronoUnit.MINUTES),
                        base.plus(30, ChronoUnit.MINUTES)),
                new ScheduleItemRequest(3, base.plus(5, ChronoUnit.MINUTES),
                        base.plus(15, ChronoUnit.MINUTES)));
        RecordedResponse result = scheduleService.publish(new SchedulePublishRequest(
                "PS3", "PLAN_S3", 1, "DEV_S3", 1, items, "SCH-OVL-2"));

        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(scheduleService.occupancy("DEV_S3", null, null))
                .hasSize(1)
                .allMatch(item -> item.fractionNo() == 1);
        assertThat(scheduleService.schedulesOfPlan("PS3", "PLAN_S3")).hasSize(1);
    }

    @Test
    @DisplayName("同分次双占拒绝：已占用分次再次排程返回422")
    void sameFractionDoubleOccupancyRejected() {
        verifiedPlan("PS4", "PLAN_S4", 5);
        device("DEV_S4", 3);
        Instant base = Instant.now().plus(4, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        assertThat(scheduleService.publish(
                publishRequest("PS4", "PLAN_S4", 1, "DEV_S4", 1, "SCH-DUP-1", 1, 1, base))
                .httpStatus()).isEqualTo(200);

        RecordedResponse result = scheduleService.publish(
                publishRequest("PS4", "PLAN_S4", 1, "DEV_S4", 1, "SCH-DUP-2", 1, 1,
                        base.plus(30, ChronoUnit.MINUTES)));
        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(scheduleService.occupancy("DEV_S4", null, null)).hasSize(1);
    }

    @Test
    @DisplayName("窗口长度不等于等级分钟数、分次不连续、超10个：参数非法400")
    void invalidShapeRejected400() {
        verifiedPlan("PS5", "PLAN_S5", 30);
        device("DEV_S5", 3);
        Instant base = Instant.now().plus(5, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        // 长度 15 分钟 ≠ 等级1的10分钟
        SchedulePublishRequest badLength = new SchedulePublishRequest("PS5", "PLAN_S5", 1,
                "DEV_S5", 1, List.of(new ScheduleItemRequest(1, base,
                base.plus(15, ChronoUnit.MINUTES))), "SCH-BAD-1");
        assertThatThrownBy(() -> scheduleService.publish(badLength))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getHttpStatus()).isEqualTo(400));

        // 分次 1、3 不连续
        SchedulePublishRequest notConsecutive = new SchedulePublishRequest("PS5", "PLAN_S5", 1,
                "DEV_S5", 1, List.of(
                new ScheduleItemRequest(1, base, base.plus(10, ChronoUnit.MINUTES)),
                new ScheduleItemRequest(3, base.plus(10, ChronoUnit.MINUTES),
                        base.plus(20, ChronoUnit.MINUTES))), "SCH-BAD-2");
        assertThatThrownBy(() -> scheduleService.publish(notConsecutive))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getHttpStatus()).isEqualTo(400));

        // 11 个分次超过单批上限
        assertThatThrownBy(() -> scheduleService.publish(
                publishRequest("PS5", "PLAN_S5", 1, "DEV_S5", 1, "SCH-BAD-3", 1, 11, base)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getHttpStatus()).isEqualTo(400));

        assertThat(scheduleService.occupancy("DEV_S5", null, null)).isEmpty();
    }

    @Test
    @DisplayName("版本门禁：未验证计划与失效版本号都不能排程")
    void versionGate() {
        patient("PS6", "排程患者6");
        pelvisTemplate(TPL, 4500L, new BigDecimal("40.00"));
        createPlanVersion("PS6", "PLAN_S6", 1, TPL, 1000L, 200L, 5, 4400L, new BigDecimal("35.00"));
        device("DEV_S6", 3);
        Instant base = Instant.now().plus(6, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        // 未批准未验证：422
        assertThat(scheduleService.publish(
                publishRequest("PS6", "PLAN_S6", 1, "DEV_S6", 1, "SCH-GATE-1", 1, 1, base))
                .httpStatus()).isEqualTo(422);

        // 批准并验证 v1，再改版到 v2：v1 版本号排程 → 422
        workflow.audit("PS6", "PLAN_S6", 1, rid());
        workflow.approve("PS6", "PLAN_S6", 1, rid());
        workflow.verify("PS6", "PLAN_S6", 1, 200L, 200L, rid());
        createPlanVersion("PS6", "PLAN_S6", 2, TPL, 1000L, 200L, 5, 4400L, new BigDecimal("35.00"));
        workflow.audit("PS6", "PLAN_S6", 2, rid());
        workflow.approve("PS6", "PLAN_S6", 2, rid());
        workflow.verify("PS6", "PLAN_S6", 2, 200L, 200L, rid());

        RecordedResponse stale = scheduleService.publish(
                publishRequest("PS6", "PLAN_S6", 1, "DEV_S6", 1, "SCH-GATE-2", 1, 1, base));
        assertThat(stale.httpStatus()).isEqualTo(422);

        RecordedResponse current = scheduleService.publish(
                publishRequest("PS6", "PLAN_S6", 2, "DEV_S6", 1, "SCH-GATE-3", 1, 1, base));
        assertThat(current.httpStatus()).isEqualTo(200);
        assertThat(batch(current).versionNo()).isEqualTo(2);
    }

    @Test
    @DisplayName("已执行分次不能再排程")
    void executedFractionCannotBeScheduled() {
        verifiedPlan("PS7", "PLAN_S7", 5);
        device("DEV_S7", 3);
        Instant inWindow = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        assertThat(scheduleService.publish(
                publishRequest("PS7", "PLAN_S7", 1, "DEV_S7", 1, "SCH-EXE-1", 1, 1, inWindow))
                .httpStatus()).isEqualTo(200);
        assertThat(scheduleService.treat(new TreatmentRequest("PS7", "PLAN_S7", 1, 200L, "TRT-EXE-1"))
                .httpStatus()).isEqualTo(200);

        RecordedResponse result = scheduleService.publish(
                publishRequest("PS7", "PLAN_S7", 1, "DEV_S7", 1, "SCH-EXE-2", 1, 1,
                        Instant.now().plus(7, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)));
        assertThat(result.httpStatus()).isEqualTo(422);
    }

    // ---------------- 幂等边界 ----------------

    @Test
    @DisplayName("发布幂等：同键同参重放首次冻结结果，同键异参409")
    void publishIdempotency() {
        verifiedPlan("PS8", "PLAN_S8", 5);
        device("DEV_S8", 3);
        Instant base = Instant.now().plus(8, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        SchedulePublishRequest request =
                publishRequest("PS8", "PLAN_S8", 1, "DEV_S8", 1, "SCH-IDM-1", 1, 1, base);

        RecordedResponse first = scheduleService.publish(request);
        assertThat(first.httpStatus()).isEqualTo(200);

        RecordedResponse replay = scheduleService.publish(request);
        assertThat(replay.httpStatus()).isEqualTo(200);
        assertThat(replayed(replay)).isTrue();
        assertThat(scheduleService.schedulesOfPlan("PS8", "PLAN_S8")).hasSize(1);

        SchedulePublishRequest different =
                publishRequest("PS8", "PLAN_S8", 1, "DEV_S8", 1, "SCH-IDM-1", 2, 1,
                        base.plus(20, ChronoUnit.MINUTES));
        assertThatThrownBy(() -> scheduleService.publish(different))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getHttpStatus()).isEqualTo(409));
        assertThat(scheduleService.schedulesOfPlan("PS8", "PLAN_S8")).hasSize(1);
    }

    @Test
    @DisplayName("失败不占键：首次422后，同键同参在状态修复后可成功")
    void failureDoesNotOccupyKey() {
        patient("PS9", "排程患者9");
        pelvisTemplate(TPL, 4500L, new BigDecimal("40.00"));
        createPlanVersion("PS9", "PLAN_S9", 1, TPL, 1000L, 200L, 5, 4400L, new BigDecimal("35.00"));
        workflow.audit("PS9", "PLAN_S9", 1, rid());
        workflow.approve("PS9", "PLAN_S9", 1, rid());
        device("DEV_S9", 3);
        Instant base = Instant.now().plus(9, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        SchedulePublishRequest request =
                publishRequest("PS9", "PLAN_S9", 1, "DEV_S9", 1, "SCH-FAIL-1", 1, 1, base);

        // 未验证：422，且不占键
        assertThat(scheduleService.publish(request).httpStatus()).isEqualTo(422);

        workflow.verify("PS9", "PLAN_S9", 1, 200L, 200L, rid());
        RecordedResponse second = scheduleService.publish(request);
        assertThat(second.httpStatus()).isEqualTo(200);
        assertThat(scheduleService.schedulesOfPlan("PS9", "PLAN_S9")).hasSize(1);
    }

    // ---------------- 受控治疗入口 ----------------

    @Test
    @DisplayName("受控治疗：窗口内匹配排程执行成功，原子释放占用并冻结实绩")
    void treatSuccessReleasesOccupancy() {
        verifiedPlan("PT1", "PLAN_T1", 5);
        device("DEV_T1", 3);
        Instant start = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        RecordedResponse published = scheduleService.publish(
                publishRequest("PT1", "PLAN_T1", 1, "DEV_T1", 1, "SCH-TRT-1", 1, 1, start));
        assertThat(published.httpStatus()).isEqualTo(200);

        RecordedResponse treated = scheduleService.treat(
                new TreatmentRequest("PT1", "PLAN_T1", 1, 200L, "TRT-1"));
        assertThat(treated.httpStatus()).isEqualTo(200);

        // 台账 1 条；明细 COMPLETED 且实绩冻结；批次 COMPLETED；占用已释放
        assertThat(workflow.fractionLedger("PT1", "PLAN_T1")).hasSize(1);
        List<DeviceOccupancyItem> occupancy = scheduleService.occupancy("DEV_T1", null, null);
        assertThat(occupancy).hasSize(1);
        assertThat(occupancy.get(0).status()).isEqualTo("COMPLETED");
        ScheduleBatchResponse batchView = scheduleService.schedulesOfPlan("PT1", "PLAN_T1").get(0);
        assertThat(batchView.status()).isEqualTo("COMPLETED");
        assertThat(batchView.items().get(0).deliveredDoseCgy()).isEqualTo(200L);
        assertThat(batchView.items().get(0).executedAt()).isNotNull();
    }

    @Test
    @DisplayName("受控治疗：窗口外拒绝执行")
    void treatOutsideWindowRejected() {
        verifiedPlan("PT2", "PLAN_T2", 5);
        device("DEV_T2", 3);
        Instant past = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        assertThat(scheduleService.publish(
                publishRequest("PT2", "PLAN_T2", 1, "DEV_T2", 1, "SCH-TRT-2", 1, 1, past))
                .httpStatus()).isEqualTo(200);

        RecordedResponse result = scheduleService.treat(
                new TreatmentRequest("PT2", "PLAN_T2", 1, 200L, "TRT-2"));
        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(workflow.fractionLedger("PT2", "PLAN_T2")).isEmpty();
    }

    @Test
    @DisplayName("受控治疗：无排程占用拒绝；跳次拒绝并告警")
    void treatRequiresScheduleAndOrder() {
        verifiedPlan("PT3", "PLAN_T3", 5);
        device("DEV_T3", 3);
        device("DEV_T3B", 3);

        // 无排程：422
        assertThat(scheduleService.treat(new TreatmentRequest("PT3", "PLAN_T3", 1, 200L, "TRT-3A"))
                .httpStatus()).isEqualTo(422);

        // 两台设备同窗口排分次1、2；先执行分次2 → 跳次 409 告警
        Instant start = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        assertThat(scheduleService.publish(
                publishRequest("PT3", "PLAN_T3", 1, "DEV_T3", 1, "SCH-TRT-3A", 1, 1, start))
                .httpStatus()).isEqualTo(200);
        assertThat(scheduleService.publish(
                publishRequest("PT3", "PLAN_T3", 1, "DEV_T3B", 1, "SCH-TRT-3B", 2, 1, start))
                .httpStatus()).isEqualTo(200);

        RecordedResponse skip = scheduleService.treat(
                new TreatmentRequest("PT3", "PLAN_T3", 2, 200L, "TRT-3B"));
        assertThat(skip.httpStatus()).isEqualTo(409);
        assertThat(workflow.fractionLedger("PT3", "PLAN_T3")).isEmpty();
    }

    // ---------------- 取消与版本门禁 ----------------

    @Test
    @DisplayName("取消：释放占用、版本加1；版本冲突409；重复取消422；同键重放")
    void cancelLifecycle() {
        verifiedPlan("PC1", "PLAN_C1", 5);
        device("DEV_C1", 3);
        Instant base = Instant.now().plus(10, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        RecordedResponse published = scheduleService.publish(
                publishRequest("PC1", "PLAN_C1", 1, "DEV_C1", 1, "SCH-CXL-1", 1, 2, base));
        long batchId = batch(published).batchId();

        // 版本冲突：409，且失败不占键
        RecordedResponse conflict = scheduleService.cancel(batchId,
                new ScheduleCancelRequest(5, "SCH-CXL-9"));
        assertThat(conflict.httpStatus()).isEqualTo(409);

        RecordedResponse cancelled = scheduleService.cancel(batchId,
                new ScheduleCancelRequest(1, "SCH-CXL-2"));
        assertThat(cancelled.httpStatus()).isEqualTo(200);
        ScheduleBatchResponse view = batch(cancelled);
        assertThat(view.status()).isEqualTo("CANCELLED");
        assertThat(view.batchVersion()).isEqualTo(2);
        assertThat(view.items()).allMatch(item -> "CANCELLED".equals(item.status()));

        // 占用已释放：同分次可重新排程
        assertThat(scheduleService.publish(
                publishRequest("PC1", "PLAN_C1", 1, "DEV_C1", 1, "SCH-CXL-3", 1, 2, base))
                .httpStatus()).isEqualTo(200);

        // 同键重放首次取消结果
        RecordedResponse replay = scheduleService.cancel(batchId,
                new ScheduleCancelRequest(1, "SCH-CXL-2"));
        assertThat(replay.httpStatus()).isEqualTo(200);
        assertThat(replayed(replay)).isTrue();

        // 新键重复取消：422
        assertThat(scheduleService.cancel(batchId, new ScheduleCancelRequest(2, "SCH-CXL-4"))
                .httpStatus()).isEqualTo(422);
    }

    @Test
    @DisplayName("对象不存在：设备/批次/患者 404")
    void notFoundSemantics() {
        assertThatThrownBy(() -> scheduleService.changeCapability("DEV_NONE",
                new DeviceCapabilityRequest(2, 1, "SCH-NF-1")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getHttpStatus()).isEqualTo(404));
        assertThatThrownBy(() -> scheduleService.cancel(999999L,
                new ScheduleCancelRequest(1, "SCH-NF-2")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getHttpStatus()).isEqualTo(404));
        assertThatThrownBy(() -> scheduleService.occupancy("DEV_NONE", null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getHttpStatus()).isEqualTo(404));
    }

    // ---------------- 改版与能力下调联动 ----------------

    @Test
    @DisplayName("计划改版：未开始排程取消并释放资源，已执行分次剂量与实绩不改写")
    void revisionCancelsUnstartedSchedules() {
        verifiedPlan("PR1", "PLAN_R1", 5);
        device("DEV_R1", 3);
        device("DEV_R1B", 3);
        Instant start = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        // 分次1窗口覆盖当前时刻（DEV_R1），分次2在未来（DEV_R1B）
        assertThat(scheduleService.publish(
                publishRequest("PR1", "PLAN_R1", 1, "DEV_R1", 1, "SCH-REV-1", 1, 1, start))
                .httpStatus()).isEqualTo(200);
        assertThat(scheduleService.publish(
                publishRequest("PR1", "PLAN_R1", 1, "DEV_R1B", 1, "SCH-REV-2", 2, 1,
                        Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)))
                .httpStatus()).isEqualTo(200);
        assertThat(scheduleService.treat(new TreatmentRequest("PR1", "PLAN_R1", 1, 200L, "TRT-REV-1"))
                .httpStatus()).isEqualTo(200);

        // 改版到 v2：旧版本未开始排程（分次2）取消释放，分次1实绩保留
        createPlanVersion("PR1", "PLAN_R1", 2, TPL, 1000L, 200L, 5, 4400L,
                new BigDecimal("35.00"));
        workflow.audit("PR1", "PLAN_R1", 2, rid());
        workflow.approve("PR1", "PLAN_R1", 2, rid());

        List<DeviceOccupancyItem> occupancyB = scheduleService.occupancy("DEV_R1B", null, null);
        assertThat(occupancyB).hasSize(1);
        assertThat(occupancyB.get(0).status()).isEqualTo("CANCELLED");
        List<DeviceOccupancyItem> occupancyA = scheduleService.occupancy("DEV_R1", null, null);
        assertThat(occupancyA.get(0).status()).isEqualTo("COMPLETED");

        // 已执行分次剂量与实绩不改写
        var ledger = workflow.fractionLedger("PR1", "PLAN_R1");
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).deliveredDoseCgy()).isEqualTo(200L);
        ScheduleBatchResponse batchA = scheduleService.schedulesOfPlan("PR1", "PLAN_R1").stream()
                .filter(b -> b.deviceId().equals("DEV_R1")).findFirst().orElseThrow();
        assertThat(batchA.items().get(0).deliveredDoseCgy()).isEqualTo(200L);

        // 不能用失效版本的已取消排程治疗
        assertThat(scheduleService.treat(new TreatmentRequest("PR1", "PLAN_R1", 2, 200L, "TRT-REV-2"))
                .httpStatus()).isEqualTo(422);
    }

    @Test
    @DisplayName("设备能力下调：只阻断尚未开始项，已完成实绩保留旧等级证据")
    void deviceDowngradeBlocksOnlyUnstarted() {
        verifiedPlan("PR2", "PLAN_R2", 5);
        device("DEV_R2", 3);
        Instant start = Instant.now().minus(15, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        // 分次1窗口覆盖当前时刻，分次2在未来；等级3（30分钟）
        assertThat(scheduleService.publish(
                publishRequest("PR2", "PLAN_R2", 1, "DEV_R2", 3, "SCH-DG-1", 1, 2, start))
                .httpStatus()).isEqualTo(200);
        assertThat(scheduleService.treat(new TreatmentRequest("PR2", "PLAN_R2", 1, 200L, "TRT-DG-1"))
                .httpStatus()).isEqualTo(200);

        // 能力下调 3→2：expectedVersion 门禁 + 版本加1
        RecordedResponse changed = scheduleService.changeCapability("DEV_R2",
                new DeviceCapabilityRequest(2, 1, "SCH-DG-2"));
        assertThat(changed.httpStatus()).isEqualTo(200);
        assertThat(((DeviceCapabilityResponse) changed.body()).version()).isEqualTo(2);

        // 尚未开始的分次2被阻断
        RecordedResponse blocked = scheduleService.treat(
                new TreatmentRequest("PR2", "PLAN_R2", 2, 200L, "TRT-DG-2"));
        assertThat(blocked.httpStatus()).isEqualTo(422);

        // 已完成分次1的实绩与等级证据不改写
        ScheduleBatchResponse view = scheduleService.schedulesOfPlan("PR2", "PLAN_R2").get(0);
        assertThat(view.level()).isEqualTo(3);
        ScheduleItemView first = view.items().get(0);
        assertThat(first.status()).isEqualTo("COMPLETED");
        assertThat(first.deliveredDoseCgy()).isEqualTo(200L);
        assertThat(workflow.fractionLedger("PR2", "PLAN_R2")).hasSize(1);
    }

    @Test
    @DisplayName("设备能力变更：版本冲突409，成功加1，同键重放，异参409")
    void capabilityVersioningAndIdempotency() {
        device("DEV_CAP", 3);

        assertThat(scheduleService.changeCapability("DEV_CAP",
                new DeviceCapabilityRequest(2, 9, "CAP-1")).httpStatus()).isEqualTo(409);

        RecordedResponse changed = scheduleService.changeCapability("DEV_CAP",
                new DeviceCapabilityRequest(2, 1, "CAP-2"));
        assertThat(changed.httpStatus()).isEqualTo(200);
        assertThat(((DeviceCapabilityResponse) changed.body()).version()).isEqualTo(2);

        RecordedResponse replay = scheduleService.changeCapability("DEV_CAP",
                new DeviceCapabilityRequest(2, 1, "CAP-2"));
        assertThat(replay.httpStatus()).isEqualTo(200);
        assertThat(replayed(replay)).isTrue();

        assertThatThrownBy(() -> scheduleService.changeCapability("DEV_CAP",
                new DeviceCapabilityRequest(1, 1, "CAP-2")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getHttpStatus()).isEqualTo(409));
    }

    // ---------------- 历史入口兼容 ----------------

    @Test
    @DisplayName("未启用排程控制的历史分次入口保持兼容")
    void legacyFractionEntryCompatible() {
        verifiedPlan("PL1", "PLAN_L1", 5);
        RecordedResponse legacy = workflow.recordFraction("PL1", "PLAN_L1", 1, 200L, "LEG-1");
        assertThat(legacy.httpStatus()).isEqualTo(200);
        assertThat(workflow.fractionLedger("PL1", "PLAN_L1")).hasSize(1);
    }
}
