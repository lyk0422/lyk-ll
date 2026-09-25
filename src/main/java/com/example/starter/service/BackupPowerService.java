package com.example.starter.service;

import com.example.starter.domain.BaseStation;
import com.example.starter.domain.CapacityReading;
import com.example.starter.domain.DispatchOrder;
import com.example.starter.domain.Generator;
import com.example.starter.domain.GeneratorStatus;
import com.example.starter.domain.OrderEvent;
import com.example.starter.domain.OrderStatus;
import com.example.starter.domain.OutageEvent;
import com.example.starter.repository.DispatchRepository;
import com.example.starter.repository.MasterDataRepository;
import com.example.starter.repository.OutageRepository;
import com.example.starter.repository.ReadingRepository;
import com.example.starter.support.Clock;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import com.example.starter.web.UnprocessableException;
import com.example.starter.web.dto.AlertRequest;
import com.example.starter.web.dto.AlertResponse;
import com.example.starter.web.dto.ClosureRequest;
import com.example.starter.web.dto.ClosureResponse;
import com.example.starter.web.dto.ConfirmationRequest;
import com.example.starter.web.dto.ConfirmationResponse;
import com.example.starter.web.dto.DispatchRequest;
import com.example.starter.web.dto.DispatchResponse;
import com.example.starter.web.dto.GeneratorRequest;
import com.example.starter.web.dto.OrderResponse;
import com.example.starter.web.dto.ReadingRequest;
import com.example.starter.web.dto.ReadingResponse;
import com.example.starter.web.dto.ReassignmentRequest;
import com.example.starter.web.dto.ReassignmentResponse;
import com.example.starter.web.dto.RestorationRequest;
import com.example.starter.web.dto.RestorationResponse;
import com.example.starter.web.dto.StationRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 基站备电调度核心服务：告警合并、容量估算与归位、油机唯一分配、
 * 发电确认超时改派、市电恢复解除与结案终态。
 */
@Service
public class BackupPowerService {

    /** 派单后油机到位启动并回传发电确认的时限：30 分钟；恰满判超时。 */
    static final long CONFIRM_TIMEOUT_SECONDS = 30 * 60L;

    /** 生成油机调度单的支撑时长阈值：低于 120 分钟生成，恰为 120 分钟不生成。 */
    static final int DISPATCH_THRESHOLD_MINUTES = 120;

    private final MasterDataRepository masterDataRepository;
    private final OutageRepository outageRepository;
    private final ReadingRepository readingRepository;
    private final DispatchRepository dispatchRepository;
    private final OrderResponseAssembler assembler;
    private final Clock clock;

    public BackupPowerService(MasterDataRepository masterDataRepository,
                              OutageRepository outageRepository,
                              ReadingRepository readingRepository,
                              DispatchRepository dispatchRepository,
                              OrderResponseAssembler assembler,
                              Clock clock) {
        this.masterDataRepository = masterDataRepository;
        this.outageRepository = outageRepository;
        this.readingRepository = readingRepository;
        this.dispatchRepository = dispatchRepository;
        this.assembler = assembler;
        this.clock = clock;
    }

    // ---------------------------------------------------------------------
    // 合成主数据
    // ---------------------------------------------------------------------

    /** 创建基站（合成数据）。 */
    @Transactional
    public BaseStation createStation(StationRequest request) {
        Instant now = clock.now();
        BaseStation station = new BaseStation(null, request.stationCode(), request.stationName(),
                request.batteryCapacityWh(), request.dischargeRateW(), null, now);
        masterDataRepository.insertStation(station);
        return masterDataRepository.getStationByCode(request.stationCode());
    }

    /** 创建油机（合成数据），初始空闲。 */
    @Transactional
    public Generator createGenerator(GeneratorRequest request) {
        Instant now = clock.now();
        Generator generator = new Generator(null, request.genCode(), request.genName(),
                GeneratorStatus.IDLE, null, now);
        masterDataRepository.insertGenerator(generator);
        return masterDataRepository.getGeneratorByCode(request.genCode());
    }

