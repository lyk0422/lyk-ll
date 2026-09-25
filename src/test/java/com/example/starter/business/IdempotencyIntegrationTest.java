package com.example.starter.business;

import com.example.starter.domain.ValveAction;
import com.example.starter.dto.AdjustRequest;
import com.example.starter.dto.AdjustResponse;
import com.example.starter.dto.ArchiveResponse;
import com.example.starter.dto.EvaluationResponse;
import com.example.starter.dto.TemperatureReportResponse;
import com.example.starter.service.BalanceService;
import com.example.starter.testsupport.AbstractIntegrationTest;
import com.example.starter.testsupport.Requests;
import com.example.starter.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 幂等语义测试：同键同参返回首次结果、同键异参 409 且开度不变、各接口重放。
 */
class IdempotencyIntegrationTest extends AbstractIntegrationTest {

    private static final String STATION = "ST-I";
    private static final List<String> UNITS = List.of("ST-I-U01", "ST-I-U02", "ST-I-U03");

    @Autowired
    private BalanceService balanceService;

    @Test
    void sameKeySameParams_returnsFirstResult_andValveMovesOnce() {
        createStation(STATION, 3);
        AdjustRequest request = Requests.adjust("idem-adj-1", STATION,
                Requests.step(UNITS.get(0), ValveAction.OPEN, "8"));

        AdjustResponse first = balanceService.adjust(request);
        AdjustResponse replay = balanceService.adjust(request);

        assertThat(first.replay()).isFalse();
        assertThat(first.taskCreated()).isTrue();
        assertThat(replay.replay()).isTrue();
        assertThat(replay.taskCreated()).isTrue();
        assertThat(replay.taskId()).isEqualTo(first.taskId());
        assertThat(replay.steps()).hasSize(1);
        assertThat(replay.steps().get(0).delta()).isEqualByComparingTo(first.steps().get(0).delta());
        assertThat(replay.steps().get(0).toOpening()).isEqualByComparingTo(first.steps().get(0).toOpening());
        assertThat(replay.steps().get(0).versionNo()).isEqualTo(first.steps().get(0).versionNo());
        // 阀门只实际动作一次
        assertThat(valveOpening(STATION, UNITS.get(0))).isEqualByComparingTo("58.000");
        assertThat(count("valve_opening_version")).isEqualTo(1);
        assertThat(count("adjustment_record")).isEqualTo(1);
        Integer replayCount = jdbc.queryForObject(
                "SELECT replay_count FROM idempotent_request WHERE request_id = ?",
                Integer.class, "idem-adj-1");
        assertThat(replayCount).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentParams_returns409_andValveUnchanged() {
        createStation(STATION, 3);
        balanceService.adjust(Requests.adjust("idem-adj-2", STATION,
                Requests.step(UNITS.get(0), ValveAction.OPEN, "5")));
        assertThat(valveOpening(STATION, UNITS.get(0))).isEqualByComparingTo("55.000");

        // 同键异参：幅度不同
        assertThatThrownBy(() -> balanceService.adjust(Requests.adjust("idem-adj-2", STATION,
                Requests.step(UNITS.get(0), ValveAction.OPEN, "6"))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("IDEMPOTENT_CONFLICT");
                });

        // 同键异参：方向不同
        assertThatThrownBy(() -> balanceService.adjust(Requests.adjust("idem-adj-2", STATION,
                Requests.step(UNITS.get(0), ValveAction.CLOSE, "5"))))
                .isInstanceOf(ApiException.class);

        // 开度与版本均未被冲突请求改变
        assertThat(valveOpening(STATION, UNITS.get(0))).isEqualByComparingTo("55.000");
        assertThat(count("valve_opening_version")).isEqualTo(1);
    }

    @Test
    void evaluateAndTemperatureAndArchive_sameKeyReplay() {
        createStation(STATION, 3);
        balanceService.reportTemperature(Requests.stationReadings(
                "idem-temp-1", STATION, UNITS, List.of("44.0", "45.0", "46.0"), clock.instant()));
        balanceService.adjust(Requests.adjust("idem-adj-3", STATION,
                Requests.step(UNITS.get(0), ValveAction.OPEN, "1")));

        EvaluationResponse eval1 = balanceService.evaluate(
                Requests.evaluate("idem-eval-1", STATION, "JUDGMENT"));
        EvaluationResponse eval1Replay = balanceService.evaluate(
                Requests.evaluate("idem-eval-1", STATION, "JUDGMENT"));
        assertThat(eval1Replay.replay()).isTrue();
        assertThat(eval1Replay.tempDiff()).isEqualByComparingTo(eval1.tempDiff());
        // 重放不重复落评估记录
        assertThat(count("evaluation_record")).isEqualTo(1);

        TemperatureReportResponse tempReplay = balanceService.reportTemperature(Requests.stationReadings(
                "idem-temp-1", STATION, UNITS, List.of("44.0", "45.0", "46.0"), clock.instant()));
        assertThat(tempReplay.replay()).isTrue();
        assertThat(tempReplay.accepted()).isEqualTo(3);
        // 温度数据只有首次真正入库 3 条
        assertThat(count("temperature_reading")).isEqualTo(3);

        ArchiveResponse archive = balanceService.archive(Requests.archive("idem-arc-1", STATION));
        ArchiveResponse archiveReplay = balanceService.archive(Requests.archive("idem-arc-1", STATION));
        assertThat(archive.archived()).isTrue();
        assertThat(archiveReplay.replay()).isTrue();
        assertThat(archiveReplay.archivedAt()).isEqualTo(archive.archivedAt());
    }

    @Test
    void failedRequest_releasesClaim_andSameParamsCanSucceed() {
        createStation(STATION, 3);
        // 首次请求因步进越界失败，不占用幂等键；修正参数后同键同参可成功
        assertThatThrownBy(() -> balanceService.adjust(Requests.adjust("idem-retry-1", STATION,
                Requests.step(UNITS.get(0), ValveAction.OPEN, "60"))))
                .isInstanceOf(ApiException.class);
        assertThat(count("idempotent_request")).isZero();

        AdjustResponse retry = balanceService.adjust(Requests.adjust("idem-retry-1", STATION,
                Requests.step(UNITS.get(0), ValveAction.OPEN, "5")));
        assertThat(retry.replay()).isFalse();
        assertThat(valveOpening(STATION, UNITS.get(0))).isEqualByComparingTo("55.000");
    }
}
