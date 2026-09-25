package com.example.starter.bootstrap;

import com.example.starter.bootstrap.SyntheticDataInitializer;
import com.example.starter.repo.CatalogRepository;
import com.example.starter.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地启动入口测试：合成数据初始化器幂等写入演示换热站 ST-DEMO。
 */
class SyntheticDataInitializerTest extends AbstractIntegrationTest {

    @Autowired
    private SyntheticDataInitializer initializer;

    @Autowired
    private CatalogRepository catalog;

    @Test
    void initializerCreatesDemoStationOnce_andIsIdempotent() throws Exception {
        assertThat(catalog.findStationByCode("ST-DEMO")).isNull();

        initializer.run(null);

        var station = catalog.findStationByCode("ST-DEMO");
        assertThat(station).isNotNull();
        var units = catalog.findUnitsByStation(station.getId());
        assertThat(units).extracting(u -> u.getUnitCode())
                .containsExactly("U-101", "U-102", "U-103");
        for (var unit : units) {
            var valve = catalog.findValveByUnit(unit.getId());
            assertThat(valve).isNotNull();
            assertThat(valve.getOpening()).isEqualByComparingTo(new BigDecimal("50.000"));
        }

        // 再次执行不重复创建（唯一约束也会兜底）
        initializer.run(null);
        var again = catalog.findStationByCode("ST-DEMO");
        assertThat(again.getId()).isEqualTo(station.getId());
        assertThat(catalog.findUnitsByStation(station.getId())).hasSize(3);
    }
}
