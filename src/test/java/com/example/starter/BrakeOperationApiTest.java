package com.example.starter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 列车制动机试验与摘挂核对 API 的主流程、失败分支与幂等边界测试(真实 H2 库)。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:test_flow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BrakeOperationApiTest {

    private static final BigDecimal CAR_LENGTH = new BigDecimal("1.10");
    private static final BigDecimal CAR_WEIGHT = new BigDecimal("80.00");
    private static final int SET_PRESSURE = 500;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AtomicInteger carSeq = new AtomicInteger();
    private final AtomicInteger requestSeq = new AtomicInteger();

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM idempotency_record");
        jdbcTemplate.update("DELETE FROM pressure_reading");
        jdbcTemplate.update("DELETE FROM brake_test");
        jdbcTemplate.update("DELETE FROM consist_check");
        jdbcTemplate.update("DELETE FROM operation_car");
        jdbcTemplate.update("DELETE FROM train_operation");
    }

    @AfterAll
    void releaseDatabase() {
        // 释放命名内存库, 避免 DB_CLOSE_DELAY=-1 造成的跨用例残留
        jdbcTemplate.execute("SHUTDOWN");
    }

    @Test
    void fullFlowReceiveShuntCheckTestConfirmClose() throws Exception {
        long id = receive("T01", 2, 0);

        // 摘挂: 挂入 1 辆, 编组版本递增
        mockMvc.perform(post("/api/operations/{id}/shunts", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "requestId", nextRequestId(),
                                "attachCars", cars(1, 0),
                                "detachCarNos", List.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistVersion").value(2))
                .andExpect(jsonPath("$.carCount").value(3));

        checkMatched(id, 3);
        passSensitivity(id);
        reportPressure(id, SET_PRESSURE);
        passStability(id, 140, false);

        mockMvc.perform(post("/api/operations/{id}/departure-confirm", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("requestId", nextRequestId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmed").value(true));

        mockMvc.perform(post("/api/operations/{id}/close", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("requestId", nextRequestId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(true))
                .andExpect(jsonPath("$.alreadyClosed").value(false));

        mockMvc.perform(get("/api/operations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void consistMismatchRefusesDeparture() throws Exception {
        long id = receive("T02", 2, 0);

        // 运统1 辆数与实际不一致
        mockMvc.perform(post("/api/operations/{id}/consist-checks", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "requestId", nextRequestId(),
                                "expectedCars", 3,
                                "expectedLength", expectedLength(2),
                                "expectedWeight", expectedWeight(2)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(false));

        passSensitivity(id);
        reportPressure(id, SET_PRESSURE);
        passStability(id, 140, false);

        MvcResult result = mockMvc.perform(post("/api/operations/{id}/departure-confirm", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("requestId", nextRequestId()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.confirmed").value(false))
                .andReturn();
        assertTrue(responseBody(result).contains("运统1"));

        // 拒绝发车不改变作业状态
        mockMvc.perform(get("/api/operations/{id}", id))
                .andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    @Test
    void cutoutRatioExactlySixPercentAllowed() throws Exception {
        long id = receive("T03", 50, 3);
        checkMatched(id, 50);
        passSensitivity(id);
        reportPressure(id, SET_PRESSURE);
        passStability(id, 140, false);

        mockMvc.perform(get("/api/operations/{id}/cutout-cars", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cutoutCount").value(3))
                .andExpect(jsonPath("$.cutoutRatioPercent").value(6.00));

        mockMvc.perform(post("/api/operations/{id}/departure-confirm", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("requestId", nextRequestId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmed").value(true));
    }

    @Test
    void cutoutRatioAboveSixPercentRefused() throws Exception {
        long id = receive("T04", 50, 4);
        checkMatched(id, 50);
        passSensitivity(id);
        reportPressure(id, SET_PRESSURE);
        passStability(id, 140, false);

        MvcResult result = mockMvc.perform(post("/api/operations/{id}/departure-confirm", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("requestId", nextRequestId()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.confirmed").value(false))
                .andReturn();
        assertTrue(responseBody(result).contains("关门车"));
    }

    @Test
    void stabilityExactly140PassesAndOtherReductionFails() throws Exception {
        // 恰 140kPa 且无紧急制动: 合格
        long ok = receive("T05", 2, 0);
        passSensitivity(ok);
        reportPressure(ok, SET_PRESSURE);
        passStability(ok, 140, false);

        // 减压 139kPa: 不合格
        long low = receive("T06", 2, 0);
        passSensitivity(low);
        reportPressure(low, SET_PRESSURE);
        mockMvc.perform(post("/api/operations/{id}/brake-tests", low)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "requestId", nextRequestId(),
                                "phase", "STABILITY",
                                "reductionKpa", 139,
                                "emergencyOccurred", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("FAIL"));

        // 恰 140kPa 但发生紧急制动: 不合格
        long emergency = receive("T07", 2, 0);
        passSensitivity(emergency);
        reportPressure(emergency, SET_PRESSURE);
        mockMvc.perform(post("/api/operations/{id}/brake-tests", emergency)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "requestId", nextRequestId(),
                                "phase", "STABILITY",
                                "reductionKpa", 140,
                                "emergencyOccurred", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("FAIL"));

        // 安定不合格的列车不得发车
        mockMvc.perform(post("/api/operations/{id}/consist-checks", low)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "requestId", nextRequestId(),
                                "expectedCars", 2,
                                "expectedLength", expectedLength(2),
                                "expectedWeight", expectedWeight(2)))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/operations/{id}/departure-confirm", low)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("requestId", nextRequestId()))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void sensitivityRequires50KpaAndAllCarsBraked() throws Exception {
        long id = receive("T08", 2, 0);
        // 减压不足: 不合格
        mockMvc.perform(post("/api/operations/{id}/brake-tests", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "requestId", nextRequestId(),
                                "phase", "SENSITIVITY",
                                "reductionKpa", 40,
                                "allCarsBraked", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("FAIL"));
        // 恰 50kPa 但存在未制动车辆: 不合格
        mockMvc.perform(post("/api/operations/{id}/brake-tests", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "requestId", nextRequestId(),
                                "phase", "SENSITIVITY",
                                "reductionKpa", 50,
                                "allCarsBraked", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("FAIL"));
        // 恰 50kPa 且全列制动: 合格
        passSensitivity(id);
    }

    @Test
    void pressureExactlySetPressureAllowsStability() throws Exception {
        long id = receive("T09", 2, 0);
        passSensitivity(id);

        // 499kPa 未达定压: 安定试验不予受理
        reportPressure(id, SET_PRESSURE - 1);
        mockMvc.perform(post("/api/operations/{id}/brake-tests", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "requestId", nextRequestId(),
                                "phase", "STABILITY",
                                "reductionKpa", 140,
                                "emergencyOccurred", false))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PRESSURE_NOT_RECOVERED"));

        // 恰达定压 500kPa: 允许下一项
        reportPressure(id, SET_PRESSURE);
        passStability(id, 140, false);
    }

    @Test
    void shuntInvalidatesTestConclusionsInSameTransaction() throws Exception {
        long id = receive("T10", 2, 0);
        checkMatched(id, 2);
        passSensitivity(id);
        reportPressure(id, SET_PRESSURE);
        passStability(id, 140, false);

        // 摘挂变更: 编组版本递增, 原试验结论作废
        mockMvc.perform(post("/api/operations/{id}/shunts", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "requestId", nextRequestId(),
                                "attachCars", cars(1, 0),
                                "detachCarNos", List.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistVersion").value(2));

        Integer invalidCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM brake_test WHERE operation_id = ? AND valid = FALSE",
                Integer.class, id);
        assertEquals(2, invalidCount, "感度与安定两条旧结论须在同一事务内作废");

        // 变更后不得沿用旧结论发车
        MvcResult result = mockMvc.perform(post("/api/operations/{id}/departure-confirm", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("requestId", nextRequestId()))))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        assertTrue(responseBody(result).contains("感度试验"));
        assertTrue(responseBody(result).contains("安定试验"));

        // 试验曲线可复算: 旧结论保留且标记作废
        mockMvc.perform(get("/api/operations/{id}/brake-curve", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tests[0].valid").value(false))
                .andExpect(jsonPath("$.tests[1].valid").value(false));

        // 编组版本可复算: 两个版本的辆数
        mockMvc.perform(get("/api/operations/{id}/consist-versions", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0].carCount").value(2))
                .andExpect(jsonPath("$.versions[1].carCount").value(3));
    }

    @Test
    void invalidDetachRollsBackShunt() throws Exception {
        long id = receive("T11", 2, 0);
        mockMvc.perform(post("/api/operations/{id}/shunts", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "requestId", nextRequestId(),
                                "attachCars", List.of(),
                                "detachCarNos", List.of("NO_SUCH_CAR")))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CAR_NOT_FOUND"));

        // 事务回滚: 编组版本与车辆数不变
        mockMvc.perform(get("/api/operations/{id}", id))
                .andExpect(jsonPath("$.consistVersion").value(1))
                .andExpect(jsonPath("$.carCount").value(2));
    }

    @Test
    void idempotentReplayReturnsFirstResult() throws Exception {
        Map<String, Object> body = Map.of(
                "requestId", "replay-1",
                "trackNo", "T12",
                "trainNo", "K101",
                "setPressureKpa", SET_PRESSURE,
                "cars", cars(2, 0));
        MvcResult first = mockMvc.perform(post("/api/operations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult replay = mockMvc.perform(post("/api/operations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andReturn();

        assertEquals(responseBody(first), responseBody(replay), "同键同参须返回首次结果");
        Integer operations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM train_operation", Integer.class);
        assertEquals(1, operations, "重放不得重复建作业");
    }

    @Test
    void sameKeyDifferentParamsReturns409AndKeepsState() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(Map.of(
                "requestId", "conflict-1",
                "trackNo", "T13",
                "trainNo", "K102",
                "setPressureKpa", SET_PRESSURE,
                "cars", cars(2, 0)));
        mockMvc.perform(post("/api/operations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated());

        Map<String, Object> tampered = new LinkedHashMap<>(body);
        tampered.put("trackNo", "T13B");
        mockMvc.perform(post("/api/operations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(tampered)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"));

        Integer operations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM train_operation", Integer.class);
        assertEquals(1, operations, "同键异参不得改变作业状态");
    }

    @Test
    void closeIsTerminalAndLatePressureOnlyRecorded() throws Exception {
        long id = receive("T14", 2, 0);
        checkMatched(id, 2);
        passSensitivity(id);
        reportPressure(id, SET_PRESSURE);
        passStability(id, 140, false);
        mockMvc.perform(post("/api/operations/{id}/departure-confirm", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("requestId", nextRequestId()))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/operations/{id}/close", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("requestId", nextRequestId()))))
                .andExpect(status().isOk());

        // 重复销记: 幂等成功
        mockMvc.perform(post("/api/operations/{id}/close", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("requestId", nextRequestId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(true))
                .andExpect(jsonPath("$.alreadyClosed").value(true));

        // 销记后迟到风压数据只记迟到事件
        mockMvc.perform(post("/api/operations/{id}/pressures", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "requestId", nextRequestId(),
                                "pressureKpa", 480))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.late").value(true));
        Integer lateCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pressure_reading WHERE operation_id = ? AND late = TRUE",
                Integer.class, id);
        assertEquals(1, lateCount);

        // 终态后禁止摘挂与试验
        mockMvc.perform(post("/api/operations/{id}/shunts", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "requestId", nextRequestId(),
                                "attachCars", cars(1, 0),
                                "detachCarNos", List.of()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATE"));
        mockMvc.perform(post("/api/operations/{id}/brake-tests", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "requestId", nextRequestId(),
                                "phase", "SENSITIVITY",
                                "reductionKpa", 50,
                                "allCarsBraked", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATE"));

        // 销记释放股道: 同股道可接入新作业
        receive("T14", 1, 0);
    }

    @Test
    void stabilityRequiresPriorPassedSensitivity() throws Exception {
        long id = receive("T15", 2, 0);
        mockMvc.perform(post("/api/operations/{id}/brake-tests", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "requestId", nextRequestId(),
                                "phase", "STABILITY",
                                "reductionKpa", 140,
                                "emergencyOccurred", false))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SENSITIVITY_REQUIRED"));
    }

    private long receive(String trackNo, int carCount, int cutoutCount) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/operations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "requestId", nextRequestId(),
                                "trackNo", trackNo,
                                "trainNo", "K" + trackNo,
                                "setPressureKpa", SET_PRESSURE,
                                "cars", cars(carCount, cutoutCount)))))
                .andExpect(status().isCreated())
                .andReturn();
        return responseJson(result).get("operationId").longValue();
    }

    private void checkMatched(long id, int carCount) throws Exception {
        mockMvc.perform(post("/api/operations/{id}/consist-checks", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "requestId", nextRequestId(),
                                "expectedCars", carCount,
                                "expectedLength", expectedLength(carCount),
                                "expectedWeight", expectedWeight(carCount)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(true));
    }

    private void passSensitivity(long id) throws Exception {
        mockMvc.perform(post("/api/operations/{id}/brake-tests", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "requestId", nextRequestId(),
                                "phase", "SENSITIVITY",
                                "reductionKpa", 50,
                                "allCarsBraked", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("PASS"));
    }

    private void passStability(long id, int reductionKpa, boolean emergencyOccurred) throws Exception {
        mockMvc.perform(post("/api/operations/{id}/brake-tests", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "requestId", nextRequestId(),
                                "phase", "STABILITY",
                                "reductionKpa", reductionKpa,
                                "emergencyOccurred", emergencyOccurred))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("PASS"));
    }

    private void reportPressure(long id, int pressureKpa) throws Exception {
        mockMvc.perform(post("/api/operations/{id}/pressures", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "requestId", nextRequestId(),
                                "pressureKpa", pressureKpa))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.late").value(false));
    }

    private List<Map<String, Object>> cars(int count, int cutoutCount) {
        assertFalse(cutoutCount > count);
        List<Map<String, Object>> cars = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> car = new LinkedHashMap<>();
            car.put("carNo", "CAR" + carSeq.incrementAndGet());
            car.put("lengthCoeff", CAR_LENGTH);
            car.put("weightTons", CAR_WEIGHT);
            car.put("brakeCutout", i < cutoutCount);
            cars.add(car);
        }
        return cars;
    }

    private BigDecimal expectedLength(int carCount) {
        return CAR_LENGTH.multiply(BigDecimal.valueOf(carCount));
    }

    private BigDecimal expectedWeight(int carCount) {
        return CAR_WEIGHT.multiply(BigDecimal.valueOf(carCount));
    }

    private String nextRequestId() {
        return "req-" + requestSeq.incrementAndGet();
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String responseBody(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private JsonNode responseJson(MvcResult result) throws Exception {
        return objectMapper.readTree(responseBody(result));
    }
}
