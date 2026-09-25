package com.example.starter.config;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Clock;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.example.starter.persistence.NominationRepository;
import com.example.starter.persistence.StationRepository;

/**
 * 本地启动自动初始化：仅写入幂等的合成交接站与日指定示例数据，供本地直接联调；
 * 不依赖任何外部计量系统。读数与凭证仍通过 API 产生。
 */
@Component
public class SyntheticDataSeeder implements ApplicationRunner {

    /** 示例交接站编码。 */
    public static final String DEMO_STATION = "DEMO-STATION";
    /** 示例气量日（UTC）。 */
    public static final LocalDate DEMO_GAS_DAY = LocalDate.of(2026, 9, 10);

    private final StationRepository stationRepository;
    private final NominationRepository nominationRepository;
    private final Clock clock;

    public SyntheticDataSeeder(StationRepository stationRepository,
            NominationRepository nominationRepository, Clock applicationClock) {
        this.stationRepository = stationRepository;
        this.nominationRepository = nominationRepository;
        this.clock = applicationClock;
    }

    @Override
    public void run(ApplicationArguments args) {
        Instant now = Instant.now(clock);
        if (stationRepository.findByCode(DEMO_STATION).isEmpty()) {
            stationRepository.insert(DEMO_STATION, "示例一号交接站", "示例交出方", "示例接收方", now);
            nominationRepository.save(DEMO_STATION, DEMO_GAS_DAY, new BigDecimal("8000"),
                    new BigDecimal("0.5"), now);
        }
    }
}
