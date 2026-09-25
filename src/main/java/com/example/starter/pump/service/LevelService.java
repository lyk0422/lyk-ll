package com.example.starter.pump.service;

import com.example.starter.pump.core.TimeProvider;
import com.example.starter.pump.domain.PumpStation;
import com.example.starter.pump.domain.PumpdownEvent;
import com.example.starter.pump.error.ApiException;
import com.example.starter.pump.repo.CatalogRepository;
import com.example.starter.pump.repo.LevelReadingRepository;
import com.example.starter.pump.repo.MissContactOrderRepository;
import com.example.starter.pump.repo.PumpTaskRepository;
import com.example.starter.pump.repo.PumpdownEventRepository;
import com.example.starter.pump.web.dto.LevelIngestRequest;
import com.example.starter.pump.web.dto.LevelIngestResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 液位复测：站点＋时刻去重、恰达目标位/低于安全下限自动停泵、
 * 恰满30分钟无数据失联派单、归档后迟到数据只记迟到事件。
 */
@Service
public class LevelService {

    /** 失联阈值：距末次液位恰满30分钟即判定（等于30分钟合法）。 */
    static final Duration MISS_CONTACT_AFTER = Duration.ofMinutes(30);

    private final CatalogRepository catalogRepository;
    private final PumpdownEventRepository eventRepository;
    private final LevelReadingRepository levelRepository;
    private final MissContactOrderRepository missContactRepository;
    private final PumpCommandService pumpCommandService;
    private final TimeProvider timeProvider;

    public LevelService(CatalogRepository catalogRepository,
                        PumpdownEventRepository eventRepository,
                        LevelReadingRepository levelRepository,
                        MissContactOrderRepository missContactRepository,
                        PumpCommandService pumpCommandService,
                        TimeProvider timeProvider) {
        this.catalogRepository = catalogRepository;
        this.eventRepository = eventRepository;
        this.levelRepository = levelRepository;
        this.missContactRepository = missContactRepository;
        this.pumpCommandService = pumpCommandService;
        this.timeProvider = timeProvider;
    }

    /** 处理液位上送。 */
    public LevelIngestResult ingest(LevelIngestRequest request) {
        PumpStation station = catalogRepository.findStation(request.stationCode())
                .orElseThrow(() -> ApiException.unprocessable("STATION_NOT_FOUND")
                        .raise("泵站不存在: " + request.stationCode()));
        LocalDateTime receivedAt = timeProvider.now();

        PumpdownEvent active = eventRepository.findActiveByZone(station.zoneCode()).orElse(null);
        if (active == null) {
            return handleWhenNoActiveEvent(station, request, receivedAt);
        }
        return handleDuringPumpdown(station, active, request, receivedAt);
    }

    /** 无活动预降：若分区最近事件已归档且本次在归档后接收，只记迟到事件。 */
    private LevelIngestResult handleWhenNoActiveEvent(PumpStation station,
                                                      LevelIngestRequest request,
                                                      LocalDateTime receivedAt) {
        PumpdownEvent latest = eventRepository.findLatestByZone(station.zoneCode()).orElse(null);
        if (latest != null && latest.archived() && receivedAt.isAfter(latest.archivedAt())) {
            boolean inserted = levelRepository.insertLate(station.code(), request.readingTime(),
                    request.level(), receivedAt);
            return new LevelIngestResult(null, station.code(), request.readingTime(),
                    inserted, !inserted, true, null, null, null, null);
        }
        throw ApiException.unprocessable("NO_ACTIVE_PUMPDOWN")
                .raise("泵站 " + station.code() + " 所在分区无进行中的预降事件，液位无法归属");
    }

    private LevelIngestResult handleDuringPumpdown(PumpStation station, PumpdownEvent event,
                                                   LevelIngestRequest request,
                                                   LocalDateTime receivedAt) {
        // 站点＋时刻去重：命中则原样返回，不产生任何新副作用
        Long existingId = levelRepository.findIdByStationAndTime(
                station.code(), request.readingTime());
        if (existingId != null) {
            return new LevelIngestResult(existingId, station.code(), request.readingTime(),
                    false, true, false, null, null, null, null);
        }

        // 失联判定以本次数据之前的末次液位时刻为基准，须在插入本次数据前取
        LocalDateTime lastReading = levelRepository.latestReadingTimeBefore(
                station.code(), event.id(), request.readingTime());

        Long readingId = levelRepository.insert(station.code(), event.id(),
                request.readingTime(), request.level(), receivedAt);
        if (readingId == null) {
            // 并发下站点＋时刻被抢占，按去重返回首次ID
            Long concurrentId = levelRepository.findIdByStationAndTime(
                    station.code(), request.readingTime());
            return new LevelIngestResult(concurrentId, station.code(), request.readingTime(),
                    false, true, false, null, null, null, null);
        }

        AutoStop autoStop = evaluateAutoStop(station, event, request);
        MissContact missContact = evaluateMissContact(station, event, lastReading, request);

        return new LevelIngestResult(readingId, station.code(), request.readingTime(),
                true, false, false,
                autoStop.taskId(), autoStop.reason(),
                missContact.orderId(), missContact.triggeredAt());
    }

    /** 恰达目标位停泵；严格低于安全下限立即停泵（恰等下限合法不停）。 */
    private AutoStop evaluateAutoStop(PumpStation station, PumpdownEvent event,
                                      LevelIngestRequest request) {
        BigDecimal level = request.level();
        String reason;
        if (level.compareTo(station.safeLowLevel()) < 0) {
            reason = "SAFE_LOW";
        } else if (level.compareTo(station.targetLevel()) <= 0) {
            reason = "TARGET_REACHED";
        } else {
            return AutoStop.NONE;
        }
        Long taskId = pumpCommandService.autoStop(station, event, request.readingTime(), reason);
        return taskId == null ? AutoStop.NONE : new AutoStop(taskId, reason);
    }

    /** 距末次液位恰满30分钟判定失联派单；不足30分钟不判。尚无数据时以预降开始时刻为基准。 */
    private MissContact evaluateMissContact(PumpStation station, PumpdownEvent event,
                                            LocalDateTime lastReading,
                                            LevelIngestRequest request) {
        LocalDateTime baseline = lastReading != null ? lastReading : event.startedAt();
        Duration gap = Duration.between(baseline, request.readingTime());
        if (gap.compareTo(MISS_CONTACT_AFTER) < 0) {
            return MissContact.NONE;
        }
        // 判定时刻为基准时刻恰满30分钟
        LocalDateTime triggeredAt = baseline.plus(MISS_CONTACT_AFTER);
        Long orderId = missContactRepository.createIfAbsent(station.code(), event.id(),
                baseline, triggeredAt);
        return new MissContact(orderId, triggeredAt);
    }

    private record AutoStop(Long taskId, String reason) {
        static final AutoStop NONE = new AutoStop(null, null);
    }

    private record MissContact(Long orderId, LocalDateTime triggeredAt) {
        static final MissContact NONE = new MissContact(null, null);
    }
}
