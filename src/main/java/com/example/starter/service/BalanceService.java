package com.example.starter.service;

import com.example.starter.domain.AdjustmentTask;
import com.example.starter.domain.HeatingUnit;
import com.example.starter.domain.TemperatureReading;
import com.example.starter.repository.AdjustmentTaskRepository;
import com.example.starter.repository.LateTemperatureEventRepository;
import com.example.starter.repository.TemperatureRepository;
import com.example.starter.repository.UnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 运行监测与失衡判定：温度上报（含归档后迟到事件）、站内温度分布与平衡判定。
 */
@Service
public class BalanceService {

    /** 平衡阈值：站内回水温度最高最低差不超过 2℃（恰 2℃ 合格）。 */
    public static final BigDecimal BALANCE_THRESHOLD = new BigDecimal("2");

    private final UnitRepository unitRepository;
    private final TemperatureRepository temperatureRepository;
    private final AdjustmentTaskRepository taskRepository;
    private final LateTemperatureEventRepository lateEventRepository;
    private final Clock clock;

    public BalanceService(UnitRepository unitRepository,
                          TemperatureRepository temperatureRepository,
                          AdjustmentTaskRepository taskRepository,
                          LateTemperatureEventRepository lateEventRepository,
                          Clock clock) {
        this.unitRepository = unitRepository;
        this.temperatureRepository = temperatureRepository;
        this.taskRepository = taskRepository;
        this.lateEventRepository = lateEventRepository;
        this.clock = clock;
    }

    /**
     * 单条温度上报请求。
     *
     * @param unitId      单元 ID
     * @param temperature 回水温度，单位 ℃
     * @param recordedAt  采集时间（UTC）
     */
    public record ReadingCommand(long unitId, BigDecimal temperature, Instant recordedAt) {
    }

    /**
     * 上报结果。
     *
     * @param accepted   正常入库条数
     * @param lateEvents 归档后迟到、仅登记事件的条数
     */
    public record ReadingResult(int accepted, int lateEvents) {
    }

    /**
     * 批量上报回水温度。归档任务的采集时间早于归档时间的迟到数据只记迟到事件，不入库。
     */
    @Transactional
    public ReadingResult submitReadings(long stationId, List<ReadingCommand> readings) {
        Map<Long, HeatingUnit> units = new LinkedHashMap<>();
        for (HeatingUnit unit : unitRepository.findByStationId(stationId)) {
            units.put(unit.id(), unit);
        }
        Optional<AdjustmentTask> archived = taskRepository.findLatestArchivedByStationId(stationId);
        Instant now = clock.instant();
        int accepted = 0;
        int late = 0;
        for (ReadingCommand reading : readings) {
            if (!units.containsKey(reading.unitId())) {
                throw new NotFoundException("单元不存在或不属于该换热站: unitId=" + reading.unitId());
            }
            if (archived.isPresent() && reading.recordedAt().isBefore(archived.get().archivedAt())) {
                lateEventRepository.insert(reading.unitId(), archived.get().id(),
                        reading.temperature(), reading.recordedAt(), now);
                late++;
            } else {
                temperatureRepository.insert(reading.unitId(), reading.temperature(),
                        reading.recordedAt(), now);
                accepted++;
            }
        }
        return new ReadingResult(accepted, late);
    }

    /**
     * 单元温度分布项。
     *
     * @param unitId       单元 ID
     * @param unitCode     单元编码
     * @param temperature  最新回水温度，单位 ℃；无数据时为 null
     * @param recordedAt   采集时间（UTC）；无数据时为 null
     * @param classification 失衡分类：UNDERFLOW 欠流 / OVERFLOW 过流；平衡或无数据时为 null
     */
    public record UnitTemperature(long unitId, String unitCode, BigDecimal temperature,
                                  Instant recordedAt, AdjustmentTask.ImbalanceType classification) {
    }

    /**
     * 站内平衡判定结果。
     *
     * @param stationId 换热站 ID
     * @param units     各单元温度分布
     * @param maxTemp   最高回水温度，单位 ℃
     * @param minTemp   最低回水温度，单位 ℃
     * @param diff      最高最低差，单位 ℃
     * @param balanced  是否平衡：diff 不超过 2℃（恰 2℃ 合格）
     */
    public record BalanceView(long stationId, List<UnitTemperature> units,
                              BigDecimal maxTemp, BigDecimal minTemp, BigDecimal diff,
                              boolean balanced) {
    }

    /**
     * 计算换热站在指定时间点（含）的温度分布与平衡判定。
     */
    @Transactional(readOnly = true)
    public BalanceView evaluateBalance(long stationId, Instant asOf) {
        List<HeatingUnit> units = unitRepository.findByStationId(stationId);
        if (units.isEmpty()) {
            throw new NotFoundException("换热站不存在或无单元: stationId=" + stationId);
        }
        Map<Long, TemperatureReading> latest = new LinkedHashMap<>();
        for (TemperatureReading reading : temperatureRepository.findLatestPerUnit(stationId, asOf)) {
            latest.put(reading.unitId(), reading);
        }
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (TemperatureReading reading : latest.values()) {
            sum = sum.add(reading.temperature());
            count++;
        }
        BigDecimal avg = count == 0 ? null
                : sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);

        List<UnitTemperature> items = new ArrayList<>();
        BigDecimal max = null;
        BigDecimal min = null;
        for (HeatingUnit unit : units) {
            TemperatureReading reading = latest.get(unit.id());
            AdjustmentTask.ImbalanceType classification = null;
            if (reading != null) {
                BigDecimal temp = reading.temperature();
                max = max == null || temp.compareTo(max) > 0 ? temp : max;
                min = min == null || temp.compareTo(min) < 0 ? temp : min;
                if (avg != null && temp.compareTo(avg) < 0) {
                    classification = AdjustmentTask.ImbalanceType.UNDERFLOW;
                } else if (avg != null && temp.compareTo(avg) > 0) {
                    classification = AdjustmentTask.ImbalanceType.OVERFLOW;
                }
                items.add(new UnitTemperature(unit.id(), unit.code(), temp,
                        reading.recordedAt(), classification));
            } else {
                items.add(new UnitTemperature(unit.id(), unit.code(), null, null, null));
            }
        }
        BigDecimal diff = max == null ? null : max.subtract(min);
        boolean balanced = diff != null && diff.compareTo(BALANCE_THRESHOLD) <= 0;
        return new BalanceView(stationId, items, max, min, diff, balanced);
    }
}
