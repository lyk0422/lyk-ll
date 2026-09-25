package com.example.starter.hazmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.example.starter.hazmat.api.ApiException;
import com.example.starter.hazmat.domain.ShipmentStatus;
import com.example.starter.hazmat.service.ServiceResult;

/**
 * 主流程与路线核准：类别/等级匹配、禁行路线拒绝、管制时段边界、单车唯一在途。
 */
class LifecycleAndApprovalTest extends HazmatIntegrationTestBase {

    private static final LocalDateTime DEPARTURE = LocalDateTime.parse("2026-10-02T08:00:00");

    @Test
    void happyFlowApplyApproveDispatch() {
        ServiceResult applied = apply("req-1", "沪A-1001", "FLAMMABLE", "R-G2", DEPARTURE);
        String no = shipmentNo(applied);
        assertThat(status(applied)).isEqualTo("APPLIED");

        assertThat(status(approve(no))).isEqualTo("ROUTE_APPROVED");
        assertThat(status(dispatch(no, "disp-1"))).isEqualTo("DISPATCHED");

        // 首个途经点打卡后进入在途。
        ServiceResult checkin = checkin(no, "ck-0", 0, DEPARTURE.plusMinutes(35));
        assertThat(status(checkin)).isEqualTo("IN_TRANSIT");
        assertThat(waypoint(no, 0).get("state")).isEqualTo("CHECKED");
    }

    @Test
    void highLevelCategoryRejectedOnLowerLevelRoute() {
        // EXPLOSIVE 要求等级 3，R-G2 等级 2 → 系统驳回 422，运单终态 ROUTE_REJECTED。
        String no = shipmentNo(apply("req-lvl", "沪A-1001", "EXPLOSIVE", "R-G2", DEPARTURE));
        ServiceResult result = approve(no);
        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(result.body().get("errorCode")).isEqualTo("ROUTE_REJECTED");
        assertThat(view(no).get("status")).isEqualTo(ShipmentStatus.ROUTE_REJECTED.name());
        // 驳回释放占用：同车可再次申请。
        assertThat(apply("req-lvl2", "沪A-1001", "EXPLOSIVE", "R-G3", DEPARTURE).httpStatus()).isEqualTo(200);
    }

    @Test
    void forbiddenRouteRejected() {
        String no = shipmentNo(apply("req-ban", "沪A-1001", "TOXIC", "R-BAN", DEPARTURE));
        ServiceResult result = approve(no);
        assertThat(result.httpStatus()).isEqualTo(422);
        assertThat(result.body().get("errorCode")).isEqualTo("ROUTE_REJECTED");
        assertThat(view(no).get("status")).isEqualTo("ROUTE_REJECTED");
    }

    @Test
    void controlPeriodBoundaryOnApproval() {
        // 发运 07:30 → p0 计划 08:00 恰为管制起始（受管制）→ 驳回。
        LocalDateTime atStart = LocalDateTime.parse("2026-10-01T07:30:00");
        String no1 = shipmentNo(apply("req-ctl-1", "沪A-1001", "TOXIC", "R-G2", atStart));
        assertThat(approve(no1).httpStatus()).isEqualTo(422);
        assertThat(view(no1).get("status")).isEqualTo("ROUTE_REJECTED");

        // 发运 09:30 → p0 计划 10:00 恰为管制结束（不受管制）→ 核准通过。
        LocalDateTime atEnd = LocalDateTime.parse("2026-10-01T09:30:00");
        String no2 = shipmentNo(apply("req-ctl-2", "沪A-1002", "TOXIC", "R-G2", atEnd));
        assertThat(status(approve(no2))).isEqualTo("ROUTE_APPROVED");
    }

    @Test
    void oneVehicleOnlyOneActiveShipment() {
        apply("req-busy-1", "沪B-2001", "TOXIC", "R-G1", DEPARTURE);
        assertThatThrownBy(() -> apply("req-busy-2", "沪B-2001", "TOXIC", "R-G1", DEPARTURE))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("VEHICLE_BUSY"));
        // 另一辆车不受影响。
        assertThat(apply("req-busy-3", "沪A-1002", "TOXIC", "R-G1", DEPARTURE).httpStatus()).isEqualTo(200);
    }

    @Test
    void frozenVehicleCannotApply() {
        String no = shipmentNo(apply("req-frz", "沪A-1001", "TOXIC", "R-G1", DEPARTURE));
        repository.freezeVehicle("沪A-1001", 999L, LocalDateTime.now());
        assertThatThrownBy(() -> apply("req-frz-2", "沪A-1001", "TOXIC", "R-G1", DEPARTURE))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("VEHICLE_FROZEN"));
        assertThat(repository.findShipmentByNo(no)).isPresent();
    }
}
