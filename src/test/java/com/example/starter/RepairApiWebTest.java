package com.example.starter;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web 层全流程：接报研判→派单→双确认复电→确认链复算，以及同键异参 409 映射。
 */
@AutoConfigureMockMvc
class RepairApiWebTest extends AbstractRepairTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void 接报派单复电全流程与确认链复算() throws Exception {
        String report = mvc.perform(post("/api/fault-reports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"w-1","feederId":"F01","faultPoint":"10kV干线#3",
                         "affectedUsers":200,"importantUser":"市一医院","reportedAt":"2026-09-26T10:00:00"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.merged").value(false))
                .andReturn().getResponse().getContentAsString();
        long orderId = Long.parseLong(report.replaceAll(".*\"workOrderId\":(\\d+).*", "$1"));
        long team = teamId("抢修一班");

        mvc.perform(post("/api/dispatches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"w-2\",\"workOrderId\":" + orderId + ",\"teamId\":" + team + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DISPATCHED"));

        mvc.perform(post("/api/restore-confirmations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"w-3\",\"workOrderId\":" + orderId
                        + ",\"role\":\"DISPATCHER\",\"confirmer\":\"调度员甲\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restored").value(false));

        mvc.perform(post("/api/restore-confirmations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"w-4\",\"workOrderId\":" + orderId
                        + ",\"role\":\"FIELD\",\"confirmer\":\"现场员乙\",\"loadKw\":980.5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restored").value(true))
                .andExpect(jsonPath("$.status").value("RESTORED"));

        mvc.perform(get("/api/work-orders/{id}/restore-chain", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restored").value(true))
                .andExpect(jsonPath("$.confirmations", hasSize(2)))
                .andExpect(jsonPath("$.loadKw").value(980.5));
    }

    @Test
    void 同键异参经HTTP返回409() throws Exception {
        String body = """
                {"requestId":"w-5","feederId":"F02","faultPoint":"台区X",
                 "affectedUsers":50,"reportedAt":"2026-09-26T11:00:00"}
                """;
        mvc.perform(post("/api/fault-reports").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/api/fault-reports").contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("50", "51")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }
}
