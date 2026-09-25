package com.example.starter.schedule.config;

import com.example.starter.schedule.repository.MasterDataRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证默认本地启动路径：application.yaml 默认嵌入式 H2（MySQL 兼容模式）、
 * schema.sql 自动建表、合成数据初始化器写入卫星/地面站/过境窗口，
 * 全程不依赖 MySQL、Docker 或外部预报系统。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "schedule.missed-report.enabled=false")
class LocalStartupSeederTest {

    @Autowired
    private MasterDataRepository masterData;

    @Test
    @DisplayName("默认启动自动初始化 2 颗卫星、2 个地面站与三天合成过境窗口")
    void defaultStartupSeedsSyntheticData() {
        assertThat(masterData.findSatelliteByCode("SAT-01")).isPresent();
        assertThat(masterData.findSatelliteByCode("SAT-02")).isPresent();
        var stations = masterData.findAllStations();
        assertThat(stations).hasSize(2);

        long stationId = stations.get(0).id();
        long sat1 = masterData.findSatelliteByCode("SAT-01").orElseThrow().id();
        var windows = masterData.findWindows(stationId, sat1,
                java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString());
        assertThat(windows).isNotEmpty();
        // 半开窗口拓扑完整：开始时刻严格早于结束时刻
        assertThat(windows).allSatisfy(w -> assertThat(w.start()).isBefore(w.end()));
    }
}
