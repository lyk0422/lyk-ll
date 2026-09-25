package com.example.starter.waterlogging;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
/**
 * 真实 HTTP 入口端到端测试（随机端口 + H2）：预警启动→联动状态→复测收官→归档终态，
 * 以及同键异参 409、黄色预警 422、参数非法 400 等 HTTP 语义。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // 端到端上下文使用独立命名内存库，与服务层测试的库完全隔离。
        "spring.datasource.url=jdbc:h2:mem:waterlogging_web;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000"
})
@Import(AbstractH2IntegrationTest.FixedClockConfiguration.class)
class WebApiEndToEndTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 9, 0, 0);

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private Map<String, Object> warningBody(String requestId, String level, BigDecimal target,
                                            BigDecimal gate) {
        return Map.of(
                "requestId", requestId,
                "warningLevel", level,
                "basinZone", "DONGWAN",
                "riverName", "东湾河",
                "targetLevel", target,
                "warningTime", T0.toString(),
                "gateOpening", gate);
    }

    @Test
    void fullFlowThroughHttpEndpoints() {
        // 橙色预警启动：201。
        ResponseEntity<Map> created = restTemplate.exchange(
                url("/api/warnings/start"), HttpMethod.POST,
                new HttpEntity<>(warningBody("HTTP-WARN-1", "ORANGE",
                        new BigDecimal("3.00"), new BigDecimal("60.00"))),
                Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().get("replay")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> plan = (Map<String, Object>) created.getBody().get("data");
        assertThat(plan.get("status")).isEqualTo("DRAINING");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stations = (List<Map<String, Object>>) plan.get("stations");
        assertThat(stations).hasSize(2);

        // 同键同参重放：200，replay=true。
        ResponseEntity<Map> replay = restTemplate.exchange(
                url("/api/warnings/start"), HttpMethod.POST,
                new HttpEntity<>(warningBody("HTTP-WARN-1", "ORANGE",
                        new BigDecimal("3.00"), new BigDecimal("60.00"))),
                Map.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().get("replay")).isEqualTo(true);

        // 同键异参：409，错误码可识别，泵站状态不变。
        ResponseEntity<Map> conflict = restTemplate.exchange(
                url("/api/warnings/start"), HttpMethod.POST,
                new HttpEntity<>(warningBody("HTTP-WARN-1", "ORANGE",
                        new BigDecimal("3.20"), new BigDecimal("60.00"))),
                Map.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().get("code")).isEqualTo("IDEMPOTENCY_FINGERPRINT_MISMATCH");

        // 黄色预警：422。
        ResponseEntity<Map> yellow = restTemplate.exchange(
                url("/api/warnings/start"), HttpMethod.POST,
                new HttpEntity<>(warningBody("HTTP-WARN-Y", "YELLOW",
                        new BigDecimal("3.00"), new BigDecimal("60.00"))),
                Map.class);
        assertThat(yellow.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 参数非法（闸门开度 120）：400。
        ResponseEntity<Map> invalid = restTemplate.exchange(
                url("/api/warnings/start"), HttpMethod.POST,
                new HttpEntity<>(warningBody("HTTP-WARN-BAD", "ORANGE",
                        new BigDecimal("3.00"), new BigDecimal("120.00"))),
                Map.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody().get("code")).isEqualTo("VALIDATION_FAILED");

        // 两站复测恰达目标位，序列收官。
        postReading("HTTP-R-1", "DW-01", T0.plusMinutes(20), new BigDecimal("3.00"));
        postReading("HTTP-R-2", "DW-02", T0.plusMinutes(21), new BigDecimal("3.00"));
        ResponseEntity<Map> planView = restTemplate.getForEntity(
                url("/api/plans/HTTP-WARN-1"), Map.class);
        assertThat(planView.getBody().get("status")).isEqualTo("COMPLETED");

        // 复算接口：启停序列 4 条（2 START + 2 STOP）、水位曲线 2 条。
        ResponseEntity<List> commands = restTemplate.getForEntity(
                url("/api/plans/HTTP-WARN-1/commands"), List.class);
        assertThat(commands.getBody()).hasSize(4);
        ResponseEntity<List> curve = restTemplate.getForEntity(
                url("/api/plans/HTTP-WARN-1/curve"), List.class);
        assertThat(curve.getBody()).hasSize(2);

        // 复盘归档：200；再次归档仍成功（终态幂等）。
        LocalDateTime archivedAt = T0.plusMinutes(40);
        archive("HTTP-ARCH-1", archivedAt, "复盘通过");
        ResponseEntity<Map> archived = archive("HTTP-ARCH-2", archivedAt.plusMinutes(1), "再次请求");
        assertThat(archived.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) archived.getBody().get("data")).get("status")).isEqualTo("ARCHIVED");

        // 归档后迟到数据：late=true，且仅入迟到事件表。
        ResponseEntity<Map> late = postReading(
                "HTTP-R-LATE", "DW-01", archivedAt.plusMinutes(10), new BigDecimal("2.90"));
        assertThat(((Map<?, ?>) late.getBody().get("data")).get("late")).isEqualTo(true);
        Integer lateCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM late_level_event", Integer.class);
        assertThat(lateCount).isEqualTo(1);

        // 失联派单台账复算接口可访问。
        ResponseEntity<List> lossOrders = restTemplate.getForEntity(
                url("/api/stations/DW-01/loss-orders"), List.class);
        assertThat(lossOrders.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Map> postReading(String requestId, String station, LocalDateTime time,
                                            BigDecimal level) {
        return restTemplate.exchange(url("/api/readings"), HttpMethod.POST, new HttpEntity<>(Map.of(
                "requestId", requestId,
                "stationCode", station,
                "readingTime", time.toString(),
                "level", level)), Map.class);
    }

    private ResponseEntity<Map> archive(String requestId, LocalDateTime archiveTime, String remark) {
        return restTemplate.exchange(url("/api/reviews/archive"), HttpMethod.POST, new HttpEntity<>(Map.of(
                "requestId", requestId,
                "planRequestId", "HTTP-WARN-1",
                "archiveTime", archiveTime.toString(),
                "remark", remark)), Map.class);
    }
}
