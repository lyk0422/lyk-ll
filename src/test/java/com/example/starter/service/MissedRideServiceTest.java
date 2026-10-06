package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.support.BaseIntegrationTest;
import com.example.starter.support.NotFoundException;
import com.example.starter.web.dto.CheckInRequest;
import com.example.starter.web.dto.MissedResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 漏乘名单测试：实时比对打卡记录生成合成通知，重复查询不重复生成。
 */
class MissedRideServiceTest extends BaseIntegrationTest {

    @Autowired
    private MissedRideService missedRideService;

    @Autowired
    private CheckInService checkInService;

    private void fixtures() {
        insertVehicle(1, 30);
        insertRoute(1, 1L, "RUNNING");
        insertQualification(1, today().minusDays(1), today().plusMonths(6), "ACTIVE");
        for (long s = 1; s <= 3; s++) {
            insertStudent(s);
            assignStudent(1, s);
        }
    }

    @Test
    void missedListExcludesCheckedInStudents() {
        fixtures();
        checkInService.checkIn(new CheckInRequest("ci-1", 1L, 1L, today()));

        MissedResult result = missedRideService.missed(1L, today());
        assertThat(result.missedStudentIds()).containsExactly(2L, 3L);
        assertThat(result.notificationsGenerated()).isEqualTo(2);
        Integer notifications = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE route_id = 1 AND type = 'MISSED_RIDE'",
                Integer.class);
        assertThat(notifications).isEqualTo(2);
    }

    @Test
    void repeatedQueryDoesNotDuplicateNotifications() {
        fixtures();
        missedRideService.missed(1L, today());
        MissedResult second = missedRideService.missed(1L, today());
        assertThat(second.missedStudentIds()).containsExactly(1L, 2L, 3L);
        assertThat(second.notificationsGenerated()).isZero();
        assertThat(count("notification")).isEqualTo(3);
    }

    @Test
    void emptyMissedWhenAllCheckedIn() {
        fixtures();
        for (long s = 1; s <= 3; s++) {
            checkInService.checkIn(new CheckInRequest("ci-all-" + s, 1L, s, today()));
        }
        MissedResult result = missedRideService.missed(1L, today());
        assertThat(result.missedStudentIds()).isEmpty();
        assertThat(result.notificationsGenerated()).isZero();
    }

    @Test
    void notFoundForUnknownRoute() {
        assertThatThrownBy(() -> missedRideService.missed(999L, today()))
                .isInstanceOf(NotFoundException.class);
    }
}
