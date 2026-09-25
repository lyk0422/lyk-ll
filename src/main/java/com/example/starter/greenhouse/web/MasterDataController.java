package com.example.starter.greenhouse.web;

import com.example.starter.greenhouse.dto.CropCreateRequest;
import com.example.starter.greenhouse.dto.ZoneCreateRequest;
import com.example.starter.greenhouse.po.Crop;
import com.example.starter.greenhouse.po.Zone;
import com.example.starter.greenhouse.service.MasterDataService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合成基础数据（作物生育期窗口、分区）维护入口。
 */
@RestController
@RequestMapping("/api/greenhouse")
public class MasterDataController {

    private final MasterDataService service;

    public MasterDataController(MasterDataService service) {
        this.service = service;
    }

    @PostMapping("/crops")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createCrop(@Valid @RequestBody CropCreateRequest req) {
        Crop c = service.createCrop(req);
        return Map.of("id", c.getId(), "cropCode", c.getCropCode(), "name", c.getName(),
                "stageCode", c.getStageCode());
    }

    @PostMapping("/zones")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createZone(@Valid @RequestBody ZoneCreateRequest req) {
        Zone z = service.createZone(req);
        return Map.of("id", z.getId(), "zoneCode", z.getZoneCode(), "name", z.getName());
    }
}
