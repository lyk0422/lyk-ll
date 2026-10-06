package com.example.starter.web;

import com.example.starter.service.RepairService;
import com.example.starter.web.dto.ApiDtos.AddRepairRecordRequest;
import com.example.starter.web.dto.ApiDtos.CreateRepairOrderRequest;
import com.example.starter.web.dto.ApiDtos.ReinspectRequest;
import com.example.starter.web.dto.ApiDtos.ReinspectResponse;
import com.example.starter.web.dto.ApiDtos.RepairOrderResponse;
import com.example.starter.web.dto.ApiDtos.RepairRecordResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 维修单、维修记录与复检入口。
 */
@RestController
@RequestMapping("/api/repair-orders")
public class RepairController {

    private final RepairService repairService;

    public RepairController(RepairService repairService) {
        this.repairService = repairService;
    }

    @PostMapping
    public RepairOrderResponse create(@Valid @RequestBody CreateRepairOrderRequest req) {
        return repairService.createOrder(req);
    }

    @PostMapping("/{orderId}/repairs")
    public RepairRecordResponse addRepairRecord(@PathVariable long orderId,
                                                @Valid @RequestBody AddRepairRecordRequest req) {
        return repairService.addRepairRecord(orderId, req);
    }

    @PostMapping("/{orderId}/reinspections")
    public ReinspectResponse reinspect(@PathVariable long orderId,
                                       @Valid @RequestBody ReinspectRequest req) {
        return repairService.reinspect(orderId, req);
    }
}
