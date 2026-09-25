package com.example.starter.atm.support;

import com.example.starter.atm.repo.AtmRepository;
import com.example.starter.atm.repo.CashBoxRepository;
import com.example.starter.atm.repo.GuardRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 合成主数据初始化：启动后确保 3 台 ATM、每台 2 个款箱、3 名押运员就绪。
 * 金额单位：分。H2 内存库重启即清空，因此仅在空库时插入。
 */
@Component
@Order(1)
public class SyntheticDataInitializer implements CommandLineRunner {

    private final AtmRepository atmRepository;
    private final CashBoxRepository boxRepository;
    private final GuardRepository guardRepository;
    private final DomainClock clock;

    public SyntheticDataInitializer(AtmRepository atmRepository,
                                CashBoxRepository boxRepository,
                                GuardRepository guardRepository,
                                DomainClock clock) {
        this.atmRepository = atmRepository;
        this.boxRepository = boxRepository;
        this.guardRepository = guardRepository;
        this.clock = clock;
    }

    @Override
    public void run(String... args) {
        long now = clock.nowMillis();
        if (atmRepository.count() == 0) {
            long atm1 = atmRepository.insert("ATM-001", "南京路商圈存取款机", 500_000_00L, 300_000_00L, now);
            long atm2 = atmRepository.insert("ATM-002", "大学城地铁站存取款机", 200_000_00L, 120_000_00L, now);
            long atm3 = atmRepository.insert("ATM-003", "软件园园区存取款机", 100_000_00L, 80_000_00L, now);
            boxRepository.insert("BOX-001-1", atm1, 500_000_00L, now);
            boxRepository.insert("BOX-001-2", atm1, 500_000_00L, now);
            boxRepository.insert("BOX-002-1", atm2, 200_000_00L, now);
            boxRepository.insert("BOX-002-2", atm2, 200_000_00L, now);
            boxRepository.insert("BOX-003-1", atm3, 100_000_00L, now);
            boxRepository.insert("BOX-003-2", atm3, 100_000_00L, now);
        }
        if (guardRepository.count() == 0) {
            guardRepository.insert("GRD-A", "押运员甲", now);
            guardRepository.insert("GRD-B", "押运员乙", now);
            guardRepository.insert("GRD-C", "押运员丙", now);
        }
    }
}
