package com.example.starter.schedule;

import com.example.starter.schedule.domain.ContactWindow;
import com.example.starter.schedule.repository.MasterDataRepository;
import com.example.starter.schedule.support.MutableClock;
import com.example.starter.schedule.support.TestClockConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * H2（MODE=MySQL）集成测试基类：隔离内存库、可控时钟、每用例前清表，
 * 不启动 Web 容器与定时扫描，不依赖任何外部数据库。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestClockConfiguration.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:schedule_it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "schedule.synthetic-seed.enabled=false",
        "schedule.missed-report.enabled=false"
})
public abstract class AbstractIntegrationTest {

    protected static final String DAY = "2026-09-25";

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MasterDataRepository masterData;

    @Autowired
    protected MutableClock mutableClock;

    /**
     * 每个用例前清空全部业务表（含锁表），避免顺序依赖。
     */
    protected void cleanAll() {
        for (String table : List.of(
                "contact_report_event",
                "contact_report",
                "schedule_entry",
                "schedule_request",
                "schedule_version",
                "station_day_lock",
                "contact_window",
                "ground_station",
                "satellite")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    protected long createSatellite(String code) {
        return masterData.insertSatellite(code, "卫星-" + code);
    }

    protected long createStation(String code) {
        return masterData.insertStation(code, "地面站-" + code);
    }

    /**
     * 创建 DAY 当日指定时刻（UTC）的过境窗口。
     */
    protected ContactWindow window(long satelliteId, long stationId,
                                   int sh, int sm, int eh, int em) {
        LocalDate day = LocalDate.parse(DAY);
        Instant start = day.atTime(sh, sm).toInstant(ZoneOffset.UTC);
        Instant end = day.atTime(eh, em).toInstant(ZoneOffset.UTC);
        long id = masterData.insertWindow(satelliteId, stationId, start, end, DAY);
        return new ContactWindow(id, satelliteId, stationId, start, end, DAY);
    }
}
