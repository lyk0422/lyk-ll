package com.example.starter.fermentation;

import com.example.starter.fermentation.api.BatchDtos.CoolingRequest;
import com.example.starter.fermentation.api.BatchDtos.CreateBatchRequest;
import com.example.starter.fermentation.api.BatchDtos.DiacetylTestRequest;
import com.example.starter.fermentation.api.BatchDtos.ExtendMaturationRequest;
import com.example.starter.fermentation.api.BatchDtos.JudgeRequest;
import com.example.starter.fermentation.api.BatchDtos.StartPhaseRequest;
import com.example.starter.fermentation.api.BatchDtos.TemperatureReadingRequest;
import com.example.starter.fermentation.api.BatchDtos.TransferRequest;
import com.example.starter.fermentation.api.YeastDtos.PitchYeastRequest;
import com.example.starter.fermentation.api.YeastDtos.RegisterYeastRequest;
import com.example.starter.fermentation.api.YeastDtos.YeastRecoveryRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 发酵批次测试基类：使用独立 H2 内存库（test_demo），每个用例前清理业务数据，
 * 提供贯穿“满罐→主酵→降温→后熟→判定→转出”的合成数据驱动助手。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public abstract class BaseFermentationTest {

    /** 合成基准时间：满罐时刻。 */
    static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanBusinessData() {
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM temperature_reading");
        jdbc.update("DELETE FROM diacetyl_test");
        jdbc.update("DELETE FROM yeast_recovery_record");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM fermentation_batch");
        jdbc.update("DELETE FROM yeast_unit");
        jdbc.update("DELETE FROM curve_segment WHERE curve_id IN"
                + " (SELECT id FROM process_curve WHERE version > 1)");
        jdbc.update("DELETE FROM process_curve WHERE version > 1");
        jdbc.update("UPDATE process_curve SET status = 'ACTIVE'"
                + " WHERE curve_code = 'LAGER-CURVE' AND version = 1");
    }

    MvcResult doPost(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    /** 创建批次（满罐），断言 201 并返回批次 ID。 */
    long createBatch(String requestId, String batchNo, String tankCode) throws Exception {
        MvcResult result = doPost("/api/batches",
                new CreateBatchRequest(requestId, batchNo, tankCode, "LAGER-CURVE", T0));
        assertEquals(201, result.getResponse().getStatus(), "创建批次应返回 201");
        return json(result).get("id").asLong();
    }

    void startMainFerment(long batchId, String requestId, LocalDateTime at) throws Exception {
        MvcResult result = doPost("/api/batches/" + batchId + "/main-ferment/start",
                new StartPhaseRequest(requestId, at));
        assertEquals(200, result.getResponse().getStatus(), "开始主酵应返回 200");
    }

    MvcResult addReading(long batchId, String requestId, LocalDateTime at, String temperature)
            throws Exception {
        return doPost("/api/batches/" + batchId + "/temperature-readings",
                new TemperatureReadingRequest(requestId, at, new BigDecimal(temperature)));
    }

    MvcResult addDiacetyl(long batchId, String requestId, LocalDateTime at, String value)
            throws Exception {
        return doPost("/api/batches/" + batchId + "/diacetyl-tests",
                new DiacetylTestRequest(requestId, at, new BigDecimal(value)));
    }

    MvcResult cooling(long batchId, String requestId, LocalDateTime at) throws Exception {
        return doPost("/api/batches/" + batchId + "/cooling", new CoolingRequest(requestId, at));
    }

    MvcResult startMaturation(long batchId, String requestId, LocalDateTime at) throws Exception {
        return doPost("/api/batches/" + batchId + "/maturation/start",
                new StartPhaseRequest(requestId, at));
    }

    MvcResult extendMaturation(long batchId, String requestId, LocalDateTime at) throws Exception {
        return doPost("/api/batches/" + batchId + "/maturation/extend",
                new ExtendMaturationRequest(requestId, at));
    }

    MvcResult judge(long batchId, String requestId, LocalDateTime at) throws Exception {
        return doPost("/api/batches/" + batchId + "/judge", new JudgeRequest(requestId, at));
    }

    MvcResult transfer(long batchId, String requestId, LocalDateTime at) throws Exception {
        return doPost("/api/batches/" + batchId + "/transfer", new TransferRequest(requestId, at));
    }

    /**
     * 驱动批次进入后熟：满罐 → 主酵 → 两次合格双乙酰（间隔 13h）→ 降温 → 后熟开始。
     *
     * @return 批次 ID；后熟开始时间为 {@code T0 + 15h}
     */
    long driveToMaturation(String batchNo, String tankCode) throws Exception {
        long id = createBatch(newRequestId(), batchNo, tankCode);
        startMainFerment(id, newRequestId(), T0);
        assertEquals(201, addDiacetyl(id, newRequestId(), T0.plusHours(1), "0.09")
                .getResponse().getStatus());
        assertEquals(201, addDiacetyl(id, newRequestId(), T0.plusHours(14), "0.08")
                .getResponse().getStatus());
        assertEquals(200, cooling(id, newRequestId(), T0.plusHours(14))
                .getResponse().getStatus());
        assertEquals(200, startMaturation(id, newRequestId(), T0.plusHours(15))
                .getResponse().getStatus());
        return id;
    }

    /** 后熟开始时间（与 {@link #driveToMaturation} 保持一致）。 */
    LocalDateTime maturationStart() {
        return T0.plusHours(15);
    }

    /** 登记合成酵母单元，断言 201。 */
    void registerYeast(String requestId, String unitCode) throws Exception {
        MvcResult result = doPost("/api/yeast-units", new RegisterYeastRequest(requestId, unitCode));
        assertEquals(201, result.getResponse().getStatus(), "登记酵母单元应返回 201");
    }

    /** 受控投酵请求。 */
    MvcResult pitch(long batchId, String requestId, String unitCode, int expectedVersion,
                    LocalDateTime at) throws Exception {
        return doPost("/api/batches/" + batchId + "/yeast-pitch",
                new PitchYeastRequest(requestId, unitCode, expectedVersion, at));
    }

    /** 回收请求。 */
    MvcResult recover(long batchId, String requestId, int expectedVersion, LocalDateTime at,
                      String viabilityPercent, boolean contaminated) throws Exception {
        return doPost("/api/batches/" + batchId + "/yeast-recovery",
                new YeastRecoveryRequest(requestId, expectedVersion, at,
                        new BigDecimal(viabilityPercent), contaminated));
    }

    /** 查酵母单元。 */
    JsonNode yeastUnit(String unitCode) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/yeast-units/" + unitCode)).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        return json(result);
    }

    /** 查批次详情。 */
    JsonNode batch(long id) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + id)).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        return json(result);
    }
}
