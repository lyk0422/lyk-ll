package com.example.starter.museum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 影像留档与差异工单新写操作的幂等性测试（HTTP 层）：
 * 指纹覆盖 requestId 与全部业务字段，同键同参重放首次结果，同键异参 409。
 */
@AutoConfigureMockMvc
class ImageIdempotencyTest extends MuseumTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String newId() {
        return UUID.randomUUID().toString();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** 申请合同→登记出借影像→双方交付点交，返回合同 id。 */
    private long createDisplayedContractWithDeliveryImages(String deliveryImages) throws Exception {
        MvcResult apply = mockMvc.perform(post("/api/loans").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"%s","artifactId":1,"showcaseId":1,"borrower":"某省博物馆",
                 "startDate":"2026-02-01","endDate":"2027-02-01"}
                """.formatted(newId())))
                .andExpect(status().isCreated()).andReturn();
        long contractId = json(apply).get("contractId").longValue();
        mockMvc.perform(post("/api/loans/" + contractId + "/handovers/DELIVERY/images")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","features":%s}""".formatted(newId(), deliveryImages)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loans/" + contractId + "/handover")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","party":"MUSEUM"}""".formatted(newId())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/loans/" + contractId + "/handover")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","party":"BORROWER"}""".formatted(newId())))
                .andExpect(status().isOk());
        return contractId;
    }

    @Test
    void imageRegistrationReplayReturnsFirstResultAndConflictOnMismatch() throws Exception {
        MvcResult apply = mockMvc.perform(post("/api/loans").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"%s","artifactId":1,"showcaseId":1,"borrower":"某省博物馆",
                 "startDate":"2026-02-01","endDate":"2027-02-01"}
                """.formatted(newId())))
                .andExpect(status().isCreated()).andReturn();
        long contractId = json(apply).get("contractId").longValue();

        String requestId = newId();
        String body = """
                {"requestId":"%s","features":[
                  {"code":"FEATURE_SEAL","value":"seal-hash-A1"},
                  {"code":"FEATURE_EDGE","value":"edge-intact-B2"}]}
                """.formatted(requestId);
        MvcResult first = mockMvc.perform(post("/api/loans/" + contractId + "/handovers/DELIVERY/images")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/loans/" + contractId + "/handovers/DELIVERY/images")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(2, count("image_feature"));
        // 同键异参：409，且不新增特征
        String altered = body.replace("seal-hash-A1", "seal-hash-CHANGED");
        mockMvc.perform(post("/api/loans/" + contractId + "/handovers/DELIVERY/images")
                .contentType(MediaType.APPLICATION_JSON).content(altered))
                .andExpect(status().isConflict());
        assertEquals(2, count("image_feature"));
    }

    @Test
    void returnHandoverWithDiffReplaysFirstOrderWithoutDuplicate() throws Exception {
        String deliveryImages = """
                [{"code":"FEATURE_SEAL","value":"seal-hash-A1"},
                 {"code":"FEATURE_EDGE","value":"edge-intact-B2"},
                 {"code":"FEATURE_PATCH","value":"patch-37-C3"}]""";
        long contractId = createDisplayedContractWithDeliveryImages(deliveryImages);

        mockMvc.perform(post("/api/loans/" + contractId + "/handovers/RETURN/images")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","features":[
                          {"code":"FEATURE_SEAL","value":"seal-hash-A1"},
                          {"code":"FEATURE_EDGE","value":"edge-DAMAGED-X9"},
                          {"code":"FEATURE_PATCH","value":"patch-37-C3"}]}
                        """.formatted(newId())))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loans/" + contractId + "/return-handover")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","party":"MUSEUM"}""".formatted(newId())))
                .andExpect(status().isOk());

        String finalizeId = newId();
        String finalizeBody = """
                {"requestId":"%s","party":"BORROWER"}""".formatted(finalizeId);
        MvcResult first = mockMvc.perform(post("/api/loans/" + contractId + "/return-handover")
                .contentType(MediaType.APPLICATION_JSON).content(finalizeBody))
                .andExpect(status().isOk()).andReturn();
        JsonNode firstJson = json(first);
        assertEquals(1, firstJson.path("imageComparison").path("diffCount").asInt());
        long orderId = firstJson.path("imageComparison").path("discrepancyOrderId").longValue();

        MvcResult replay = mockMvc.perform(post("/api/loans/" + contractId + "/return-handover")
                .contentType(MediaType.APPLICATION_JSON).content(finalizeBody))
                .andExpect(status().isOk()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(1, count("discrepancy_order"));
        assertEquals(1, count("discrepancy_item"));

        // 差异工单未闭环：结案 409
        mockMvc.perform(post("/api/loans/" + contractId + "/close")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s"}""".formatted(newId())))
                .andExpect(status().isConflict());

        // 工单详情可查询，状态 OPEN
        mockMvc.perform(get("/api/discrepancy-orders/" + orderId))
                .andExpect(status().isOk());

        // 闭环工单（重放返回首次结果，不重复闭环）
        String closeOrderId = newId();
        String closeOrderBody = """
                {"requestId":"%s","handler":"点交员王五"}""".formatted(closeOrderId);
        MvcResult closed = mockMvc.perform(post("/api/discrepancy-orders/" + orderId + "/close")
                .contentType(MediaType.APPLICATION_JSON).content(closeOrderBody))
                .andExpect(status().isOk()).andReturn();
        MvcResult closedReplay = mockMvc.perform(post("/api/discrepancy-orders/" + orderId + "/close")
                .contentType(MediaType.APPLICATION_JSON).content(closeOrderBody))
                .andExpect(status().isOk()).andReturn();
        assertEquals(closed.getResponse().getContentAsString(), closedReplay.getResponse().getContentAsString());

        // 闭环后结案放行
        mockMvc.perform(post("/api/loans/" + contractId + "/close")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s"}""".formatted(newId())))
                .andExpect(status().isOk());
        assertEquals("CLOSED", contractStatus(contractId));
    }
}
