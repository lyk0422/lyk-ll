package com.example.starter.greenhouse;

import com.example.starter.greenhouse.dto.StrategyDraftRequest;
import com.example.starter.greenhouse.service.MasterDataService;
import com.example.starter.greenhouse.service.StrategyService;
import com.example.starter.greenhouse.testsupport.MutableClock;
import com.example.starter.greenhouse.testsupport.TableCleaner;
import com.example.starter.greenhouse.testsupport.TestClockConfig;
import com.example.starter.greenhouse.dto.CropCreateRequest;
import com.example.starter.greenhouse.dto.ZoneCreateRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 全部业务测试的基类：加载真实 Spring 上下文与 H2（MySQL 模式），每例前清表。
 */
@SpringBootTest
@org.springframework.context.annotation.Import(TestClockConfig.class)
public abstract class BaseGreenhouseTest {

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected MasterDataService masterDataService;
    @Autowired
    protected StrategyService strategyService;
    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void cleanTables() {
        TableCleaner.clean(jdbc);
        clock.setInstant(java.time.Instant.parse("2026-09-25T00:00:00Z"));
    }

    protected Long createCrop(String code) {
        // 白天温度[20,28] 湿度[60,80]；夜间温度[14,20] 湿度[65,85]
        return masterDataService.createCrop(new CropCreateRequest(
                code, "测试番茄-" + code, "FRUITING",
                bd("20"), bd("28"), bd("60"), bd("80"),
                bd("14"), bd("20"), bd("65"), bd("85"))).getId();
    }

    protected Long createZone(String code) {
        return masterDataService.createZone(new ZoneCreateRequest(code, "测试分区-" + code)).getId();
    }

    protected void draftValidate(String strategyCode, String cropCode, String zoneCode,
                                 String dayTemp, String dayTempTol, String dayHum, String dayHumTol,
                                 String nightTemp, String nightTempTol, String nightHum, String nightHumTol) {
        strategyService.draft(new StrategyDraftRequest(
                strategyCode, cropCode, zoneCode,
                bd(dayTemp), bd(dayTempTol), bd(dayHum), bd(dayHumTol),
                bd(nightTemp), bd(nightTempTol), bd(nightHum), bd(nightHumTol)));
        strategyService.validate(strategyCode);
    }

    protected static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
