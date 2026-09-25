package com.example.starter.hazmat.web;

import com.example.starter.hazmat.repo.AlertRepository;
import com.example.starter.hazmat.service.CheckinService;
import com.example.starter.hazmat.service.RerouteService;
import com.example.starter.hazmat.service.TimeoutScanService;
import com.example.starter.hazmat.service.WaybillService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 危化品运输监管接口：申请、核准、发运、打卡、改线、核销与超时复算。
 */
@RestController
@RequestMapping("/api")
public class HazmatController {

    private final WaybillService waybillService;
    private final CheckinService checkinService;
    private final RerouteService rerouteService;
    private final TimeoutScanService scanService;
    private final AlertRepository alerts;

    public HazmatController(WaybillService waybillService, CheckinService checkinService,
                            RerouteService rerouteService, TimeoutScanService scanService,
                            AlertRepository alerts) {
        this.waybillService = waybillService;
        this.checkinService = checkinService;
        this.rerouteService = rerouteService;
        this.scanService = scanService;
        this.alerts = alerts;
    }

    /** 运输申请（requestId 幂等）。 */
    @PostMapping("/waybills/apply")
    public WaybillView apply(@Valid @RequestBody ApplyRequest request) {
        return waybillService.apply(request.requestId(), request.vehicleCode(),
                request.hazmatLevel(), request.routeId(), request.plannedDeparture());
    }

    /** 路线核准：类别等级匹配与管制时段（半开区间）校验。 */
    @PostMapping("/waybills/{id}/approve")
    public WaybillView approve(@PathVariable long id) {
        return waybillService.approve(id);
    }

    /** 发运：生成在途途经点实例。 */
    @PostMapping("/waybills/{id}/depart")
    public WaybillView depart(@PathVariable long id) {
        return waybillService.depart(id);
    }

    /** 到达核销：结案终态，重复核销幂等成功。 */
    @PostMapping("/waybills/{id}/close")
    public WaybillView close(@PathVariable long id) {
        return waybillService.close(id);
    }

    /** 运单查询（含当前路线途经点）。 */
    @GetMapping("/waybills/{id}")
    public WaybillView get(@PathVariable long id) {
        return waybillService.getView(id);
    }

    /** 押运打卡（requestId 幂等，运单+途经点序号去重）。 */
    @PostMapping("/checkins")
    public CheckinView checkin(@Valid @RequestBody CheckinRequest request) {
        return checkinService.checkin(request.requestId(), request.waybillId(),
                request.waypointSeq(), request.checkinTime());
    }

    /** 紧急改线申请（requestId 幂等）。 */
    @PostMapping("/reroutes/apply")
    public RerouteView rerouteApply(@Valid @RequestBody RerouteRequest request) {
        return rerouteService.apply(request.requestId(), request.waybillId(),
                request.newRouteId());
    }

    /** 改线核准：与原路线终止在同一事务内生效。 */
    @PostMapping("/reroutes/{id}/approve")
    public RerouteView rerouteApprove(@PathVariable long id) {
        return rerouteService.approve(id);
    }

    /** 手动触发超时复算（偏离预警//失联升级）。 */
    @PostMapping("/scan/timeouts")
    public ResultView scanTimeouts() {
        return scanService.scanOnce();
    }

    /** 车辆告警事件查询（偏离预警/失联/逆序/迟到）。 */
    @GetMapping("/alerts")
    public List<String> alerts(@RequestParam String vehicleCode) {
        return alerts.findMessagesByVehicle(vehicleCode);
    }
}
