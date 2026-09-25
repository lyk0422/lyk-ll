package com.example.starter.marpol.web;

import com.example.starter.marpol.service.ShipService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合成船舶与航次管理接口。
 */
@RestController
@RequestMapping("/api")
public class ShipController {

    private final ShipService shipService;

    public ShipController(ShipService shipService) {
        this.shipService = shipService;
    }

    @PostMapping("/ships")
    @ResponseStatus(HttpStatus.CREATED)
    public void createShip(@Valid @RequestBody ShipCreateRequest request) {
        shipService.createShip(request.shipId(), request.shipName());
    }

    @PostMapping("/voyages")
    @ResponseStatus(HttpStatus.CREATED)
    public void createVoyage(@Valid @RequestBody VoyageCreateRequest request) {
        shipService.createVoyage(request.shipId(), request.voyageNo());
    }

    @PostMapping("/voyages/close")
    public VoyageResponse closeVoyage(@RequestParam String shipId,
                                      @RequestParam String voyageNo) {
        return shipService.closeVoyage(shipId, voyageNo);
    }

    @GetMapping("/voyages/{shipId}/{voyageNo}")
    public VoyageResponse getVoyage(@PathVariable String shipId,
                                    @PathVariable String voyageNo) {
        return shipService.getVoyage(shipId, voyageNo);
    }
}
