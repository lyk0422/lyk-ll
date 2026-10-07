package com.example.starter.radio.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.starter.radio.model.MotherLiquor;
import com.example.starter.radio.repository.MotherLiquorRepository;
import com.example.starter.radio.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 未发药整批撤回 HTTP 层主流程与错误语义。
 * 两任务一并撤回后母液余额保持 2200（基线计算不扣库，撤回不加量）。
 */
@AutoConfigureMockMvc
class WithdrawalApiTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MotherLiquorRepository motherLiquors;

    @Test
    void withdrawTwoTasksOverHttpAndMotherBalanceStaysUnchanged() throws Exception {
        long calibratorId = postAndGetId("/api/calibrators",
                "{\"code\":\"CAL-WD-1\",\"name\":\"合成活度计\"}");
        mvc.perform(post("/api/calibrators/daily-check").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-WD-CHK\",\"calibratorId\":" + calibratorId
                                + ",\"checkDate\":\"2026-09-26\",\"referenceActivityBq\":1000.0,"
                                + "\"measuredActivityBq\":1000.0}"))
                .andExpect(status().isOk());
        long motherId = postAndGetId("/api/mother-liquors",
                "{\"batchCode\":\"ML-WD-1\",\"nuclideCode\":\"SYN-1H\",\"initialActivityBq\":2200.0,"
                        + "\"referenceTime\":\"2026-09-26T00:00:00Z\"}");
        long rx1 = postAndGetId("/api/prescriptions",
                "{\"code\":\"RX-WD-1\",\"nuclideCode\":\"SYN-1H\",\"prescribedActivityBq\":1000.0,"
                        + "\"administrationTime\":\"2026-09-26T01:00:00Z\"}");
        long rx2 = postAndGetId("/api/prescriptions",
                "{\"code\":\"RX-WD-2\",\"nuclideCode\":\"SYN-1H\",\"prescribedActivityBq\":1000.0,"
                        + "\"administrationTime\":\"2026-09-26T01:00:00Z\"}");
        long d1 = postAndGetId("/api/dispensings/calculate",
                calcBody("REQ-WD-C1", motherId, rx1, calibratorId));
        long d2 = postAndGetId("/api/dispensings/calculate",
                calcBody("REQ-WD-C2", motherId, rx2, calibratorId));

        String submitBody = "{\"requestId\":\"REQ-WD-SUBMIT\",\"reason\":\"合成整批撤回\",\"items\":["
                + "{\"dispensingId\":" + d1 + ",\"expectedVersion\":1,\"reason\":\"合成原因-甲\"},"
                + "{\"dispensingId\":" + d2 + ",\"expectedVersion\":1,\"reason\":\"合成原因-乙\"}]}";
        long withdrawalId = postWithdrawalId(submitBody);
        mvc.perform(get("/api/withdrawals/{id}", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].frozenVersionNo").value(1));

        // 同键异参（改原因）→ 409
        String conflictBody = submitBody.replace("合成整批撤回", "不同原因");
        mvc.perform(post("/api/withdrawals").contentType(MediaType.APPLICATION_JSON).content(conflictBody))
                .andExpect(status().isConflict());
        // 空清单 → 400
        mvc.perform(post("/api/withdrawals").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-WD-BAD\",\"items\":[]}"))
                .andExpect(status().isBadRequest());

        // 旧版本批准 → 409；双人批准（两名不同人员，版本 1→2→3）
        mvc.perform(post("/api/withdrawals/{id}/approvals", withdrawalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"alice\",\"expectedVersion\":2}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/withdrawals/{id}/approvals", withdrawalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"alice\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstApprover").value("alice"))
                .andExpect(jsonPath("$.versionNo").value(2));
        // 单人重复批准 → 409
        mvc.perform(post("/api/withdrawals/{id}/approvals", withdrawalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"alice\",\"expectedVersion\":2}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/withdrawals/{id}/approvals", withdrawalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"bob\",\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.secondApprover").value("bob"))
                .andExpect(jsonPath("$.versionNo").value(3));

        // 两任务一起 VOID
        mvc.perform(get("/api/dispensings/{id}", d1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOID"))
                .andExpect(jsonPath("$.firstChecker").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/dispensings/{id}", d2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOID"));

        // 已批准不能撤销 → 422
        mvc.perform(post("/api/withdrawals/{id}/cancel", withdrawalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-WD-CANCEL\",\"expectedVersion\":3}"))
                .andExpect(status().isUnprocessableEntity());

        // 证据查询：逐项冻结版本与原因、双人批准链
        mvc.perform(get("/api/withdrawals/{id}", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalChain.length()").value(2))
                .andExpect(jsonPath("$.items[0].reason").value("合成原因-甲"))
                .andExpect(jsonPath("$.items[1].frozenVersionNo").value(1));

        // 两任务一并撤回后母液余额不变
        MotherLiquor mother = motherLiquors.findById(motherId).orElseThrow();
        assertThat(mother.remainingActivityBq()).isEqualTo(2200.0);
    }

    private String calcBody(String requestId, long motherId, long rxId, long calibratorId) {
        return "{\"requestId\":\"" + requestId + "\",\"motherLiquorId\":" + motherId
                + ",\"prescriptionId\":" + rxId + ",\"calibratorId\":" + calibratorId + "}";
    }

    private long postWithdrawalId(String body) throws Exception {
        MvcResult result = mvc.perform(post("/api/withdrawals").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        String json = result.getResponse().getContentAsString();
        return Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private long postAndGetId(String url, String body) throws Exception {
        MvcResult result = mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        String json = result.getResponse().getContentAsString();
        return Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }
}
