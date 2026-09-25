package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.example.starter.support.AppClock;
import com.example.starter.web.dto.DeclareLotRequest;
import com.example.starter.web.dto.FieldInspectionRequest;
import com.example.starter.web.dto.LabInspectionRequest;
import com.example.starter.web.dto.RevokeLotRequest;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * API 失败分支与补充幂等/终态边界测试（真实 H2）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CertificationApiEdgeCasesTest {

    private static final Instant BASE_TIME = Instant.parse("2026-09-01T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AppClock appClock;

    private int seq;

    @BeforeEach
    void cleanDbAndClock() {
        jdbc.update("DELETE FROM certification_audit");
        jdbc.update("DELETE FROM certificate");
        jdbc.update("DELETE FROM field_inspection");
        jdbc.update("DELETE FROM lab_inspection");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM seed_lot");
        appClock.setClock(Clock.fixed(BASE_TIME, ZoneOffset.UTC));
    }

    private String lotNo() {
        return "LOT-ED-" + (++seq);
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private ResultActions declare(DeclareLotRequest request) throws Exception {
        return mockMvc.perform(post("/api/lots/declarations")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)));
    }

    @Test
    @DisplayName("批次不存在：详情 404，发证 404")
    void notFound() throws Exception {
        mockMvc.perform(get("/api/lots/NO-SUCH-LOT"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/lots/NO-SUCH-LOT/certifications"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("请求参数非法：标签数量非正、等级非法、纯度超范围 → 400")
    void validationErrors() throws Exception {
        declare(new DeclareLotRequest("R1", lotNo(), "V-RICE-001", "P-A-01",
                "ORIGINAL", 0, "合成申报人")).andExpect(status().isBadRequest());
        declare(new DeclareLotRequest("R2", lotNo(), "V-RICE-001", "P-A-01",
                "SUPER", 10, "合成申报人")).andExpect(status().isBadRequest());

        String lotNo = lotNo();
        declare(new DeclareLotRequest("R3", lotNo, "V-RICE-001", "P-A-01",
                "ORIGINAL", 10, "合成申报人")).andExpect(status().isOk());
        FieldInspectionRequest bad = new FieldInspectionRequest("R4", lotNo,
                new BigDecimal("120.000"), "FIRST", "合成田间检验员");
        mockMvc.perform(post("/api/lots/" + lotNo + "/field-inspections")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest());
        // 校验失败不产生任何业务数据
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM field_inspection WHERE lot_no = ?", Integer.class, lotNo);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("未知品种/地块 → 422，批次不落库")
    void unknownReferenceData() throws Exception {
        String lotNo = lotNo();
        ResultActions result = declare(new DeclareLotRequest("R5", lotNo, "V-UNKNOWN",
                "P-A-01", "IMPROVED", 10, "合成申报人"));
        result.andExpect(status().isUnprocessableEntity());
        assertThat(json(result).path("code").asText()).isEqualTo("UNKNOWN_VARIETY");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM seed_lot WHERE lot_no = ?", Integer.class, lotNo);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("检验同键同参重放返回首次结论且不新增记录；同键跨类型复用 → 409 不建批次")
    void inspectionReplaySameKey() throws Exception {
        String lotNo = lotNo();
        declare(new DeclareLotRequest("R6", lotNo, "V-RICE-001", "P-A-01",
                "ORIGINAL", 10, "合成申报人")).andExpect(status().isOk());

        FieldInspectionRequest request = new FieldInspectionRequest("R6-F", lotNo,
                new BigDecimal("99.800"), "FIRST", "合成田间检验员");
        String first = json(mockMvc.perform(post("/api/lots/" + lotNo + "/field-inspections")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request))).andExpect(status().isOk()))
                .toString();
        String replay = json(mockMvc.perform(post("/api/lots/" + lotNo + "/field-inspections")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request))).andExpect(status().isOk()))
                .toString();
        assertThat(replay).isEqualTo(first);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM field_inspection WHERE lot_no = ?", Integer.class, lotNo);
        assertThat(count).isEqualTo(1);

        // requestId 全局唯一：用已存在的检验 requestId 再申报另一批次 → 409 且不建批次
        String otherLot = lotNo();
        ResultActions crossType = declare(new DeclareLotRequest("R6-F", otherLot,
                "V-RICE-001", "P-A-01", "IMPROVED", 5, "合成申报人"));
        crossType.andExpect(status().isConflict());
        assertThat(json(crossType).path("code").asText())
                .isEqualTo("IDEMPOTENT_PARAM_MISMATCH");
        Integer otherCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM seed_lot WHERE lot_no = ?", Integer.class, otherLot);
        assertThat(otherCount).isZero();
    }

    @Test
    @DisplayName("室内检测首检不合格：第 15 日复检覆盖首检，唯一有效结论，首检保留可查")
    void labRetestOverridesFirst() throws Exception {
        String lotNo = lotNo();
        declare(new DeclareLotRequest("R9", lotNo, "V-RICE-001", "P-A-01",
                "IMPROVED", 33, "合成申报人")).andExpect(status().isOk());

        mockMvc.perform(post("/api/lots/" + lotNo + "/lab-inspections")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new LabInspectionRequest("R9-L1",
                        lotNo, new BigDecimal("84.999"), "FIRST", "合成室内检测员"))))
                .andExpect(status().isOk());
        appClock.setClock(Clock.fixed(Instant.parse("2026-09-16T12:00:00Z"), ZoneOffset.UTC));
        ResultActions retest = mockMvc.perform(post("/api/lots/" + lotNo + "/lab-inspections")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new LabInspectionRequest("R9-L2",
                        lotNo, new BigDecimal("86.000"), "RETEST", "合成室内检测员"))));
        retest.andExpect(status().isOk());
        JsonNode retestJson = json(retest);
        assertThat(retestJson.path("round").asInt()).isEqualTo(2);
        assertThat(retestJson.path("verdict").asText()).isEqualTo("PASS");
        assertThat(retestJson.path("effective").asBoolean()).isTrue();

        Integer effectiveCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM lab_inspection WHERE lot_no = ? AND effective = TRUE",
                Integer.class, lotNo);
        assertThat(effectiveCount).isEqualTo(1);
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(1) FROM lab_inspection WHERE lot_no = ?", Integer.class, lotNo);
        assertThat(total).isEqualTo(2);
        Boolean firstEffective = jdbc.queryForObject(
                "SELECT effective FROM lab_inspection WHERE lot_no = ? AND inspect_round = 1",
                Boolean.class, lotNo);
        assertThat(firstEffective).isFalse();
    }

    @Test
    @DisplayName("注销终态后复检（RETEST）同样被拒绝")
    void retestRejectedAfterRevoke() throws Exception {
        String lotNo = lotNo();
        declare(new DeclareLotRequest("R7", lotNo, "V-RICE-001", "P-A-01",
                "ORIGINAL", 10, "合成申报人")).andExpect(status().isOk());
        mockMvc.perform(post("/api/lots/" + lotNo + "/field-inspections")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new FieldInspectionRequest("R7-F",
                        lotNo, new BigDecimal("99.500"), "FIRST", "合成田间检验员"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/lots/" + lotNo + "/revocation")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(
                        new RevokeLotRequest("合成注销", "合成管理员"))))
                .andExpect(status().isOk());

        ResultActions retest = mockMvc.perform(post("/api/lots/" + lotNo + "/field-inspections")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new FieldInspectionRequest("R7-FR",
                        lotNo, new BigDecimal("99.990"), "RETEST", "合成田间检验员"))));
        retest.andExpect(status().isUnprocessableEntity());
        assertThat(json(retest).path("code").asText()).isEqualTo("LOT_CANCELLED");
        Integer round2 = jdbc.queryForObject(
                "SELECT COUNT(1) FROM field_inspection WHERE lot_no = ? AND inspect_round = 2",
                Integer.class, lotNo);
        assertThat(round2).isZero();
    }

    @Test
    @DisplayName("双检未齐前重复提交另一类型首检 → 409 且不产生重复记录")
    void duplicateFirstInspectionConflict() throws Exception {
        String lotNo = lotNo();
        declare(new DeclareLotRequest("R8", lotNo, "V-RICE-001", "P-A-01",
                "IMPROVED", 10, "合成申报人")).andExpect(status().isOk());
        mockMvc.perform(post("/api/lots/" + lotNo + "/lab-inspections")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new LabInspectionRequest("R8-L1",
                        lotNo, new BigDecimal("86.000"), "FIRST", "合成室内检测员"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/lots/" + lotNo + "/lab-inspections")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new LabInspectionRequest("R8-L2",
                        lotNo, new BigDecimal("99.000"), "FIRST", "合成室内检测员"))))
                .andExpect(status().isConflict());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM lab_inspection WHERE lot_no = ? AND inspect_round = 1",
                Integer.class, lotNo);
        assertThat(count).isEqualTo(1);
    }
}
