package com.example.starter.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.example.starter.model.BatteryReading;
import com.example.starter.model.DispatchOrder;
import com.example.starter.model.Generator;
import com.example.starter.model.OutageEvent;
import com.example.starter.model.Station;
import com.example.starter.repository.BatteryReadingRepository;
import com.example.starter.repository.DispatchOrderRepository;
import com.example.starter.repository.GeneratorRepository;
import com.example.starter.repository.OrderEventRepository;
import com.example.starter.repository.OutageEventRepository;
import com.example.starter.repository.StationRepository;
import com.example.starter.web.dto.ApiDtos.AlarmResponse;
import com.example.starter.web.dto.ApiDtos.MainsFailureAlarmRequest;
import com.example.starter.web.dto.ApiDtos.MainsRecoveryRequest;
import com.example.starter.web.dto.ApiDtos.RecoveryResponse;

/**
 * 告警服务：市电中断触发备电调度，市电恢复解除调度。
 * 同站操作通过基站行锁串行化；派单唯一性由数据库唯一约束兜底。
 */
@Service
public class AlarmService {

    /** 备电支撑时长阈值：低于 2 小时生成调度单，恰为 2 小时不生成。 */
    static final BigDecimal SUPPORT_THRESHOLD_HOURS = new BigDecimal("2");

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal SECONDS_PER_HOUR = new BigDecimal("3600");

    private final StationRepository stationRepository;
    private final GeneratorRepository generatorRepository;
    private final BatteryReadingRepository readingRepository;
    private final OutageEventRepository outageRepository;
    private final DispatchOrderRepository orderRepository;
    private final OrderEventRepository eventRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public AlarmService(StationRepository stationRepository,
                        GeneratorRepository generatorRepository,
                        BatteryReadingRepository readingRepository,
                        OutageEventRepository outageRepository,
                        DispatchOrderRepository orderRepository,
                        OrderEventRepository eventRepository,
                        IdempotencyService idempotencyService, Clock clock) {
        this.stationRepository = stationRepository;
        this.generatorRepository = generatorRepository;
        this.readingRepository = readingRepository;
        this.outageRepository = outageRepository;
        this.orderRepository = orderRepository;
        this.eventRepository = eventRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 市电中断告警：合并事件期内重复告警，按剩余容量估算支撑时长，低于 2 小时生成调度单。
     */
    public AlarmResponse mainsFailure(MainsFailureAlarmRequest request) {
        return idempotencyService.execute("mainsFailure", request.requestId(),
                new Object[]{request.stationCode(), request.outageStartTime()},
                AlarmResponse.class,
                () -> doMainsFailure(request));
    }

    private AlarmResponse doMainsFailure(MainsFailureAlarmRequest request) {
        Instant now = Instant.now(clock);
        // 基站行锁：串行化同站告警，保证事件合并与派单唯一。
        Station station = stationRepository.findByCodeForUpdate(request.stationCode())
                .orElseThrow(() -> BusinessException.notFound("STATION_NOT_FOUND",
                        "基站不存在: " + request.stationCode()));

        Optional<OutageEvent> active = outageRepository.findActiveByStation(station.code());
        if (active.isPresent()) {
            // 同一停电事件期内重复告警：合并为一个事件，不重复派单。
            OutageEvent event = active.get();
            return new AlarmResponse(event.id(), event.stationCode(), event.outageStartTime(),
                    true, null, null);
        }

        long eventId = outageRepository.insert(station.code(), request.outageStartTime(), now);
        BigDecimal supportHours = estimateSupportHours(station, request.outageStartTime());
        if (supportHours.compareTo(SUPPORT_THRESHOLD_HOURS) >= 0) {
            // 支撑时长达到 2 小时（含）以上：电池可支撑，不生成调度单。
            return new AlarmResponse(eventId, station.code(), request.outageStartTime(),
                    false, supportHours, null);
        }

        Generator generator = assignIdleGenerator();
        Instant deadlineAt = now.plusSeconds(supportHours
                .multiply(SECONDS_PER_HOUR).setScale(0, RoundingMode.FLOOR).longValue());
        long orderId = orderRepository.insert("alarm-" + request.requestId(), station.code(),
                generator.code(), supportHours, deadlineAt, now);
        eventRepository.append(orderId, "CREATED",
                "市电中断告警生成调度单，油机 " + generator.code() + " 前往基站 " + station.code(), now);
        DispatchOrder order = orderRepository.findById(orderId).orElseThrow();
        return new AlarmResponse(eventId, station.code(), request.outageStartTime(),
                false, supportHours, DispatchOrderViews.toView(order));
    }

    /**
     * 市电恢复：事件转已恢复，未完成调度单转“已解除”并释放油机。
     */
    public RecoveryResponse mainsRecovery(MainsRecoveryRequest request) {
        return idempotencyService.execute("mainsRecovery", request.requestId(),
                new Object[]{request.stationCode(), request.recoveredAt()},
                RecoveryResponse.class,
                () -> doMainsRecovery(request));
    }

    private RecoveryResponse doMainsRecovery(MainsRecoveryRequest request) {
        Instant now = Instant.now(clock);
        Station station = stationRepository.findByCodeForUpdate(request.stationCode())
                .orElseThrow(() -> BusinessException.notFound("STATION_NOT_FOUND",
                        "基站不存在: " + request.stationCode()));
        OutageEvent event = outageRepository.findActiveByStation(station.code())
                .orElseThrow(() -> BusinessException.conflict("NO_ACTIVE_OUTAGE",
                        "基站当前没有停电中的事件: " + station.code()));
        outageRepository.resolve(event.id(), request.recoveredAt());

        List<Long> releasedOrderIds = new ArrayList<>();
        for (DispatchOrder order : orderRepository.findUnfinishedByStationForUpdate(station.code())) {
            String generatorCode = order.generatorCode();
            if (orderRepository.release(order.id(), now) > 0) {
                if (generatorCode != null) {
                    generatorRepository.release(generatorCode);
                }
                eventRepository.append(order.id(), "RELEASED",
                        "市电恢复，调度单解除" + (generatorCode != null ? "，释放油机 " + generatorCode : ""),
                        now);
                releasedOrderIds.add(order.id());
            }
        }
        return new RecoveryResponse(event.id(), station.code(), true, releasedOrderIds);
    }

    /**
     * 按停电起始时刻（含）之前最近一次读数估算可支撑时长；无读数按满容量估算。
     */
    private BigDecimal estimateSupportHours(Station station, Instant outageStartTime) {
        BigDecimal remainingPercent = readingRepository
                .findLatestAtOrBefore(station.code(), outageStartTime)
                .map(BatteryReading::remainingPercent)
                .orElse(HUNDRED);
        return station.batteryCapacityKwh()
                .multiply(remainingPercent)
                .divide(HUNDRED.multiply(station.loadKw()), 4, RoundingMode.HALF_UP);
    }

    /**
     * 分配一台空闲油机；条件更新保证并发分配唯一成功。
     */
    private Generator assignIdleGenerator() {
        while (true) {
            Optional<Generator> idle = generatorRepository.findFirstIdle();
            if (idle.isEmpty()) {
                throw BusinessException.conflict("NO_IDLE_GENERATOR", "当前没有可分配的空闲油机");
            }
            if (generatorRepository.tryAssign(idle.get().code()) == 1) {
                return idle.get();
            }
            // 被并发事务抢走，重试下一台空闲油机。
        }
    }
}
