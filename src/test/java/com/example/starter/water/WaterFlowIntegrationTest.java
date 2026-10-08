package com.example.starter.water;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 水质监测主流程集成测试（H2 MODE=MySQL）：阈值告警、恰 3 点升级、回执超时、
 * 复测事务、传感器失联与复连、日评估复算。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:waterquality_flow;"
        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WaterFlowIntegrationTest extends AbstractWaterIntegrationTest {

    @AfterAll
    void releaseDatabase() {
        jdbcTemplate.execute("SHUTDOWN");
    }

    @Test
    void exactlyAtLimitSampleIsNormal() throws Exception {
        postSample(1L, "r-limit", 5.0, 0.5, 18.0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.violating").value(false))
                .andExpect(jsonPath("$.consecutiveViolations").value(0))
                .andExpect(jsonPath("$.alarmIds").isEmpty());
        assertEquals(0, countRows("alarm"));
        assertEquals("NORMAL", tankColumn(1L, "status"));
    }

    @Test
    void consecutiveCounterResetsOnNormalSample() throws Exception {
        postSample(1L, "r1", 4.0, 0.3, 22.0).andExpect(jsonPath("$.consecutiveViolations").value(1));
        postSample(1L, "r2", 6.0, 0.3, 22.0).andExpect(jsonPath("$.consecutiveViolations").value(0));
        postSample(1L, "r3", 4.0, 0.3, 22.0).andExpect(jsonPath("$.consecutiveViolations").value(1));
        postSample(1L, "r4", 4.0, 0.3, 22.0)
                .andExpect(jsonPath("$.consecutiveViolations").value(2))
                .andExpect(jsonPath("$.tankStatus").value("NORMAL"));
        assertEquals(0, countRows("disposal_order"));
        assertEquals(3, countRows("alarm"));
    }

    @Test
    void exactlyThreeViolationsTriggerEmergencyAndAutoDisposal() throws Exception {
        postSample(1L, "r1", 4.0, 0.3, 22.0).andExpect(jsonPath("$.tankStatus").value("NORMAL"));
        postSample(1L, "r2", 4.0, 0.3, 22.0).andExpect(jsonPath("$.tankStatus").value("NORMAL"));
        String body = postSample(1L, "r3", 4.0, 0.3, 22.0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tankStatus").value("EMERGENCY"))
                .andExpect(jsonPath("$.consecutiveViolations").value(3))
                .andExpect(jsonPath("$.alarmIds.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        long orderId = Long.parseLong(body.replaceAll(".*\"disposalOrderId\":(\\d+).*", "$1"));
        assertEquals("EMERGENCY", tankColumn(1L, "status"));
        assertEquals("1", tankColumn(1L, "display_suspended"));

        assertEquals("OXYGENATE", jdbcTemplate.queryForObject(
                "SELECT type FROM disposal_order WHERE id = ?", String.class, orderId));
        Timestamp deadline = jdbcTemplate.queryForObject(
                "SELECT confirm_deadline FROM disposal_order WHERE id = ?", Timestamp.class, orderId);
        assertEquals(TestClockConfig.BASE_INSTANT.plus(Duration.ofMinutes(5)), deadline.toInstant());

        postExhibit(1L, "ex-1", "海龟特展").andExpect(status().isConflict());
        assertEquals(0, countRows("exhibit_registration"));
    }

    @Test
    void ammoniaViolationTriggersWaterChangeOrder() throws Exception {
        long orderId = driveTankToEmergencyWith(2L, "nh3", 6.0, 0.6, 22.0);
        assertEquals("WATER_CHANGE", jdbcTemplate.queryForObject(
                "SELECT type FROM disposal_order WHERE id = ?", String.class, orderId));
    }

    @Test
    void confirmWithinDeadlineSucceeds() throws Exception {
        long orderId = driveTankToEmergency(1L, "c1");
        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        postConfirm(orderId, "c1-confirm")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertEquals("CONFIRMED", jdbcTemplate.queryForObject(
                "SELECT status FROM disposal_order WHERE id = ?", String.class, orderId));
    }

    @Test
    void confirmAtExactlyDeadlineEscalates() throws Exception {
        long orderId = driveTankToEmergency(1L, "c2");
        clock.advance(Duration.ofMinutes(5));
        postConfirm(orderId, "c2-confirm").andExpect(status().isConflict());

        assertEquals("ESCALATED", jdbcTemplate.queryForObject(
                "SELECT status FROM disposal_order WHERE id = ?", String.class, orderId));
        Timestamp escalatedAt = jdbcTemplate.queryForObject(
                "SELECT escalated_at FROM disposal_order WHERE id = ?", Timestamp.class, orderId);
        assertEquals(TestClockConfig.BASE_INSTANT.plus(Duration.ofMinutes(5)), escalatedAt.toInstant());

        List<String> followUps = jdbcTemplate.queryForList(
                "SELECT type FROM disposal_order WHERE tank_id = 1 AND status = 'PENDING'", String.class);
        assertEquals(List.of("ISOLATE"), followUps);
    }

    @Test
    void sweepEscalatesOnlyAtExactlyFiveMinutes() throws Exception {
        long orderId = driveTankToEmergency(1L, "s1");
        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        postSweep().andExpect(status().isOk())
                .andExpect(jsonPath("$.escalatedOrderIds").isEmpty());

        clock.advance(Duration.ofSeconds(1));
        postSweep().andExpect(status().isOk())
                .andExpect(jsonPath("$.escalatedOrderIds[0]").value(orderId))
                .andExpect(jsonPath("$.followUpOrderIds.length()").value(1));
        assertEquals("ESCALATED", jdbcTemplate.queryForObject(
                "SELECT status FROM disposal_order WHERE id = ?", String.class, orderId));
    }

    @Test
    void retestPassRestoresTankAndResolvesAlarmsInSameTransaction() throws Exception {
        driveTankToEmergency(1L, "t1");
        postRetest(1L, "t1-retest", 6.0, 0.3, 22.0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.tankStatus").value("NORMAL"))
                .andExpect(jsonPath("$.resolvedAlarms").value(4));

        assertEquals("NORMAL", tankColumn(1L, "status"));
        assertEquals("0", tankColumn(1L, "display_suspended"));
        assertEquals("0", tankColumn(1L, "consecutive_violations"));
        assertEquals(0, countRows("alarm") - countRowsWhere("alarm", "status = 'RESOLVED'"));
        assertEquals(1, countRowsWhere("retest_record", "passed = 1"));
        postExhibit(1L, "t1-ex", "海龟特展").andExpect(status().isOk());
    }

    @Test
    void retestFailKeepsEmergency() throws Exception {
        driveTankToEmergency(1L, "t2");
        postRetest(1L, "t2-retest", 4.0, 0.3, 22.0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.tankStatus").value("EMERGENCY"));
        assertEquals("EMERGENCY", tankColumn(1L, "status"));
        assertEquals("1", tankColumn(1L, "display_suspended"));
        assertEquals(4, countRowsWhere("alarm", "status = 'ACTIVE'"));
    }

    @Test
    void retestOnNormalTankRejectedAndNoRecordWritten() throws Exception {
        postRetest(2L, "t3-retest", 6.0, 0.3, 22.0).andExpect(status().isConflict());
        assertEquals(0, countRows("retest_record"));
    }

    @Test
    void sensorLostAtExactlyTenMinutes() throws Exception {
        postHeartbeat(1L, "h1").andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(9).plusSeconds(59));
        postSweep().andExpect(jsonPath("$.lostSensorTankIds").isEmpty());
        assertEquals("ONLINE", tankColumn(1L, "sensor_status"));

        clock.advance(Duration.ofSeconds(1));
        postSweep().andExpect(status().isOk())
                .andExpect(jsonPath("$.lostSensorTankIds[0]").value(1));
        assertEquals("LOST", tankColumn(1L, "sensor_status"));
        assertEquals("1", tankColumn(1L, "data_missing"));
        assertEquals("1", tankColumn(1L, "auto_disposal_suspended"));
    }

    @Test
    void emergencyDuringSensorLossSuspendsAutoDisposal() throws Exception {
        postHeartbeat(1L, "h1").andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(10));
        postSweep().andExpect(status().isOk());

        postSample(1L, "d1", 4.0, 0.3, 22.0).andExpect(status().isOk());
        postSample(1L, "d2", 4.0, 0.3, 22.0).andExpect(status().isOk());
        postSample(1L, "d3", 4.0, 0.3, 22.0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tankStatus").value("EMERGENCY"))
                .andExpect(jsonPath("$.autoDisposalSuspended").value(true))
                .andExpect(jsonPath("$.disposalOrderId").doesNotExist());

        assertEquals(0, countRows("disposal_order"));
        assertEquals(4, countRows("alarm"));
        postExhibit(1L, "d-ex", "海龟特展").andExpect(status().isConflict());
    }

    @Test
    void reconnectRequiresManualConfirmAndBackfillsDisposal() throws Exception {
        postHeartbeat(1L, "h1").andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(10));
        postSweep().andExpect(status().isOk());
        postSample(1L, "d1", 4.0, 0.3, 22.0).andExpect(status().isOk());
        postSample(1L, "d2", 4.0, 0.3, 22.0).andExpect(status().isOk());
        postSample(1L, "d3", 4.0, 0.3, 22.0).andExpect(status().isOk());

        postHeartbeat(1L, "h2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sensorStatus").value("AWAITING_CONFIRM"));
        assertEquals("1", tankColumn(1L, "auto_disposal_suspended"));

        postReconnectConfirm(1L, "rc1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sensorStatus").value("ONLINE"))
                .andExpect(jsonPath("$.backfilledOrderId").isNumber());
        assertEquals("0", tankColumn(1L, "auto_disposal_suspended"));
        assertEquals("0", tankColumn(1L, "data_missing"));
        // 原告警链保留：失联期间告警仍生效，补算仅追加处置指令。
        assertEquals(4, countRowsWhere("alarm", "status = 'ACTIVE'"));
        assertEquals(1, countRows("disposal_order"));

        postReconnectConfirm(1L, "rc2").andExpect(status().isConflict());
    }

    @Test
    void dailyAssessmentOutputsStableDurationAndAlarmChain() throws Exception {
        long orderId = driveTankToEmergency(1L, "a1");
        clock.advance(Duration.ofHours(2));
        postRetest(1L, "a1-retest", 6.0, 0.3, 22.0).andExpect(status().isOk());
        clock.setInstant(Instant.parse("2026-10-08T16:00:00Z"));

        String body = mockMvc.perform(get("/api/assessment/daily").param("date", "2026-10-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-10-08"))
                .andReturn().getResponse().getContentAsString();

        com.jayway.jsonpath.DocumentContext json = com.jayway.jsonpath.JsonPath.parse(body);
        List<Object> tank1Stable = json.read("$.tanks[?(@.tankId == 1)].stableSeconds");
        List<Object> tank2Stable = json.read("$.tanks[?(@.tankId == 2)].stableSeconds");
        assertEquals(79200L, ((Number) tank1Stable.get(0)).longValue());
        assertEquals(86400L, ((Number) tank2Stable.get(0)).longValue());

        List<Integer> alarmCount = json.read("$.tanks[?(@.tankId == 1)].alarms.length()");
        assertEquals(4, alarmCount.get(0));
        List<Object> emergencyOrders = json.read(
                "$.tanks[?(@.tankId == 1)].alarms[?(@.level == 'EMERGENCY')].orders.length()");
        assertEquals(1, emergencyOrders.get(0));
        List<Object> orderTypes = json.read(
                "$.tanks[?(@.tankId == 1)].alarms[?(@.level == 'EMERGENCY')].orders[0].type");
        assertEquals("OXYGENATE", orderTypes.get(0));

        // 可复算：重复计算结果一致。
        String recomputed = mockMvc.perform(get("/api/assessment/daily").param("date", "2026-10-08"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(body, recomputed);
    }

    private long driveTankToEmergencyWith(long tankId, String requestPrefix, double dissolvedOxygen,
                                          double ammoniaNitrogen, double temperature) throws Exception {
        long orderId = -1;
        for (int i = 1; i <= 3; i++) {
            String body = postSample(tankId, requestPrefix + "-" + i, dissolvedOxygen,
                    ammoniaNitrogen, temperature).andReturn().getResponse().getContentAsString();
            if (i == 3) {
                orderId = Long.parseLong(body.replaceAll(".*\"disposalOrderId\":(\\d+).*", "$1"));
            }
        }
        return orderId;
    }

    private int countRowsWhere(String table, String where) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class);
        return count == null ? 0 : count;
    }
}
