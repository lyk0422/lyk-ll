package com.example.starter.lot;

import com.example.starter.lot.api.ConversionRequest;
import com.example.starter.lot.api.CreateLotRequest;
import com.example.starter.lot.api.QuarantineRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批次谱系 API 的 H2 事务集成测试：真实建表、真实事务回滚与真实 HTTP 语义，
 * 不使用 mock 替代数据库边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LotApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM quarantine_source");
        jdbc.update("DELETE FROM quarantine_event");
        jdbc.update("DELETE FROM genealogy_edge");
        jdbc.update("DELETE FROM conversion_input");
        jdbc.update("DELETE FROM conversion_output");
        jdbc.update("DELETE FROM conversion");
        jdbc.update("DELETE FROM lot");
        jdbc.execute("ALTER TABLE conversion ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE quarantine_event ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE quarantine_source ALTER COLUMN id RESTART WITH 1");
    }

    private MvcResult createLot(String lotId, String unit, String quantity) throws Exception {
        return createLot(lotId, unit, quantity, null);
    }

    private MvcResult createLot(String lotId, String unit, String quantity, String remark) throws Exception {
        CreateLotRequest request = new CreateLotRequest(
                lotId, unit, new BigDecimal(quantity), remark);
        return mockMvc.perform(post("/api/lots")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();
    }

    private MvcResult convert(String requestId, String unit, String allowedLoss,
                              List<ConversionRequest.InputItem> inputs,
                              List<ConversionRequest.OutputItem> outputs) throws Exception {
        ConversionRequest request = new ConversionRequest(
                requestId, unit, new BigDecimal(allowedLoss), inputs, outputs);
        return mockMvc.perform(post("/api/lots/conversions")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();
    }

    private ConversionRequest.InputItem in(String lotId, String qty, int version) {
        return new ConversionRequest.InputItem(lotId, new BigDecimal(qty), version);
    }

    private ConversionRequest.OutputItem out(String lotId, String qty) {
        return new ConversionRequest.OutputItem(lotId, new BigDecimal(qty));
    }

    private MvcResult quarantine(String lotId, QuarantineRequest.Action action) throws Exception {
        return mockMvc.perform(post("/api/lots/quarantine")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new QuarantineRequest(lotId, action, "synthetic"))))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("建批次：数量按三位小数存储并给出可用余额")
    void createLot_threeDecimalScaleAndBalance() throws Exception {
        MvcResult result = createLot("A", "KG", "12.5");
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus()).isEqualTo(201);
        mockMvc.perform(get("/api/lots/A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialQty").value(12.500))
                .andExpect(jsonPath("$.consumedQty").value(0.000))
                .andExpect(jsonPath("$.availableQty").value(12.500))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    @DisplayName("建批次：非正数量被 Bean 校验拒绝（400）")
    void createLot_rejectsNonPositive() throws Exception {
        MvcResult result = createLot("A", "KG", "0");
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(result).get("code").asText()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("建批次：超过三位小数拒绝且不静默舍入（422）")
    void createLot_rejectsFourDecimals() throws Exception {
        CreateLotRequest request = new CreateLotRequest(
                "A", "KG", new BigDecimal("1.0005"), null);
        MvcResult result = mockMvc.perform(post("/api/lots")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(result).get("code").asText()).isEqualTo("QTY_PRECISION_EXCEEDED");
    }

    @Test
    @DisplayName("建批次：lotId 冲突返回 409")
    void createLot_duplicateConflict() throws Exception {
        createLot("A", "KG", "10");
        MvcResult result = createLot("A", "KG", "20");
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(result).get("code").asText()).isEqualTo("LOT_ID_CONFLICT");
    }

    @Test
    @DisplayName("拆批：一次消费一个父批次产生两个子批次，损耗在边界内，余额与版本正确")
    void conversion_splitHappyPath() throws Exception {
        createLot("A", "KG", "100");
        MvcResult result = convert("REQ-1", "KG", "1.000",
                List.of(in("A", "60.000", 0)),
                List.of(out("B", "40.000"), out("C", "19.000")));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json(result);
        assertThat(body.get("totalInputQty").decimalValue()).isEqualByComparingTo("60.000");
        assertThat(body.get("totalOutputQty").decimalValue()).isEqualByComparingTo("59.000");
        assertThat(body.get("loss").decimalValue()).isEqualByComparingTo("1.000");
        assertThat(body.get("conversionId").asLong()).isEqualTo(1L);

        mockMvc.perform(get("/api/lots/A"))
                .andExpect(jsonPath("$.availableQty").value(40.000))
                .andExpect(jsonPath("$.consumedQty").value(60.000))
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(get("/api/lots/B"))
                .andExpect(jsonPath("$.initialQty").value(40.000))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    @DisplayName("并批：多父批次原子消费，产出共享谱系边")
    void conversion_mergeMultipleParents() throws Exception {
        createLot("A", "KG", "100");
        createLot("B", "KG", "100");
        MvcResult result = convert("REQ-M", "KG", "0",
                List.of(in("A", "30", 0), in("B", "30", 0)),
                List.of(out("M", "60")));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(get("/api/lots/M/genealogy"))
                .andExpect(jsonPath("$.parents.length()").value(2))
                .andExpect(jsonPath("$.ancestors.length()").value(2));
        mockMvc.perform(get("/api/lots/A/genealogy"))
                .andExpect(jsonPath("$.children.length()").value(1))
                .andExpect(jsonPath("$.descendants.length()").value(1));
    }

    @Test
    @DisplayName("损耗边界：总产出超过总消费整笔回滚（422），父批次余额不变")
    void conversion_outputExceedsInputRollsBack() throws Exception {
        createLot("A", "KG", "100");
        MvcResult result = convert("REQ-OE", "KG", "0",
                List.of(in("A", "50", 0)),
                List.of(out("X", "50.001")));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(result).get("code").asText()).isEqualTo("OUTPUT_EXCEEDS_INPUT");

        mockMvc.perform(get("/api/lots/A"))
                .andExpect(jsonPath("$.consumedQty").value(0.000))
                .andExpect(jsonPath("$.version").value(0));
        MvcResult missing = mockMvc.perform(get("/api/lots/X")).andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("损耗边界：差额恰好等于允许损耗时通过；超出则整笔回滚（422）")
    void conversion_lossBoundary() throws Exception {
        createLot("P1", "KG", "100");
        createLot("P2", "KG", "100");

        MvcResult ok = convert("REQ-LOSS-OK", "KG", "2.500",
                List.of(in("P1", "10", 0)),
                List.of(out("OK", "7.500")));
        assertThat(ok.getResponse().getStatus()).isEqualTo(201);

        MvcResult bad = convert("REQ-LOSS-BAD", "KG", "2.499",
                List.of(in("P2", "10", 0)),
                List.of(out("BAD", "7.500")));
        assertThat(bad.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(bad).get("code").asText()).isEqualTo("LOSS_EXCEEDED");
        mockMvc.perform(get("/api/lots/P2"))
                .andExpect(jsonPath("$.consumedQty").value(0.000));
    }

    @Test
    @DisplayName("部分消费：可连续消费至余额耗尽；超用最后一笔整笔回滚")
    void conversion_partialConsumptionAndOveruse() throws Exception {
        createLot("A", "KG", "100");

        assertThat(convert("REQ-P1", "KG", "0",
                List.of(in("A", "30", 0)), List.of(out("C1", "30")))
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(convert("REQ-P2", "KG", "0",
                List.of(in("A", "70", 1)), List.of(out("C2", "70")))
                .getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(get("/api/lots/A"))
                .andExpect(jsonPath("$.availableQty").value(0.000))
                .andExpect(jsonPath("$.version").value(2));

        MvcResult overuse = convert("REQ-P3", "KG", "0",
                List.of(in("A", "0.001", 2)), List.of(out("C3", "0.001")));
        assertThat(overuse.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(overuse).get("code").asText()).isEqualTo("INSUFFICIENT_BALANCE");
    }

    @Test
    @DisplayName("超用回滚：多父批次中第二个余额不足，第一个父批次的消费同样回滚")
    void conversion_secondParentInsufficientRollsBackAll() throws Exception {
        createLot("A", "KG", "100");
        createLot("B", "KG", "10");
        MvcResult result = convert("REQ-ROLLBACK", "KG", "0",
                List.of(in("A", "50", 0), in("B", "10.001", 0)),
                List.of(out("Z", "50")));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        mockMvc.perform(get("/api/lots/A"))
                .andExpect(jsonPath("$.consumedQty").value(0.000))
                .andExpect(jsonPath("$.version").value(0));
        mockMvc.perform(get("/api/lots/B"))
                .andExpect(jsonPath("$.consumedQty").value(0.000));
    }

    @Test
    @DisplayName("单位不一致返回 422")
    void conversion_unitMismatch() throws Exception {
        createLot("A", "KG", "100");
        MvcResult result = convert("REQ-U", "L", "0",
                List.of(in("A", "10", 0)), List.of(out("U", "10")));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(result).get("code").asText()).isEqualTo("UNIT_MISMATCH");
    }

    @Test
    @DisplayName("expectedVersion 与当前版本不一致返回 409")
    void conversion_versionMismatch() throws Exception {
        createLot("A", "KG", "100");
        convert("REQ-V0", "KG", "0",
                List.of(in("A", "10", 0)), List.of(out("V0", "10")));
        MvcResult result = convert("REQ-V1", "KG", "0",
                List.of(in("A", "10", 0)), List.of(out("V1", "10")));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(result).get("code").asText()).isEqualTo("VERSION_CONFLICT");
    }

    @Test
    @DisplayName("子批次 ID 与既有批次冲突返回 409")
    void conversion_childIdConflict() throws Exception {
        createLot("A", "KG", "100");
        createLot("B", "KG", "1");
        MvcResult result = convert("REQ-CONF", "KG", "0",
                List.of(in("A", "10", 0)), List.of(out("B", "10")));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(result).get("code").asText()).isEqualTo("LOT_ID_CONFLICT");
    }

    @Test
    @DisplayName("谱系环：以已存在的祖先作为新子批次被拒绝，且边不新增")
    void conversion_cycleRejected() throws Exception {
        createLot("A", "KG", "100");
        convert("REQ-C1", "KG", "0",
                List.of(in("A", "10", 0)), List.of(out("B", "10")));
        // 输入 B、输出 A：A 已存在（ID 冲突先触发），本质上成环的边无法写入。
        MvcResult result = convert("REQ-CYC", "KG", "0",
                List.of(in("B", "5", 0)), List.of(out("A", "5")));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        mockMvc.perform(get("/api/lots/B/genealogy"))
                .andExpect(jsonPath("$.parents.length()").value(1))
                .andExpect(jsonPath("$.children.length()").value(0));
    }

    @Test
    @DisplayName("请求指纹：同键同完整请求重放首次结果（conversionId 一致、不重复记账）")
    void idempotency_samePayloadReplaysFirstResult() throws Exception {
        createLot("A", "KG", "100");
        List<ConversionRequest.InputItem> inputs = List.of(in("A", "10", 0));
        List<ConversionRequest.OutputItem> outputs = List.of(out("D", "10"));
        MvcResult first = convert("REQ-IDEM", "KG", "0", inputs, outputs);
        MvcResult second = convert("REQ-IDEM", "KG", "0",
                List.of(in("A", "10.000", 0)), List.of(out("D", "10.000")));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(first).get("conversionId").asLong())
                .isEqualTo(json(second).get("conversionId").asLong());
        mockMvc.perform(get("/api/lots/A"))
                .andExpect(jsonPath("$.consumedQty").value(10.000))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    @DisplayName("请求指纹：同键不同数量返回 409 且不执行新转换")
    void idempotency_differentQuantityConflict() throws Exception {
        createLot("A", "KG", "100");
        convert("REQ-FP", "KG", "0",
                List.of(in("A", "10", 0)), List.of(out("Q", "10")));
        MvcResult result = convert("REQ-FP", "KG", "0",
                List.of(in("A", "11", 0)), List.of(out("Q2", "11")));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(result).get("code").asText()).isEqualTo("IDEMPOTENCY_FINGERPRINT_MISMATCH");
        mockMvc.perform(get("/api/lots/A")).andExpect(jsonPath("$.version").value(1));
    }

    @Test
    @DisplayName("请求指纹：同键不同允许损耗返回 409")
    void idempotency_differentLossConflict() throws Exception {
        createLot("A", "KG", "100");
        convert("REQ-FPL", "KG", "1",
                List.of(in("A", "10", 0)), List.of(out("QL", "9")));
        MvcResult result = convert("REQ-FPL", "KG", "0.5",
                List.of(in("A", "10", 0)), List.of(out("QL2", "9.5")));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(result).get("code").asText()).isEqualTo("IDEMPOTENCY_FINGERPRINT_MISMATCH");
    }

    @Test
    @DisplayName("请求指纹：同键不同 expectedVersion 返回 409")
    void idempotency_differentVersionConflict() throws Exception {
        createLot("A", "KG", "100");
        convert("REQ-FPV", "KG", "0",
                List.of(in("A", "10", 0)), List.of(out("QV", "10")));
        MvcResult result = convert("REQ-FPV", "KG", "0",
                List.of(in("A", "10", 1)), List.of(out("QV2", "10")));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(result).get("code").asText()).isEqualTo("IDEMPOTENCY_FINGERPRINT_MISMATCH");
    }

    @Test
    @DisplayName("隔离传播：隔离先提交，新子批次出生即隔离；解除来源后全链恢复可用")
    void quarantine_firstThenConversionChildBornQuarantined() throws Exception {
        createLot("A", "KG", "100");
        assertThat(quarantine("A", QuarantineRequest.Action.QUARANTINE)
                .getResponse().getStatus()).isEqualTo(200);

        MvcResult conversion = convert("REQ-QC", "KG", "0",
                List.of(in("A", "20", 0)), List.of(out("C", "20")));
        assertThat(conversion.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(conversion).get("children").get(0).get("status").asText())
                .isEqualTo("QUARANTINED");
        assertThat(json(conversion).get("children").get(0).get("activeQuarantineSources").get(0).asText())
                .isEqualTo("A");

        mockMvc.perform(get("/api/lots/C"))
                .andExpect(jsonPath("$.status").value("QUARANTINED"))
                .andExpect(jsonPath("$.activeQuarantineSources.length()").value(1));

        assertThat(quarantine("A", QuarantineRequest.Action.RELEASE)
                .getResponse().getStatus()).isEqualTo(200);
        mockMvc.perform(get("/api/lots/A")).andExpect(jsonPath("$.status").value("AVAILABLE"));
        mockMvc.perform(get("/api/lots/C"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.activeQuarantineSources.length()").value(0));
    }

    @Test
    @DisplayName("隔离传播：转换先提交，随后隔离必须把已存在子/孙批次纳入影响面")
    void quarantine_conversionFirstThenPropagationIncludesChild() throws Exception {
        createLot("A", "KG", "100");
        convert("REQ-G1", "KG", "0", List.of(in("A", "20", 0)), List.of(out("B", "20")));
        convert("REQ-G2", "KG", "0", List.of(in("B", "10", 0)), List.of(out("D", "10")));

        MvcResult q = quarantine("A", QuarantineRequest.Action.QUARANTINE);
        assertThat(q.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(q).get("affected").isArray()).isTrue();

        mockMvc.perform(get("/api/lots/quarantine/A/impact"))
                .andExpect(jsonPath("$.lots.length()").value(3))
                .andExpect(jsonPath("$.lots[0].lotId").value("A"));
        mockMvc.perform(get("/api/lots/B")).andExpect(jsonPath("$.status").value("QUARANTINED"));
        mockMvc.perform(get("/api/lots/D")).andExpect(jsonPath("$.status").value("QUARANTINED"));
    }

    @Test
    @DisplayName("多来源隔离与解除次序：解除祖先来源后仍因自身来源隔离，全部解除才放行")
    void quarantine_multipleSourcesReleaseOrder() throws Exception {
        createLot("A", "KG", "100");
        convert("REQ-MS", "KG", "0", List.of(in("A", "50", 0)), List.of(out("B", "50")));

        quarantine("A", QuarantineRequest.Action.QUARANTINE);
        quarantine("B", QuarantineRequest.Action.QUARANTINE);

        mockMvc.perform(get("/api/lots/B"))
                .andExpect(jsonPath("$.status").value("QUARANTINED"))
                .andExpect(jsonPath("$.activeQuarantineSources.length()").value(2));

        // 仅解除祖先 A 的来源：B 仍因自身来源 B 保持隔离，不得提前放行。
        quarantine("A", QuarantineRequest.Action.RELEASE);
        mockMvc.perform(get("/api/lots/A")).andExpect(jsonPath("$.status").value("AVAILABLE"));
        mockMvc.perform(get("/api/lots/B"))
                .andExpect(jsonPath("$.status").value("QUARANTINED"))
                .andExpect(jsonPath("$.activeQuarantineSources.length()").value(1))
                .andExpect(jsonPath("$.activeQuarantineSources[0]").value("B"));

        // 解除自身来源后才恢复可用。
        quarantine("B", QuarantineRequest.Action.RELEASE);
        mockMvc.perform(get("/api/lots/B"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.activeQuarantineSources.length()").value(0));
    }

    @Test
    @DisplayName("解除未设置自身隔离的批次返回 422（其隔离应来自祖先来源解除）")
    void quarantine_releaseWithoutOwnSourceRejected() throws Exception {
        createLot("A", "KG", "100");
        convert("REQ-RO", "KG", "0", List.of(in("A", "10", 0)), List.of(out("B", "10")));
        quarantine("A", QuarantineRequest.Action.QUARANTINE);
        MvcResult result = quarantine("B", QuarantineRequest.Action.RELEASE);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(result).get("code").asText()).isEqualTo("NOT_QUARANTINED");
        // 解除 A 后，B 随之恢复。
        quarantine("A", QuarantineRequest.Action.RELEASE);
        mockMvc.perform(get("/api/lots/B")).andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    @DisplayName("重复设置同一隔离为幂等：不重复登记来源，影响面一致")
    void quarantine_repeatedQuarantineIsIdempotent() throws Exception {
        createLot("A", "KG", "100");
        convert("REQ-RQ", "KG", "0", List.of(in("A", "10", 0)), List.of(out("B", "10")));
        quarantine("A", QuarantineRequest.Action.QUARANTINE);
        quarantine("A", QuarantineRequest.Action.QUARANTINE);
        mockMvc.perform(get("/api/lots/B"))
                .andExpect(jsonPath("$.activeQuarantineSources.length()").value(1));
        Integer eventCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM quarantine_event WHERE source_lot_id = 'A' AND action = 'QUARANTINE'",
                Integer.class);
        assertThat(eventCount).isEqualTo(1);
    }

    @Test
    @DisplayName("谱系查询：多层链条的父母、子女、祖先、后代完整且去重")
    void genealogy_multiLevelChain() throws Exception {
        createLot("A", "KG", "100");
        createLot("X", "KG", "100");
        convert("REQ-GG1", "KG", "0",
                List.of(in("A", "20", 0), in("X", "20", 0)), List.of(out("B", "40")));
        convert("REQ-GG2", "KG", "0", List.of(in("B", "10", 0)), List.of(out("D", "10")));

        mockMvc.perform(get("/api/lots/D/genealogy"))
                .andExpect(jsonPath("$.parents.length()").value(1))
                .andExpect(jsonPath("$.ancestors.length()").value(3));
        mockMvc.perform(get("/api/lots/A/genealogy"))
                .andExpect(jsonPath("$.descendants.length()").value(2));
        mockMvc.perform(get("/api/lots/NOPE/genealogy"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("可用余额查询：隔离不改变数量台账，余额仅随消费变化")
    void balance_unaffectedByQuarantine() throws Exception {
        createLot("A", "KG", "100.000");
        convert("REQ-BA", "KG", "0", List.of(in("A", "30", 0)), List.of(out("B", "30")));
        quarantine("A", QuarantineRequest.Action.QUARANTINE);
        mockMvc.perform(get("/api/lots/A"))
                .andExpect(jsonPath("$.availableQty").value(70.000))
                .andExpect(jsonPath("$.consumedQty").value(30.000))
                .andExpect(jsonPath("$.status").value("QUARANTINED"));
    }
}
