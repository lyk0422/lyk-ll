package com.example.starter.bootstrap;

import com.example.starter.repo.CatalogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

/**
 * 本地启动入口的合成数据初始化：内存库为空时写入一个演示换热站。
 *
 * <p>换热站 ST-DEMO 含 3 个单元（U-101/U-102/U-103），入口阀门初始开度均为 50%。
 * 数据仅存在于当前 JVM 生命周期，重启即清空。</p>
 */
@Component
public class SyntheticDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SyntheticDataInitializer.class);

    private final CatalogRepository catalog;
    private final Clock clock;

    public SyntheticDataInitializer(CatalogRepository catalog, Clock clock) {
        this.catalog = catalog;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (catalog.findStationByCode("ST-DEMO") != null) {
            return;
        }
        Instant now = Instant.now(clock);
        long stationId = catalog.createStation("ST-DEMO", "演示换热站", now);
        long u1 = catalog.createUnit(stationId, "U-101", "1号楼单元", now);
        long u2 = catalog.createUnit(stationId, "U-102", "2号楼单元", now);
        long u3 = catalog.createUnit(stationId, "U-103", "3号楼单元", now);
        BigDecimal initial = new BigDecimal("50.000");
        catalog.createValve(u1, "V-101", initial, now);
        catalog.createValve(u2, "V-102", initial, now);
        catalog.createValve(u3, "V-103", initial, now);
        log.info("合成数据初始化完成：换热站 ST-DEMO（stationId={}），单元 U-101/U-102/U-103，阀门初始开度 50%", stationId);
    }
}
