package com.example.starter;

import java.time.LocalDate;

import com.example.starter.repo.QualificationRepository;
import com.example.starter.service.ExpiryService;
import com.example.starter.service.SettlementService;
import com.example.starter.web.dto.QualificationDtos.ExpireResponse;
import com.example.starter.web.dto.QualificationDtos.SettlementResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资格到期边界与停运改派事务测试：恰满到期日当日 24:00（UTC 日界）失效，
 * 失效线路自动停运留痕，学生改派记录与通知同事务生成并纳入日结。
 */
class ExpiryServiceTest extends BaseIntegrationTest {

    @Autowired
    private ExpiryService expiryService;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private QualificationRepository qualificationRepository;

    /**
     * 构造执行中线路 + 有效资格 + 学生，返回线路ID。
     */
    private long setupInProgressRoute(String suffix, LocalDate validTo, int studentCount) {
        long vehicleId = insertVehicle("PLATE-E-" + suffix, 40);
        jdbc.sql("INSERT INTO route (route_code, name, vehicle_id, station_spacing_m, road_grade,"
                        + " required_seats, status) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params("RC-E-" + suffix, "合成线路" + suffix, vehicleId, 800, 2, 30,
                        "IN_PROGRESS")
                .update();
        long routeId = jdbc.sql("SELECT MAX(id) FROM route WHERE route_code = ?")
                .param("RC-E-" + suffix)
                .query(Long.class)
                .single();
        qualificationRepository.insert(routeId, validTo.minusYears(1).plusDays(1), validTo,
                "INITIAL", newRequestId());
        for (int i = 0; i < studentCount; i++) {
            insertStudent("STU-E-" + suffix + "-" + i, routeId);
        }
        return routeId;
    }

    @Test
    void 到期日当日仍有效_不触发停运() {
        setupInProgressRoute("D1", TODAY, 2);

        ExpireResponse response = expiryService.expireQualifications(newRequestId());

        assertThat(response.expiredQualificationIds()).isEmpty();
        assertThat(response.suspendedRouteIds()).isEmpty();
        assertThat(count("suspension_trace")).isZero();
        assertThat(count("reassignment")).isZero();
    }

    @Test
    void 到期日次日资格失效_线路停运留痕_学生改派与通知同事务生成() {
        long suspendedRouteId = setupInProgressRoute("D2", TODAY.minusDays(1), 3);
        long targetRouteId = setupInProgressRoute("D3", TODAY.plusDays(100), 0);

        ExpireResponse response = expiryService.expireQualifications(newRequestId());

        assertThat(response.expiredQualificationIds()).hasSize(1);
        assertThat(response.suspendedRouteIds()).containsExactly(suspendedRouteId);
        assertThat(response.reassignmentCount()).isEqualTo(3);
        assertThat(response.notificationCount()).isEqualTo(3);

        // 停运留痕
        assertThat(count("suspension_trace")).isEqualTo(1);
        String routeStatus = jdbc.sql("SELECT status FROM route WHERE id = ?")
                .param(suspendedRouteId)
                .query(String.class)
                .single();
        assertThat(routeStatus).isEqualTo("SUSPENDED");
        assertThat(qualificationRepository.findById(response.expiredQualificationIds().get(0))
                .orElseThrow().status()).isEqualTo("EXPIRED");

        // 学生已改派至目标线路
        Long movedStudents = jdbc.sql("SELECT COUNT(*) FROM student WHERE route_id = ?")
                .param(targetRouteId)
                .query(Long.class)
                .single();
        assertThat(movedStudents).isEqualTo(3);

        // 改派结果纳入日结且可复算
        SettlementResponse settlement = settlementService.settle(TODAY);
        assertThat(settlement.reassignmentCount()).isEqualTo(3);
        assertThat(settlement.reassignments())
                .allSatisfy(item -> {
                    assertThat(item.fromRouteId()).isEqualTo(suspendedRouteId);
                    assertThat(item.toRouteId()).isEqualTo(targetRouteId);
                    assertThat(item.reason()).isEqualTo("QUALIFICATION_EXPIRED");
                });
        SettlementResponse recomputed = settlementService.settle(TODAY);
        assertThat(recomputed).isEqualTo(settlement);
    }

    @Test
    void 无可改派线路时学生置为待改派且留痕完整() {
        long suspendedRouteId = setupInProgressRoute("D4", TODAY.minusDays(1), 2);

        ExpireResponse response = expiryService.expireQualifications(newRequestId());

        assertThat(response.reassignmentCount()).isEqualTo(2);
        Long unassigned = jdbc.sql("SELECT COUNT(*) FROM student WHERE route_id IS NULL")
                .query(Long.class)
                .single();
        assertThat(unassigned).isEqualTo(2);
        Long nullTarget = jdbc.sql("SELECT COUNT(*) FROM reassignment WHERE to_route_id IS NULL")
                .query(Long.class)
                .single();
        assertThat(nullTarget).isEqualTo(2);
        assertThat(count("notification")).isEqualTo(2);
    }
}
