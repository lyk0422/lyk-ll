package com.example.starter;

import java.util.List;

import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfig;
import com.example.starter.web.dto.BatchView;
import com.example.starter.web.dto.DeclareRequest;
import com.example.starter.web.dto.DeclareResponse;
import com.example.starter.web.dto.InspectionRequest;
import com.example.starter.web.dto.InspectionResponse;
import com.example.starter.web.dto.JudgmentRequest;
import com.example.starter.web.dto.JudgmentResponse;
import com.example.starter.web.dto.PreQuarantineRequest;
import com.example.starter.web.dto.PreQuarantineResponse;
import com.example.starter.web.dto.RiskBindRequest;
import com.example.starter.web.dto.RiskChangeRequest;
import com.example.starter.web.dto.RiskChecklistResponse;
import com.example.starter.web.dto.RiskResultRequest;
import com.example.starter.web.dto.RiskResultResponse;
import com.example.starter.web.dto.RiskSummaryView;
import com.example.starter.web.dto.ShipmentProgressView;
import com.example.starter.web.dto.SplitEnableRequest;
import com.example.starter.web.dto.SplitEnableResponse;
import com.example.starter.web.dto.VehicleCreateRequest;
import com.example.starter.web.dto.VehicleDepartRequest;
import com.example.starter.web.dto.VehicleRevokeRequest;
import com.example.starter.web.dto.VehicleView;
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
        jdbc.update("DELETE FROM shipment_head");
        jdbc.update("DELETE FROM shipment_vehicle");
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

    // ---------- 分车出厂流程辅助 ----------

    protected SplitEnableResponse enableSplit(String requestId, String batchNo) throws Exception {
        return postOk("/api/batches/" + batchNo + "/shipments/enable",
                new SplitEnableRequest(requestId, "分车员甲", clock.instant().toString()),
                SplitEnableResponse.class);
    }

    protected VehicleView createVehicle(String requestId, String batchNo, String vehicleNo,
                                        List<String> headNos, int expectedVersion) throws Exception {
        return postOk("/api/batches/" + batchNo + "/shipments/vehicles",
                new VehicleCreateRequest(requestId, vehicleNo, headNos, expectedVersion,
                        clock.instant().toString()),
                VehicleView.class);
    }

    protected VehicleView revokeVehicle(String requestId, String batchNo, String vehicleNo,
                                        int expectedVersion) throws Exception {
        return postOk("/api/batches/" + batchNo + "/shipments/vehicles/" + vehicleNo + "/revoke",
                new VehicleRevokeRequest(requestId, expectedVersion, clock.instant().toString()),
                VehicleView.class);
    }

    protected VehicleView departVehicle(String requestId, String batchNo, String vehicleNo,
                                        int expectedVersion) throws Exception {
        return postOk("/api/batches/" + batchNo + "/shipments/vehicles/" + vehicleNo + "/depart",
                new VehicleDepartRequest(requestId, expectedVersion, clock.instant().toString(), "核销员甲"),
                VehicleView.class);
    }

    protected ShipmentProgressView getShipment(String batchNo) throws Exception {
        String json = mockMvc.perform(get("/api/batches/{batchNo}/shipments", batchNo))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return om.readValue(json, ShipmentProgressView.class);
    }

    /**
     * 准备一个已判定分流完成的批次：全部头只同步检验后按给定类别逐头判定。
     * 头只编号与类别按下标一一对应，返回该批头只编号列表。
     */
    protected List<String> readyJudgedBatch(String batchNo, List<String> headNos,
                                            List<String> categories) throws Exception {
        declareOk("d-" + batchNo, batchNo, headNos);
        preQuarantineOk(batchNo, "pq-" + batchNo);
        for (int i = 0; i < headNos.size(); i++) {
            inspectOk(batchNo, "i-" + batchNo + "-" + i, headNos.get(i));
        }
        JudgmentRequest.JudgmentItem[] items = new JudgmentRequest.JudgmentItem[headNos.size()];
        for (int i = 0; i < headNos.size(); i++) {
            items[i] = new JudgmentRequest.JudgmentItem(headNos.get(i), categories.get(i), null, null);
        }
        postOk("/api/batches/" + batchNo + "/judgments",
                new JudgmentRequest("j-" + batchNo, List.of(items)), JudgmentResponse.class);
        return headNos;
    }
}
