package com.example.starter.hazmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.example.starter.hazmat.api.ApiException;
import com.example.starter.hazmat.dto.ApplyRequest;
import com.example.starter.hazmat.dto.CheckinRequest;
import com.example.starter.hazmat.service.ServiceResult;

/**
 * requestId 幂等：同键同参返回首次结果；同键异参 409；失败首次结果也重放。
 */
class IdempotencyTest extends HazmatIntegrationTestBase {

    private static final LocalDateTime DEPARTURE = LocalDateTime.parse("2026-10-02T08:00:00");

    @Test
    void sameKeySameParamsReplaysFirstApplyResult() {
        ApplyRequest req = new ApplyRequest("idem-apply-1", "沪A-1001", "FLAMMABLE", "R-G3", DEPARTURE);
        ServiceResult first = service.apply(req);
        ServiceResult replay = service.apply(req);
        assertThat(replay.httpStatus()).isEqualTo(200);
        assertThat(replay.body().get("shipmentNo")).isEqualTo(first.body().get("shipmentNo"));
        // 未产生第二条运单。
        assertThat(repository.findActiveShipment("沪A-1001")).isPresent();
    }

    @Test
    void sameKeyDifferentParamsConflicts() {
        service.apply(new ApplyRequest("idem-apply-2", "沪A-1001", "FLAMMABLE", "R-G3", DEPARTURE));
        ApplyRequest changed = new ApplyRequest("idem-apply-2", "沪A-1002", "FLAMMABLE", "R-G3", DEPARTURE);
        assertThatThrownBy(() -> service.apply(changed))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("IDEMPOTENT_FINGERPRINT_MISMATCH"));
        // 被拒绝的异参请求没有占用第二辆车。
        assertThat(repository.findActiveShipment("沪A-1002")).isEmpty();
    }

    @Test
    void sameKeyReplaysFailedDispatchResult() {
        String no = shipmentNo(apply("idem-fail-apply", "沪A-1002", "TOXIC", "R-G1", DEPARTURE));
        // 未核准即发运 → 首次失败 409。
        assertThatThrownBy(() -> dispatch(no, "idem-dispatch-bad"))
                .isInstanceOf(ApiException.class);
        // 同键重放仍返回相同失败（409），不产生额外状态。
        assertThatThrownBy(() -> dispatch(no, "idem-dispatch-bad"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isNotEqualTo("IDEMPOTENT_FINGERPRINT_MISMATCH"));
        assertThat(view(no).get("status")).isEqualTo("APPLIED");
    }

    @Test
    void checkinSameKeySameParamsReplays() {
        String no = shipmentNo(apply("idem-ck-apply", "沪B-2001", "FLAMMABLE", "R-G3", DEPARTURE));
        approve(no);
        dispatch(no, "idem-ck-dispatch");
        CheckinRequest req = new CheckinRequest("idem-checkin-0", 0, DEPARTURE.plusMinutes(31));
        ServiceResult first = service.checkin(no, req);
        assertThat(first.httpStatus()).isEqualTo(200);
        // 同键同参重放：返回首次成功，不产生重复打卡（不会报 WAYPOINT_ALREADY_CHECKED）。
        ServiceResult replay = service.checkin(no, req);
        assertThat(replay.httpStatus()).isEqualTo(200);
        assertThat(waypoint(no, 0).get("state")).isEqualTo("CHECKED");
    }

    @Test
    void checkinSameKeyDifferentTimeConflicts() {
        String no = shipmentNo(apply("idem-ck2-apply", "沪A-1001", "FLAMMABLE", "R-G3", DEPARTURE));
        approve(no);
        dispatch(no, "idem-ck2-dispatch");
        service.checkin(no, new CheckinRequest("idem-checkin-x", 0, DEPARTURE.plusMinutes(31)));
        assertThatThrownBy(() -> service.checkin(no,
                new CheckinRequest("idem-checkin-x", 0, DEPARTURE.plusMinutes(35))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("IDEMPOTENT_FINGERPRINT_MISMATCH"));
    }
}
