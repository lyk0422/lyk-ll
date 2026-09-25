package com.example.starter.dye;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缸差判定：同配方两缸出缸 ΔE 差 &gt; 0.5 回修（仅一次），复检仍超差降级；恰 0.5 合格。
 * 归档终态幂等；归档后迟到测色只记事件。
 */
class LotDifferenceIntegrationTest extends FlowIntegrationTest {

    private void setup() throws Exception {
        dyestuff("D1", "200000");
        vat("V1", "500");
        vat("V2", "500");
        Map<String, String> recipe = new LinkedHashMap<>();
        recipe.put("D1", "2.0000"); // 浴比10 × 100kg → 2000g
        publishRecipe("R1", 10, recipe);
    }

    private void runToDischarged(String runNo, String vat, String outDeltaE) throws Exception {
        schedule(runNo, vat, "R1", "100");
        feed("FD-" + runNo, runNo, Map.of("D1", "2000.000"), 200);
        JsonNode m = measure(runNo, outDeltaE);
        assertThat(m.path("status").asText()).isEqualTo("DISCHARGED");
    }

    @Test
    void gapExactlyHalfPasses() throws Exception {
        setup();
        runToDischarged("RUN-A", "V1", "0.2");
        runToDischarged("RUN-B", "V2", "0.7"); // 差恰 0.5
        JsonNode judge = judge("JD-B", "RUN-B", "RUN-A", 200);
        assertThat(judge.path("disposition").asText()).isEqualTo("PASS");
        assertThat(judge.path("deltaEGap").doubleValue()).isEqualTo(0.5);
    }

    @Test
    void overGapRepairsOnceThenGradesDownAndArchives() throws Exception {
        setup();
        runToDischarged("RUN-A", "V1", "0.2");
        runToDischarged("RUN-B", "V2", "0.9"); // 差 0.7 > 0.5

        // 第一次判定：回修一次
        JsonNode first = judge("JD-1", "RUN-B", "RUN-A", 200);
        assertThat(first.path("disposition").asText()).isEqualTo("REPAIR");
        assertThat(first.path("repairCount").asInt()).isEqualTo(1);
        assertThat(first.path("status").asText()).isEqualTo("REPAIRED");
        assertThat(jdbc.queryForObject("select repair_count from vat_run where run_no='RUN-B'",
                Integer.class)).isEqualTo(1);

        // 回修复检测色仍超差（0.85 vs 0.2 = 0.65）
        JsonNode remeasure = measure("RUN-B", "0.85");
        assertThat(remeasure.path("status").asText()).isEqualTo("DISCHARGED");
        JsonNode second = judge("JD-2", "RUN-B", "RUN-A", 200);
        assertThat(second.path("disposition").asText()).isEqualTo("GRADED_DOWN");
        assertThat(second.path("repairCount").asInt()).isEqualTo(1);
        assertThat(second.path("status").asText()).isEqualTo("GRADED_DOWN");

        // 台账可复算：两条判定记录 REPAIR / GRADED_DOWN
        var results = jdbc.queryForList("select result from lot_judgement where run_no='RUN-B' order by id",
                String.class);
        assertThat(results).containsExactly("REPAIR", "GRADED_DOWN");

        // 未形成终局前不能归档（PASS/GRADED_DOWN 才可；此处已 GRADED_DOWN 可以）
        JsonNode archived = archive("AR-1", "RUN-B", 200);
        assertThat(archived.path("status").asText()).isEqualTo("ARCHIVED");

        // 重复归档（换新 requestId）幂等成功
        JsonNode again = archive("AR-2", "RUN-B", 200);
        assertThat(again.path("status").asText()).isEqualTo("ARCHIVED");
        // 归档台账仅一条 ARCHIVED（重复归档不再写）
        Integer archiveRows = jdbc.queryForObject(
                "select count(*) from lot_judgement where run_no='RUN-B' and result='ARCHIVED'",
                Integer.class);
        assertThat(archiveRows).isEqualTo(1);

        // 归档后迟到测色：只记迟到事件，缸次仍 ARCHIVED
        JsonNode late = measure("RUN-B", "0.3");
        assertThat(late.path("lateEvent").asBoolean()).isTrue();
        assertThat(late.path("status").asText()).isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForObject("select count(*) from late_color_event where run_id="
                + "(select id from vat_run where run_no='RUN-B')", Integer.class)).isEqualTo(1);
        String runStatus = jdbc.queryForObject("select status from vat_run where run_no='RUN-B'",
                String.class);
        assertThat(runStatus).isEqualTo("ARCHIVED");

        // 归档后不能再判定
        judge("JD-3", "RUN-B", "RUN-A", 422);
    }

    @Test
    void cannotArchiveBeforeFinalDisposition() throws Exception {
        setup();
        runToDischarged("RUN-A", "V1", "0.9");
        // 尚未判定，无 PASS/GRADED_DOWN 处置，不能归档
        archive("AR-EARLY", "RUN-A", 422);
    }

    @Test
    void judgementRequiresDischargedState() throws Exception {
        setup();
        schedule("RUN-A", "V1", "R1", "100");
        // 未投料测色直接判定 → 422
        judge("JD-EARLY", "RUN-A", null, 422);
    }

    @Test
    void judgeAndArchiveIdempotentReplayAndConflict() throws Exception {
        setup();
        runToDischarged("RUN-A", "V1", "0.2");
        runToDischarged("RUN-B", "V2", "0.3"); // 差 0.1 合格

        // 首次判定
        JsonNode first = judge("JD-SAME", "RUN-B", "RUN-A", 200);
        assertThat(first.path("disposition").asText()).isEqualTo("PASS");
        // 同键同参重放：返回首次结果，台账不重复
        JsonNode replay = judge("JD-SAME", "RUN-B", "RUN-A", 200);
        assertThat(replay.path("disposition").asText()).isEqualTo("PASS");
        assertThat(jdbc.queryForObject(
                "select count(*) from lot_judgement where request_id='JD-SAME'", Integer.class))
                .isEqualTo(1);
        // 同键异参（换对照缸）→ 409
        judge("JD-SAME", "RUN-B", null, 409);

        // 归档同键重放
        archive("AR-SAME", "RUN-B", 200);
        archive("AR-SAME", "RUN-B", 200);
        assertThat(jdbc.queryForObject(
                "select count(*) from lot_judgement where request_id='AR-SAME'", Integer.class))
                .isEqualTo(1);
    }
}
