package com.example.starter.web;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.GeneratorDispatchService;
import com.example.starter.web.dto.GeneratorDispatchRequest;
import com.example.starter.web.dto.GeneratorDispatchResponse;
import com.example.starter.web.dto.GeneratorOrderView;
import com.example.starter.web.dto.GeneratorReassignRequest;
import com.example.starter.web.dto.GeneratorReassignResponse;
import com.example.starter.web.dto.OccupationEntry;
import com.example.starter.web.dto.VehicleArrivalRequest;
import com.example.starter.web.dto.VehicleArrivalResponse;
import com.example.starter.web.dto.VehicleRegisterRequest;
import com.example.starter.web.dto.VehicleView;

/**
 * 重要用户发电车组合调度 REST 入口：发电车池登记/台账、组合调度、逐车到场、
 * 到达超时整体改派、调度单详情与车占用台账。所有写操作携带 requestId 幂等与 expectedVersion。
 */
@RestController
@RequestMapping("/api/generator")
public class GeneratorController {

    private final GeneratorDispatchService generator;

    public GeneratorController(GeneratorDispatchService generator) {
        this.generator = generator;
    }

    /** 登记发电车（正数整数kW容量、服务馈线、可用状态），requestId 幂等。 */
    @PostMapping("/vehicles")
    public VehicleView register(@Validated @RequestBody VehicleRegisterRequest req) {
        return generator.registerVehicle(req);
    }

    /** 发电车池台账：容量、馈线、可用状态与当前占用；可按馈线过滤。 */
    @GetMapping("/vehicles")
    public List<VehicleView> vehicles(@RequestParam(required = false) String feederId) {
        return generator.listVehicles(feederId);
    }

    /** 组合调度下发，requestId 幂等，expectedVersion 从 1 开始。 */
    @PostMapping("/dispatches")
    public GeneratorDispatchResponse dispatch(@Validated @RequestBody GeneratorDispatchRequest req) {
        return generator.dispatch(req);
    }

    /** 逐车到场确认，requestId 幂等；全部到场且未超时才供电。 */
    @PostMapping("/arrivals")
    public VehicleArrivalResponse arrive(@Validated @RequestBody VehicleArrivalRequest req) {
        return generator.arrive(req);
    }

    /** 到达超时整体改派，requestId 幂等；已到场车保留、其余原子换新车。 */
    @PostMapping("/reassignments")
    public GeneratorReassignResponse reassign(@Validated @RequestBody GeneratorReassignRequest req) {
        return generator.reassign(req);
    }

    /** 调度单详情（含组合车辆到场与事件轨迹，查询时复算到达超时）。 */
    @GetMapping("/orders/{id}")
    public GeneratorOrderView order(@PathVariable long id) {
        return generator.viewOrder(id);
    }

    /** 发电车占用台账：含历史占用，可按车过滤。 */
    @GetMapping("/occupations")
    public List<OccupationEntry> occupations(@RequestParam(required = false) Long vehicleId) {
        return generator.occupationLedger(vehicleId);
    }
}
