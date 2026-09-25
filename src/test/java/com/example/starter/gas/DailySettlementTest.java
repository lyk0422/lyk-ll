package com.example.starter.gas;

import com.example.starter.gas.service.GasStationService;
import com.example.starter.gas.web.IdempotencyConflictException;
import com.example.starter.gas.web.dto.DetectionRequest;
import com.example.starter.gas.web.dto.SettlementRequest;
import com.example.starter.gas.web.dto.SettlementResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 日结勾稽：恰 3% 合格封存、超差转人工、封存终态幂等、封存后迟到检测只记事件。
 */
@Import(FixedClockConfig.class)
class DailySettlementTest extends AbstractGasIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);

    @Autowired
    private GasStationService service;

    /** 写入一条合格浓度检测，流量 1000m³，按指定实耗上报。 */
    private void feedOneReading(String requestId, String actualConsumeG) {
        service.detect(new DetectionRequest(requestId, STATION, DAY.atTime(8, 0),
                new BigDecimal("20.00"), new BigDecimal("1000"), new BigDecimal(actualConsumeG)));
    }

    @Test
    void deviationExactlyThreePercentPassesAndSeals() {
        // 理论 1000*0.02 = 20g，实耗 20.6g，偏差恰 0.03 合格
        feedOneReading("D-3PCT", "20.600000");

        SettlementResponse resp = service.settle(new SettlementRequest("S-1", STATION, DAY));
        assertThat(resp.status()).isEqualTo("SEALED");
        assertThat(resp.withinTolerance()).isTrue();
        assertThat(resp.deviationRatio()).isEqualByComparingTo("0.030000");
        assertThat(resp.totalFlowM3()).isEqualByComparingTo("1000");
        assertThat(resp.theoreticalConsumeG()).isEqualByComparingTo("20.000000");
        assertThat(resp.actualConsumeG()).isEqualByComparingTo("20.600000");
        assertThat(resp.sealedAt()).isNotNull();
    }

    @Test
    void deviationJustAboveThreePercentGoesManual() {
        // 实耗 20.61g，偏差 0.0305 > 0.03 转人工
        feedOneReading("D-MANUAL", "20.610000");

        SettlementResponse resp = service.settle(new SettlementRequest("S-1", STATION, DAY));
        assertThat(resp.status()).isEqualTo("MANUAL");
        assertThat(resp.withinTolerance()).isFalse();
        assertThat(resp.deviationRatio()).isEqualByComparingTo("0.030500");
        assertThat(resp.sealedAt()).isNull();
        assertThat(countOf("station_event", "event_kind = 'MANUAL_REVIEW'")).isEqualTo(1);
    }

    @Test
    void sealedSettlementIsTerminalAndRepeatedSealIsIdempotent() {
        feedOneReading("D-SEAL", "20.600000");
        SettlementResponse first = service.settle(new SettlementRequest("S-FIRST", STATION, DAY));
        assertThat(first.status()).isEqualTo("SEALED");

        // 新 requestId 重复封存：幂等成功，返回首次结果，不产生第二条结算单
        SettlementResponse repeat = service.settle(new SettlementRequest("S-AGAIN", STATION, DAY));
        assertThat(repeat.idempotentReplayed()).isTrue();
        assertThat(repeat.status()).isEqualTo("SEALED");
        assertThat(repeat.requestId()).isEqualTo("S-FIRST");
        assertThat(replayHasNoNewSettlement());

        // 原 requestId 同参重放也返回首次结果
        SettlementResponse replay = service.settle(new SettlementRequest("S-FIRST", STATION, DAY));
        assertThat(replay.idempotentReplayed()).isTrue();
        assertThat(replayHasNoNewSettlement());
    }

    @Test
    void sameKeyDifferentDateReturns409() {
        feedOneReading("D-409", "20.000000");
        service.settle(new SettlementRequest("S-CONF", STATION, DAY));
        assertThatThrownBy(() -> service.settle(
                new SettlementRequest("S-CONF", STATION, DAY.plusDays(1))))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void lateDetectionAfterSealOnlyRecordsLateEvent() {
        feedOneReading("D-EARLY", "20.000000");
        SettlementResponse sealed = service.settle(new SettlementRequest("S-SEAL", STATION, DAY));
        assertThat(sealed.status()).isEqualTo("SEALED");

        LocalDateTime lateAt = DAY.atTime(23, 30);
        var lateResp = service.detect(new DetectionRequest("D-LATE", STATION, lateAt,
                new BigDecimal("25"), new BigDecimal("500"), null));

        // 迟到数据不参与曲线/流量/投加，只登记迟到事件
        assertThat(lateResp.acceptedAsLate()).isTrue();
        assertThat(countOf("late_detection_event", "request_id = ?", "D-LATE")).isEqualTo(1);
        assertThat(countOf("concentration_detection", "request_id = ?", "D-LATE")).isZero();
        assertThat(countOf("flow_reading", "request_id = ?", "D-LATE")).isZero();
        assertThat(countOf("odorant_injection", "source_request_id = ?", "D-LATE")).isZero();
        assertThat(countOf("station_event", "event_kind = 'LATE_DETECTION'")).isEqualTo(1);
        // 封存终态结果不受迟到数据影响
        SettlementResponse repeat = service.settle(new SettlementRequest("S-SEAL", STATION, DAY));
        assertThat(replayHasNoNewSettlement());
        assertThat(repeat.totalFlowM3()).isEqualByComparingTo("1000");
    }

    private boolean replayHasNoNewSettlement() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(1) FROM daily_settlement WHERE station_code = ? AND settle_date = ?",
                Integer.class, STATION, java.sql.Date.valueOf(DAY));
        return n != null && n == 1;
    }
}
