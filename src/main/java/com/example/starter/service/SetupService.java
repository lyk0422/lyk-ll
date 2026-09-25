package com.example.starter.service;

import com.example.starter.api.dto.SetupDtos;
import com.example.starter.repo.CropRepository;
import com.example.starter.repo.ZoneRepository;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 合成基础数据（作物、生育期窗口、分区）维护。
 */
@Service
public class SetupService {

    private final CropRepository cropRepository;
    private final ZoneRepository zoneRepository;
    private final Clock clock;

    public SetupService(CropRepository cropRepository, ZoneRepository zoneRepository, Clock clock) {
        this.cropRepository = cropRepository;
        this.zoneRepository = zoneRepository;
        this.clock = clock;
    }

    @Transactional
    public void upsertCrop(SetupDtos.CropSetupRequest request) {
        Instant now = clock.instant();
        List<CropRepository.StageWindow> stages = request.stages().stream()
                .map(s -> new CropRepository.StageWindow(
                        request.cropCode(), s.stageCode(), s.stageName(),
                        s.dayTempMin(), s.dayTempMax(), s.nightTempMin(), s.nightTempMax(),
                        s.humidityMin(), s.humidityMax()))
                .toList();
        cropRepository.upsertCrop(request.cropCode(), request.cropName(), now, stages);
    }

    @Transactional
    public SetupDtos.ZoneResponse createZone(SetupDtos.ZoneSetupRequest request) {
        if (zoneRepository.exists(request.zoneCode())) {
            throw new ConflictException("分区编码已存在：" + request.zoneCode());
        }
        zoneRepository.insert(request.zoneCode(), request.zoneName(), clock.instant());
        return new SetupDtos.ZoneResponse(request.zoneCode(), request.zoneName());
    }

    public List<SetupDtos.ZoneResponse> listZones() {
        return zoneRepository.findAll().stream()
                .map(z -> new SetupDtos.ZoneResponse(z.zoneCode(), z.zoneName()))
                .toList();
    }

    /**
     * 校验引用存在性，供其他服务复用。
     */
    public void requireZone(String zoneCode) {
        if (!zoneRepository.exists(zoneCode)) {
            throw new NotFoundException("分区不存在：" + zoneCode);
        }
    }
}
