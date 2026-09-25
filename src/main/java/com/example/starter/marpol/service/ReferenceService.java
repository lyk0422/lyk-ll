package com.example.starter.marpol.service;

import com.example.starter.marpol.error.ConflictException;
import com.example.starter.marpol.error.NotFoundException;
import com.example.starter.marpol.model.Ship;
import com.example.starter.marpol.model.Voyage;
import com.example.starter.marpol.repo.ReferenceRepository;
import com.example.starter.marpol.support.ServiceClock;
import com.example.starter.marpol.web.CreateShipRequest;
import com.example.starter.marpol.web.CreateVoyageRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;

/**
 * 参考数据服务：合成船舶、航次（开放/关闭）与地理要素维护。
 */
@Service
public class ReferenceService {

    private final ReferenceRepository refs;
    private final JdbcTemplate jdbc;
    private final ServiceClock clock;
    private final ObjectMapper objectMapper;

    public ReferenceService(ReferenceRepository refs,
                            JdbcTemplate jdbc,
                            ServiceClock clock,
                            ObjectMapper objectMapper) {
        this.refs = refs;
        this.jdbc = jdbc;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Ship createShip(CreateShipRequest req) {
        try {
            return refs.createShip(req.imo(), req.name());
        } catch (DuplicateKeyException e) {
            throw new ConflictException("船舶已存在: " + req.imo());
        }
    }

    @Transactional(readOnly = true)
    public Ship requireShip(String imo) {
        Ship ship = refs.findShipByImo(imo);
        if (ship == null) {
            throw new NotFoundException("船舶不存在: " + imo);
        }
        return ship;
    }

    @Transactional
    public Voyage createVoyage(CreateVoyageRequest req) {
        Ship ship = requireShip(req.shipImo());
        try {
            return refs.createVoyage(ship, req.voyageNo());
        } catch (DuplicateKeyException e) {
            throw new ConflictException("航次已存在: " + req.shipImo() + "/" + req.voyageNo());
        }
    }

    /**
     * 关闭航次；关闭后新增记录/新版本一律拒绝。重复关闭保持幂等。
     */
    @Transactional
    public Voyage closeVoyage(String shipImo, String voyageNo) {
        Ship ship = requireShip(shipImo);
        Voyage voyage = refs.findVoyage(ship.id(), voyageNo);
        if (voyage == null) {
            throw new NotFoundException("航次不存在: " + shipImo + "/" + voyageNo);
        }
        return refs.closeVoyage(voyage.id());
    }

    /**
     * 登记合成特殊区域。polygon 为 [[lon,lat],...] 环点。
     */
    @Transactional
    public void createSpecialArea(String name, List<double[]> polygon) {
        if (polygon == null || polygon.size() < 3) {
            throw new ConflictException("特殊区域多边形至少需要 3 个环点");
        }
        try {
            String json = objectMapper.writeValueAsString(polygon);
            jdbc.update("INSERT INTO special_area (name, polygon_json, created_at) VALUES (?, ?, ?)",
                    name, json, Timestamp.from(clock.now()));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("多边形数据无法序列化", e);
        }
    }

    /**
     * 登记合成陆地参照点。
     */
    @Transactional
    public void createLandMark(String name, double longitude, double latitude) {
        jdbc.update("INSERT INTO land_mark (name, longitude, latitude, created_at) VALUES (?, ?, ?, ?)",
                name, longitude, latitude, Timestamp.from(clock.now()));
    }
}
