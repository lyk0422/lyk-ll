package com.example.starter.waste;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 日结服务：按品类汇总当日进出量，实时由联单与回执明细计算，重复查询结果稳定。
 */
@Service
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final Clock clock;

    public SettlementService(SettlementRepository settlementRepository, Clock businessClock) {
        this.settlementRepository = settlementRepository;
        this.clock = businessClock;
    }

    /**
     * 查询指定自然日的日结汇总；date 为空时取业务时钟当日。
     */
    @Transactional(readOnly = true)
    public DailySettlementResponse daily(LocalDate date) {
        LocalDate effective = date != null ? date : LocalDate.now(clock);
        var start = effective.atStartOfDay();
        var end = effective.plusDays(1).atStartOfDay();
        return new DailySettlementResponse(effective, settlementRepository.summarize(effective, start, end));
    }
}
