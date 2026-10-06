package com.example.starter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 集成测试基类：共享应用上下文、独立测试内存库、可控时钟与合成数据。
 * 每个用例前清空全部业务表并重建合成数据，避免用例间顺序依赖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(BaseIntegrationTest.TestConfig.class)
public abstract class BaseIntegrationTest {

    /** 测试基准时刻（UTC）。 */
    protected static final Instant T0 = Instant.parse("2026-01-01T08:00:00Z");

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM purchase_suggestion");
        jdbc.update("DELETE FROM preventive_evaluation");
        jdbc.update("DELETE FROM pest_trace");
        jdbc.update("DELETE FROM treatment_placement");
        jdbc.update("DELETE FROM outbound_record");
        jdbc.update("DELETE FROM zone_active_case");
        jdbc.update("DELETE FROM pest_case");
        jdbc.update("DELETE FROM pesticide");
        jdbc.update("DELETE FROM archive_zone");
        seed();
        clock.setInstant(T0);
    }

    /**
     * 合成数据：A-B-C 为一个相邻组团，D 为独立库区；药剂 1 在有效期内，药剂 2 已过期。
     */
    private void seed() {
        jdbc.update("INSERT INTO archive_zone (code, name, adjacent_codes) VALUES ('A', '一号库区', 'B')");
        jdbc.update("INSERT INTO archive_zone (code, name, adjacent_codes) VALUES ('B', '二号库区', 'A,C')");
        jdbc.update("INSERT INTO archive_zone (code, name, adjacent_codes) VALUES ('C', '三号库区', 'B')");
        jdbc.update("INSERT INTO archive_zone (code, name, adjacent_codes) VALUES ('D', '四号库区', '')");
        jdbc.update("INSERT INTO pesticide (id, name, quantity, unit, safety_line, expiry_date)"
                + " VALUES (1, '磷化铝片剂', 100.00, '克', 20.00, DATE '2030-12-31')");
        jdbc.update("INSERT INTO pesticide (id, name, quantity, unit, safety_line, expiry_date)"
                + " VALUES (2, '陈旧熏蒸剂', 30.00, '克', 5.00, DATE '2020-01-01')");
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }

        /**
         * 测试上下文关闭时释放命名内存库（DB_CLOSE_DELAY=-1 不会自动释放）。
         */
        @Bean
        DisposableBean testDatabaseShutdown(DataSource dataSource) {
            return () -> {
                try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
                    st.execute("SHUTDOWN");
                } catch (Exception ignored) {
                    // 上下文销毁阶段忽略连接关闭异常
                }
            };
        }
    }

    protected ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    protected long reportCase(String zoneCode, String requestId) throws Exception {
        MvcResult result = postJson("/api/zones/" + zoneCode + "/pest-reports",
                Map.of("requestId", requestId, "reporter", "张三", "description", "发现虫迹"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("caseId").asLong();
    }

    protected void confirmCase(long caseId, String requestId) throws Exception {
        postJson("/api/cases/" + caseId + "/confirm",
                Map.of("requestId", requestId, "operator", "防虫员"))
                .andExpect(status().isOk());
    }

    protected long registerPlacement(long caseId, String requestId, String registrar) throws Exception {
        return registerPlacement(caseId, requestId, 1L, 10, registrar);
    }

    protected long registerPlacement(long caseId, String requestId, long pesticideId, int dosage,
            String registrar) throws Exception {
        MvcResult result = postJson("/api/cases/" + caseId + "/placements",
                Map.of("requestId", requestId, "pesticideId", pesticideId, "dosage", dosage,
                        "point", "3排-2架", "registrar", registrar))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("placementId").asLong();
    }

    protected void confirmPlacement(long placementId, String requestId, String registrar) throws Exception {
        postJson("/api/placements/" + placementId + "/confirm",
                Map.of("requestId", requestId, "registrar", registrar, "pesticideId", 1,
                        "dosage", 10, "point", "3排-2架"))
                .andExpect(status().isOk());
    }

    protected void applyRelease(long caseId, String requestId) throws Exception {
        postJson("/api/cases/" + caseId + "/release-application",
                Map.of("requestId", requestId, "operator", "库房员"))
                .andExpect(status().isOk());
    }

    protected void confirmRelease(long caseId, String requestId, String side) throws Exception {
        postJson("/api/cases/" + caseId + "/release-confirmation",
                Map.of("requestId", requestId, "side", side))
                .andExpect(status().isOk());
    }

    protected BigDecimal pesticideQuantity(long pesticideId) {
        return jdbc.queryForObject("SELECT quantity FROM pesticide WHERE id = " + pesticideId,
                BigDecimal.class);
    }

    protected String caseStatus(long caseId) {
        return jdbc.queryForObject("SELECT status FROM pest_case WHERE id = " + caseId, String.class);
    }
}
