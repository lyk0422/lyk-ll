package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import com.example.starter.support.BaseIntegrationTest;
import com.example.starter.support.ConflictException;
import com.example.starter.support.Limits;
import com.example.starter.web.dto.AssessmentRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 安全评估测试：逐项校验、恰等限值通过、同线路同日期拒绝、车辆独占、幂等回放。
 */
class AssessmentServiceTest extends BaseIntegrationTest {

    @Autowired
    private AssessmentService assessmentService;

    private AssessmentRequest request(String requestId, long routeId, long vehicleId,
                                      int roadGrade, List<Integer> distances) {
        return new AssessmentRequest(requestId, routeId, vehicleId, roadGrade, distances);
    }

    @Test
    void passWhenAllLimitsExactlyMet() {
        insertVehicle(1, Limits.MAX_VEHICLE_SEATS);
        insertRoute(1, null, "APPLIED");
        // 恰等限值：间距 500/3000 米、道路等级 3、座位数恰等上限且不小于学生数
        AssessmentRequest.Result result = assessmentService.assess(
                request("req-pass-exact", 1, 1, Limits.MIN_ROAD_GRADE,
                        List.of(Limits.MIN_STOP_DISTANCE_M, Limits.MAX_STOP_DISTANCE_M)));

        assertThat(result.passed()).isTrue();
        assertThat(result.reasons()).isEmpty();
        assertThat(result.assessDate()).isEqualTo(today());
        assertThat(result.validFrom()).isEqualTo(today());
        assertThat(result.validTo()).isEqualTo(today().plusYears(1));
        assertThat(jdbc.queryForObject("SELECT status FROM route WHERE id = 1", String.class))
                .isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT status FROM qualification WHERE route_id = 1", String.class))
                .isEqualTo("ACTIVE");
        assertThat(count("route_status_log")).isEqualTo(1);
    }

    @Test
    void passWhenSeatsExactlyEqualStudentCount() {
        insertVehicle(1, 3);
        insertRoute(1, null, "APPLIED");
        for (long s = 1; s <= 3; s++) {
            insertStudent(s);
            assignStudent(1, s);
        }
        AssessmentRequest.Result result = assessmentService.assess(
                request("req-seats-exact", 1, 1, 4, List.of(800)));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void rejectWhenDistanceBelowMin() {
        insertVehicle(1, 30);
        insertRoute(1, null, "APPLIED");
        AssessmentRequest.Result result = assessmentService.assess(
                request("req-dist-low", 1, 1, 3, List.of(Limits.MIN_STOP_DISTANCE_M - 1, 800)));
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("站点间距"));
        assertThat(jdbc.queryForObject("SELECT status FROM route WHERE id = 1", String.class))
                .isEqualTo("APPLIED");
        assertThat(count("qualification")).isZero();
    }

