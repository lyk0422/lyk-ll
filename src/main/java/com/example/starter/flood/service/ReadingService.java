package com.example.starter.flood.service;

import com.example.starter.flood.repo.PondPO;
import com.example.starter.flood.repo.PondRepository;
import com.example.starter.flood.repo.ReadingRepository;
import com.example.starter.flood.repo.WarningPO;
import com.example.starter.flood.repo.WarningRepository;
import com.example.starter.flood.support.JsonSupport;
import com.example.starter.flood.web.dto.RainfallRequest;
import com.example.starter.flood.web.dto.ReadingResult;
import com.example.starter.flood.web.dto.WaterReadingRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 降雨/水位读数服务。
 *
 * <p>去重：以“测点编码 + 采样时刻”为唯一键，重复上报直接丢弃并返回未受理；</p>
 * <p>归位：以采样时刻落库，乱序上报不影响按时刻排序的后续使用；</p>
 * <p>迟到：读数在处置链复盘归档之后才送达（该库已无生效处置链且存在已归档链），
 * 只在迟到事件表登记，不进入读数表，避免污染已复盘结论。</p>
 */
@Service
public class ReadingService {

    private final ReadingRepository readingRepository;
    private final PondRepository pondRepository;
    private final WarningRepository warningRepository;
    private final JsonSupport jsonSupport;
    private final Clock clock;

    public ReadingService(ReadingRepository readingRepository, PondRepository pondRepository,
                          WarningRepository warningRepository, JsonSupport jsonSupport,
                          Clock floodClock) {
        this.readingRepository = readingRepository;
        this.pondRepository = pondRepository;
        this.warningRepository = warningRepository;
        this.jsonSupport = jsonSupport;
        this.clock = floodClock;
    }

    /**
     * 上报降雨读数。
     */
    @Transactional
    public ReadingResult reportRainfall(String pondCode, RainfallRequest request) {
        PondPO pond = pondRepository.requireByCode(pondCode);
        LocalDateTime recordedAt = LocalDateTime.now(clock);
        if (isLateAfterArchive(pond.id, request.sampleTime())) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("stationCode", request.stationCode());
            payload.put("sampleTime", request.sampleTime().toString());
            payload.put("rainfallDepth", request.rainfallDepth());
            payload.put("durationMinutes", request.durationMinutes());
            readingRepository.insertLateEvent(pond.id, "RAIN", request.stationCode(),
                    request.sampleTime(), recordedAt, jsonSupport.toJson(payload));
            return new ReadingResult(true, true,
                    dedupKey(request.stationCode(), request.sampleTime()), request.sampleTime());
        }
        boolean inserted = readingRepository.insertRainfall(pond.id, request.stationCode(),
                request.sampleTime(), request.rainfallDepth(), request.durationMinutes(),
                recordedAt);
        return new ReadingResult(inserted, false,
                dedupKey(request.stationCode(), request.sampleTime()), request.sampleTime());
    }

    /**
     * 上报水位/监测读数。
     */
    @Transactional
    public ReadingResult reportWater(String pondCode, WaterReadingRequest request) {
        PondPO pond = pondRepository.requireByCode(pondCode);
        LocalDateTime recordedAt = LocalDateTime.now(clock);
        if (isLateAfterArchive(pond.id, request.sampleTime())) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("stationCode", request.stationCode());
            payload.put("sampleTime", request.sampleTime().toString());
            payload.put("waterLevel", request.waterLevel());
            payload.put("dryBeachLength", request.dryBeachLength());
            payload.put("phreaticDepth", request.phreaticDepth());
            readingRepository.insertLateEvent(pond.id, "WATER", request.stationCode(),
                    request.sampleTime(), recordedAt, jsonSupport.toJson(payload));
            return new ReadingResult(true, true,
                    dedupKey(request.stationCode(), request.sampleTime()), request.sampleTime());
        }
        boolean inserted = readingRepository.insertWater(pond.id, request.stationCode(),
                request.sampleTime(), request.waterLevel(), request.dryBeachLength(),
                request.phreaticDepth(), recordedAt);
        return new ReadingResult(inserted, false,
                dedupKey(request.stationCode(), request.sampleTime()), request.sampleTime());
    }

    /**
     * 判迟到：该库已无生效处置链但存在已归档链，即读数在复盘归档之后才送达。
     * 仍有生效链时读数正常入库，归档判定不依据采样时刻（乱序读数应按时刻归位而非丢弃）。
     */
    private boolean isLateAfterArchive(Long pondId, LocalDateTime sampleTime) {
        return warningRepository.countActiveByPond(pondId) == 0
                && !warningRepository.findArchivedByPond(pondId).isEmpty();
    }

    private String dedupKey(String stationCode, LocalDateTime sampleTime) {
        return stationCode + "@" + sampleTime;
    }
}