    public BaseStation getStation(String stationCode) {
        return masterDataRepository.findStationByCode(stationCode)
                .orElseThrow(() -> new NotFoundException("STATION_NOT_FOUND",
                        "基站不存在: " + stationCode));
    }

    // ---------------------------------------------------------------------
    // 市电中断告警：按“基站＋停电起始时刻”去重，重复告警合并为一个事件
    // ---------------------------------------------------------------------

    @Transactional
    public AlertResponse handleAlert(AlertRequest request) {
        BaseStation station = getStation(request.stationCode());
        Instant now = clock.now();
        boolean merged = !outageRepository.insertIfAbsent(
                new OutageEvent(null, station.id(), request.outageStartAt(),
                        com.example.starter.domain.OutageStatus.ACTIVE, null, now));
        OutageEvent event = outageRepository.find(station.id(), request.outageStartAt()).orElseThrow();
        return new AlertResponse(request.requestId(), event.id(), station.stationCode(),
                event.outageStartAt(), merged, event.status().name());
    }

    // ---------------------------------------------------------------------
    // 容量抄表：按“基站＋抄表时刻”去重，乱序按抄表时刻归位
    // ---------------------------------------------------------------------

    @Transactional
    public ReadingResponse handleReading(ReadingRequest request) {
        BaseStation station = getStation(request.stationCode());
        Instant now = clock.now();
        int estimatedMinutes = estimateMinutes(station, request.remainingWh());
        boolean deduped = !readingRepository.insertIfAbsent(station.id(), request.readAt(),
                request.remainingWh(), estimatedMinutes, now);

        // 无论是否乱序，均按抄表时刻取最新一条作为当前剩余容量（乱序归位）。
        CapacityReading latest = readingRepository.findLatestByReadAt(station.id()).orElseThrow();
        if (station.currentRemainingWh() == null
                || latest.remainingWh().compareTo(station.currentRemainingWh()) != 0) {
            masterDataRepository.updateRemainingWh(station.id(), latest.remainingWh());
        }
        boolean isLatest = latest.readAt().equals(request.readAt());
        return new ReadingResponse(request.requestId(), station.stationCode(), request.readAt(),
                request.remainingWh(), estimatedMinutes, deduped, isLatest);
    }

    /** 按平均放电功率估算可支撑分钟数：剩余 Wh / 功率 W * 60，向下取整。 */
    static int estimateMinutes(BaseStation station, BigDecimal remainingWh) {
        return remainingWh.multiply(BigDecimal.valueOf(60))
                .divide(station.dischargeRateW(), 0, RoundingMode.FLOOR)
                .intValueExact();
    }

    // ---------------------------------------------------------------------
    // 调度单生成：支撑时长低于 2 小时才派单；同站一活动单；同油机并发唯一成功
    // ---------------------------------------------------------------------

