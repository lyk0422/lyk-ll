package com.example.starter.groundwire;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.groundwire.GroundWireDtos.WireView;

/**
 * 接地线实物台账查询接口：按编号或状态查询实物状态与版本。
 */
@RestController
@RequestMapping("/api/ground-wires")
public class GroundWireInventoryController {

    private final GroundWireService service;

    public GroundWireInventoryController(GroundWireService service) {
        this.service = service;
    }

    /** 台账列表，可按状态（AVAILABLE/BORROWED/APPLIED/REMOVED_PENDING_RETURN）过滤。 */
    @GetMapping
    public List<WireView> list(@RequestParam(required = false) String status) {
        return service.listWires(status);
    }

    /** 按唯一编号查询单条实物。 */
    @GetMapping("/{wireNo}")
    public WireView get(@PathVariable String wireNo) {
        return service.getWire(wireNo);
    }
}
