package com.example.starter.web;

import java.util.List;

import com.example.starter.service.ElevatorService;
import com.example.starter.service.MaintenanceService;
import com.example.starter.web.dto.ApiDtos.ChainEntryView;
import com.example.starter.web.dto.ApiDtos.ElevatorRecheckRequest;
import com.example.starter.web.dto.ApiDtos.ElevatorRequest;
import com.example.starter.web.dto.ApiDtos.ElevatorView;
import com.example.starter.web.dto.ApiDtos.MaintenanceView;
import com.example.starter.web.dto.ApiDtos.StaffRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 电梯台账与停复梯链接口。
 */
@RestController
@RequestMapping("/api/elevators")
public class ElevatorController {

    private final ElevatorService elevatorService;
    private final MaintenanceService maintenanceService;

    public ElevatorController(ElevatorService elevatorService, MaintenanceService maintenanceService) {
        this.elevatorService = elevatorService;
        this.maintenanceService = maintenanceService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ElevatorView create(@Valid @RequestBody ElevatorRequest req) {
        return elevatorService.createElevator(req);
    }

    @PostMapping("/staff")
    @ResponseStatus(HttpStatus.CREATED)
    public long createStaff(@Valid @RequestBody StaffRequest req) {
        return elevatorService.createStaff(req);
    }

    @GetMapping("/{id}")
    public ElevatorView get(@PathVariable long id) {
        return elevatorService.getElevator(id);
    }

    @GetMapping("/{id}/chain")
    public List<ChainEntryView> chain(@PathVariable long id) {
        return elevatorService.chain(id);
    }

    @GetMapping("/{id}/maintenance")
    public List<MaintenanceView> maintenance(@PathVariable long id) {
        return maintenanceService.ledger(id);
    }

    /** 电梯级复检：合格后与恢复运行同一事务完成（维保锁定/强制停梯复梯入口） */
    @PostMapping("/{id}/recheck")
    public ElevatorView recheck(@PathVariable long id, @RequestBody ElevatorRecheckRequest req) {
        return elevatorService.recheckElevator(id, req);
    }
}