    @Test
    void rejectWhenDistanceAboveMax() {
        insertVehicle(1, 30);
        insertRoute(1, null, "APPLIED");
        AssessmentRequest.Result result = assessmentService.assess(
                request("req-dist-high", 1, 1, 3, List.of(Limits.MAX_STOP_DISTANCE_M + 1)));
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("站点间距"));
    }

    @Test
    void rejectWhenRoadGradeBelowMin() {
        insertVehicle(1, 30);
        insertRoute(1, null, "APPLIED");
        AssessmentRequest.Result result = assessmentService.assess(
                request("req-grade-low", 1, 1, Limits.MIN_ROAD_GRADE - 1, List.of(800)));
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("道路等级"));
    }

    @Test
    void rejectWhenSeatsInsufficient() {
        insertVehicle(1, 2);
        insertRoute(1, null, "APPLIED");
        for (long s = 1; s <= 3; s++) {
            insertStudent(s);
            assignStudent(1, s);
        }
        AssessmentRequest.Result result = assessmentService.assess(
                request("req-seats-few", 1, 1, 3, List.of(800)));
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("座位数"));
    }

    @Test
    void rejectWhenSeatsAboveMax() {
        insertVehicle(1, Limits.MAX_VEHICLE_SEATS + 1);
        insertRoute(1, null, "APPLIED");
        AssessmentRequest.Result result = assessmentService.assess(
                request("req-seats-many", 1, 1, 3, List.of(800)));
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("座位数"));
    }

    @Test
    void rejectDuplicateAssessmentSameRouteSameDate() {
        insertVehicle(1, 30);
        insertRoute(1, null, "APPLIED");
        // 首次评估未通过（道路等级不足），线路保持 APPLIED，同日再次评估被拒绝
        assessmentService.assess(request("req-dup-1", 1, 1, 2, List.of(800)));

        assertThatThrownBy(() -> assessmentService.assess(request("req-dup-2", 1, 1, 3, List.of(900))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("同线路同日期");
        assertThat(count("assessment")).isEqualTo(1);
    }

    @Test
    void rejectAssessmentWhenRouteAlreadyRunning() {
        insertVehicle(1, 30);
        insertRoute(1, null, "APPLIED");
        assessmentService.assess(request("req-run-1", 1, 1, 3, List.of(800)));

        clock.setInstant(DEFAULT_NOW.plus(java.time.Duration.ofDays(1)));
        assertThatThrownBy(() -> assessmentService.assess(request("req-run-2", 1, 1, 3, List.of(800))))
                .isInstanceOf(com.example.starter.support.BusinessException.class)
                .hasMessageContaining("执行中");
    }

    @Test
    void allowAssessmentSameRouteOnDifferentDate() {
        insertVehicle(1, 30);
        insertRoute(1, null, "APPLIED");
        assessmentService.assess(request("req-day-1", 1, 1, 2, List.of(800)));

        clock.setInstant(DEFAULT_NOW.plus(java.time.Duration.ofDays(1)));
        AssessmentRequest.Result second = assessmentService.assess(
                request("req-day-2", 1, 1, 3, List.of(800)));
        assertThat(second.passed()).isTrue();
        assertThat(count("assessment")).isEqualTo(2);
    }

    @Test
    void rejectWhenVehicleBusyOnRunningRoute() {
        insertVehicle(1, 30);
        insertRoute(1, 1L, "RUNNING");
        insertQualification(1, today().minusDays(10), today().plusMonths(6), "ACTIVE");
        insertRoute(2, null, "APPLIED");

        assertThatThrownBy(() -> assessmentService.assess(request("req-busy", 2, 1, 3, List.of(800))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("占用");
        assertThat(jdbc.queryForObject("SELECT status FROM route WHERE id = 2", String.class))
                .isEqualTo("APPLIED");
    }

    @Test
    void idempotentReplayReturnsFirstResult() {
        insertVehicle(1, 30);
        insertRoute(1, null, "APPLIED");
        AssessmentRequest req = request("req-idem", 1, 1, 3, List.of(800));

        AssessmentRequest.Result first = assessmentService.assess(req);
        AssessmentRequest.Result replay = assessmentService.assess(req);

        assertThat(replay).isEqualTo(first);
        assertThat(count("assessment")).isEqualTo(1);
        assertThat(count("qualification")).isEqualTo(1);
        assertThat(count("idempotency_record")).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentParamsConflicts() {
        insertVehicle(1, 30);
        insertVehicle(2, 30);
        insertRoute(1, null, "APPLIED");
        assessmentService.assess(request("req-conflict", 1, 1, 3, List.of(800)));

        assertThatThrownBy(() -> assessmentService.assess(request("req-conflict", 1, 2, 3, List.of(800))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");
        assertThat(count("assessment")).isEqualTo(1);
    }

    @Test
    void rejectAllFailingItemsTogether() {
        insertVehicle(1, Limits.MAX_VEHICLE_SEATS + 1);
        insertRoute(1, null, "APPLIED");
        insertStudent(1);
        assignStudent(1, 1);
        AssessmentRequest.Result result = assessmentService.assess(
                request("req-multi", 1, 1, 1, List.of(100, 9000)));
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void qualificationExpiryDateIsExactlyOneYearLater() {
        insertVehicle(1, 30);
        insertRoute(1, null, "APPLIED");
        assessmentService.assess(request("req-expiry-date", 1, 1, 3, List.of(800)));
        LocalDate validTo = jdbc.queryForObject(
                "SELECT valid_to FROM qualification WHERE route_id = 1", LocalDate.class);
        assertThat(validTo).isEqualTo(LocalDate.ofInstant(DEFAULT_NOW, java.time.ZoneOffset.UTC).plusYears(1));
    }
}
