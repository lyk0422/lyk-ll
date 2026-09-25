package com.example.starter.schedule.config;

import com.example.starter.schedule.repository.MasterDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 合成数据初始化：卫星、地面站与过境预报窗口，全部仅存于 JVM 内存。
 *
 * <p>为便于演示，按启动时 UTC 日期为每个站生成“昨天/今天/明天”三天窗口，
 * 其中包含端点相接与内部重叠两种典型拓扑。</p>
 */
@Component
@Order(0)
@ConditionalOnProperty(name = "schedule.synthetic-seed.enabled", havingValue = "true",
        matchIfMissing = true)
public class SyntheticDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SyntheticDataSeeder.class);

    private final MasterDataRepository masterData;
    private final Clock clock;

    public SyntheticDataSeeder(MasterDataRepository masterData, Clock clock) {
        this.masterData = masterData;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!masterData.findAllStations().isEmpty()) {
            return;
        }

        long sat1 = masterData.insertSatellite("SAT-01", "合成卫星一号");
        long sat2 = masterData.insertSatellite("SAT-02", "合成卫星二号");
        long station1 = masterData.insertStation("GS-01", "合成地面站一号");
        long station2 = masterData.insertStation("GS-02", "合成地面站二号");

        LocalDate today = LocalDate.now(clock);
        List<LocalDate> days = List.of(today.minusDays(1), today, today.plusDays(1));
        for (LocalDate day : days) {
            seedDay(station1, sat1, sat2, day);
            seedDay(station2, sat1, sat2, day);
        }
        log.info("合成数据初始化完成：2 颗卫星、2 个地面站、{} 天过境窗口", days.size());
    }

    private void seedDay(long stationId, long sat1, long sat2, LocalDate day) {
        // SAT-01：01:00-01:10 与 01:10-01:20 端点相接；02:00-02:15；03:00-03:10；05:00-05:20
        window(sat1, stationId, day, 1, 0, 1, 10);
        window(sat1, stationId, day, 1, 10, 1, 20);
        window(sat1, stationId, day, 2, 0, 2, 15);
        window(sat1, stationId, day, 3, 0, 3, 10);
        window(sat1, stationId, day, 5, 0, 5, 20);
        // SAT-02：02:05-02:20 与 SAT-01 的 02:00-02:15 内部重叠；03:10-03:25 与 03:00-03:10 相接；06:00-06:15
        window(sat2, stationId, day, 2, 5, 2, 20);
        window(sat2, stationId, day, 3, 10, 3, 25);
        window(sat2, stationId, day, 6, 0, 6, 15);
    }

    private void window(long satelliteId, long stationId, LocalDate day,
                        int sh, int sm, int eh, int em) {
        Instant start = day.atTime(sh, sm).toInstant(ZoneOffset.UTC);
        Instant end = day.atTime(eh, em).toInstant(ZoneOffset.UTC);
        masterData.insertWindow(satelliteId, stationId, start, end, day.toString());
    }
}
