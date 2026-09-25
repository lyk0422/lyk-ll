package com.example.starter.marpol;

import com.example.starter.marpol.model.LandMark;
import com.example.starter.marpol.model.Ship;
import com.example.starter.marpol.model.SpecialArea;
import com.example.starter.marpol.model.Voyage;
import com.example.starter.marpol.service.ReferenceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 测试夹具：登记合成船舶、航次、特殊区域与陆地参照点。
 */
@Component
public class TestFixtures {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReferenceService referenceService;

    public Ship ship(String imo) {
        return referenceService.createShip(
                new com.example.starter.marpol.web.CreateShipRequest(imo, "SHIP-" + imo));
    }

    public Voyage voyage(String imo, String voyageNo) {
        return referenceService.createVoyage(
                new com.example.starter.marpol.web.CreateVoyageRequest(imo, voyageNo));
    }

    /**
     * 注册一个 [0,0]-[10,10] 的方形特殊区域（边界视为区域内）。
     */
    public SpecialArea squareSpecialArea() {
        referenceService.createSpecialArea("SA-SQUARE", List.of(
                new double[]{0, 0}, new double[]{10, 0},
                new double[]{10, 10}, new double[]{0, 10}));
        return new SpecialArea(1L, "SA-SQUARE", List.of(
                new double[]{0, 0}, new double[]{10, 0},
                new double[]{10, 10}, new double[]{0, 10}));
    }

    /**
     * 注册原点陆地参照点。
     */
    public LandMark originLand() {
        referenceService.createLandMark("LAND-ORIGIN", 0, 0);
        return new LandMark(1L, "LAND-ORIGIN", 0, 0);
    }
}
