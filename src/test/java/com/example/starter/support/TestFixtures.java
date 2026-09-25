package com.example.starter.support;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.example.starter.api.dto.FuelDto;
import com.example.starter.api.dto.PlanSubmitRequest;
import com.example.starter.api.dto.ReadingBatchRequest;
import com.example.starter.api.dto.ReadingItem;

/**
 * 测试合成数据构造器。
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    /** 普通低氯硫替代燃料，默认热量替代率场景使用。 */
    public static FuelDto normalFuel() {
        return new FuelDto("RDF-成型棒", "RDF", false,
                new BigDecimal("18.500"), new BigDecimal("10000.000"),
                new BigDecimal("0.010"), new BigDecimal("0.050"));
    }

    /** 高氯硫燃料：质量 10000kg、氯 0.5%→50kg、硫 8%→800kg。 */
    public static FuelDto highClSulfurFuel(String name, BigDecimal massKg,
                                           BigDecimal chlorinePct, BigDecimal sulfurPct) {
        return new FuelDto(name, "高氯硫废料", true,
                new BigDecimal("16.000"), massKg, chlorinePct, sulfurPct);
    }

    public static PlanSubmitRequest submit(String requestId, String kilnId, String license,
                                           String rate, LocalDateTime start, LocalDateTime end,
                                           List<FuelDto> fuels) {
        return new PlanSubmitRequest(requestId, kilnId, license, new BigDecimal(rate), start, end, fuels);
    }

    public static ReadingBatchRequest readings(String planNo, ReadingItem... items) {
        return new ReadingBatchRequest(planNo, List.of(items));
    }

    public static ReadingItem reading(String point, LocalDateTime sampleTime,
                                      String nox, String so2, String dust) {
        return new ReadingItem(point, sampleTime,
                nox == null ? null : new BigDecimal(nox),
                so2 == null ? null : new BigDecimal(so2),
                dust == null ? null : new BigDecimal(dust));
    }
}
