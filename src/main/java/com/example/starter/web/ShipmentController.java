package com.example.starter.web;

import com.example.starter.service.ShipmentService;
import com.example.starter.web.dto.ShipmentProgressView;
import com.example.starter.web.dto.SplitEnableRequest;
import com.example.starter.web.dto.SplitEnableResponse;
import com.example.starter.web.dto.VehicleCreateRequest;
import com.example.starter.web.dto.VehicleDepartRequest;
import com.example.starter.web.dto.VehicleRevokeRequest;
import com.example.starter.web.dto.VehicleView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分车出厂接口：启用分车、车单创建/撤销、逐车核销与进度查询。
 * 原整批出厂入口保留（未启用批兼容），受控批启用分车后整批入口拒绝。
 */
@RestController
@RequestMapping("/api/batches/{batchNo}/shipments")
public class ShipmentController {

    private final ShipmentService service;

    public ShipmentController(ShipmentService service) {
        this.service = service;
    }

    /** 启用分车出厂（幂等）。 */
    @PostMapping("/enable")
    public SplitEnableResponse enable(@PathVariable String batchNo,
                                      @Valid @RequestBody SplitEnableRequest request) {
        return service.enableSplit(batchNo, request);
    }

    /** 创建车辆清单并全量预占（幂等）。 */
    @PostMapping("/vehicles")
    public VehicleView createVehicle(@PathVariable String batchNo,
                                     @Valid @RequestBody VehicleCreateRequest request) {
        return service.createVehicle(batchNo, request);
    }

    /** 撤销未发车车辆清单并释放预占（幂等）。 */
    @PostMapping("/vehicles/{vehicleNo}/revoke")
    public VehicleView revoke(@PathVariable String batchNo,
                              @PathVariable String vehicleNo,
                              @Valid @RequestBody VehicleRevokeRequest request) {
        return service.revokeVehicle(batchNo, vehicleNo, request);
    }

    /** 逐车核销发车出厂（幂等）。 */
    @PostMapping("/vehicles/{vehicleNo}/depart")
    public VehicleView depart(@PathVariable String batchNo,
                              @PathVariable String vehicleNo,
                              @Valid @RequestBody VehicleDepartRequest request) {
        return service.departVehicle(batchNo, vehicleNo, request);
    }

    /** 分车出厂进度查询：每车冻结清单与按头只勾稽的进度。 */
    @GetMapping
    public ShipmentProgressView progress(@PathVariable String batchNo) {
        return service.getProgress(batchNo);
    }
}
