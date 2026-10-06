package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.example.starter.support.BaseIntegrationTest;
import com.example.starter.web.dto.SettlementRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 日结测试：改派结果纳入日结、可复算（重复执行一致）。
 */
class SettlementServiceTest extends BaseIntegrationTest {

    private static final LocalDate VALID_TO = LocalDate.of(2026, 10, 10);
    private static final LocalDate STOP_DATE = LocalDate.of(2026, 10, 11);

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private QualificationService qualificationService;

    @Autowired
    private MissedRideService missedRideService;

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
    void settlementIncludesReassignmentsAndNotifications() {
        fixtures();
        qualificationService.runExpiry(Instant.parse("2026-10-11T00:00:00Z"));
        // 线路 2 当日另有 1 条漏乘通知
        insertStudent(9);
        assignStudent(2, 9);
        missedRideService.missed(2L, STOP_DATE);

        List<SettlementRow> rows = settlementService.settle(STOP_DATE);
        assertThat(rows).hasSize(2);
        SettlementRow route1 = rows.stream().filter(r -> r.routeId() == 1L).findFirst().orElseThrow();
        assertThat(route1.reassignedCount()).isEqualTo(2);
        assertThat(route1.notifiedCount()).isEqualTo(2);
        SettlementRow route2 = rows.stream().filter(r -> r.routeId() == 2L).findFirst().orElseThrow();
        assertThat(route2.reassignedCount()).isZero();
        assertThat(route2.notifiedCount()).isEqualTo(1);
        assertThat(count("daily_settlement")).isEqualTo(2);
    }

    @Test
    void settlementIsRecomputable() {
        fixtures();
        qualificationService.runExpiry(Instant.parse("2026-10-11T00:00:00Z"));

        List<SettlementRow> first = settlementService.settle(STOP_DATE);
        List<SettlementRow> second = settlementService.settle(STOP_DATE);
        assertThat(second).isEqualTo(first);
        // 复算幂等落库，不产生重复行
        assertThat(count("daily_settlement")).isEqualTo(1);
    }

    @Test
    void emptySettlementForDateWithoutEvents() {
        fixtures();
        List<SettlementRow> rows = settlementService.settle(STOP_DATE);
        assertThat(rows).isEmpty();
        assertThat(count("daily_settlement")).isZero();
    }
}
