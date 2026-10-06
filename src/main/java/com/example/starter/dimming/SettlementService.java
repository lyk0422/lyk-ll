package com.example.starter.dimming;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.dimming.CircuitRepository.CircuitRow;
import com.example.starter.dimming.DimmingRecordRepository.RecordRow;
import com.example.starter.dimming.Dto.CircuitSettlement;
import com.example.starter.dimming.Dto.DailySettlementResponse;
import com.example.starter.dimming.Dto.SettlementSegment;

/**
 * 日结服务：按调光留痕复算各回路当日调光次数与能耗。
 * 能耗 = Σ 档位 × 持续小时 × 每档功率(kW)，分段明细随结果输出，可独立复算。
 */
@Service
public class SettlementService {

    private final CircuitRepository circuitRepository;
    private final DimmingRecordRepository recordRepository;
    private final DimmingProperties properties;

    public SettlementService(CircuitRepository circuitRepository,
            DimmingRecordRepository recordRepository,
            DimmingProperties properties) {
        this.circuitRepository = circuitRepository;
        this.recordRepository = recordRepository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public DailySettlementResponse settle(LocalDate date) {
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        List<CircuitSettlement> circuits = new ArrayList<>();
        for (CircuitRow circuit : circuitRepository.findAll()) {
            circuits.add(settleCircuit(circuit, dayStart, dayEnd));
        }
        return new DailySettlementResponse(date.toString(), circuits);
    }

    private CircuitSettlement settleCircuit(CircuitRow circuit, LocalDateTime dayStart,
            LocalDateTime dayEnd) {
        List<RecordRow> records = recordRepository.findBetween(circuit.code(), dayStart, dayEnd);
        Integer levelAtStart = recordRepository.findLevelAt(circuit.code(), dayStart);
        int level = levelAtStart != null ? levelAtStart : circuit.initialLevel();

        List<SettlementSegment> segments = new ArrayList<>();
        LocalDateTime cursor = dayStart;
        for (RecordRow record : records) {
            addSegment(segments, cursor, record.createdAt(), level);
            level = record.toLevel();
            cursor = record.createdAt();
        }
        addSegment(segments, cursor, dayEnd, level);

        double energy = segments.stream().mapToDouble(SettlementSegment::energyKwh).sum();
        return new CircuitSettlement(circuit.code(), records.size(), round3(energy), segments);
    }

    private void addSegment(List<SettlementSegment> segments, LocalDateTime from,
            LocalDateTime to, int level) {
        if (!to.isAfter(from)) {
            return;
        }
        double hours = Duration.between(from, to).toMillis() / 3_600_000.0;
        double energy = level * hours * properties.getPowerPerLevelKw();
        segments.add(new SettlementSegment(from, to, level, round6(hours), round6(energy)));
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }
}
