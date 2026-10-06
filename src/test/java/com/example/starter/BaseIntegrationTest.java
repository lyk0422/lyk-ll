package com.example.starter;

import com.example.starter.service.ControlEventService;
import com.example.starter.service.WindIngestionService;
import com.example.starter.web.dto.WindReadingRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成测试基类：独立 H2 命名内存库（MySQL 兼容模式），每个用例前清理业务数据。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:skilift_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa",
        "spring.sql.init.mode=always"
})
public abstract class BaseIntegrationTest {

    /** 合成线路与站点 id（见 data.sql） */
    protected static final long LINE_ID = 1L;
    protected static final long STATION_1 = 1L;
    protected static final long STATION_2 = 2L;
    protected static final long STATION_3 = 3L;
    protected static final long STATION_4 = 4L;
    protected static final long SEGMENT_1 = 1L;
    protected static final long SEGMENT_2 = 2L;
    protected static final long SEGMENT_3 = 3L;

    protected static final Instant T0 = Instant.parse("2026-10-06T00:00:00Z");

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected WindIngestionService windIngestionService;
    @Autowired
    protected ControlEventService controlEventService;
    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void cleanBusinessData() {
        jdbc.update("DELETE FROM boarding");
        jdbc.update("DELETE FROM late_data_event");
        jdbc.update("DELETE FROM cabin_release");
        jdbc.update("DELETE FROM segment_clearance");
        jdbc.update("DELETE FROM reopen_confirmation");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM wind_reading");
        jdbc.update("DELETE FROM control_event");
        jdbc.update("UPDATE wind_line SET status = 'NORMAL' WHERE id = ?", LINE_ID);
    }

    /**
     * 上报一条风速数据并断言 200，返回响应 JSON。
     */
    protected JsonNode ingest(long stationId, Instant measuredAt, double speedMs) {
        return ingest(UUID.randomUUID().toString(), stationId, measuredAt, speedMs);
    }

    /**
     * 上报一条风速数据（指定 requestId）并断言 200，返回响应 JSON。
     */
    protected JsonNode ingest(String requestId, long stationId, Instant measuredAt, double speedMs) {
        ResponseEntity<String> response = windIngestionService.ingest(
                LINE_ID, new WindReadingRequest(requestId, stationId, measuredAt, speedMs));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return readJson(response.getBody());
    }

    /**
     * 连续上报某站点从 start 起共 minutes+1 个整分钟时刻的等值风速（含首尾）。
     *
     * @return 最后一次上报的响应 JSON
     */
    protected JsonNode feedMinutes(long stationId, Instant start, int minutes, double speedMs) {
        JsonNode last = null;
        for (int i = 0; i <= minutes; i++) {
            last = ingest(stationId, start.plusSeconds(60L * i), speedMs);
        }
        return last;
    }

    protected JsonNode readJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应报文解析失败: " + body, e);
        }
    }

    protected int countRows(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    protected String lineStatus() {
        return jdbc.queryForObject("SELECT status FROM wind_line WHERE id = ?", String.class, LINE_ID);
    }
}
