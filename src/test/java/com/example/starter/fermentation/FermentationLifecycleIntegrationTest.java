package com.example.starter.fermentation;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全流程：双乙酰恰 0.10、采样恰 12 小时配对降温、曲线切换原子性、
 * 后熟恰 7 天合格、延长一次与复审、转出终态与迟到事件。
 */
class FermentationLifecycleIntegrationTest extends AbstractIntegrationTest {

    private void createBatch(String batchNo, String tankCode) throws Exception {
        mockMvc.perform(post("/api/fermentation/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.createBatch(batchNo, tankCode, TestData.T0))))
                .andExpect(status().isCreated());
    }

    private void temp(String batchNo, String reqId, Instant at, String temp) throws Exception {
        mockMvc.perform(post("/api/fermentation/batches/temperatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.temp(reqId, batchNo, at, temp))))
                .andExpect(status().isOk());
    }

    @Test
    void fullFlow_diacetylBoundary_coolingAtomically_maturitySevenDays_transferTerminal() throws Exception {
        String batchNo = "B-F1";
        createBatch(batchNo, "TANK-F1");

        // 主酵先有一条温度读数进入 PRIMARY（10.00℃ 合格）
        temp(batchNo, "t-init", TestData.T0.plusSeconds(3600), "10.00");

        // 第 1 次双乙酰 0.09
        Instant d1 = TestData.T0.plusSeconds(3600);
        mockMvc.perform(post("/api/fermentation/batches/diacetyl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.diacetyl("d-1", batchNo, d1, "0.09"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.pairedForCooling").value(false));

        // 第 2 次恰 0.10，间隔恰 12 小时 → 配对成功
        Instant d2 = d1.plusSeconds(12 * 3600);
        mockMvc.perform(post("/api/fermentation/batches/diacetyl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.diacetyl("d-2", batchNo, d2, "0.10"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.hoursSincePreviousPassing").value(12))
                .andExpect(jsonPath("$.pairedForCooling").value(true));

        // 降温指令：与曲线版本切换同事务，版本 1→2
        Instant coolAt = d2.plusSeconds(600);
        mockMvc.perform(post("/api/fermentation/batches/cooling")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.cooling("c-1", batchNo, coolAt)))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromVersion").value(1))
                .andExpect(jsonPath("$.toVersion").value(2))
                .andExpect(jsonPath("$.batchStatus").value("COOLING"));

        // 降温后新曲线目标 -1℃，证明未按旧曲线 10℃ 降温
        // 进入后熟
        Instant maturationAt = coolAt.plusSeconds(3600);
        mockMvc.perform(post("/api/fermentation/batches/{0}/maturation", batchNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.maturation(maturationAt))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MATURING"));

        // 后熟保温读数：-1.00℃，目标来自新版本曲线
        mockMvc.perform(post("/api/fermentation/batches/temperatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.temp("m-1", batchNo, maturationAt, "-1.00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetTemp").value(-1.00))
                .andExpect(jsonPath("$.outOfRange").value(false));

        // 恰 7 天判定 → 合格
        Instant judgeAt = maturationAt.plus(java.time.Duration.ofDays(7));
        mockMvc.perform(post("/api/fermentation/batches/temperatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.temp("m-2", batchNo, judgeAt, "-1.20"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/fermentation/batches/judgments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.judge("j-1", batchNo, judgeAt))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.extensionGranted").value(false))
                .andExpect(jsonPath("$.batchStatus").value("MATURING"));

        // 清酒转出
        Instant transferAt = judgeAt.plusSeconds(3600);
        String transferBody = json(TestData.transfer("tr-1", batchNo, transferAt));
        mockMvc.perform(post("/api/fermentation/batches/transfers")
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchStatus").value("TRANSFERRED"))
                .andExpect(jsonPath("$.replayed").value(false));

        // 同键同参重放：原样返回首次结果快照（终态与首次转出时刻一致，不重复落库）
        mockMvc.perform(post("/api/fermentation/batches/transfers")
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchStatus").value("TRANSFERRED"))
                .andExpect(jsonPath("$.transferredAt").value(transferAt.toString()));

        Integer transferRows = jdbc.sql(
                        "SELECT COUNT(*) FROM transfer_record tr JOIN fermentation_batch b ON b.id = tr.batch_id "
                                + "WHERE b.batch_no = ?")
                .param(batchNo).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(transferRows).isEqualTo(1);

        // 新 requestId 重复转出：幂等成功，返回首次转出时刻
        mockMvc.perform(post("/api/fermentation/batches/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.transfer("tr-2", batchNo, transferAt.plusSeconds(7200)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.transferredAt").value(transferAt.toString()));

        // 同键异参 409
        mockMvc.perform(post("/api/fermentation/batches/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.transfer("tr-1", batchNo, transferAt.plusSeconds(9999)))))
                .andExpect(status().isConflict());

        // 转出后迟到温度与双乙酰：只记迟到事件
        mockMvc.perform(post("/api/fermentation/batches/temperatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.temp("late-t", batchNo, transferAt.plusSeconds(60), "-1.00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.late").value(true));
        mockMvc.perform(post("/api/fermentation/batches/diacetyl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.diacetyl("late-d", batchNo,
                                transferAt.plusSeconds(120), "0.05"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.late").value(true));

        long batchId = jdbc.sql("SELECT id FROM fermentation_batch WHERE batch_no = ?")
                .param(batchNo).query(Long.class).single();
        Integer lateCount = jdbc.sql("SELECT COUNT(*) FROM late_event WHERE batch_id = ?")
                .param(batchId).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(lateCount).isEqualTo(2);
    }

    @Test
    void diacetylPairRequiresExactlyTwelveHoursAndCoolingLeavesVersionUntouchedOnFailure() throws Exception {
        String batchNo = "B-F2";
        createBatch(batchNo, "TANK-F2");
        temp(batchNo, "i-1", TestData.T0.plusSeconds(3600), "10.00");

        Instant d1 = TestData.T0.plusSeconds(3600);
        mockMvc.perform(post("/api/fermentation/batches/diacetyl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.diacetyl("dd-1", batchNo, d1, "0.08"))))
                .andExpect(jsonPath("$.passed").value(true));

        // 间隔 11 小时 59 分，且第二次 0.11 不合格
        Instant d2 = d1.plus(java.time.Duration.ofHours(11).plusMinutes(59));
        mockMvc.perform(post("/api/fermentation/batches/diacetyl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.diacetyl("dd-2", batchNo, d2, "0.11"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.pairedForCooling").value(false));

        // 降温被拒 409
        mockMvc.perform(post("/api/fermentation/batches/cooling")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.cooling("cc-1", batchNo, d2.plusSeconds(60)))))
                .andExpect(status().isConflict());

        // 曲线版本未切换、批次仍 PRIMARY：失败未改变曲线版本
        Integer versions = jdbc.sql(
                        "SELECT COUNT(*) FROM curve_version v JOIN fermentation_batch b ON b.id = v.batch_id "
                                + "WHERE b.batch_no = ?")
                .param(batchNo).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(versions).isEqualTo(1);
        String status = jdbc.sql("SELECT status FROM fermentation_batch WHERE batch_no = ?")
                .param(batchNo).query(String.class).single();
        org.assertj.core.api.Assertions.assertThat(status).isEqualTo("PRIMARY");

        // 补一次恰 12 小时间隔的合格检测（与最近一次合格 d1 间隔超过 12 小时，但链尾是不合格 0.11，仍不配对）
        Instant d3 = d1.plus(java.time.Duration.ofHours(24));
        mockMvc.perform(post("/api/fermentation/batches/diacetyl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.diacetyl("dd-3", batchNo, d3, "0.10"))))
                .andExpect(jsonPath("$.pairedForCooling").value(false));

        // 再与 d3 恰间隔 12 小时的合格检测 → 配对成功
        Instant d4 = d3.plus(java.time.Duration.ofHours(12));
        mockMvc.perform(post("/api/fermentation/batches/diacetyl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.diacetyl("dd-4", batchNo, d4, "0.09"))))
                .andExpect(jsonPath("$.pairedForCooling").value(true));
    }

    @Test
    void maturityFailureExtendsOnceThenPasses() throws Exception {
        String batchNo = "B-F3";
        createBatch(batchNo, "TANK-F3");
        temp(batchNo, "p-1", TestData.T0.plusSeconds(3600), "10.00");
        Instant d1 = TestData.T0.plusSeconds(3600);
        mockMvc.perform(post("/api/fermentation/batches/diacetyl")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(TestData.diacetyl("e-1", batchNo, d1, "0.05")))).andExpect(status().isOk());
        Instant d2 = d1.plus(java.time.Duration.ofHours(12));
        mockMvc.perform(post("/api/fermentation/batches/diacetyl")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(TestData.diacetyl("e-2", batchNo, d2, "0.05")))).andExpect(status().isOk());
        Instant coolAt = d2.plusSeconds(600);
        mockMvc.perform(post("/api/fermentation/batches/cooling")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.cooling("ec-1", batchNo, coolAt))))
                .andExpect(status().isOk());
        Instant maturationAt = coolAt.plusSeconds(3600);
        mockMvc.perform(post("/api/fermentation/batches/{0}/maturation", batchNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.maturation(maturationAt))))
                .andExpect(status().isOk());
        temp(batchNo, "em-0", maturationAt, "-1.00");

        // 未满 7 天判定不合格 → 延长一次
        Instant earlyJudge = maturationAt.plus(java.time.Duration.ofDays(7).minusMinutes(1));
        mockMvc.perform(post("/api/fermentation/batches/judgments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.judge("ej-1", batchNo, earlyJudge))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.extensionGranted").value(true))
                .andExpect(jsonPath("$.batchStatus").value("MATURING"));

        // 延长后继续保温 -1℃，自延长时刻起再满 7 天 → 合格
        temp(batchNo, "em-1", earlyJudge.plusSeconds(3600), "-1.00");
        Instant finalJudge = earlyJudge.plus(java.time.Duration.ofDays(7));
        temp(batchNo, "em-2", finalJudge, "-1.00");
        mockMvc.perform(post("/api/fermentation/batches/judgments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.judge("ej-2", batchNo, finalJudge))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.extensionGranted").value(false));
    }

    @Test
    void maturityFailsAgainAfterExtensionGoesReviewAndBlocksTransfer() throws Exception {
        String batchNo = "B-F4";
        createBatch(batchNo, "TANK-F4");
        temp(batchNo, "p-1", TestData.T0.plusSeconds(3600), "10.00");
        Instant d1 = TestData.T0.plusSeconds(3600);
        mockMvc.perform(post("/api/fermentation/batches/diacetyl")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(TestData.diacetyl("f-1", batchNo, d1, "0.05")))).andExpect(status().isOk());
        Instant d2 = d1.plus(java.time.Duration.ofHours(12));
        mockMvc.perform(post("/api/fermentation/batches/diacetyl")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(TestData.diacetyl("f-2", batchNo, d2, "0.05")))).andExpect(status().isOk());
        Instant coolAt = d2.plusSeconds(600);
        mockMvc.perform(post("/api/fermentation/batches/cooling")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.cooling("fc-1", batchNo, coolAt))))
                .andExpect(status().isOk());
        Instant maturationAt = coolAt.plusSeconds(3600);
        mockMvc.perform(post("/api/fermentation/batches/{0}/maturation", batchNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.maturation(maturationAt))))
                .andExpect(status().isOk());
        temp(batchNo, "fm-0", maturationAt, "-1.00");

        Instant j1 = maturationAt.plus(java.time.Duration.ofDays(3));
        // 第一次不合格 → 延长
        mockMvc.perform(post("/api/fermentation/batches/judgments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.judge("fj-1", batchNo, j1))))
                .andExpect(jsonPath("$.extensionGranted").value(true));

        // 延长后仍提前判定不合格 → 整批复审
        temp(batchNo, "fm-1", j1.plusSeconds(3600), "-1.00");
        Instant j2 = j1.plus(java.time.Duration.ofDays(3));
        mockMvc.perform(post("/api/fermentation/batches/judgments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.judge("fj-2", batchNo, j2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.extensionGranted").value(false))
                .andExpect(jsonPath("$.batchStatus").value("REVIEW"));

        // 复审批次禁止转出
        mockMvc.perform(post("/api/fermentation/batches/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TestData.transfer("ftr-1", batchNo, j2.plusSeconds(3600)))))
                .andExpect(status().isConflict());
    }
}
