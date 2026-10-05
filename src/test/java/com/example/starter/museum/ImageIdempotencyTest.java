package com.example.starter.museum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 影像留档与差异工单 HTTP 层幂等测试：
 * 同键同参重放首次结果（不重复落库/不重复生成工单），同键异参 409，闭环重放幂等。
 */
@AutoConfigureMockMvc
class ImageIdempotencyTest extends MuseumTestSupport {

    @Autowired
    private MockMvc mockMvc;

    /** 申请→出借影像基线→双方交付确认，进入在展；返回合同 ID。 */
    private long onDisplayWithBaseline() {
        long contractId = ((Number) loanService.apply(newRequestId(), ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER,
                "某省博物馆", LocalDate.of(2026, 2, 1), LocalDate.of(2027, 2, 1)).get("contractId"))
                .longValue();
        imageHandoverService.registerFeatures(newRequestId(), contractId,
                ImageHandoverService.HANDOVER_DELIVERY,
                features("CORNER", "hash-a1", "GLAZE", "hash-b2", "INSCRIPTION", "hash-c3"));
        loanService.confirmHandover(contractId, "DELIVERY", LoanService.PARTY_MUSEUM);
        loanService.confirmHandover(contractId, "DELIVERY", LoanService.PARTY_BORROWER);
        return contractId;
    }

    private String returnImagesBody(String requestId, String glazeValue) {
        return """
                {"requestId":"%s","features":[
                  {"key":"CORNER","value":"hash-a1"},
                  {"key":"GLAZE","value":"%s"},
                  {"key":"INSCRIPTION","value":"hash-c3"}]}
                """.formatted(requestId, glazeValue);
    }

    @Test
    void returnImagesReplayReturnsFirstResultWithoutDuplicateOrder() throws Exception {
        long contractId = onDisplayWithBaseline();
        String requestId = UUID.randomUUID().toString();
        String body = returnImagesBody(requestId, "CHANGED");
        MvcResult first = mockMvc.perform(post("/api/loans/" + contractId + "/return-images")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/loans/" + contractId + "/return-images")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(1, count("difference_order"));
        assertEquals(1, count("difference_item"));
        assertEquals(1, count("contract_open_diff_order"));
        long returnHandoverId = jdbc.queryForObject(
                "SELECT id FROM handover WHERE contract_id = ? AND handover_type = 'RETURN'",
                Long.class, contractId);
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM handover_image_feature WHERE handover_id = ?",
                Long.class, returnHandoverId));
    }

    @Test
    void returnImagesSameKeyDifferentFeaturesReturns409() throws Exception {
        long contractId = onDisplayWithBaseline();
        String requestId = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/loans/" + contractId + "/return-images")
                .contentType(MediaType.APPLICATION_JSON).content(returnImagesBody(requestId, "CHANGED")))
                .andExpect(status().isOk());
        // 同键异参（GLAZE 特征值不同）：409，且不新增工单
        mockMvc.perform(post("/api/loans/" + contractId + "/return-images")
                .contentType(MediaType.APPLICATION_JSON).content(returnImagesBody(requestId, "ANOTHER")))
                .andExpect(status().isConflict());
        assertEquals(1, count("difference_order"));
    }

    @Test
    void identicalReturnImagesReplayStaysReleased() throws Exception {
        long contractId = onDisplayWithBaseline();
        String requestId = UUID.randomUUID().toString();
        String body = returnImagesBody(requestId, "hash-b2");
        MvcResult first = mockMvc.perform(post("/api/loans/" + contractId + "/return-images")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/loans/" + contractId + "/return-images")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(0, count("difference_order"));
    }

    @Test
    void resolveOrderReplayIsIdempotent() throws Exception {
        long contractId = onDisplayWithBaseline();
        var reg = imageHandoverService.registerFeatures(newRequestId(), contractId,
                ImageHandoverService.HANDOVER_RETURN,
                features("CORNER", "hash-a1", "GLAZE", "CHANGED", "INSCRIPTION", "hash-c3"));
        long orderId = ((Number) reg.get("orderId")).longValue();
        String requestId = UUID.randomUUID().toString();
        String body = """
                {"requestId":"%s","resolvedBy":"点交员张三"}
                """.formatted(requestId);
        MvcResult first = mockMvc.perform(post("/api/difference-orders/" + orderId + "/resolve")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/difference-orders/" + orderId + "/resolve")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(1, count("difference_order"));
        assertEquals(0, count("contract_open_diff_order"));
    }
}
