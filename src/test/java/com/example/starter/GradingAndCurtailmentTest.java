package com.example.starter;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

import com.example.starter.service.DefectService;
import com.example.starter.service.QueryService;
import com.example.starter.web.dto.ApiDtos.BladeStatusResponse;
import com.example.starter.web.dto.ApiDtos.DefectResponse;
import com.example.starter.web.dto.ApiDtos.RegisterDefectRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 合成分级与结构级缺陷立即限功率。
 */
class GradingAndCurtailmentTest extends BaseIntegrationTest {

    @Autowired
    private DefectService defectService;

    @Autowired
    private QueryService queryService;

    private final AtomicLong requestSeq = new AtomicLong();

    private DefectResponse register(long bladeId, String type, String depthRatio) {
        return defectService.registerDefect(new RegisterDefectRequest(
                "req-grade-" + requestSeq.incrementAndGet(), null, bladeId, "叶中10-15m",
                type, new BigDecimal("120.00"), new BigDecimal(depthRatio)));
    }

    @Test
    void 按合成分级表定级_表面_夹层_结构() {
        long blade = bladeId("BLADE-A01");
        assertThat(register(blade, "CRACK", "0.05").level()).isEqualTo("SURFACE");
        assertThat(register(blade, "CRACK", "0.20").level()).isEqualTo("SANDWICH");
        assertThat(register(blade, "CRACK", "0.50").level()).isEqualTo("STRUCTURAL");
        // 边界值：恰等于上限归入该级
        assertThat(register(blade, "CRACK", "0.10").level()).isEqualTo("SURFACE");
        assertThat(register(blade, "CRACK", "0.30").level()).isEqualTo("SANDWICH");
        // 未配置类型走通配规则
        assertThat(register(blade, "PAINT", "0.05").level()).isEqualTo("SURFACE");
        assertThat(register(blade, "PAINT", "0.90").level()).isEqualTo("STRUCTURAL");
    }

    @Test
    void 结构级缺陷登记后立即限功率() {
        long blade = bladeId("BLADE-A01");
        DefectResponse defect = register(blade, "CRACK", "0.60");

        assertThat(defect.curtailmentId()).isNotNull();
        BladeStatusResponse status = queryService.bladeStatus(blade);
        assertThat(status.curtailed()).isTrue();
        assertThat(status.activeCurtailments())
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.reason()).isEqualTo("STRUCTURAL_DEFECT");
                    assertThat(c.sourceDefectId()).isEqualTo(defect.id());
                    assertThat(c.liftedAt()).isNull();
                });
    }

    @Test
    void 表面与夹层缺陷不触发限功率() {
        long blade = bladeId("BLADE-A02");
        // 使用不同缺陷类型，避免同类 90 日内复发触发根因评估限功率
        DefectResponse surface = register(blade, "EROSION", "0.10");
        DefectResponse sandwich = register(blade, "DELAMINATION", "0.10");

        assertThat(surface.level()).isEqualTo("SURFACE");
        assertThat(sandwich.level()).isEqualTo("SANDWICH");
        assertThat(surface.curtailmentId()).isNull();
        assertThat(sandwich.curtailmentId()).isNull();
        assertThat(queryService.bladeStatus(blade).curtailed()).isFalse();
    }
}
