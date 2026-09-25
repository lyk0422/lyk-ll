package com.example.starter.marpol.web;

import com.example.starter.marpol.model.Ship;
import com.example.starter.marpol.model.Voyage;
import com.example.starter.marpol.service.ReferenceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 参考数据 API：合成船舶、航次、特殊区域、陆地参照点。
 */
@RestController
@RequestMapping("/api/reference")
public class ReferenceController {

    private final ReferenceService referenceService;

    public ReferenceController(ReferenceService referenceService) {
        this.referenceService = referenceService;
    }

    @PostMapping("/ships")
    @ResponseStatus(HttpStatus.CREATED)
    public Ship createShip(@Valid @RequestBody CreateShipRequest request) {
        return referenceService.createShip(request);
    }

    @PostMapping("/voyages")
    @ResponseStatus(HttpStatus.CREATED)
    public Voyage createVoyage(@Valid @RequestBody CreateVoyageRequest request) {
        return referenceService.createVoyage(request);
    }

    @PostMapping("/voyages/close")
    public Voyage closeVoyage(@RequestParam String shipImo, @RequestParam String voyageNo) {
        return referenceService.closeVoyage(shipImo, voyageNo);
    }

    @PostMapping("/special-areas")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> createSpecialArea(@RequestParam String name,
                                                 @RequestBody List<double[]> polygon) {
        referenceService.createSpecialArea(name, polygon);
        return Map.of("name", name);
    }

    @PostMapping("/land-marks")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> createLandMark(@RequestParam String name,
                                              @RequestParam double longitude,
                                              @RequestParam double latitude) {
        referenceService.createLandMark(name, longitude, latitude);
        return Map.of("name", name);
    }
}
