package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.dto.AlertView;
import com.example.starter.dto.CreateDefectRequest;
import com.example.starter.dto.CreateRepairOrderRequest;
import com.example.starter.dto.CreateReinspectionRequest;
import com.example.starter.dto.DefectResponse;
import com.example.starter.dto.EscalateResponse;
import com.example.starter.dto.ReinspectionResponse;
import com.example.starter.dto.RepairOrderView;
import com.example.starter.error.ApiException;
import com.example.starter.model.DefectLevel;
import com.example.starter.model.ReinspectionResult;
import com.example.starter.service.DefectService;
import com.example.starter.service.QueryService;
import com.example.starter.service.RepairService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 维修闭环测试：恰第 7 日闭环有效、逾期升级保留首次登记时刻、复检不合格维持限功率。
 */
class RepairClosureTest extends BaseIntegrationTest {

    @Autowired
    private DefectService defectService;

    @Autowired
    private RepairService repairService;

    @Autowired
    private QueryService queryService;

    private String rid() {
        return UUID.randomUUID().toString();
    }

    private RepairOrderView newStructuralOrder(long bladeId) {
        DefectResponse defect = defectService.register(new CreateDefectRequest(
                rid(), bladeId, "叶根", DefectLevel.STRUCTURAL, "CRACK", null));
        return repairService.createOrder(new CreateRepairOrderRequest(rid(), defect.id()));
    }

    @Test
    void closureExactlyOnDaySevenIsOnTime() {
        RepairOrderView order = newStructuralOrder(1L);
        assertThat(order.deadlineAt())
                .isEqualTo(LocalDateTime.of(2026, 1, 8, 0, 0, 0));

        // 恰第 7 日（deadline 时刻）复检合格闭环
        clock.advance(Duration.ofDays(7));
        ReinspectionResponse response = repairService.reinspect(
                order.id(), new CreateReinspectionRequest(rid(), ReinspectionResult.PASS));

        assertThat(response.orderStatus()).isEqualTo("CLOSED");
        assertThat(response.powerLimitActive()).isFalse();

        RepairOrderView closed = queryService.repairOrders(1L).get(0);
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.closeResult()).isEqualTo("ON_TIME");
        assertThat(closed.closedAt()).isEqualTo(order.deadlineAt());

        // 限功率已撤销，链条保留完整记录
        assertThat(queryService.powerLimits(1L)).hasSize(1);
        assertThat(queryService.powerLimits(1L).get(0).active()).isFalse();
        assertThat(queryService.powerLimits(1L).get(0).revokedAt()).isEqualTo(order.deadlineAt());
    }

    @Test
    void closureOneSecondAfterDeadlineIsLate() {
        RepairOrderView order = newStructuralOrder(1L);

        clock.advance(Duration.ofDays(7).plusSeconds(1));
        ReinspectionResponse response = repairService.reinspect(
                order.id(), new CreateReinspectionRequest(rid(), ReinspectionResult.PASS));

        assertThat(response.orderStatus()).isEqualTo("CLOSED");
        RepairOrderView closed = queryService.repairOrders(1L).get(0);
        assertThat(closed.closeResult()).isEqualTo("LATE");
    }

    @Test
    void failedReinspectionKeepsOrderOpenAndPowerLimit() {
        RepairOrderView order = newStructuralOrder(1L);

        clock.advance(Duration.ofDays(2));
        ReinspectionResponse fail = repairService.reinspect(
                order.id(), new CreateReinspectionRequest(rid(), ReinspectionResult.FAIL));

        assertThat(fail.orderStatus()).isEqualTo("IN_PROGRESS");
        assertThat(fail.powerLimitActive()).isTrue();

        RepairOrderView stillOpen = queryService.repairOrders(1L).get(0);
        assertThat(stillOpen.status()).isEqualTo("IN_PROGRESS");
        assertThat(stillOpen.closedAt()).isNull();
        assertThat(queryService.powerLimits(1L).get(0).active()).isTrue();

        // 维修后再次复检合格方可闭环并撤销限功率
        clock.advance(Duration.ofDays(1));
        ReinspectionResponse pass = repairService.reinspect(
                order.id(), new CreateReinspectionRequest(rid(), ReinspectionResult.PASS));
        assertThat(pass.orderStatus()).isEqualTo("CLOSED");
        assertThat(pass.powerLimitActive()).isFalse();
    }

    @Test
    void closedOrderRejectsFurtherReinspection() {
        RepairOrderView order = newStructuralOrder(1L);
        repairService.reinspect(order.id(), new CreateReinspectionRequest(rid(), ReinspectionResult.PASS));

        assertThatThrownBy(() -> repairService.reinspect(
                order.id(), new CreateReinspectionRequest(rid(), ReinspectionResult.PASS)))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void overdueEscalationKeepsFirstDetectedAtAndIsIdempotent() {
        RepairOrderView order = newStructuralOrder(1L);
        LocalDateTime firstDetectedAt = order.deadlineAt().minusDays(7);

        // 恰第 7 日扫描：未逾期，不产生告警
        clock.advance(Duration.ofDays(7));
        EscalateResponse onDeadline = repairService.escalateOverdue();
        assertThat(onDeadline.escalatedCount()).isZero();

        // 超过期限后扫描：升级并告警
        clock.advance(Duration.ofSeconds(1));
        EscalateResponse escalated = repairService.escalateOverdue();
        assertThat(escalated.escalatedCount()).isEqualTo(1);
        AlertView alert = escalated.alerts().get(0);
        assertThat(alert.repairOrderId()).isEqualTo(order.id());
        assertThat(alert.firstDetectedAt()).isEqualTo(firstDetectedAt);

        // 重复扫描幂等：不重复告警
        EscalateResponse again = repairService.escalateOverdue();
        assertThat(again.escalatedCount()).isZero();
        assertThat(queryService.alerts(1L)).hasSize(1);

        // 维修记录不覆盖原缺陷：首次登记时刻保持不变
        LocalDateTime detectedAt = jdbc.queryForObject(
                "SELECT detected_at FROM defect_record WHERE id = ?",
                LocalDateTime.class, order.defectId());
        assertThat(detectedAt).isEqualTo(firstDetectedAt);

        // 逾期升级后限功率仍维持，维修单标记已升级
        assertThat(queryService.powerLimits(1L).get(0).active()).isTrue();
        assertThat(queryService.repairOrders(1L).get(0).escalated()).isTrue();
    }

    @Test
    void repairLedgerIsStableAcrossQueries() {
        newStructuralOrder(1L);
        List<RepairOrderView> first = queryService.repairOrders(1L);
        List<RepairOrderView> second = queryService.repairOrders(1L);
        assertThat(first).isEqualTo(second);
    }
}
