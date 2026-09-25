package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.repository.BatteryReadingRepository;
import com.example.starter.service.BusinessException;
import com.example.starter.web.dto.ApiDtos.BatteryReadingResponse;

/**
 * 电池容量读数去重与乱序归位测试。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_reading;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class BatteryReadingTest extends BaseIntegrationTest {

    @Autowired
    private BatteryReadingRepository readingRepository;

    @Test
    void outOfOrderReadingsArePositionedByReadingTime() {
        givenStation("BS-1", "50", "100");
        // 乱序到达：先到晚读数，再到早读数。
        givenReading("req-r1", "BS-1", T0.plus(Duration.ofMinutes(10)), "90");
        givenReading("req-r2", "BS-1", T0.plus(Duration.ofMinutes(5)), "50");
        givenReading("req-r3", "BS-1", T0, "100");

        // 按抄表时刻归位：T0+10 前最近一次是 90%。
        assertThat(readingRepository.findLatestAtOrBefore("BS-1", T0.plus(Duration.ofMinutes(10)))
                .orElseThrow().remainingPercent()).isEqualByComparingTo("90");
        // T0+6 前最近一次是 T0+5 的 50%。
        assertThat(readingRepository.findLatestAtOrBefore("BS-1", T0.plus(Duration.ofMinutes(6)))
                .orElseThrow().remainingPercent()).isEqualByComparingTo("50");
        // T0+4 前最近一次是 T0 的 100%。
        assertThat(readingRepository.findLatestAtOrBefore("BS-1", T0.plus(Duration.ofMinutes(4)))
                .orElseThrow().remainingPercent()).isEqualByComparingTo("100");
    }

    @Test
    void sameStationAndReadingTimeIsDeduplicated() {
        givenStation("BS-2", "50", "100");
        BatteryReadingResponse first = givenReading("req-r4", "BS-2", T0, "80");
        // 不同 requestId、相同“基站＋抄表时刻＋数值”：命中去重，返回已有读数。
        BatteryReadingResponse second = givenReading("req-r5", "BS-2", T0, "80");

        assertThat(first.deduplicated()).isFalse();
        assertThat(second.deduplicated()).isTrue();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(count("battery_reading")).isEqualTo(1);
    }

    @Test
    void sameStationAndTimeWithDifferentValueIsRejected() {
        givenStation("BS-3", "50", "100");
        givenReading("req-r6", "BS-3", T0, "80");

        assertThatThrownBy(() -> givenReading("req-r7", "BS-3", T0, "75"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).code())
                .isEqualTo("READING_CONFLICT");
        assertThat(count("battery_reading")).isEqualTo(1);
    }

    @Test
    void readingForUnknownStationReturnsNotFound() {
        assertThatThrownBy(() -> givenReading("req-r8", "BS-MISSING", T0, "80"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).code())
                .isEqualTo("STATION_NOT_FOUND");
    }
}