    @Transactional
    public DispatchResponse handleDispatch(DispatchRequest request) {
        BaseStation station = getStation(request.stationCode());
        Instant now = clock.now();

        OutageEvent event = outageRepository.find(station.id(), request.outageStartAt())
                .orElseThrow(() -> new UnprocessableException("OUTAGE_EVENT_NOT_FOUND",
                        "停电事件不存在，请先上报告警"));
        if (event.status() != com.example.starter.domain.OutageStatus.ACTIVE) {
            throw new UnprocessableException("OUTAGE_ALREADY_RESTORED", "该停电事件已恢复，不再派单");
        }

        CapacityReading latest = readingRepository.findLatestByReadAt(station.id())
                .orElseThrow(() -> new UnprocessableException("NO_CAPACITY_READING",
                        "尚无容量读数，无法估算可支撑时长"));
        int estimatedMinutes = latest.estimatedMinutes();
        if (estimatedMinutes >= DISPATCH_THRESHOLD_MINUTES) {
            // 恰为 2 小时不生成调度单。
            return new DispatchResponse(request.requestId(), false, null,
                    estimatedMinutes, "SUPPORT_TIME_SUFFICIENT");
        }

        if (dispatchRepository.findActiveOrderIdByStation(station.id()).isPresent()) {
            throw new ConflictException("STATION_ORDER_ACTIVE", "该基站已有未完成调度单");
        }

        Instant dispatchDeadline = now.plusSeconds(CONFIRM_TIMEOUT_SECONDS);
        // 预计到达时限不晚于电池按当前读数耗尽的时刻（UTC）。
        Instant arrivalDeadline = now.plus(Duration.ofMinutes(estimatedMinutes));
        long orderId = dispatchRepository.insertOrder(request.requestId(), station.id(), event.id(),
                arrivalDeadline, dispatchDeadline, now);
        if (!dispatchRepository.tryAcquireStationLock(station.id(), orderId)) {
            throw new ConflictException("STATION_ORDER_ACTIVE", "该基站已有未完成调度单");
        }

        Generator allocated = allocateGenerator(request.genCode(), orderId, null);
        if (dispatchRepository.markDispatched(orderId, allocated.id(), now) != 1) {
            throw new IllegalStateException("调度单状态异常，派单条件更新失败");
        }
        dispatchRepository.insertEvent(orderId, OrderEvent.TYPE_DISPATCHED, allocated.id(), now, null);

        DispatchOrder order = dispatchRepository.getById(orderId);
        return new DispatchResponse(request.requestId(), true,
                assembler.toResponse(order, station), estimatedMinutes, "DISPATCHED");
    }

    /**
     * 分配油机：指定编号则抢该台，否则逐台尝试空闲油机。
     * 占坑表主键冲突 + generator 行条件更新双重保证并发下唯一成功。
     */
    private Generator allocateGenerator(String requestedGenCode, long orderId, Long excludeGenId) {
        Instant now = clock.now();
        if (requestedGenCode != null && !requestedGenCode.isBlank()) {
            Generator generator = masterDataRepository.findGeneratorByCode(requestedGenCode)
                    .orElseThrow(() -> new NotFoundException("GENERATOR_NOT_FOUND",
                            "油机不存在: " + requestedGenCode));
            occupy(generator, orderId);
            return generator;
        }
        List<Generator> idleGenerators = masterDataRepository.findIdleGenerators(excludeGenId);
        for (Generator candidate : idleGenerators) {
            try {
                occupy(candidate, orderId);
                return candidate;
            } catch (GeneratorBusyException busy) {
                // 并发下被其他调度单抢先：尝试下一台空闲油机。
            }
        }
        throw new ConflictException("NO_AVAILABLE_GENERATOR", "当前没有可调度的空闲油机");
    }

    private void occupy(Generator generator, long orderId) {
        if (!dispatchRepository.tryAcquireGeneratorLock(generator.id(), orderId)) {
            throw new GeneratorBusyException();
        }
        if (!dispatchRepository.occupyGeneratorIfIdle(generator.id(), orderId)) {
            // 占坑成功但行状态非空闲（极端并发）：撤销占坑。
            dispatchRepository.releaseGeneratorLock(generator.id());
            throw new GeneratorBusyException();
        }
    }

    /** 内部信号：候选油机在抢占中被其他事务抢走。 */
    private static final class GeneratorBusyException extends RuntimeException {
    }

    // ---------------------------------------------------------------------
    // 发电确认：30 分钟内确认成功；恰满/超时升级异常并释放原油机；迟到确认只记事件
    // ---------------------------------------------------------------------

