package com.example.starter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 集成测试基类：独立 H2 内存库，场景间清理业务数据并复位线路状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class BaseIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    /** 合成线路 id（data.sql 初始化）。 */
    protected long lineId;
    /** 分段 id 列表，按顺序号升序。 */
    protected List<Long> segmentIds;

    @BeforeEach
    void cleanBusinessData() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM late_wind_event");
        jdbc.update("DELETE FROM wind_reading");
        jdbc.update("DELETE FROM reopen_confirmation");
        jdbc.update("DELETE FROM reopen_request");
        jdbc.update("DELETE FROM rescue_confirmation");
        jdbc.update("DELETE FROM segment_release");
        jdbc.update("DELETE FROM control_event");
        jdbc.update("UPDATE ski_line SET status = 'NORMAL'");
        lineId = jdbc.queryForObject("SELECT id FROM ski_line WHERE code = 'YH-01'", Long.class);
        segmentIds = jdbc.query("SELECT id FROM segment WHERE line_id = ? ORDER BY seq",
                (rs, n) -> rs.getLong("id"), lineId);
    }

    /** 构造风速上报请求 JSON。 */
    protected String windJson(String requestId, long segmentId, LocalDateTime measuredAt, String speed) {
        return """
                {"requestId":"%s","segmentId":%d,"measuredAt":"%s","speed":%s}
                """.formatted(requestId, segmentId, measuredAt.toString(), speed).trim();
    }

    /** 以固定风速连续上报 minutes 分钟（从 start 起逐分钟），requestId 带序号后缀。 */
    protected void feedWind(long segmentId, LocalDateTime start, int minutes, BigDecimal speed,
                            String requestIdPrefix) throws Exception {
        for (int i = 0; i < minutes; i++) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/lines/{lineId}/wind-readings", lineId)
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(windJson(requestIdPrefix + "-" + i, segmentId,
                                    start.plusMinutes(i), speed.toPlainString())))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        }
    }

    protected long countRows(String table) {
        Long cnt = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return cnt == null ? 0 : cnt;
    }

    protected String lineStatus() {
        return jdbc.queryForObject("SELECT status FROM ski_line WHERE id = ?", String.class, lineId);
    }
}
