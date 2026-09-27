package com.example.starter.tank;

import com.example.starter.web.dto.TankRegisterRequest;
import com.example.starter.web.dto.TankRegisterResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 储罐测试夹具：标准储罐 容量100/安全储备10/余额50，登记后版本为1。
 */
public final class TankFixtures {

    static final String STATION = "ST01";
    static final BigDecimal CAPACITY = new BigDecimal("100.000");
    static final BigDecimal RESERVE = new BigDecimal("10.000");
    static final BigDecimal BALANCE = new BigDecimal("50.000");
    static final LocalDateTime T = LocalDateTime.of(2026, 9, 26, 10, 0);

    private static final AtomicInteger SEQ = new AtomicInteger();

    private TankFixtures() {
    }

    public static String nextRequestId(String prefix) {
        return prefix + "-" + SEQ.incrementAndGet();
    }

    /**
     * 登记标准储罐：容量100、安全储备10、初始余额50，版本1。
     */
    public static TankRegisterResponse registerStandard(TankService tankService) {
        return register(tankService, STATION, CAPACITY, RESERVE, BALANCE);
    }

    public static TankRegisterResponse register(TankService tankService, String stationCode,
                                                BigDecimal capacity, BigDecimal safetyReserve,
                                                BigDecimal balance) {
        return tankService.register(new TankRegisterRequest(stationCode,
                nextRequestId("TANK-REG"), "TK-" + stationCode + "-" + SEQ.get(),
                capacity, safetyReserve, balance, T));
    }
}
