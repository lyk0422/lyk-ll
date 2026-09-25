package com.example.starter.greenhouse.support;

import com.example.starter.greenhouse.dto.BudgetAllocateRequest;
import com.example.starter.greenhouse.dto.CropCreateRequest;
import com.example.starter.greenhouse.dto.ZoneCreateRequest;
import com.example.starter.greenhouse.service.EnergyService;
import com.example.starter.greenhouse.service.MasterDataService;
import java.math.BigDecimal;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 本地启动时写入最小合成演示数据（仅当作物表为空）：
 * 一个挂果期番茄作物、一个分区、当日（UTC）20 kWh 能耗预算。
 * 可通过 greenhouse.seed-on-startup=false 关闭；测试配置中关闭。
 */
@Component
@ConditionalOnProperty(name = "greenhouse.seed-on-startup", havingValue = "true", matchIfMissing = true)
public class SyntheticDataInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final MasterDataService masterDataService;
    private final EnergyService energyService;

    public SyntheticDataInitializer(JdbcTemplate jdbc,
                                    MasterDataService masterDataService,
                                    EnergyService energyService) {
        this.jdbc = jdbc;
        this.masterDataService = masterDataService;
        this.energyService = energyService;
    }

    @Override
    public void run(String... args) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM crop", Long.class);
        if (count != null && count > 0) {
            return;
        }
        // 挂果期番茄：白天温度[20,28]℃ 湿度[60,80]%RH；夜间温度[14,20]℃ 湿度[65,85]%RH。
        masterDataService.createCrop(new CropCreateRequest(
                "TOMATO-01", "番茄（合成演示）", "FRUITING",
                new BigDecimal("20.00"), new BigDecimal("28.00"),
                new BigDecimal("60.00"), new BigDecimal("80.00"),
                new BigDecimal("14.00"), new BigDecimal("20.00"),
                new BigDecimal("65.00"), new BigDecimal("85.00")));
        masterDataService.createZone(new ZoneCreateRequest("ZONE-A", "一号温室分区（合成演示）"));
        energyService.allocate(new BudgetAllocateRequest(
                "ZONE-A", java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString(),
                new BigDecimal("20.000")));
    }
}
