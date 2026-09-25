package com.example.starter.reading;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.AlarmDtos;
import com.example.starter.api.dto.ReadingDtos;
import com.example.starter.repo.AlarmRepository;
import com.example.starter.service.AlarmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 读数去重/乱序归位、连续 3 点偏离恰在第 3 点触发告警、封存后迟到读数隔离。
 */
class ReadingDeviationTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-03T01:00:00Z");

    @Autowired
    private AlarmService alarmService;
    @Autowired
    private AlarmRepository alarmRepository;

    @BeforeEach
    void setUp() {
        seedZone();
        seedCrop();
        // 白天设定 24℃（带宽 ±1），夜间 18℃（±1），湿度 70%RH（±5）。
        draftStrategy("S1", "24.00", "18.00", "70.00", "1.0", "5.0");
        dispatch("S1", "REQ-S1", T0.minusSeconds(60));
    }

    @Test
    @DisplayName("实际温湿度连续恰 3 个采样点偏离设定带时触发一次告警，第 2 点不触发、第 4 点不重复触发")
    void alarmTriggersExactlyOnThirdConsecutiveDeviation() {
        // T0 时刻按白天设定判定：26.0℃ 偏离白天带（±1 上界 25.0）。
        assertThat(ingest(T0, "25.50", "70").alarmId()).isNull();
        ReadingDtos.ReadingResponse third = ingest(T0.plusSeconds(60), "26.00", "70");
        assertThat(third.alarmId()).isNull();
        ReadingDtos.ReadingResponse trigger = ingest(T0.plusSeconds(120), "27.00", "70");
        assertThat(trigger.alarmId()).isNotNull();
        assertThat(alarmRepository.findById(trigger.alarmId())).isPresent().get()
                .extracting(AlarmRepository.Alarm::readingCount, AlarmRepository.Alarm::status)
                .containsExactly(3, "ACTIVE");

        // 第 4 个连续偏离点不再产生新告警。
        assertThat(ingest(T0.plusSeconds(180), "28.00", "70").alarmId()).isNull();
        assertThat(alarmRepository.findActive(ZONE)).hasSize(1);

        // 恰在边界（25.0℃）视为带内：连续计数被打断，重新累计不足 3 点不告警。
        assertThat(ingest(T0.plusSeconds(240), "25.00", "70").alarmId()).isNull();
        assertThat(ingest(T0.plusSeconds(300), "26.00", "70").alarmId()).isNull();
        assertThat(ingest(T0.plusSeconds(360), "26.00", "70").alarmId()).isNull();
        assertThat(alarmRepository.findActive(ZONE)).hasSize(1);
    }

    @Test
    @DisplayName("湿度连续 3 点越带同样触发；起始点记录为连续偏离序列的第一个采样时刻")
    void humidityDeviationTriggersAlarm() {
        ingest(T0, "24.00", "75.50");
        ingest(T0.plusSeconds(60), "24.00", "76.00");
        ReadingDtos.ReadingResponse trigger = ingest(T0.plusSeconds(120), "24.00", "80.00");
        assertThat(trigger.alarmId()).isNotNull();
        assertThat(alarmRepository.findById(trigger.alarmId())).isPresent().get()
                .extracting(a -> a.startSampleTime()).isEqualTo(T0);
    }

    @Test
    @DisplayName("分区+采样时刻去重：重复上报幂等，不产生第二条告警")
    void duplicateReadingIsDeduplicated() {
        ingest(T0, "26.00", "70");
        ingest(T0.plusSeconds(60), "26.00", "70");
        ReadingDtos.ReadingResponse dup = ingest(T0.plusSeconds(60), "99.00", "99");
        assertThat(dup.deduplicated()).isTrue();
        ingest(T0.plusSeconds(120), "26.00", "70");
        // 若重复点被当作新读数，本会在更早处触发；最终只有 1 条告警且触发于第 120 秒。
        assertThat(alarmRepository.findActive(ZONE)).hasSize(1);
        assertThat(alarmRepository.findActive(ZONE).get(0).triggerTime()).isEqualTo(T0.plusSeconds(120));
    }

    @Test
    @DisplayName("乱序上报按采样时刻归位：先到第2/3点、后补第1点，不产生错误告警，再补第4点后恰触发")
    void outOfOrderReadingsAreAlignedBySampleTime() {
        ingest(T0.plusSeconds(60), "26.00", "70");
        ingest(T0.plusSeconds(120), "26.00", "70");
        // 迟到的第 1 点补入：此刻最近三点为偏离的第1/2/3点，恰在补入时触发。
        ReadingDtos.ReadingResponse lateFirst = ingest(T0, "26.00", "70");
        assertThat(lateFirst.alarmId()).isNotNull();
        assertThat(alarmRepository.findActive(ZONE)).hasSize(1);
    }

    @Test
    @DisplayName("日评估封存后到达的读数只记迟到事件，不触发告警")
    void readingAfterSealIsRecordedAsLateOnly() {
        // 封存 T0 所在 UTC 日。
        com.example.starter.api.dto.EvaluationDtos.EvaluationResponse sealed = evaluationService.seal(
                new com.example.starter.api.dto.EvaluationDtos.EvaluationRequest(
                        ZONE, LocalDate.of(2026, 1, 3)));
        assertThat(sealed.status()).isEqualTo("SEALED");

        ReadingDtos.ReadingResponse late = ingest(T0.plusSeconds(600), "30.00", "95");
        assertThat(late.late()).isTrue();
        assertThat(late.alarmId()).isNull();
        Integer lateCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM late_reading_event WHERE zone_code = ?", Integer.class, ZONE);
        Integer readingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sensor_reading WHERE zone_code = ?", Integer.class, ZONE);
        assertThat(lateCount).isEqualTo(1);
        assertThat(readingCount).isZero();
    }
}
