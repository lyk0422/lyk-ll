package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.dto.CreateDefectRequest;
import com.example.starter.dto.DefectResponse;
import com.example.starter.dto.PowerLimitView;
import com.example.starter.error.ApiException;
import com.example.starter.model.DefectLevel;
import com.example.starter.service.DefectService;
import com.example.starter.service.PowerLimitService;
import com.example.starter.service.QueryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 缺陷分级与限功率联动测试。
 */
class DefectGradingTest extends BaseIntegrationTest {

    @Autowired
    private DefectService defectService;

    @Autowired
    private QueryService queryService;

    @Test
    void surfaceAndSandwichDefectsDoNotTriggerPowerLimit() {
        DefectResponse surface = defectService.register(new CreateDefectRequest(
                "req-surface-1", 1L, "叶尖前缘", DefectLevel.SURFACE, "SCRATCH", null));
        assertThat(surface.powerLimitActive()).isFalse();

        DefectResponse sandwich = defectService.register(new CreateDefectRequest(
                "req-sandwich-1", 1L, "叶中腹板", DefectLevel.SANDWICH, "DELAMINATION", null));
        assertThat(sandwich.powerLimitActive()).isFalse();

        assertThat(queryService.powerLimits(1L)).isEmpty();
    }

    @Test
    void structuralDefectTriggersPowerLimitImmediately() {
        DefectResponse response = defectService.register(new CreateDefectRequest(
                "req-structural-1", 1L, "叶根螺栓孔", DefectLevel.STRUCTURAL, "CRACK", null));

        assertThat(response.powerLimitActive()).isTrue();
        assertThat(response.assessmentTriggered()).isFalse();

        List<PowerLimitView> chain = queryService.powerLimits(1L);
        assertThat(chain).hasSize(1);
        assertThat(chain.get(0).active()).isTrue();
        assertThat(chain.get(0).reason()).isEqualTo(PowerLimitService.REASON_STRUCTURAL_DEFECT);
        assertThat(chain.get(0).startedAt()).isNotNull();
        assertThat(chain.get(0).revokedAt()).isNull();
    }

    @Test
    void structuralDefectOnOtherBladeDoesNotAffectThisBlade() {
        defectService.register(new CreateDefectRequest(
                "req-structural-b1", 1L, "叶根", DefectLevel.STRUCTURAL, "CRACK", null));

        assertThat(queryService.powerLimits(1L)).hasSize(1);
        assertThat(queryService.powerLimits(2L)).isEmpty();
    }

    @Test
    void registerDefectOnUnknownBladeReturns404() {
        assertThatThrownBy(() -> defectService.register(new CreateDefectRequest(
                "req-unknown-blade", 999L, "叶尖", DefectLevel.SURFACE, "SCRATCH", null)))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void repeatedQueriesAreStable() {
        defectService.register(new CreateDefectRequest(
                "req-structural-stable", 1L, "叶根", DefectLevel.STRUCTURAL, "CRACK", null));

        List<PowerLimitView> first = queryService.powerLimits(1L);
        List<PowerLimitView> second = queryService.powerLimits(1L);
        assertThat(first).isEqualTo(second);
    }
}
