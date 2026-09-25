package com.example.starter.flood;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.flood.repo.ReadingRepository;
import com.example.starter.flood.service.ReadingService;
import com.example.starter.flood.web.dto.RainfallRequest;
import com.example.starter.flood.web.dto.ReadingResult;
import com.example.starter.flood.web.dto.WarningView;
import com.example.starter.flood.web.dto.WaterReadingRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 读数 H2 集成测试：测点+采样时刻去重、乱序按时刻归位、归档后迟到只记事件。
 */
class ReadingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ReadingService readingService;
    @Autowired
    private ReadingRepository readingRepository;
    @Autowired
    private FlowSupport flow;

    private LocalDateTime t(int minute) {
        // 基准 2026-09-25 10:00（Asia/Shanghai，对应测试时钟 02:00Z）
        return LocalDateTime.of(2026, 9, 25, 10, minute, 0);
    }

    @Test
    @DisplayName("降雨读数：重复（测点+采样时刻）去重，乱序上报按采样时刻归位")
    void rainfallDedupAndOrdering() {
        String pond = flow.pondCode();
        RainfallRequest later = new RainfallRequest("ST-R1", t(20), new BigDecimal("30.5"), 60);
        RainfallRequest earlier = new RainfallRequest("ST-R1", t(10), new BigDecimal("12.0"), 60);

        ReadingResult first = readingService.reportRainfall(pond, later);
        assertThat(first.accepted()).isTrue();
        assertThat(first.dedupKey()).isEqualTo("ST-R1@" + t(20));

        ReadingResult second = readingService.reportRainfall(pond, earlier);
        assertThat(second.accepted()).isTrue();

        // 完全相同的测点+采样时刻重复上报：去重不入库
        RainfallRequest duplicate = new RainfallRequest("ST-R1", t(20), new BigDecimal("99.9"), 60);
        ReadingResult dupResult = readingService.reportRainfall(pond, duplicate);
        assertThat(dupResult.accepted()).isFalse();

        assertThat(readingRepository.countRainfall(jdbcTemplate.queryForObject(
                "SELECT id FROM pond WHERE pond_code = ?", Long.class, pond))).isEqualTo(2);

        // 归位验证：按采样时刻升序读出，先 10 分后 20 分，与上报顺序无关
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT sample_time FROM rainfall_reading ORDER BY sample_time");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("sample_time").toString()).contains("10:10");
        assertThat(rows.get(1).get("sample_time").toString()).contains("10:20");
    }

    @Test
    @DisplayName("水位读数去重：不同测点同时刻各自独立")
    void waterReadingsDistinctStations() {
        String pond = flow.pondCode();
        readingService.reportWater(pond, new WaterReadingRequest("ST-W1", t(0),
                new BigDecimal("200.0000"), new BigDecimal("150"), new BigDecimal("10")));
        readingService.reportWater(pond, new WaterReadingRequest("ST-W2", t(0),
                new BigDecimal("200.0000"), new BigDecimal("150"), new BigDecimal("10")));
        ReadingResult dup = readingService.reportWater(pond, new WaterReadingRequest("ST-W1",
                t(0), new BigDecimal("201.0000"), new BigDecimal("150"), new BigDecimal("10")));
        assertThat(dup.accepted()).isFalse();
        Long pondId = jdbcTemplate.queryForObject(
                "SELECT id FROM pond WHERE pond_code = ?", Long.class, pond);
        assertThat(readingRepository.countWater(pondId)).isEqualTo(2);
    }

    @Test
    @DisplayName("处置链归档后送达的读数（迟到读数）：只记迟到事件，不进读数表；生效链期间正常入库")
    void lateReadingAfterArchive() {
        String pond = flow.pondCode();
        Long pondId = jdbcTemplate.queryForObject(
                "SELECT id FROM pond WHERE pond_code = ?", Long.class, pond);

        // 归档前、生效链期间的读数正常入库
        WarningView warning = flow.openWarning("w-late-reading");
        ReadingResult beforeArchive = readingService.reportRainfall(pond,
                new RainfallRequest("ST-EARLY", t(0), new BigDecimal("8.0"), 30));
        assertThat(beforeArchive.late()).isFalse();
        assertThat(readingRepository.countRainfall(pondId)).isEqualTo(1);

        flow.archive("rv-late-reading", warning.warningNo(), "雨情结束复盘归档");

        // 归档后送达：无论采样时刻早晚均为迟到，仅记事件（乱序归位不适用于已终态链）
        ReadingResult lateRain = readingService.reportRainfall(pond,
                new RainfallRequest("ST-LATE", t(20), new BigDecimal("5.0"), 30));
        assertThat(lateRain.accepted()).isTrue();
        assertThat(lateRain.late()).isTrue();
        assertThat(readingRepository.countRainfall(pondId)).isEqualTo(1);
        assertThat(readingRepository.countLateEvents(pondId, "RAIN", "ST-LATE", t(20)))
                .isEqualTo(1);

        ReadingResult lateWater = readingService.reportWater(pond,
                new WaterReadingRequest("ST-LATE-W", t(5), new BigDecimal("200"),
                        new BigDecimal("150"), new BigDecimal("10")));
        assertThat(lateWater.late()).isTrue();
        assertThat(readingRepository.countWater(pondId)).isZero();
        assertThat(readingRepository.countLateEvents(pondId, "WATER", "ST-LATE-W", t(5)))
                .isEqualTo(1);
    }
}
