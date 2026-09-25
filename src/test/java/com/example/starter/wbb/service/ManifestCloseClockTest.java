package com.example.starter.wbb.service;

import com.example.starter.wbb.AbstractH2IntegrationTest;
import com.example.starter.wbb.config.SyntheticDataSeeder;
import com.example.starter.wbb.dto.CheckinRequest;
import com.example.starter.wbb.dto.CloseRequest;
import com.example.starter.wbb.dto.LoadConfirmRequest;
import com.example.starter.wbb.dto.ManifestResponse;
import com.example.starter.wbb.dto.PayloadItemDto;
import com.example.starter.wbb.dto.PreplanRequest;
import com.example.starter.wbb.dto.ReviewRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 时间相关测试：以固定 Clock 驱动业务时钟，关闭时间断言为确定值（Asia/Shanghai）。
 */
@ContextConfiguration(classes = ManifestCloseClockTest.FixedClockConfig.class)
class ManifestCloseClockTest extends AbstractH2IntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 26);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-26T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
        }
    }

    @Autowired
    private ManifestService service;

    @Test
    void closedAtUsesInjectedFixedClock() {
        String flight = "CA4001";
        String key = flight + "-" + DATE;
        List<PayloadItemDto> items = List.of(
                new PayloadItemDto("PAX", "CABIN", 100, new BigDecimal("8000"), new BigDecimal("16.0")),
                new PayloadItemDto("CARGO", "HOLD1", 20, new BigDecimal("2000"), new BigDecimal("16.0")));
        service.preplan(new PreplanRequest("clk-pre", flight, DATE, SyntheticDataSeeder.AIRCRAFT_TYPE,
                "B-7777", new BigDecimal("5000"), null, items));
        service.checkin(key, new CheckinRequest("clk-ci", items));
        service.loadConfirm(key, new LoadConfirmRequest("clk-load"));
        service.review(key, new ReviewRequest("clk-rwbo", "WBO", "zhang.san"));
        service.review(key, new ReviewRequest("clk-rdsp", "DSP", "li.si"));
        ManifestResponse closed = service.close(key, new CloseRequest("clk-close"));
        // 固定 UTC 04:00 → Asia/Shanghai 12:00。
        assertThat(closed.closedAt()).isEqualTo(LocalDateTime.of(2026, 9, 26, 12, 0));
    }
}
