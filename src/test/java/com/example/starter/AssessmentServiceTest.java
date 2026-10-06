package com.example.starter;

import com.example.starter.error.BusinessException;
import com.example.starter.service.AssessmentService;
import com.example.starter.service.RouteService;
import com.example.starter.web.dto.RouteDtos.AssessmentResponse;
import com.example.starter.web.dto.RouteDtos.CreateRouteRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 安全评估规则测试：站点间距、道路等级、座位数逐项校验，恰等限值通过。
 */
class AssessmentServiceTest extends BaseIntegrationTest {

    @Autowired
    private AssessmentService assessmentService;

    @Autowired
    private RouteService routeService;

    private long createRoute(int spacingM, int roadGrade, int requiredSeats, int seatCapacity) {
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        long vehicleId = insertVehicle("PLATE-" + suffix, seatCapacity);
        return routeService.createRoute(new CreateRouteRequest(newRequestId(),
                "RC-" + suffix, "合成线路", vehicleId, spacingM, roadGrade, requiredSeats))
                .id();
    }

    @Test
    void 恰等限值时评估通过并生成一年期资格() {
        long routeId = createRoute(AssessmentService.MIN_SPACING_M,
                AssessmentService.MAX_ROAD_GRADE, 40, 40);

        AssessmentResponse response = assessmentService.assess(routeId, newRequestId());

        assertThat(response.result()).isEqualTo("PASSED");
        assertThat(response.spacingOk()).isTrue();
        assertThat(response.roadOk()).isTrue();
        assertThat(response.seatsOk()).isTrue();
        assertThat(response.qualificationId()).isNotNull();
        assertThat(response.validFrom()).isEqualTo(TODAY);
        assertThat(response.validTo()).isEqualTo(TODAY.plusYears(1).minusDays(1));
        assertThat(routeService.getRoute(routeId).status()).isEqualTo("QUALIFIED");
    }

    @Test
    void 站点间距恰等上限通过_低于下限拒绝() {
        long okRoute = createRoute(AssessmentService.MAX_SPACING_M, 2, 30, 30);
        assertThat(assessmentService.assess(okRoute, newRequestId()).result()).isEqualTo("PASSED");

        long badRoute = createRoute(AssessmentService.MIN_SPACING_M - 1, 2, 30, 30);
        AssessmentResponse rejected = assessmentService.assess(badRoute, newRequestId());
        assertThat(rejected.result()).isEqualTo("REJECTED");
        assertThat(rejected.spacingOk()).isFalse();
        assertThat(rejected.qualificationId()).isNull();
        assertThat(routeService.getRoute(badRoute).status()).isEqualTo("APPLIED");
    }

    @Test
    void 站点间距高于上限拒绝() {
        long routeId = createRoute(AssessmentService.MAX_SPACING_M + 1, 2, 30, 30);
        AssessmentResponse response = assessmentService.assess(routeId, newRequestId());
        assertThat(response.result()).isEqualTo("REJECTED");
        assertThat(response.spacingOk()).isFalse();
    }

    @Test
    void 道路等级恰等限值通过_超过限值拒绝() {
        long badRoute = createRoute(800, AssessmentService.MAX_ROAD_GRADE + 1, 30, 30);
        AssessmentResponse response = assessmentService.assess(badRoute, newRequestId());
        assertThat(response.result()).isEqualTo("REJECTED");
        assertThat(response.roadOk()).isFalse();
        assertThat(response.rejectReason()).contains("道路等级");
    }

    @Test
    void 座位数恰等需求通过_不足拒绝() {
        long badRoute = createRoute(800, 2, 31, 30);
        AssessmentResponse response = assessmentService.assess(badRoute, newRequestId());
        assertThat(response.result()).isEqualTo("REJECTED");
        assertThat(response.seatsOk()).isFalse();
        assertThat(response.rejectReason()).contains("座位数");
    }

    @Test
    void 同线路同日期重复评估拒绝() {
        long routeId = createRoute(800, 2, 30, 30);
        assessmentService.assess(routeId, newRequestId());

        assertThatThrownBy(() -> assessmentService.assess(routeId, newRequestId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("ASSESSMENT_DUPLICATE");
                });
        assertThat(count("safety_assessment")).isEqualTo(1);
    }
}
