package com.example.starter;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.starter.repository.DispatchOrderRepository;
import com.example.starter.repository.GeneratorRepository;
import com.example.starter.repository.OrderEventRepository;
import com.example.starter.service.AlarmService;
import com.example.starter.service.DispatchService;
import com.example.starter.service.StationService;
import com.example.starter.web.dto.ApiDtos.AlarmResponse;
import com.example.starter.web.dto.ApiDtos.BatteryReadingRequest;
import com.example.starter.web.dto.ApiDtos.BatteryReadingResponse;
import com.example.starter.web.dto.ApiDtos.CreateGeneratorRequest;
import com.example.starter.web.dto.ApiDtos.CreateStationRequest;
import com.example.starter.web.dto.ApiDtos.MainsFailureAlarmRequest;
import com.example.starter.web.dto.ApiDtos.StationResponse;

/**
 * 集成测试基类：真实 Spring 上下文 + H2（MySQL 兼容模式）命名内存库，
 * 可控时钟，逐用例清理数据；类结束后关闭内存库释放资源。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BaseIntegrationTest {

    /** 测试基准时刻（UTC）。 */
    protected static final Instant T0 = Instant.parse("2026-09-25T08:00:00Z");

    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected StationService stationService;
    @Autowired
    protected AlarmService alarmService;
    @Autowired
    protected DispatchService dispatchService;
    @Autowired
    protected DispatchOrderRepository orderRepository;
    @Autowired
    protected GeneratorRepository generatorRepository;
    @Autowired
    protected OrderEventRepository eventRepository;

    @MockitoBean
    protected Clock clock;

    /** 当前测试时刻，由可控时钟返回。 */
    protected volatile Instant now = T0;

    @BeforeEach
    void resetState() {
        now = T0;
        Mockito.lenient().when(clock.instant()).thenAnswer(invocation -> now);
        Mockito.lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        jdbcTemplate.update("DELETE FROM order_event");
        jdbcTemplate.update("DELETE FROM dispatch_order");
        jdbcTemplate.update("DELETE FROM outage_event");
        jdbcTemplate.update("DELETE FROM battery_reading");
        jdbcTemplate.update("DELETE FROM idempotency_record");
        jdbcTemplate.update("DELETE FROM generator");
        jdbcTemplate.update("DELETE FROM station");
    }

    /**
     * 测试上下文结束后释放命名内存库。
     */
    @AfterAll
    void releaseDatabase() {
        jdbcTemplate.execute("SHUTDOWN");
    }

    /**
     * 推进可控时钟。
     */
    protected void advance(Duration duration) {
        now = now.plus(duration);
    }

    /**
     * 创建基站合成数据。
     */
    protected StationResponse givenStation(String code, String loadKw, String capacityKwh) {
        return stationService.createStation(new CreateStationRequest("req-station-" + code, code,
                new BigDecimal(loadKw), new BigDecimal(capacityKwh)));
    }

    /**
     * 创建油机合成数据。
     */
    protected void givenGenerator(String code) {
        stationService.createGenerator(new CreateGeneratorRequest("req-generator-" + code, code));
    }

    /**
     * 上报电池读数合成数据。
     */
    protected BatteryReadingResponse givenReading(String requestId, String stationCode,
                                                  Instant readingTime, String percent) {
        return stationService.createReading(new BatteryReadingRequest(requestId, stationCode,
                readingTime, new BigDecimal(percent)));
    }

    /**
     * 发起市电中断告警。
     */
    protected AlarmResponse givenAlarm(String requestId, String stationCode, Instant outageStart) {
        return alarmService.mainsFailure(new MainsFailureAlarmRequest(requestId, stationCode,
                outageStart));
    }

    /**
     * 统计表行数。
     */
    protected long count(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }
}