    @Transactional
    public ConfirmationResponse handleConfirmation(ConfirmationRequest request) {
        DispatchOrder order = getOrderByRequestId(request.orderRequestId());
        order = dispatchRepository.lockById(order.id()).orElseThrow();
        BaseStation station = getStationById(order.stationId());
        Instant now = clock.now();

        switch (order.status()) {
            case DISPATCHED -> {
                if (now.isBefore(order.dispatchDeadline())) {
                    if (dispatchRepository.confirmGeneration(order.id(), order.genId(), now) != 1) {
                        throw new IllegalStateException("发电确认条件更新失败");
                    }
                    dispatchRepository.markGeneratorStatus(order.genId(), GeneratorStatus.GENERATING, order.id());
                    dispatchRepository.insertEvent(order.id(), OrderEvent.TYPE_CONFIRMED, order.genId(), now, null);
                    return new ConfirmationResponse(request.requestId(),
                            assembler.toResponse(dispatchRepository.getById(order.id()), station),
                            ConfirmationResponse.CONFIRMED);
                }
                // 恰满 30 分钟判超时：升级发电异常，原油机同事务释放，允许改派。
                upgradeAbnormal(order, now);
                DispatchOrder refreshed = dispatchRepository.getById(order.id());
                return new ConfirmationResponse(request.requestId(),
                        assembler.toResponse(refreshed, station),
                        ConfirmationResponse.TIMEOUT_ABNORMAL);
            }
            case GENERATING -> {
                // 已确认：重复确认不再改变状态。
                return new ConfirmationResponse(request.requestId(),
                        assembler.toResponse(order, station), ConfirmationResponse.CONFIRMED);
            }
            case GENERATION_ABNORMAL, RELEASED, CLOSED -> {
                dispatchRepository.insertEvent(order.id(), OrderEvent.TYPE_LATE_CONFIRM,
                        order.genId(), now, "迟到发电确认，仅记录事件，不改变当前结论");
                return new ConfirmationResponse(request.requestId(),
                        assembler.toResponse(dispatchRepository.getById(order.id()), station),
                        ConfirmationResponse.LATE_RECORDED);
            }
            default -> throw new UnprocessableException("ORDER_NOT_CONFIRMABLE",
                    "调度单当前状态不允许发电确认: " + order.status());
        }
    }

    private void upgradeAbnormal(DispatchOrder order, Instant now) {
        if (dispatchRepository.markAbnormal(order.id(), now) != 1) {
            throw new IllegalStateException("异常升级条件更新失败");
        }
        if (order.genId() != null) {
            dispatchRepository.releaseGeneratorLock(order.genId());
            dispatchRepository.freeGenerator(order.genId());
        }
        dispatchRepository.insertEvent(order.id(), OrderEvent.TYPE_ABNORMAL, order.genId(), now,
                "派单后 30 分钟未收到发电确认，判超时");
    }

    // ---------------------------------------------------------------------
    // 改派：仅异常态可改派；原油机释放与新油机占用同一事务，不会两台同站在途
    // ---------------------------------------------------------------------

    @Transactional
    public ReassignmentResponse handleReassignment(ReassignmentRequest request) {
        DispatchOrder order = getOrderByRequestId(request.orderRequestId());
        order = dispatchRepository.lockById(order.id()).orElseThrow();
        BaseStation station = getStationById(order.stationId());
        Instant now = clock.now();

        if (order.status() != OrderStatus.GENERATION_ABNORMAL) {
            throw new UnprocessableException("REASSIGN_NOT_ALLOWED",
                    "仅发电确认超时的异常调度单允许改派，当前状态: " + order.status());
        }
        Long oldGenId = order.genId();
        String oldGenCode = oldGenId == null ? null
                : masterDataRepository.findGeneratorById(oldGenId).map(Generator::genCode).orElse(null);

        Generator newGenerator = allocateGenerator(request.newGenCode(), order.id(), oldGenId);
        Instant newDeadline = now.plusSeconds(CONFIRM_TIMEOUT_SECONDS);
        if (dispatchRepository.applyReassignment(order.id(), newGenerator.id(), newDeadline, now) != 1) {
            throw new IllegalStateException("改派条件更新失败");
        }
        dispatchRepository.insertReassignment(order.id(), oldGenId, newGenerator.id(), now,
                "CONFIRM_TIMEOUT");
        dispatchRepository.insertEvent(order.id(), OrderEvent.TYPE_REASSIGNED,
                newGenerator.id(), now, "原油机 " + oldGenCode + " 改派为 " + newGenerator.genCode());
        dispatchRepository.insertEvent(order.id(), OrderEvent.TYPE_DISPATCHED,
                newGenerator.id(), now, null);

        DispatchOrder refreshed = dispatchRepository.getById(order.id());
        return new ReassignmentResponse(request.requestId(),
                assembler.toResponse(refreshed, station), oldGenCode, newGenerator.genCode());
    }

