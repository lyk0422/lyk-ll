package com.example.starter.fermentation;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 幂等与失败分支补充：双乙酰/降温/判定同键同参回放、同键异参 409 且不改变曲线版本；
 * 后熟保温温度超差导致判定不合格；复审批次禁止再次判定。
 */
class IdempotencyAndFailureIntegrationTest extends AbstractIntegrationTest {

    private void prepareBatchThroughPrimary(String batchNo, String tank) throws Exception {
        mockMvc.perform(post("/api/fermentation/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.createBatch(batchNo, tank, TestData.T0))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/fermentation/batches/temperatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.temp("init-" + batchNo, batchNo,
                                TestData.T0.plusSeconds(3600), "10.00"))))
                .andExpect(status().isOk());
    }

    private void passDiacetylPair(String batchNo, String prefix) throws Exception {
        Instant d1 = TestData.T0.plusSeconds(2 * 3600);
        mockMvc.perform(post("/api/fermentation/batches/diacetyl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.diacetyl(prefix + "-d1", batchNo, d1, "0.08"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/fermentation/batches/diacetyl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.diacetyl(prefix + "-d2", batchNo,
                                d1.plusSeconds(12 * 3600), "0.10"))))
                .andExpect(status().isOk());
    }

    private long batchId(String batchNo) {
        return jdbc.sql("SELECT id FROM fermentation_batch WHERE batch_no = ?")
                .param(batchNo).query(Long.class).single();
    }

    @Test
    void diacetylSameKeyReplayDoesNotDuplicateAndDifferentParamsConflict() throws Exception {
        String batchNo = "B-I1";
        prepareBatchThroughPrimary(batchNo, "TANK-I1");
        Instant at = TestData.T0.plusSeconds(2 * 3600);
        String body = json(TestData.diacetyl("dia-key", batchNo, at, "0.07"));
        mockMvc.perform(post("/api/fermentation/batches/diacetyl")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/fermentation/batches/diacetyl")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valueMgL").value(0.07));

        Integer rows = jdbc.sql("SELECT COUNT(*) FROM diacetyl_test WHERE batch_id = ?")
                .param(batchId(batchNo)).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(rows).isEqualTo(1);

        mockMvc.perform(post("/api/fermentation/batches/diacetyl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.diacetyl("dia-key", batchNo, at, "0.09"))))
                .andExpect(status().isConflict());
        Integer rowsAfter = jdbc.sql("SELECT COUNT(*) FROM diacetyl_test WHERE batch_id = ?")
                .param(batchId(batchNo)).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(rowsAfter).isEqualTo(1);
    }

    @Test
    void coolingSameKeyReplayKeepsSingleVersionAndDifferentParamsConflictWithoutNewVersion()
            throws Exception {
        String batchNo = "B-I2";
        prepareBatchThroughPrimary(batchNo, "TANK-I2");
        passDiacetylPair(batchNo, "i2");

        Instant coolAt = TestData.T0.plusSeconds(40 * 3600);
        String body = json(TestData.cooling("cool-key", batchNo, coolAt));
        mockMvc.perform(post("/api/fermentation/batches/cooling")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toVersion").value(2));
        // 同键同参重放：仍只一个 COOLING 版本
        mockMvc.perform(post("/api/fermentation/batches/cooling")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toVersion").value(2));

        Integer commands = jdbc.sql("SELECT COUNT(*) FROM cooling_command WHERE batch_id = ?")
                .param(batchId(batchNo)).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(commands).isEqualTo(1);
        Integer versions = jdbc.sql("SELECT COUNT(*) FROM curve_version WHERE batch_id = ?")
                .param(batchId(batchNo)).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(versions).isEqualTo(2);

        // 同键异参：409 且不产生版本 3
        mockMvc.perform(post("/api/fermentation/batches/cooling")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.cooling("cool-key", batchNo,
                                coolAt.plusSeconds(60)))))
                .andExpect(status().isConflict());
        Integer versionsAfter = jdbc.sql("SELECT COUNT(*) FROM curve_version WHERE batch_id = ?")
                .param(batchId(batchNo)).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(versionsAfter).isEqualTo(2);
    }

    @Test
    void judgmentSameKeyReplayReturnsFirstResultAndDifferentParamsConflict() throws Exception {
        String batchNo = "B-I3";
        prepareBatchThroughPrimary(batchNo, "TANK-I3");
        passDiacetylPair(batchNo, "i3");
        Instant coolAt = TestData.T0.plusSeconds(40 * 3600);
        mockMvc.perform(post("/api/fermentation/batches/cooling")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.cooling("i3-cool", batchNo, coolAt))))
                .andExpect(status().isOk());
        Instant maturationAt = coolAt.plusSeconds(3600);
        mockMvc.perform(post("/api/fermentation/batches/{0}/maturation", batchNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.maturation(maturationAt))))
                .andExpect(status().isOk());
        // -1.00℃ 保温，恰 7 天合格
        mockMvc.perform(post("/api/fermentation/batches/temperatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.temp("i3-m1", batchNo, maturationAt, "-1.00"))))
                .andExpect(status().isOk());
        Instant judgeAt = maturationAt.plus(java.time.Duration.ofDays(7));
        String body = json(TestData.judge("judge-key", batchNo, judgeAt));
        mockMvc.perform(post("/api/fermentation/batches/judgments")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true));
        // 重放返回首次结果；判定记录仅一条
        mockMvc.perform(post("/api/fermentation/batches/judgments")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true));
        Integer judgments = jdbc.sql("SELECT COUNT(*) FROM maturity_judgment WHERE batch_id = ?")
                .param(batchId(batchNo)).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(judgments).isEqualTo(1);
        // 同键异参 409
        mockMvc.perform(post("/api/fermentation/batches/judgments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.judge("judge-key", batchNo,
                                judgeAt.plusSeconds(60)))))
                .andExpect(status().isConflict());
    }

    @Test
    void maturityHoldDurationMetButTemperatureOutOfRangeFailsAndExtends() throws Exception {
        String batchNo = "B-I4";
        prepareBatchThroughPrimary(batchNo, "TANK-I4");
        passDiacetylPair(batchNo, "i4");
        Instant coolAt = TestData.T0.plusSeconds(40 * 3600);
        mockMvc.perform(post("/api/fermentation/batches/cooling")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.cooling("i4-cool", batchNo, coolAt))))
                .andExpect(status().isOk());
        Instant maturationAt = coolAt.plusSeconds(3600);
        mockMvc.perform(post("/api/fermentation/batches/{0}/maturation", batchNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.maturation(maturationAt))))
                .andExpect(status().isOk());
        // 时长恰 7 天，但保温读数 -1.60℃ 偏离目标 0.60℃（超 0.5℃）→ 不合格、延长一次
        Instant judgeAt = maturationAt.plus(java.time.Duration.ofDays(7));
        mockMvc.perform(post("/api/fermentation/batches/temperatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.temp("i4-m1", batchNo, judgeAt, "-1.60"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outOfRange").value(true));
        mockMvc.perform(post("/api/fermentation/batches/judgments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.judge("i4-j1", batchNo, judgeAt))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.extensionGranted").value(true));
    }

    @Test
    void reviewBatchRejectsFurtherJudgmentAndKeepsReviewStatus() throws Exception {
        String batchNo = "B-I5";
        prepareBatchThroughPrimary(batchNo, "TANK-I5");
        passDiacetylPair(batchNo, "i5");
        Instant coolAt = TestData.T0.plusSeconds(40 * 3600);
        mockMvc.perform(post("/api/fermentation/batches/cooling")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.cooling("i5-cool", batchNo, coolAt))))
                .andExpect(status().isOk());
        Instant maturationAt = coolAt.plusSeconds(3600);
        mockMvc.perform(post("/api/fermentation/batches/{0}/maturation", batchNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.maturation(maturationAt))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/fermentation/batches/temperatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.temp("i5-m0", batchNo, maturationAt, "-1.00"))))
                .andExpect(status().isOk());

        Instant j1 = maturationAt.plus(java.time.Duration.ofDays(2));
        mockMvc.perform(post("/api/fermentation/batches/judgments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.judge("i5-j1", batchNo, j1))))
                .andExpect(jsonPath("$.extensionGranted").value(true));
        mockMvc.perform(post("/api/fermentation/batches/temperatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.temp("i5-m1", batchNo,
                                j1.plusSeconds(3600), "-1.00"))))
                .andExpect(status().isOk());
        Instant j2 = j1.plus(java.time.Duration.ofDays(2));
        mockMvc.perform(post("/api/fermentation/batches/judgments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.judge("i5-j2", batchNo, j2))))
                .andExpect(jsonPath("$.batchStatus").value("REVIEW"));

        // 复审状态再次判定 → 409，状态不变
        mockMvc.perform(post("/api/fermentation/batches/judgments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.judge("i5-j3", batchNo,
                                j2.plus(java.time.Duration.ofDays(30))))))
                .andExpect(status().isConflict());
        String status = jdbc.sql("SELECT status FROM fermentation_batch WHERE batch_no = ?")
                .param(batchNo).query(String.class).single();
        org.assertj.core.api.Assertions.assertThat(status).isEqualTo("REVIEW");
    }
}
