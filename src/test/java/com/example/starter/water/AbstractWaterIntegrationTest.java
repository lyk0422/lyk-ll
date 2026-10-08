package com.example.starter.water;

import java.sql.Timestamp;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 集成测试基类：每个用例前清空全部业务表并重置可控时钟与合成展示池中数据，避免顺序依赖。
 */
public abstract class AbstractWaterIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetState() {
        clock.setInstant(TestClockConfig.BASE_INSTANT);
        jdbcTemplate.update("DELETE FROM idempotency_record");
        jdbcTemplate.update("DELETE FROM exhibit_registration");
        jdbcTemplate.update("DELETE FROM retest_record");
        jdbcTemplate.update("DELETE FROM disposal_order");
        jdbcTemplate.update("DELETE FROM alarm");
        jdbcTemplate.update("DELETE FROM water_sample");
        jdbcTemplate.update("DELETE FROM tank");
        insertTank(1L, "TANK-A", "测试池A");
        insertTank(2L, "TANK-B", "测试池B");
    }

    private void insertTank(long id, String code, String name) {
        jdbcTemplate.update(
                "INSERT INTO tank(id, code, name, status, display_suspended, auto_disposal_suspended,"
                        + " data_missing, consecutive_violations, sensor_status, sensor_last_seen_at, created_at)"
                        + " VALUES (?, ?, ?, 'NORMAL', 0, 0, 0, 0, 'ONLINE', ?, ?)",
                id, code, name,
                Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
    }

    protected ResultActions postSample(long tankId, String requestId, double dissolvedOxygen,
                                       double ammoniaNitrogen, double temperature) throws Exception {
        return mockMvc.perform(post("/api/tanks/{tankId}/samples", tankId)
                .contentType("application/json")
                .content(sampleJson(requestId, dissolvedOxygen, ammoniaNitrogen, temperature)));
    }

    protected String sampleJson(String requestId, double dissolvedOxygen,
                                double ammoniaNitrogen, double temperature) {
        return "{\"requestId\":\"" + requestId + "\","
                + "\"dissolvedOxygen\":" + dissolvedOxygen + ","
                + "\"ammoniaNitrogen\":" + ammoniaNitrogen + ","
                + "\"temperature\":" + temperature + "}";
    }

    protected ResultActions postRetest(long tankId, String requestId, double dissolvedOxygen,
                                       double ammoniaNitrogen, double temperature) throws Exception {
        return mockMvc.perform(post("/api/tanks/{tankId}/retest", tankId)
                .contentType("application/json")
                .content("{\"requestId\":\"" + requestId + "\","
                        + "\"dissolvedOxygen\":" + dissolvedOxygen + ","
                        + "\"ammoniaNitrogen\":" + ammoniaNitrogen + ","
                        + "\"temperature\":" + temperature + "}"));
    }

    protected ResultActions postExhibit(long tankId, String requestId, String exhibitName) throws Exception {
        return mockMvc.perform(post("/api/tanks/{tankId}/exhibits", tankId)
                .contentType("application/json")
                .content("{\"requestId\":\"" + requestId + "\",\"exhibitName\":\"" + exhibitName + "\"}"));
    }

    protected ResultActions postConfirm(long orderId, String requestId) throws Exception {
        return mockMvc.perform(post("/api/disposals/{orderId}/confirm", orderId)
                .contentType("application/json")
                .content("{\"requestId\":\"" + requestId + "\",\"confirmedBy\":\"operator-1\"}"));
    }

    protected ResultActions postHeartbeat(long tankId, String requestId) throws Exception {
        return mockMvc.perform(post("/api/tanks/{tankId}/sensor/heartbeat", tankId)
                .contentType("application/json")
                .content("{\"requestId\":\"" + requestId + "\"}"));
    }

    protected ResultActions postReconnectConfirm(long tankId, String requestId) throws Exception {
        return mockMvc.perform(post("/api/tanks/{tankId}/sensor/reconnect-confirm", tankId)
                .contentType("application/json")
                .content("{\"requestId\":\"" + requestId + "\",\"operator\":\"operator-1\"}"));
    }

    protected ResultActions postSweep() throws Exception {
        return mockMvc.perform(post("/api/maintenance/sweep"));
    }

    protected String tankColumn(long tankId, String column) {
        Object value = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM tank WHERE id = ?", Object.class, tankId);
        return String.valueOf(value);
    }

    protected int countRows(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 使 1 号池进入紧急状态（连续 3 个溶解氧越限采样点），返回自动下发的处置指令 ID。
     */
    protected long driveTankToEmergency(long tankId, String requestPrefix) throws Exception {
        long orderId = -1;
        for (int i = 1; i <= 3; i++) {
            String body = postSample(tankId, requestPrefix + "-" + i, 4.0, 0.3, 22.0)
                    .andReturn().getResponse().getContentAsString();
            if (i == 3) {
                orderId = Long.parseLong(body.replaceAll(".*\"disposalOrderId\":(\\d+).*", "$1"));
            }
        }
        return orderId;
    }
}
