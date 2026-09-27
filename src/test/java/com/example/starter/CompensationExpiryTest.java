package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;

/**
 * 批次有效期边界（可控时钟）：now&lt;截止有效，恰在截止失效，截止后失效。
 * 使用独立 H2 库（test_dye_expiry）与可变时钟，测试类结束后释放该库。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:test_dye_expiry;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompensationExpiryTest extends BaseIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-12-01T00:00:00Z");

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0, ZoneId.of("Asia/Shanghai"));
        }
    }

    @Autowired
    private MutableClock mutableClock;

    @AfterAll
    void releaseDatabase() {
        // DB_CLOSE_DELAY=-1 仅保活到 JVM 退出；此处显式释放本测试类专用库。
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void feedExactlyAtDeadline_rejected_oneSecondBefore_allowed() {
        mutableClock.setInstant(T0);
        // RED 批次系数 2.0，补偿目标 = 1000/2.0 = 500.000；截止 DEADLINE
        registerBatch("exp-r1", "B-EXP-1", "D-RED", "2.000000", "5000.000", DEADLINE.toString());

        // 缸次 1：截止前 1 秒投料 => 有效
        schedule("exp-s1", "EXP1", "EXPV1", "R-BASE", 1, "50");
        approveExp("exp-a1", "EXP1");
        mutableClock.setInstant(DEADLINE.minusSeconds(1));
        Map<String, Object> ok = postOk("/api/controlled-feedings", Map.of(
                "requestId", "exp-f1", "lotNo", "EXP1",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("500.000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.500")))));
        assertEquals("FED", ok.get("status"));
        assertEquals(0, new BigDecimal("4500.000").compareTo(batchAvailable("B-EXP-1")));

        // 缸次 2：恰在截止时刻投料 => 失效，整单拒绝且不扣减
        schedule("exp-s2", "EXP2", "EXPV2", "R-BASE", 1, "50");
        approveExp("exp-a2", "EXP2");
        mutableClock.setInstant(DEADLINE);
        ResponseEntity<Map> atDeadline = post("/api/controlled-feedings", Map.of(
                "requestId", "exp-f2", "lotNo", "EXP2",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("500.000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.500")))));
        assertEquals(422, atDeadline.getStatusCode().value(), "恰在截止失效");
        assertEquals(0, new BigDecimal("4500.000").compareTo(batchAvailable("B-EXP-1")), "拒绝不得扣减");
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='EXP2'", String.class));

        // 截止后 1 秒同样失效
        mutableClock.setInstant(DEADLINE.plusSeconds(1));
        ResponseEntity<Map> afterDeadline = post("/api/controlled-feedings", Map.of(
                "requestId", "exp-f3", "lotNo", "EXP2",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("500.000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.500")))));
        assertEquals(422, afterDeadline.getStatusCode().value());
    }

    private void approveExp(String requestId, String lotNo) {
        postOk("/api/lots/" + lotNo + "/compensation", Map.of(
                "requestId", requestId,
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-EXP-1", "factorVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-1", "factorVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "batchNo", "B-BLUE-1", "factorVersion", 1))));
    }
}
