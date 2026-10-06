package com.example.starter;

import com.example.starter.error.BusinessException;
import com.example.starter.service.CheckInService;
import com.example.starter.web.dto.RouteDtos.CheckInResponse;
import com.example.starter.web.dto.RouteDtos.MissedBoardingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 打卡去重与漏乘名单测试。
 */
class CheckInServiceTest extends BaseIntegrationTest {

    @Autowired
    private CheckInService checkInService;

    private long routeId;
    private long studentA;
    private long studentB;

    @BeforeEach
    void setupRoute() {
        long vehicleId = insertVehicle("PLATE-C-1", 40);
        jdbc.sql("INSERT INTO route (route_code, name, vehicle_id, station_spacing_m, road_grade,"
                        + " required_seats, status) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params("RC-C-1", "合成线路", vehicleId, 800, 2, 30, "IN_PROGRESS")
                .update();
        routeId = jdbc.sql("SELECT MAX(id) FROM route WHERE route_code = 'RC-C-1'")
                .query(Long.class)
                .single();
        studentA = insertStudent("STU-C-1", routeId);
        studentB = insertStudent("STU-C-2", routeId);
    }

    @Test
    void 同学生同日期同类型打卡去重() {
        CheckInResponse first = checkInService.checkIn(routeId, studentA, "BOARD", newRequestId());
        CheckInResponse second = checkInService.checkIn(routeId, studentA, "BOARD",
                newRequestId());

        assertThat(first.deduplicated()).isFalse();
        assertThat(second.deduplicated()).isTrue();
        assertThat(second.checkInId()).isEqualTo(first.checkInId());
        assertThat(count("check_in")).isEqualTo(1);
    }

    @Test
    void 同学生同日期不同类型打卡分别记录() {
        checkInService.checkIn(routeId, studentA, "BOARD", newRequestId());
        CheckInResponse alight = checkInService.checkIn(routeId, studentA, "ALIGHT",
                newRequestId());

        assertThat(alight.deduplicated()).isFalse();
        assertThat(count("check_in")).isEqualTo(2);
    }

    @Test
    void 非执行中线路拒绝打卡() {
        jdbc.sql("UPDATE route SET status = 'QUALIFIED' WHERE id = ?")
                .param(routeId)
                .update();

        assertThatThrownBy(() -> checkInService.checkIn(routeId, studentA, "BOARD",
                newRequestId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("ROUTE_NOT_IN_PROGRESS");
                });
    }

    @Test
    void 漏乘名单实时生成合成通知且重复生成不重复通知() {
        checkInService.checkIn(routeId, studentA, "BOARD", newRequestId());

        MissedBoardingResponse first = checkInService.generateMissedBoarding(routeId,
                newRequestId());
        assertThat(first.missedStudentIds()).containsExactly(studentB);
        assertThat(first.notificationIds()).hasSize(1);
        assertThat(count("notification")).isEqualTo(1);

        // 再次生成：漏乘名单一致，但不重复生成通知
        MissedBoardingResponse second = checkInService.generateMissedBoarding(routeId,
                newRequestId());
        assertThat(second.missedStudentIds()).containsExactly(studentB);
        assertThat(second.notificationIds()).isEmpty();
        assertThat(count("notification")).isEqualTo(1);
    }
}
