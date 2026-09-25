package com.example.starter.business;

import com.example.starter.domain.ValveAction;
import com.example.starter.dto.AdjustResponse;
import com.example.starter.dto.ArchiveResponse;
import com.example.starter.dto.EvaluationResponse;
import com.example.starter.service.BalanceService;
import com.example.starter.testsupport.AbstractIntegrationTest;
import com.example.starter.testsupport.Requests;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全流程端到端：初调节 → 运行监测（温度上报）→ 失衡判定 → 再调节 → 稳定满 4 小时 → 效果评估 → 归档，
 * 并复算温度分布、开度版本链与前后评估对比。
 */
class FullFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String STATION = "ST-F";
    private static final List<String> UNITS = List.of("ST-F-U01", "ST-F-U02", "ST-F-U03");

    @Autowired
    private BalanceService balanceService;

    @Test
    void fullLifecycle_adjust_monitor_judge_readjust_reevaluate_archive_andRecompute() {
        createStation(STATION, 3);

        // 1) 初调节：欠流单元 U01 先开大 5%，同时创建执行中任务
        AdjustResponse firstAdjust = balanceService.adjust(Requests.adjust("flow-adj-0", STATION,
                Requests.step(UNITS.get(0), ValveAction.OPEN, "5")));
        assertThat(firstAdjust.taskCreated()).isTrue();

        // 2) 运行监测：回水温度 42/45/48，极差 6℃
        balanceService.reportTemperature(Requests.stationReadings(
                "flow-temp-1", STATION, UNITS, List.of("42.000", "45.000", "48.000"), clock.instant()));

        // 3) 失衡判定：U01 欠流、U03 过流
        EvaluationResponse judgment = balanceService.evaluate(
                Requests.evaluate("flow-judge-1", STATION, "JUDGMENT"));
        assertThat(judgment.balanced()).isFalse();
        assertThat(judgment.tempDiff()).isEqualByComparingTo("6.000");
        assertThat(judgment.underFlow()).containsExactly(UNITS.get(0));
        assertThat(judgment.overFlow()).containsExactly(UNITS.get(2));

        // 4) 再调节：对欠流/过流单元各执行恰 10% 步进
        AdjustResponse reAdjust = balanceService.adjust(Requests.adjust("flow-adj-1", STATION,
                Requests.step(UNITS.get(0), ValveAction.OPEN, "10.000"),
                Requests.step(UNITS.get(2), ValveAction.CLOSE, "10.000")));
        assertThat(reAdjust.taskCreated()).isFalse();
        assertThat(valveOpening(STATION, UNITS.get(0))).isEqualByComparingTo("65.000");
        assertThat(valveOpening(STATION, UNITS.get(2))).isEqualByComparingTo("40.000");

        // 5) 稳定恰满 4 小时后再次监测，回水温度 44/45/46，极差恰 2℃
        clock.advanceSeconds(4 * 3600);
        balanceService.reportTemperature(Requests.stationReadings(
                "flow-temp-2", STATION, UNITS, List.of("44.000", "45.000", "46.000"), clock.instant()));

        // 6) 效果评估：恰 2℃ 判平衡
        EvaluationResponse reEval = balanceService.evaluate(
                Requests.evaluate("flow-reeval-1", STATION, "RE_EVAL"));
        assertThat(reEval.balanced()).isTrue();
        assertThat(reEval.tempDiff()).isEqualByComparingTo("2.000");

        // 7) 归档终态
        ArchiveResponse archive = balanceService.archive(Requests.archive("flow-arc-1", STATION));
        assertThat(archive.archived()).isTrue();

        // 8) 复算：温度分布
        List<Map<String, Object>> distribution = balanceService.temperatureDistribution(STATION);
        assertThat(distribution).hasSize(3);
        assertThat(distribution.get(0).get("returnTemp").toString()).isEqualTo("44.000");
        assertThat(distribution.get(2).get("returnTemp").toString()).isEqualTo("46.000");

        // 复算：开度版本链共 3 条（U01 两条版本、U03 一条），链连续
        List<Map<String, Object>> chain = balanceService.openingVersionChain(STATION);
        assertThat(chain).hasSize(3);
        assertThat(chain).extracting(r -> r.get("versionNo")).containsExactly(1, 2, 1);
        assertThat(chain).extracting(r -> r.get("toOpening").toString())
                .containsExactly("55.000", "65.000", "40.000");

        // 复算：前后评估对比（先失衡 6℃，后平衡 2℃）
        List<Map<String, Object>> history = balanceService.evaluationHistory(STATION);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("phase")).isEqualTo("JUDGMENT");
        assertThat(history.get(0).get("balanced")).isEqualTo(false);
        assertThat(history.get(0).get("tempDiff").toString()).isEqualTo("6.000");
        assertThat(history.get(1).get("phase")).isEqualTo("RE_EVAL");
        assertThat(history.get(1).get("balanced")).isEqualTo(true);
        assertThat(history.get(1).get("tempDiff").toString()).isEqualTo("2.000");
    }
}
