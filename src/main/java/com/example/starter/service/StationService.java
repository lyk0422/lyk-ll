package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.example.starter.model.BatteryReading;
import com.example.starter.model.Generator;
import com.example.starter.model.Station;
import com.example.starter.repository.BatteryReadingRepository;
import com.example.starter.repository.GeneratorRepository;
import com.example.starter.repository.StationRepository;
import com.example.starter.web.dto.ApiDtos.BatteryReadingRequest;
import com.example.starter.web.dto.ApiDtos.BatteryReadingResponse;
import com.example.starter.web.dto.ApiDtos.CreateGeneratorRequest;
import com.example.starter.web.dto.ApiDtos.CreateStationRequest;
import com.example.starter.web.dto.ApiDtos.GeneratorResponse;
import com.example.starter.web.dto.ApiDtos.StationResponse;

/**
 * 基站、油机档案与电池读数服务；全部写接口以 requestId 幂等。
 */
@Service
public class StationService {

    private final StationRepository stationRepository;
    private final GeneratorRepository generatorRepository;
    private final BatteryReadingRepository readingRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public StationService(StationRepository stationRepository,
                          GeneratorRepository generatorRepository,
                          BatteryReadingRepository readingRepository,
                          IdempotencyService idempotencyService, Clock clock) {
        this.stationRepository = stationRepository;
        this.generatorRepository = generatorRepository;
        this.readingRepository = readingRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 创建基站（合成数据入口）。
     */
    public StationResponse createStation(CreateStationRequest request) {
        return idempotencyService.execute("createStation", request.requestId(),
                new Object[]{request.stationCode(), request.loadKw(), request.batteryCapacityKwh()},
                StationResponse.class,
                () -> {
                    if (stationRepository.findByCode(request.stationCode()).isPresent()) {
                        throw BusinessException.conflict("STATION_EXISTS",
                                "基站编码已存在: " + request.stationCode());
                    }
                    long id = stationRepository.insert(request.stationCode(), request.loadKw(),
                            request.batteryCapacityKwh(), Instant.now(clock));
                    return new StationResponse(id, request.stationCode(), request.loadKw(),
                            request.batteryCapacityKwh());
                });
    }

    /**
     * 创建油机（合成数据入口），默认空闲。
     */
    public GeneratorResponse createGenerator(CreateGeneratorRequest request) {
        return idempotencyService.execute("createGenerator", request.requestId(),
                new Object[]{request.generatorCode()},
                GeneratorResponse.class,
                () -> {
                    if (generatorRepository.findByCode(request.generatorCode()).isPresent()) {
                        throw BusinessException.conflict("GENERATOR_EXISTS",
                                "油机编号已存在: " + request.generatorCode());
                    }
                    long id = generatorRepository.insert(request.generatorCode(), Instant.now(clock));
                    return new GeneratorResponse(id, request.generatorCode(), "IDLE");
                });
    }

    /**
     * 上报电池容量读数：按“基站＋抄表时刻”去重，乱序按抄表时刻归位。
     */
    public BatteryReadingResponse createReading(BatteryReadingRequest request) {
        return idempotencyService.execute("createReading", request.requestId(),
                new Object[]{request.stationCode(), request.readingTime(), request.remainingPercent()},
                BatteryReadingResponse.class,
                () -> {
                    stationRepository.findByCode(request.stationCode())
                            .orElseThrow(() -> BusinessException.notFound("STATION_NOT_FOUND",
                                    "基站不存在: " + request.stationCode()));
                    Optional<BatteryReading> existing = readingRepository
                            .findByStationAndTime(request.stationCode(), request.readingTime());
                    if (existing.isPresent()) {
                        BatteryReading reading = existing.get();
                        if (reading.remainingPercent().compareTo(request.remainingPercent()) != 0) {
                            throw BusinessException.conflict("READING_CONFLICT",
                                    "同一基站同一抄表时刻已存在不同读数");
                        }
                        return new BatteryReadingResponse(reading.id(), reading.stationCode(),
                                reading.readingTime(), reading.remainingPercent(), true);
                    }
                    long id = readingRepository.insert(request.stationCode(), request.readingTime(),
                            request.remainingPercent(), request.requestId(), Instant.now(clock));
                    return new BatteryReadingResponse(id, request.stationCode(),
                            request.readingTime(), request.remainingPercent(), false);
                });
    }

    /**
     * 查询油机当前状态，供测试与排障。
     */
    public Generator requireGenerator(String code) {
        return generatorRepository.findByCode(code)
                .orElseThrow(() -> BusinessException.notFound("GENERATOR_NOT_FOUND",
                        "油机不存在: " + code));
    }

    /**
     * 查询基站，不存在抛 404。
     */
    public Station requireStation(String code) {
        return stationRepository.findByCode(code)
                .orElseThrow(() -> BusinessException.notFound("STATION_NOT_FOUND",
                        "基站不存在: " + code));
    }
}
