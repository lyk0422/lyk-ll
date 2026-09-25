package com.example.starter.greenhouse.service;

import com.example.starter.greenhouse.dto.CropCreateRequest;
import com.example.starter.greenhouse.dto.ZoneCreateRequest;
import com.example.starter.greenhouse.po.Crop;
import com.example.starter.greenhouse.po.Zone;
import com.example.starter.greenhouse.repo.CropRepository;
import com.example.starter.greenhouse.repo.ZoneRepository;
import com.example.starter.greenhouse.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 作物与分区合成基础数据维护。
 */
@Service
public class MasterDataService {

    private final CropRepository cropRepository;
    private final ZoneRepository zoneRepository;
    private final Clock clock;

    public MasterDataService(CropRepository cropRepository, ZoneRepository zoneRepository, Clock clock) {
        this.cropRepository = cropRepository;
        this.zoneRepository = zoneRepository;
        this.clock = clock;
    }

    public Crop createCrop(CropCreateRequest req) {
        if (req.dayTempMin().compareTo(req.dayTempMax()) > 0
                || req.nightTempMin().compareTo(req.nightTempMax()) > 0
                || req.dayHumidityMin().compareTo(req.dayHumidityMax()) > 0
                || req.nightHumidityMin().compareTo(req.nightHumidityMax()) > 0) {
            throw ApiException.badRequest("允许窗口下界不得大于上界");
        }
        Crop crop = new Crop();
        crop.setCropCode(req.cropCode());
        crop.setName(req.name());
        crop.setStageCode(req.stageCode());
        crop.setDayTempMin(req.dayTempMin());
        crop.setDayTempMax(req.dayTempMax());
        crop.setDayHumidityMin(req.dayHumidityMin());
        crop.setDayHumidityMax(req.dayHumidityMax());
        crop.setNightTempMin(req.nightTempMin());
        crop.setNightTempMax(req.nightTempMax());
        crop.setNightHumidityMin(req.nightHumidityMin());
        crop.setNightHumidityMax(req.nightHumidityMax());
        crop.setCreatedAt(Instant.now(clock));
        try {
            return cropRepository.insert(crop);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("作物代码已存在: " + req.cropCode());
        }
    }

    public Zone createZone(ZoneCreateRequest req) {
        Zone zone = new Zone();
        zone.setZoneCode(req.zoneCode());
        zone.setName(req.name());
        zone.setCreatedAt(Instant.now(clock));
        try {
            return zoneRepository.insert(zone);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("分区代码已存在: " + req.zoneCode());
        }
    }
}
