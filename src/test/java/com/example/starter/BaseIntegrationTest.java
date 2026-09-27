package com.example.starter;

import java.util.List;

import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfig;
import com.example.starter.web.dto.BatchView;
import com.example.starter.web.dto.DeclareRequest;
import com.example.starter.web.dto.DeclareResponse;
import com.example.starter.web.dto.InspectionRequest;
import com.example.starter.web.dto.InspectionResponse;
import com.example.starter.web.dto.PreQuarantineRequest;
import com.example.starter.web.dto.PreQuarantineResponse;
import com.example.starter.web.dto.RiskBindRequest;
import com.example.starter.web.dto.RiskChangeRequest;
import com.example.starter.web.dto.RiskChecklistResponse;
import com.example.starter.web.dto.RiskResultRequest;
import com.example.starter.web.dto.RiskResultResponse;
import com.example.starter.web.dto.RiskSummaryView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 集成测试基类：真实 Spring 上下文 + H2（MySQL 兼容模式），每个用例前清空业务表并复位时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
abstract class BaseIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper om;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM harmless_receipt");
        jdbc.update("DELETE FROM judgment_detail");
        jdbc.update("DELETE FROM risk_sample");
        jdbc.update("DELETE FROM risk_checklist");
        jdbc.update("DELETE FROM risk_change_event");
        jdbc.update("DELETE FROM risk_binding");
        jdbc.update("DELETE FROM head_ledger");
        jdbc.update("DELETE FROM slaughter_batch");
        clock.set(TestClockConfig.T0);
    }

    protected ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)));
    }

    protected <T> T postOk(String url, Object body, Class<T> type) throws Exception {
        String json = postJson(url, body)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return om.readValue(json, type);
    }

    protected BatchView getView(String batchNo) throws Exception {
        String json = mockMvc.perform(get("/api/batches/{batchNo}", batchNo))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return om.readValue(json, BatchView.class);
    }

    // ---------- 流程辅助 ----------

    protected DeclareResponse declareOk(String requestId, String batchNo, List<String> headNos)
            throws Exception {
        return postOk("/api/batches/declare",
                new DeclareRequest(requestId, batchNo, "PIG", "合成养殖场", headNos),
                DeclareResponse.class);
    }

    protected PreQuarantineResponse preQuarantineOk(String batchNo, String requestId) throws Exception {
        return postOk("/api/batches/{batchNo}/pre-quarantine".replace("{batchNo}", batchNo),
                new PreQuarantineRequest(requestId, "检疫员甲"), PreQuarantineResponse.class);
    }

    protected InspectionResponse inspectOk(String batchNo, String requestId, String headNo)
            throws Exception {
        return postOk("/api/batches/" + batchNo + "/inspections",
                new InspectionRequest(requestId, headNo, "NORMAL"), InspectionResponse.class);
    }

    protected int countRows(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    // ---------- 风险抽检流程辅助 ----------

    protected RiskChecklistResponse bindRisk(String requestId, String batchNo, String level,
                                             String ruleVersion, List<String> declared,
                                             int expectedVersion) throws Exception {
        return postOk("/api/batches/" + batchNo + "/risk/bind",
                new RiskBindRequest(requestId, level, ruleVersion, declared, expectedVersion,
                        clock.instant().toString()),
                RiskChecklistResponse.class);
    }

    protected RiskChecklistResponse changeRisk(String requestId, String batchNo, String level,
                                               String ruleVersion, List<String> declared,
                                               int expectedVersion) throws Exception {
        return postOk("/api/batches/" + batchNo + "/risk/changes",
                new RiskChangeRequest(requestId, level, ruleVersion, declared, expectedVersion,
                        clock.instant().toString()),
                RiskChecklistResponse.class);
    }

    protected RiskResultResponse riskResult(String requestId, String batchNo, String headNo,
                                            String result, int expectedVersion) throws Exception {
        return postOk("/api/batches/" + batchNo + "/risk/results",
                new RiskResultRequest(requestId, headNo, result, expectedVersion,
                        clock.instant().toString()),
                RiskResultResponse.class);
    }

    protected RiskSummaryView getRisk(String batchNo) throws Exception {
        String json = mockMvc.perform(get("/api/batches/{batchNo}/risk", batchNo))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return om.readValue(json, RiskSummaryView.class);
    }
}
