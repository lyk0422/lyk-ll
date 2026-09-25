package com.example.starter.marpol.service;

import com.example.starter.marpol.domain.Ship;
import com.example.starter.marpol.domain.Voyage;
import com.example.starter.marpol.repo.ShipRepository;
import com.example.starter.marpol.support.ApiException;
import com.example.starter.marpol.support.TimeService;
import com.example.starter.marpol.web.VoyageResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 合成船舶与航次管理。
 */
@Service
public class ShipService {

    private final ShipRepository shipRepository;
    private final TimeService timeService;

    public ShipService(ShipRepository shipRepository, TimeService timeService) {
        this.shipRepository = shipRepository;
        this.timeService = timeService;
    }

    @Transactional
    public void createShip(String shipId, String shipName) {
        Ship ship = new Ship();
        ship.setShipId(shipId);
        ship.setShipName(shipName);
        ship.setCreatedAt(timeService.now());
        try {
            shipRepository.insertShip(ship);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("船舶已存在: " + shipId);
        }
    }

    @Transactional
    public void createVoyage(String shipId, String voyageNo) {
        if (shipRepository.findShip(shipId).isEmpty()) {
            throw ApiException.notFound("船舶不存在: " + shipId);
        }
        Voyage voyage = new Voyage();
        voyage.setShipId(shipId);
        voyage.setVoyageNo(voyageNo);
        voyage.setStatus("OPEN");
        voyage.setCreatedAt(timeService.now());
        try {
            shipRepository.insertVoyage(voyage);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("航次已存在: " + shipId + "/" + voyageNo);
        }
    }

    @Transactional
    public VoyageResponse closeVoyage(String shipId, String voyageNo) {
        if (shipRepository.findVoyage(shipId, voyageNo).isEmpty()) {
            throw ApiException.notFound("航次不存在: " + shipId + "/" + voyageNo);
        }
        int updated = shipRepository.closeVoyage(shipId, voyageNo, timeService.now());
        if (updated == 0) {
            throw ApiException.conflict("航次已关闭: " + shipId + "/" + voyageNo);
        }
        Voyage voyage = shipRepository.findVoyage(shipId, voyageNo).orElseThrow();
        return new VoyageResponse(voyage.getShipId(), voyage.getVoyageNo(), voyage.getStatus(),
                voyage.getClosedAt());
    }

    @Transactional(readOnly = true)
    public VoyageResponse getVoyage(String shipId, String voyageNo) {
        Voyage voyage = shipRepository.findVoyage(shipId, voyageNo)
                .orElseThrow(() -> ApiException.notFound(
                        "航次不存在: " + shipId + "/" + voyageNo));
        return new VoyageResponse(voyage.getShipId(), voyage.getVoyageNo(), voyage.getStatus(),
                voyage.getClosedAt());
    }
}
