package com.example.starter.gas;

import com.example.starter.gas.service.GasStationService;
import com.example.starter.gas.web.dto.ConcentrationReplayResponse;
import com.example.starter.gas.web.dto.DetectionRequest;
import com.example.starter.gas.web.dto.LinkageRequest;
import com.example.starter.gas.web.dto.PressureChainReplayResponse;
import com.example.starter.gas.web.dto.ValveCommandRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 浓度曲线与压力链复算：从落库序列重放判定，核对锁阀点、复开清零与恰等限值。
 */
@Import(FixedClockConfig.class)
class ReplayTest extends AbstractGasIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);

    @Autowired
    private GasStationService service;

    @Test
    void concentrationReplayReproducesBoundsAndLockPoint() {
        service.detect(new DetectionRequest("R-1", STATION, DAY.atTime(8, 0),
                new BigDecimal("24.00"), new BigDecimal("100"), null));
        service.detect(new DetectionRequest("R-2", STATION, DAY.atTime(8, 5),
                new BigDecimal("24.01"), new BigDecimal("100"), null));
        service.detect(new DetectionRequest("R-3", STATION, DAY.atTime(8, 10),
                new BigDecimal("25"), new BigDecimal("100"), null));

        ConcentrationReplayResponse replay = service.replayConcentration(STATION, DAY);
        assertThat(replay.lowerMg()).isEqualByComparingTo("16");
        assertThat(replay.upperMg()).isEqualByComparingTo("24");
        assertThat(replay.points()).hasSize(3);
        // 恰上边界合格，后两点越限，第 3 点恰为连续第 2 次越限 -> 锁阀点
        assertThat(replay.points().get(0).inRange()).isTrue();
        assertThat(replay.points().get(1).runningViolations()).isEqualTo(1);
        assertThat(replay.points().get(1).lockPoint()).isFalse();
        assertThat(replay.points().get(2).runningViolations()).isEqualTo(2);
        assertThat(replay.points().get(2).lockPoint()).isTrue();
        assertThat(replay.lockPointCount()).isEqualTo(1);
    }

    @Test
    void concentrationReplayResetsAtReopen() {
        service.detect(new DetectionRequest("R-1", STATION, DAY.atTime(8, 0),
                new BigDecimal("25"), new BigDecimal("100"), null));
        service.detect(new DetectionRequest("R-2", STATION, DAY.atTime(8, 5),
                new BigDecimal("25"), new BigDecimal("100"), null));
        service.valveCommand(new ValveCommandRequest("R-V", STATION, "REOPEN",
                "alice", "bob", DAY.atTime(8, 20)));
        // 复开后再一次越限只计 1，不构成新的锁阀点
        service.detect(new DetectionRequest("R-3", STATION, DAY.atTime(8, 30),
                new BigDecimal("25"), new BigDecimal("100"), null));

        ConcentrationReplayResponse replay = service.replayConcentration(STATION, DAY);
        assertThat(replay.points()).hasSize(3);
        assertThat(replay.points().get(1).lockPoint()).isTrue();
        assertThat(replay.points().get(2).runningViolations()).isEqualTo(1);
        assertThat(replay.points().get(2).lockPoint()).isFalse();
    }

    @Test
    void pressureReplayReproducesInclusiveLimitActions() {
        service.regulate(new LinkageRequest("P-1", STATION, DAY.atTime(9, 0),
                new BigDecimal("199.99"), new BigDecimal("100"), null));
        service.regulate(new LinkageRequest("P-2", STATION, DAY.atTime(9, 5),
                new BigDecimal("200.00"), new BigDecimal("100"), null));
        service.regulate(new LinkageRequest("P-3", STATION, DAY.atTime(9, 10),
                new BigDecimal("400.00"), new BigDecimal("100"), null));
        service.regulate(new LinkageRequest("P-4", STATION, DAY.atTime(9, 15),
                new BigDecimal("400.01"), new BigDecimal("100"), null));

        PressureChainReplayResponse replay = service.replayPressure(STATION, DAY);
        assertThat(replay.lowKpa()).isEqualByComparingTo("200");
        assertThat(replay.highKpa()).isEqualByComparingTo("400");
        assertThat(replay.points()).extracting(p -> p.pressureAction())
                .containsExactly("RAISE", "HOLD", "HOLD", "LOWER");
        assertThat(replay.regulatedCount()).isEqualTo(2);
    }
}
