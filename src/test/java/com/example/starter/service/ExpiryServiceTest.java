package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.example.starter.support.BaseIntegrationTest;
import com.example.starter.web.dto.ExpiryRunRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

/**
 * 到期停运测试：恰满到期日 24:00（UTC 日界）失效边界、自动停运留痕、
 * 改派与通知同事务、失败整体回滚、改派目标确定性。
 */
class ExpiryServiceTest extends BaseIntegrationTest {

    private static final LocalDate VALID_TO = LocalDate.of(2026, 10, 10);

    @Autowired
    private QualificationService qualificationService;

    /**
     * 线路 1：RUNNING，资格 2026-10-10 到期，2 名学生；
     * 线路 2：RUNNING，资格长期有效，车辆 56 座（可接收改派）。
     */
    private void fixtures() {
        insertVehicle(1, 30);
        insertVehicle(2, 56);
        insertRoute(1, 1L, "RUNNING");
        insertRoute(2, 2L, "RUNNING");
        insertQualification(1, VALID_TO.minusYears(1), VALID_TO, "ACTIVE");
        insertQualification(2, VALID_TO.minusYears(1), VALID_TO.plusYears(10), "ACTIVE");
        insertStudent(1);
        insertStudent(2);
        assignStudent(1, 1);
        assignStudent(1, 2);
    }

    @Test
    void notExpiredBeforeBoundary() {
        fixtures();
        // 到期日当日 23:59:59 UTC 仍有效
        ExpiryRunRequest.Result result = qualificationService.runExpiry(
                Instant.parse("2026-10-10T23:59:59Z"));
        assertThat(result.stoppedRouteIds()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM route WHERE id = 1", String.class))
                .isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT status FROM qualification WHERE route_id = 1", String.class))
                .isEqualTo("ACTIVE");
    }

    @Test
    void expiredExactlyAtBoundaryMidnight() {
        fixtures();
        // 恰满到期日当日 24:00（即次日 00:00 UTC）失效
        ExpiryRunRequest.Result result = qualificationService.runExpiry(
                Instant.parse("2026-10-11T00:00:00Z"));
        assertThat(result.stoppedRouteIds()).containsExactly(1L);
        assertThat(result.reassignmentCount()).isEqualTo(2);
        assertThat(result.notificationCount()).isEqualTo(2);

        assertThat(jdbc.queryForObject("SELECT status FROM route WHERE id = 1", String.class))
                .isEqualTo("STOPPED");
        assertThat(jdbc.queryForObject("SELECT status FROM qualification WHERE route_id = 1", String.class))
                .isEqualTo("EXPIRED");
        // 停运留痕
        List<String> logs = jdbc.query(
                "SELECT from_status || '->' || to_status FROM route_status_log WHERE route_id = 1",
                (rs, i) -> rs.getString(1));
        assertThat(logs).containsExactly("RUNNING->STOPPED");
        // 改派与通知同事务生成，目标为可接收线路 2
        List<Long> targets = jdbc.query(
                "SELECT target_route_id FROM reassignment WHERE route_id = 1 ORDER BY student_id",
                (rs, i) -> rs.getLong(1));
        assertThat(targets).containsExactly(2L, 2L);
        Integer reassignNotifications = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE route_id = 1 AND type = 'ROUTE_STOPPED_REASSIGN'",
                Integer.class);
        assertThat(reassignNotifications).isEqualTo(2);
        // 线路 2 不受影响
        assertThat(jdbc.queryForObject("SELECT status FROM route WHERE id = 2", String.class))
                .isEqualTo("RUNNING");
    }

    @Test
    void unassignedWhenNoTargetRouteHasFreeSeats() {
        insertVehicle(1, 30);
        insertRoute(1, 1L, "RUNNING");
        insertQualification(1, VALID_TO.minusYears(1), VALID_TO, "ACTIVE");
        insertStudent(1);
        assignStudent(1, 1);

        ExpiryRunRequest.Result result = qualificationService.runExpiry(
                Instant.parse("2026-10-11T00:00:00Z"));
        assertThat(result.reassignmentCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM reassignment WHERE route_id = 1 AND student_id = 1", String.class))
                .isEqualTo("UNASSIGNED");
        assertThat(jdbc.queryForObject(
                "SELECT target_route_id FROM reassignment WHERE route_id = 1 AND student_id = 1",
                Long.class)).isNull();
    }

    @Test
    void reassignTargetIsDeterministicByRouteIdOrder() {
        fixtures();
        // 再增一条可接收线路 3（ID 更大），目标应稳定为线路 2
        insertVehicle(3, 56);
        insertRoute(3, 3L, "RUNNING");
        insertQualification(3, VALID_TO.minusYears(1), VALID_TO.plusYears(10), "ACTIVE");

        qualificationService.runExpiry(Instant.parse("2026-10-11T00:00:00Z"));
        List<Long> targets = jdbc.query(
                "SELECT target_route_id FROM reassignment WHERE route_id = 1 ORDER BY student_id",
                (rs, i) -> rs.getLong(1));
        assertThat(targets).containsExactly(2L, 2L);
    }

    @Test
    void wholeTransactionRollsBackOnFailure() {
        fixtures();
        // 预置冲突改派记录，使到期处理在改派环节失败
        jdbc.update("INSERT INTO reassignment (route_id, student_id, stop_date, target_route_id, status)"
                + " VALUES (1, 1, DATE '2026-10-11', 2, 'ASSIGNED')");

        assertThatThrownBy(() -> qualificationService.runExpiry(Instant.parse("2026-10-11T00:00:00Z")))
                .isInstanceOf(DuplicateKeyException.class);

        // 整体回滚：资格仍有效、线路仍执行中、无停运留痕、无通知
        assertThat(jdbc.queryForObject("SELECT status FROM qualification WHERE route_id = 1", String.class))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM route WHERE id = 1", String.class))
                .isEqualTo("RUNNING");
        assertThat(count("route_status_log")).isZero();
        assertThat(count("notification")).isZero();
        assertThat(count("reassignment")).isEqualTo(1);
    }

    @Test
    void rerunAfterSuccessDoesNotDuplicate() {
        fixtures();
        qualificationService.runExpiry(Instant.parse("2026-10-11T00:00:00Z"));
        ExpiryRunRequest.Result second = qualificationService.runExpiry(
                Instant.parse("2026-10-12T00:00:00Z"));
        assertThat(second.stoppedRouteIds()).isEmpty();
        assertThat(count("reassignment")).isEqualTo(2);
        assertThat(count("notification")).isEqualTo(2);
    }
}
