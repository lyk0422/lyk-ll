package com.example.starter.rt.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.rt.dto.DeviceCapabilityRequest;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.dto.ScheduleCancelRequest;
import com.example.starter.rt.dto.ScheduleItemRequest;
import com.example.starter.rt.dto.SchedulePublishRequest;
import com.example.starter.rt.dto.SchedulePublishResponse;
import com.example.starter.rt.dto.ScheduleResponse;
import com.example.starter.rt.repository.SchedulingRepository;
import com.example.starter.rt.web.ApiException;

/**
 * 排程门禁 requestId 幂等边界 H2 测试：
 * 同键同参重放首次冻结结果、同键异参409、失败（4xx）不占键可修正重试。
 */
class ScheduleIdempotencyTest extends SchedulingTestBase {

    private static final OffsetDateTime T0 =
            OffsetDateTime.of(2026, 10, 1, 2, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private SchedulingRepository scheduleRepo;

    private SchedulePublishRequest req(String requestId, OffsetDateTime start, int minutes) {
        return new SchedulePublishRequest(PATIENT, PLAN, 1, requestId,
                List.of(new ScheduleItemRequest(1, DEVICE, 1, start, start.plusMinutes(minutes))));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object body) {
        assertThat(body).isInstanceOf(Map.class);
        return (Map<String, Object>) body;
    }

    @Test
    @DisplayName("发布同键同参：重放首次冻结结果replayed=true，不产生新排程/占用")
    void publishSameKeyReplaysFrozenResult() {
        approvedVerifiedPlan();
        registerDevice(2);

        RecordedResponse first = scheduling.publish(req("ID-PUB", T0, 10));
        assert200(first);
        SchedulePublishResponse firstBody = (SchedulePublishResponse) first.body();
        Long firstId = firstBody.schedules().get(0).id();

        RecordedResponse replay = scheduling.publish(req("ID-PUB", T0, 10));
        assert200(replay);
        Map<String, Object> replayedBody = asMap(replay.body());
        assertThat(replayedBody.get("replayed")).isEqualTo(true);
        List<?> replayedSchedules = (List<?>) replayedBody.get("schedules");
        Map<String, Object> row = (Map<String, Object>) replayedSchedules.get(0);
        assertThat(((Number) row.get("id")).longValue()).isEqualTo(firstId);

        assertThat(scheduling.planSchedules(PATIENT, PLAN, "BOOKED")).hasSize(1);
        assertThat(scheduleRepo.countOccupancies()).isEqualTo(1);
    }

    @Test
    @DisplayName("发布同键异参：409指纹冲突，不产生占用")
    void publishSameKeyDifferentParamsConflict() {
        approvedVerifiedPlan();
        registerDevice(2);
        assert200(scheduling.publish(req("ID-PUB2", T0, 10)));

        // 不同窗口时刻
        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> scheduling.publish(req("ID-PUB2", T0.plusMinutes(30), 10)));
        assertThat(ex.getHttpStatus()).isEqualTo(409);
        assertThat(ex.getCode()).isEqualTo("IDEMPOTENCY_FINGERPRINT_CONFLICT");

        // 不同集合结构（参数中携带含分隔符的合法文本也必须区分）
        SchedulePublishRequest injected = new SchedulePublishRequest(PATIENT, PLAN, 1, "ID-PUB2",
                List.of(new ScheduleItemRequest(1, DEVICE, 1, T0, T0.plusMinutes(10)),
                        new ScheduleItemRequest(2, DEVICE, 1, T0.plusMinutes(10),
                                T0.plusMinutes(20))));
        ApiException ex2 = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> scheduling.publish(injected));
        assertThat(ex2.getCode()).isEqualTo("IDEMPOTENCY_FINGERPRINT_CONFLICT");

        assertThat(scheduleRepo.countOccupancies()).isEqualTo(1);
    }

    @Test
    @DisplayName("发布业务失败不占键：同键修正参数后可成功")
    void failedPublishDoesNotOccupyKey() {
        approvedVerifiedPlan();
        registerDevice(2);
        // 先占用 T0 窗口与分次1
        assert200(scheduling.publish(req("ID-ANCHOR", T0, 10)));

        // 同 requestId 首次因设备窗口重叠 422（分次2，窗口与锚点重叠）
        SchedulePublishRequest overlapping = new SchedulePublishRequest(PATIENT, PLAN, 1, "ID-RETRY",
                List.of(new ScheduleItemRequest(2, DEVICE, 1,
                        T0.plusMinutes(5), T0.plusMinutes(15))));
        RecordedResponse failed = scheduling.publish(overlapping);
        assertThat(failed.httpStatus()).isEqualTo(422);
        assertThat(scheduleRepo.findByPublishRequestId("ID-RETRY")).isEmpty();

        // 同键改为不冲突窗口：成功（证明失败未占键；分次2原本未占用）
        SchedulePublishRequest fixed = new SchedulePublishRequest(PATIENT, PLAN, 1, "ID-RETRY",
                List.of(new ScheduleItemRequest(2, DEVICE, 1,
                        T0.plusMinutes(30), T0.plusMinutes(40))));
        assert200(scheduling.publish(fixed));
        assertThat(scheduleRepo.findByPublishRequestId("ID-RETRY")).hasSize(1);
    }

    @Test
    @DisplayName("取消同键同参重放；版本错误失败不占键，修正后成功")
    void cancelReplayAndFailureNoKey() {
        approvedVerifiedPlan();
        registerDevice(2);
        ScheduleResponse s = ((SchedulePublishResponse) scheduling
                .publish(req("ID-PUBC", T0, 10)).body()).schedules().get(0);

        // 错误 expectedVersion：409 业务结果，不占键
        RecordedResponse badVersion = scheduling.cancel(
                new ScheduleCancelRequest(s.id(), 7, "ID-CANCEL"));
        assertThat(badVersion.httpStatus()).isEqualTo(409);

        // 同键用正确版本：成功
        RecordedResponse ok = scheduling.cancel(
                new ScheduleCancelRequest(s.id(), 1, "ID-CANCEL"));
        assert200(ok);

        // 再次同键同参重放
        RecordedResponse replay = scheduling.cancel(
                new ScheduleCancelRequest(s.id(), 1, "ID-CANCEL"));
        assert200(replay);
        assertThat(asMap(replay.body()).get("replayed")).isEqualTo(true);

        assertThat(scheduleRepo.countOccupancies()).isZero();
    }

    @Test
    @DisplayName("设备能力下调同键重放首次结果，版本号为首次冻结值")
    void capabilitySameKeyReplays() {
        registerDevice(3);
        assert200(scheduling.downgradeCapability(
                new DeviceCapabilityRequest(DEVICE, 1, 2, "ID-CAP")));
        RecordedResponse replay = scheduling.downgradeCapability(
                new DeviceCapabilityRequest(DEVICE, 1, 2, "ID-CAP"));
        assert200(replay);
        Map<String, Object> body = asMap(replay.body());
        assertThat(body.get("replayed")).isEqualTo(true);
        // 重放首次冻结：等级2、版本2；设备表未再次变更
        assertThat(((Number) body.get("version")).intValue()).isEqualTo(2);
        assertThat(((Number) body.get("capabilityLevel")).intValue()).isEqualTo(2);
        assertThat(scheduling.devices().get(0).version()).isEqualTo(2);
    }
}
