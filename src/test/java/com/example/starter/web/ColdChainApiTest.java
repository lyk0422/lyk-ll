package com.example.starter.web;

import com.example.starter.data.ColdChainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 冷链追溯 API 集成测试（H2 内存库，真实事务）。
 * 覆盖：建货件、乱序读数、阈值端点、连续超限、缺号、交接回滚、幂等重放与请求指纹。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ColdChainApiTest {

    private static final long MINUTE = 60_000L;
    private static final long T0 = 1_700_000_000_000L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ColdChainRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    // ------------------------------------------------------------------ 辅助

    private void createShipment(String id) throws Exception {
        createShipment(id, 10, 15);
    }

    private void createShipment(String id, int maxExcursionMinutes, int maxSampleIntervalMinutes)
            throws Exception {
        mvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "shipmentId": "%s",
                                  "minTempC": 2.0,
                                  "maxTempC": 8.0,
                                  "maxExcursionMinutes": %d,
                                  "maxSampleIntervalMinutes": %d,
                                  "custodian": "carrier-A",
                                  "startTime": %d
                                }
                                """.formatted(id, maxExcursionMinutes, maxSampleIntervalMinutes, T0)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shipmentId").value(id))
                .andExpect(jsonPath("$.version").value(0));
    }

    private void writeReading(String id, long seq, long offsetMinutes, String temp, int expectedStatus)
            throws Exception {
        mvc.perform(post("/api/shipments/{id}/readings", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sequenceNo": %d, "sampledAt": %d, "temperatureC": %s}
                                """.formatted(seq, T0 + offsetMinutes * MINUTE, temp)))
                .andExpect(status().is(expectedStatus));
    }

    private void handover(String id, String requestId, int expectedVersion, String custodian,
                          String receiver, long offsetMinutes, long lastSequence, int expectedStatus)
            throws Exception {
        mvc.perform(post("/api/shipments/{id}/handovers", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "%s",
                                  "expectedVersion": %d,
                                  "custodian": "%s",
                                  "receiver": "%s",
                                  "handoverAt": %d,
                                  "lastSequence": %d
                                }
                                """.formatted(requestId, expectedVersion, custodian, receiver,
                                T0 + offsetMinutes * MINUTE, lastSequence)))
                .andExpect(status().is(expectedStatus));
    }

    // ------------------------------------------------------------------ 建货件

    @Test
    void createShipmentThenDuplicateIs409() throws Exception {
        createShipment("SH-1");
        mvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "shipmentId": "SH-1",
                                  "minTempC": 2.0, "maxTempC": 8.0,
                                  "maxExcursionMinutes": 10, "maxSampleIntervalMinutes": 15,
                                  "custodian": "carrier-A", "startTime": %d
                                }
                                """.formatted(T0)))
                .andExpect(status().isConflict());
    }

    @Test
    void createShipmentRejectsInvertedRange() throws Exception {
        mvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "shipmentId": "SH-BAD",
                                  "minTempC": 9.0, "maxTempC": 2.0,
                                  "maxExcursionMinutes": 10, "maxSampleIntervalMinutes": 15,
                                  "custodian": "carrier-A", "startTime": %d
                                }
                                """.formatted(T0)))
                .andExpect(status().isUnprocessableEntity());
    }

    // ------------------------------------------------------------------ 读数

    @Test
    void readingReplaySameParamsReturnsOriginalAndChangeIs409() throws Exception {
        createShipment("SH-2");
        writeReading("SH-2", 1, 0, "4.0", 200);
        // 同序号同参重放：返回原结果
        mvc.perform(post("/api/shipments/SH-2/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sequenceNo": 1, "sampledAt": %d, "temperatureC": 4.0}
                                """.formatted(T0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.temperatureC").value(4.0));
        // 同序号改值：409
        writeReading("SH-2", 1, 0, "5.5", 409);
        writeReading("SH-2", 1, 5, "4.0", 409);
    }

    @Test
    void readingToUnknownShipmentIs404() throws Exception {
        writeReading("NOPE", 1, 0, "4.0", 404);
    }

    @Test
    void readingBeforeSegmentStartIs422() throws Exception {
        createShipment("SH-3");
        writeReading("SH-3", 1, -5, "4.0", 422);
    }

    // ------------------------------------------------------------------ 交接主流程

    @Test
    void handoverClearWithOutOfOrderReadingsAndThresholdEndpoints() throws Exception {
        createShipment("SH-4");
        // 乱序到达 + 端点等于阈值（2.0/8.0 均合格）
        writeReading("SH-4", 3, 10, "8.0", 200);
        writeReading("SH-4", 1, 0, "2.0", 200);
        writeReading("SH-4", 2, 5, "5.0", 200);
        handover("SH-4", "REQ-1", 0, "carrier-A", "carrier-B", 20, 3, 201);

        mvc.perform(get("/api/shipments/SH-4/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipment.version").value(1))
                .andExpect(jsonPath("$.segments", hasSize(2)))
                .andExpect(jsonPath("$.segments[0].status").value("CLEAR"))
                .andExpect(jsonPath("$.segments[0].custodian").value("carrier-A"))
                .andExpect(jsonPath("$.segments[0].firstSequence").value(1))
                .andExpect(jsonPath("$.segments[0].lastSequence").value(3))
                .andExpect(jsonPath("$.segments[0].readings", hasSize(3)))
                .andExpect(jsonPath("$.segments[1].status").value("OPEN"))
                .andExpect(jsonPath("$.segments[1].custodian").value("carrier-B"));
    }

    @Test
    void handoverExcursionWhenContinuousBreachExceedsLimit() throws Exception {
        createShipment("SH-5", 10, 60);
        writeReading("SH-5", 1, 0, "4.0", 200);
        writeReading("SH-5", 2, 5, "9.5", 200);   // 超限开始
        writeReading("SH-5", 3, 25, "6.0", 200);  // 超限持续 20 分钟 > 10
        handover("SH-5", "REQ-1", 0, "carrier-A", "carrier-B", 30, 3, 201);

        mvc.perform(get("/api/shipments/SH-5/evidence"))
                .andExpect(jsonPath("$.segments[0].status").value("EXCURSION"));
    }

    @Test
    void handoverIncompleteWhenSequenceMissing() throws Exception {
        createShipment("SH-6");
        writeReading("SH-6", 1, 0, "4.0", 200);
        writeReading("SH-6", 3, 10, "5.0", 200);  // 缺序号 2
        handover("SH-6", "REQ-1", 0, "carrier-A", "carrier-B", 20, 3, 201);

        mvc.perform(get("/api/shipments/SH-6/evidence"))
                .andExpect(jsonPath("$.segments[0].status").value("INCOMPLETE"));
    }

    @Test
    void handoverIncompleteWhenSampleIntervalExceeded() throws Exception {
        createShipment("SH-7", 10, 15);
        writeReading("SH-7", 1, 0, "4.0", 200);
        writeReading("SH-7", 2, 20, "5.0", 200);  // 间隔 20 分钟 > 15
        handover("SH-7", "REQ-1", 0, "carrier-A", "carrier-B", 25, 2, 201);

        mvc.perform(get("/api/shipments/SH-7/evidence"))
                .andExpect(jsonPath("$.segments[0].status").value("INCOMPLETE"));
    }

    // ------------------------------------------------------------------ 交接失败分支与回滚

    @Test
    void handoverVersionConflictIs409AndLeavesNoPartialState() throws Exception {
        createShipment("SH-8");
        writeReading("SH-8", 1, 0, "4.0", 200);
        handover("SH-8", "REQ-1", 5, "carrier-A", "carrier-B", 10, 1, 409);

        // 回滚验证：段仍打开、版本仍为 0、无冻结记录，可用正确版本重新交接
        mvc.perform(get("/api/shipments/SH-8/evidence"))
                .andExpect(jsonPath("$.shipment.version").value(0))
                .andExpect(jsonPath("$.segments", hasSize(1)))
                .andExpect(jsonPath("$.segments[0].status").value("OPEN"));
        handover("SH-8", "REQ-2", 0, "carrier-A", "carrier-B", 10, 1, 201);
    }

    @Test
    void handoverCustodianMismatchIs422AndRollsBack() throws Exception {
        createShipment("SH-9");
        writeReading("SH-9", 1, 0, "4.0", 200);
        handover("SH-9", "REQ-1", 0, "carrier-X", "carrier-B", 10, 1, 422);

        mvc.perform(get("/api/shipments/SH-9/evidence"))
                .andExpect(jsonPath("$.shipment.version").value(0))
                .andExpect(jsonPath("$.segments[0].status").value("OPEN"));
    }

    @Test
    void handoverTimeRegressionIs422AndRollsBack() throws Exception {
        createShipment("SH-10");
        writeReading("SH-10", 1, 0, "4.0", 200);
        writeReading("SH-10", 2, 10, "5.0", 200);
        // 交接时间早于最后采样时间：时间倒退
        handover("SH-10", "REQ-1", 0, "carrier-A", "carrier-B", 5, 2, 422);

        mvc.perform(get("/api/shipments/SH-10/evidence"))
                .andExpect(jsonPath("$.shipment.version").value(0))
                .andExpect(jsonPath("$.segments[0].status").value("OPEN"));
    }

    @Test
    void handoverWithMissingSequenceStillClosesAsIncompleteNotRollback() throws Exception {
        // 缺号属于证据不完整：段关闭为 INCOMPLETE 并打开新段（不是校验失败回滚）
        createShipment("SH-11");
        writeReading("SH-11", 2, 5, "4.0", 200);
        writeReading("SH-11", 4, 15, "5.0", 200);  // 缺序号 3
        handover("SH-11", "REQ-1", 0, "carrier-A", "carrier-B", 20, 4, 201);

        mvc.perform(get("/api/shipments/SH-11/evidence"))
                .andExpect(jsonPath("$.shipment.version").value(1))
                .andExpect(jsonPath("$.segments[0].status").value("INCOMPLETE"))
                .andExpect(jsonPath("$.segments[1].status").value("OPEN"));
    }

    @Test
    void handoverEmptySegmentIs422() throws Exception {
        createShipment("SH-12");
        handover("SH-12", "REQ-1", 0, "carrier-A", "carrier-B", 10, 1, 422);
    }

    // ------------------------------------------------------------------ 幂等与请求指纹

    @Test
    void handoverReplaySameParamsReturnsFrozenResult() throws Exception {
        createShipment("SH-13");
        writeReading("SH-13", 1, 0, "4.0", 200);
        handover("SH-13", "REQ-1", 0, "carrier-A", "carrier-B", 10, 1, 201);

        // 同 requestId 同参重放：返回首次冻结结果
        mvc.perform(post("/api/shipments/SH-13/handovers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-1",
                                  "expectedVersion": 0,
                                  "custodian": "carrier-A",
                                  "receiver": "carrier-B",
                                  "handoverAt": %d,
                                  "lastSequence": 1
                                }
                                """.formatted(T0 + 10 * MINUTE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.status").value("CLEAR"))
                .andExpect(jsonPath("$.closedVersion").value(0))
                .andExpect(jsonPath("$.currentVersion").value(1));

        // 版本没有再次推进
        mvc.perform(get("/api/shipments/SH-13/evidence"))
                .andExpect(jsonPath("$.shipment.version").value(1))
                .andExpect(jsonPath("$.segments", hasSize(2)));
    }

    @Test
    void handoverSameRequestIdDifferentParamsIs409() throws Exception {
        createShipment("SH-14");
        writeReading("SH-14", 1, 0, "4.0", 200);
        handover("SH-14", "REQ-1", 0, "carrier-A", "carrier-B", 10, 1, 201);

        // 同键异参：版本变化
        handover("SH-14", "REQ-1", 1, "carrier-A", "carrier-B", 10, 1, 409);
        // 同键异参：lastSequence 变化
        handover("SH-14", "REQ-1", 0, "carrier-A", "carrier-B", 10, 2, 409);
        // 同键异参：接收方变化
        handover("SH-14", "REQ-1", 0, "carrier-A", "carrier-C", 10, 1, 409);
    }

    // ------------------------------------------------------------------ 已关闭段保护

    @Test
    void writingToClosedSegmentRangeIs409() throws Exception {
        createShipment("SH-15");
        writeReading("SH-15", 1, 0, "4.0", 200);
        writeReading("SH-15", 2, 5, "5.0", 200);
        handover("SH-15", "REQ-1", 0, "carrier-A", "carrier-B", 10, 2, 201);

        // 已关闭段序号范围内补写：409
        writeReading("SH-15", 1, 1, "4.0", 409);
        // 新段（序号 > 2）可继续写
        writeReading("SH-15", 3, 12, "5.0", 200);
    }

    @Test
    void secondHandoverAdvancesVersionAndSegments() throws Exception {
        createShipment("SH-16");
        writeReading("SH-16", 1, 0, "4.0", 200);
        handover("SH-16", "REQ-1", 0, "carrier-A", "carrier-B", 10, 1, 201);
        writeReading("SH-16", 2, 12, "5.0", 200);
        handover("SH-16", "REQ-2", 1, "carrier-B", "carrier-C", 20, 2, 201);

        mvc.perform(get("/api/shipments/SH-16/evidence"))
                .andExpect(jsonPath("$.shipment.version").value(2))
                .andExpect(jsonPath("$.segments", hasSize(3)))
                .andExpect(jsonPath("$.segments[0].status").value("CLEAR"))
                .andExpect(jsonPath("$.segments[1].status").value("CLEAR"))
                .andExpect(jsonPath("$.segments[1].custodian").value("carrier-B"))
                .andExpect(jsonPath("$.segments[2].status").value("OPEN"))
                .andExpect(jsonPath("$.segments[2].custodian").value("carrier-C"));
    }

    @Test
    void evidenceForUnknownShipmentIs404() throws Exception {
        mvc.perform(get("/api/shipments/NOPE/evidence"))
                .andExpect(status().isNotFound());
    }

    @Test
    void validationFailureIs400() throws Exception {
        mvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"shipmentId": "", "minTempC": 2.0}
                                """))
                .andExpect(status().isBadRequest());
    }
}
