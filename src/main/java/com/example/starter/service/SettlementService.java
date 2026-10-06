package com.example.starter.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.domain.Circuit;
import com.example.starter.dto.SettlementView;
import com.example.starter.repo.CircuitRepository;
import com.example.starter.repo.DimLevelRepository;
import com.example.starter.repo.RecordRepository;
import com.example.starter.repo.SettlementRepository;

/**
 * 日结服务：按日输出各回路调光次数与能耗。
 * 能耗完全由调光留痕与档位功率字典复算：日初档位取当日之前最近一次留痕的调光后档位，
 * 无留痕时取回路初始化档位；逐段按“档位功率 × 时长”累计，结果可复算、可重复日结覆盖。
 */
@Service
public class SettlementService {

    private final CircuitRepository circuitRepo;
    private final RecordRepository recordRepo;
    private final DimLevelRepository dimLevelRepo;
    private final SettlementRepository settlementRepo;
    private final Clock clock;

    public SettlementService(CircuitRepository circuitRepo, RecordRepository recordRepo,
                             DimLevelRepository dimLevelRepo, SettlementRepository settlementRepo,
                             Clock clock) {
        this.circuitRepo = circuitRepo;
        this.recordRepo = recordRepo;
        this.dimLevelRepo = dimLevelRepo;
        this.settlementRepo = settlementRepo;
        this.clock = clock;
    }

    /**
     * 执行某日(UTC)日结：复算各回路调光次数与能耗，写入快照并返回。
     * 当日未结束时按当前时刻截断；未来日期能耗为 0。
     */
    @Transactional
    public List<SettlementView> settle(LocalDate date) {
        Instant dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant now = clock.instant();
        Instant end = dayEnd.isAfter(now) ? now : dayEnd;
        if (end.isBefore(dayStart)) {
            end = dayStart;
        }
        List<SettlementView> views = new ArrayList<>();
        for (Circuit circuit : circuitRepo.findAll()) {
            List<RecordRepository.DimRecord> records = recordRepo.findBetween(circuit.id(), dayStart, dayEnd);
            int level = recordRepo.findLatestBefore(circuit.id(), dayStart)
                    .map(RecordRepository.DimRecord::toLevel)
                    .orElse(circuit.initialLevel());
            long wattSeconds = 0L;
            Instant segmentStart = dayStart;
            for (RecordRepository.DimRecord record : records) {
                wattSeconds += wattsOf(level) * Duration.between(segmentStart, record.createdAt()).getSeconds();
                segmentStart = record.createdAt();
                level = record.toLevel();
            }
            wattSeconds += wattsOf(level) * Duration.between(segmentStart, end).getSeconds();
            long energyMwh = wattSeconds * 1000L / 3600L;
            settlementRepo.upsert(circuit.id(), date, records.size(), energyMwh, now);
            views.add(new SettlementView(circuit.id(), circuit.code(), circuit.name(), date,
                    records.size(), energyMwh, energyMwh / 1000.0d));
        }
        return views;
    }

    /** 查询某日已生成的日结快照。 */
    @Transactional(readOnly = true)
    public List<SettlementRepository.SettlementRow> findSnapshot(LocalDate date) {
        return settlementRepo.findByDate(date);
    }

    private int wattsOf(int level) {
        Integer watts = dimLevelRepo.wattsFor(level);
        return watts == null ? 0 : watts;
    }
}