    // ---------------------------------------------------------------------
    // 市电恢复：事件结束、电池回充、活动调度单解除并释放油机（与确认竞态决出唯一终态）
    // ---------------------------------------------------------------------

    @Transactional
    public RestorationResponse handleRestoration(RestorationRequest request) {
        BaseStation station = getStation(request.stationCode());
        OutageEvent event = outageRepository.findActiveByStation(station.id())
                .orElseThrow(() -> new UnprocessableException("NO_ACTIVE_OUTAGE",
                        "该基站没有停电中的事件"));
        // 锁事件行，与并发的告警/恢复串行。
        outageRepository.findByIdForUpdate(event.id());

        if (!outageRepository.markRestoredIfActive(event.id(), request.restoredAt())) {
            throw new UnprocessableException("OUTAGE_ALREADY_RESTORED", "停电事件已恢复");
        }
        masterDataRepository.rechargeToFull(station.id(), station.capacityWh());

        OrderResponse releasedOrderView = null;
        Long activeOrderId = dispatchRepository.findActiveOrderIdByStation(station.id()).orElse(null);
        if (activeOrderId != null) {
            DispatchOrder active = dispatchRepository.lockById(activeOrderId).orElseThrow();
            if (dispatchRepository.markReleasedIfActive(active.id(), request.restoredAt()) == 1) {
                if (active.genId() != null) {
                    dispatchRepository.releaseGeneratorLock(active.genId());
                    dispatchRepository.freeGenerator(active.genId());
                }
                dispatchRepository.releaseStationLock(station.id());
                dispatchRepository.insertEvent(active.id(), OrderEvent.TYPE_RELEASED,
                        active.genId(), request.restoredAt(), "市电恢复，调度单解除");
                releasedOrderView = assembler.toResponse(
                        dispatchRepository.getById(active.id()), station);
            }
        }
        return new RestorationResponse(request.requestId(), station.stationCode(), event.id(),
                request.restoredAt(), releasedOrderView, station.capacityWh());
    }

    // ---------------------------------------------------------------------
    // 结案：终态；重复结案幂等成功；迟到确认不影响已结案结论
    // ---------------------------------------------------------------------

    @Transactional
    public ClosureResponse handleClosure(ClosureRequest request) {
        DispatchOrder order = getOrderByRequestId(request.orderRequestId());
        order = dispatchRepository.lockById(order.id()).orElseThrow();
        BaseStation station = getStationById(order.stationId());
        Instant now = clock.now();

        if (order.status() == OrderStatus.CLOSED) {
            return new ClosureResponse(request.requestId(),
                    assembler.toResponse(order, station), true);
        }
        if (order.status() == OrderStatus.RELEASED) {
            throw new UnprocessableException("ORDER_ALREADY_TERMINAL",
                    "调度单已解除，不能结案");
        }
        if (dispatchRepository.closeIfOpen(order.id(), now) != 1) {
            throw new IllegalStateException("结案条件更新失败");
        }
        if (order.genId() != null) {
            dispatchRepository.releaseGeneratorLock(order.genId());
            dispatchRepository.freeGenerator(order.genId());
        }
        dispatchRepository.releaseStationLock(order.stationId());
        dispatchRepository.insertEvent(order.id(), OrderEvent.TYPE_CLOSED, order.genId(), now, null);
        return new ClosureResponse(request.requestId(),
                assembler.toResponse(dispatchRepository.getById(order.id()), station), false);
    }

    // ---------------------------------------------------------------------
    // 查询
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public DispatchOrder getOrderByRequestId(String orderRequestId) {
        return dispatchRepository.findByRequestId(orderRequestId)
                .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND",
                        "调度单不存在: " + orderRequestId));
    }

    private BaseStation getStationById(long stationId) {
        return masterDataRepository.findStationById(stationId)
                .orElseThrow(() -> new NotFoundException("STATION_NOT_FOUND", "基站不存在: id=" + stationId));
    }
}
