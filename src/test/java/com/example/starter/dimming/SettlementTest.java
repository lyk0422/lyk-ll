package com.example.starter.dimming;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 日结：输出各回路调光次数与能耗，能耗按留痕分段可复算。
 * 时间线（Asia/Shanghai，时钟起点08:00）：
 * 08:00 档位3→4，10:00 档位4→2，日结 2026-01-01。
 * 能耗 = 3×8h×0.1 + 4×2h×0.1 + 2×14h×0.1 = 2.4 + 0.8 + 2.8 = 6.0 kWh。
 */
class SettlementTest extends BaseIntegrationTest {

    @Test
    void dailySettlementCountsAndEnergyAreRecomputable() throws Exception {
        dim("TUN-A-01", "set-1a", 4, "MANUAL");
        advanceMinutes(120);
        dim("TUN-A-01", "set-1b", 2, "MANUAL");

        MvcResult result = mvc.perform(get("/api/settlement/daily")
                        .param("date", "2026-01-01"))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus());
        var circuits = body(result).get("circuits");
        var circuit = circuits.get(0);
        assertEquals("TUN-A-01", circuit.get("circuitCode").asText());
        assertEquals(2, circuit.get("dimCount").asInt());
        assertEquals(6.0, circuit.get("energyKwh").asDouble(), 1e-9);

        var segments = circuit.get("segments");
        assertEquals(3, segments.size());
        assertEquals(3, segments.get(0).get("level").asInt());
        assertEquals(8.0, segments.get(0).get("hours").asDouble(), 1e-9);
        assertEquals(4, segments.get(1).get("level").asInt());
        assertEquals(2.0, segments.get(1).get("hours").asDouble(), 1e-9);
        assertEquals(2, segments.get(2).get("level").asInt());
        assertEquals(14.0, segments.get(2).get("hours").asDouble(), 1e-9);
        // 可复算：分段能耗之和等于回路能耗
        double sum = 0;
        for (var segment : segments) {
            sum += segment.get("energyKwh").asDouble();
        }
        assertEquals(circuit.get("energyKwh").asDouble(), sum, 1e-6);
    }

    @Test
    void settlementWithoutRecordsUsesInitialLevel() throws Exception {
        MvcResult result = mvc.perform(get("/api/settlement/daily")
                        .param("date", "2026-01-01"))
                .andReturn();
        var circuit = body(result).get("circuits").get(0);
        assertEquals(0, circuit.get("dimCount").asInt());
        // 3档 × 24h × 0.1kW = 7.2 kWh
        assertEquals(7.2, circuit.get("energyKwh").asDouble(), 1e-9);
    }

    @Test
    void accidentDimmingIsIncludedInSettlement() throws Exception {
        lockAccident("TUN-A-01", "set-3", "ALARM-900");
        MvcResult result = mvc.perform(get("/api/settlement/daily")
                        .param("date", "2026-01-01"))
                .andReturn();
        var circuit = body(result).get("circuits").get(0);
        assertEquals(1, circuit.get("dimCount").asInt());
        // 08:00前3档8h + 08:00后5档16h：3×8×0.1 + 5×16×0.1 = 2.4 + 8.0 = 10.4
        assertEquals(10.4, circuit.get("energyKwh").asDouble(), 1e-9);
    }
}
