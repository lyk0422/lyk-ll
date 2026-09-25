package com.example.starter.testsupport;

import com.example.starter.domain.BaseStation;
import com.example.starter.domain.Generator;
import com.example.starter.domain.GeneratorStatus;
import com.example.starter.repository.MasterDataRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测试数据夹具：直接落库合成基站与油机。
 */
@Component
public class Fixtures {

    private final MasterDataRepository masterDataRepository;

    public Fixtures(MasterDataRepository masterDataRepository) {
        this.masterDataRepository = masterDataRepository;
    }

    /** 新建基站，返回含主键的实体。 */
    public BaseStation station(String code, String capacityWh, String dischargeRateW) {
        return station(code, capacityWh, dischargeRateW, Instant.parse("2026-01-01T00:00:00Z"));
    }

    public BaseStation station(String code, String capacityWh, String dischargeRateW, Instant now) {
        BaseStation station = new BaseStation(null, code, code + "-名称",
                new BigDecimal(capacityWh), new BigDecimal(dischargeRateW), null, now);
        masterDataRepository.insertStation(station);
        return masterDataRepository.getStationByCode(code);
    }

    /** 新建空闲油机，返回含主键的实体。 */
    public Generator generator(String code) {
        masterDataRepository.insertGenerator(new Generator(
                null, code, code + "-油机", GeneratorStatus.IDLE, null,
                Instant.parse("2026-01-01T00:00:00Z")));
        return masterDataRepository.getGeneratorByCode(code);
    }
}
