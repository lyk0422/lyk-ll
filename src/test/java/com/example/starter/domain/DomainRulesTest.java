package com.example.starter.domain;

import com.example.starter.repo.ReadingRepository;
import com.example.starter.repo.StrategyRepository;
import com.example.starter.service.ReadingService;
import com.example.starter.support.FingerprintHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 不依赖 Spring/H2 的纯领域规则单测：指令能耗归一化、设定带边界、幂等指纹稳定性。
 */
class DomainRulesTest {

    private StrategyRepository.Strategy strategy(String day, String night, String humidity,
                                                 String tempTol, String humTol) {
        return new StrategyRepository.Strategy(1L, "S", "Z", "C", "ST",
                new BigDecimal(day), new BigDecimal(night), new BigDecimal(humidity),
                new BigDecimal(tempTol), new BigDecimal(humTol),
                "DISPATCHED", null, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.now(), null, Instant.now(), null);
    }

    private ReadingRepository.Reading reading(String temp, String humidity) {
        return new ReadingRepository.Reading(1L, "Z", Instant.parse("2026-01-01T01:00:00Z"),
                new BigDecimal(temp), new BigDecimal(humidity));
    }

    @Test
    @DisplayName("通风零能耗归一化为 0.000，且忽略传入能耗")
    void ventilationIsZeroEnergy() {
        assertThat(CommandType.VENTILATE.normalizeEnergy(new BigDecimal("3.5")))
                .isEqualByComparingTo("0.000");
        assertThat(CommandType.VENTILATE.normalizeEnergy(null)).isEqualByComparingTo("0.000");
    }

    @Test
    @DisplayName("加热/补光必须携带正能耗")
    void energyCommandsRequirePositive() {
        assertThatThrownBy(() -> CommandType.HEAT.normalizeEnergy(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommandType.SUPPLEMENT_LIGHT.normalizeEnergy(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CommandType.HEAT.normalizeEnergy(new BigDecimal("1.25")))
                .isEqualByComparingTo("1.25");
    }

    @Test
    @DisplayName("恰在设定带边界视为带内；超出任意一条带（温/湿）即为偏离")
    void bandBoundaryIsInBand() {
        var s = strategy("24.00", "18.00", "70.00", "1.00", "5.00");
        // 白天带上界恰 25.0、夜间带恰命中，均为带内。
        assertThat(ReadingService.isOutOfBand(s, reading("25.00", "75.00"))).isFalse();
        assertThat(ReadingService.isOutOfBand(s, reading("18.00", "65.00"))).isFalse();
        // 温度越带 0.01、湿度越带 0.01，均判偏离。
        assertThat(ReadingService.isOutOfBand(s, reading("25.01", "70"))).isTrue();
        assertThat(ReadingService.isOutOfBand(s, reading("24.00", "75.01"))).isTrue();
    }

    @Test
    @DisplayName("幂等指纹：同键同参稳定一致；requestId 或任一字段变化指纹即不同")
    void fingerprintCoversRequestIdAndAllFields() {
        ObjectMapper mapper = JsonMapper.builder().build();
        FingerprintHasher hasher = new FingerprintHasher(mapper);
        record Payload(String zone, String command, BigDecimal kwh) {
        }
        Payload p1 = new Payload("Z1", "HEAT", new BigDecimal("2.50"));
        Payload p1Copy = new Payload("Z1", "HEAT", new BigDecimal("2.50"));
        Payload p2 = new Payload("Z1", "HEAT", new BigDecimal("2.51"));

        String h1 = hasher.hash("REQ-1", "CONTROL_COMMAND", p1);
        assertThat(h1).isEqualTo(hasher.hash("REQ-1", "CONTROL_COMMAND", p1Copy));
        assertThat(h1).isNotEqualTo(hasher.hash("REQ-2", "CONTROL_COMMAND", p1));
        assertThat(h1).isNotEqualTo(hasher.hash("REQ-1", "CONTROL_COMMAND", p2));
        assertThat(h1).isNotEqualTo(hasher.hash("REQ-1", "ALARM_ACK", p1));
    }
}
