package com.example.starter.ride;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.ride.Idempotency.ApiResult;
import com.example.starter.ride.dto.RideDtos.LockRemovalRequest;
import com.example.starter.ride.dto.RideDtos.MaintenanceCreateRequest;
import com.example.starter.ride.dto.RideDtos.MaintenanceFinishRequest;
import com.example.starter.ride.dto.RideDtos.MaintenanceStartRequest;

/**
 * 检修挂牌流程 API：建档→启动→双人签认撤锁→结束。
 * 不替代原日检、试运行、年度证书与大修监督检验门禁。
 */
@RestController
@RequestMapping("/api/rides/{rideId}/maintenances")
public class MaintenanceController {

    private final MaintenanceService service;

    public MaintenanceController(MaintenanceService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<String> create(@PathVariable long rideId,
            @Valid @RequestBody MaintenanceCreateRequest request) {
        return render(service.create(rideId, request));
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable long rideId) {
        return service.listMaintenances(rideId);
    }

    @GetMapping("/{maintenanceId}")
    public Map<String, Object> get(@PathVariable long rideId,
            @PathVariable long maintenanceId) {
        return service.getMaintenance(rideId, maintenanceId);
    }

    @PostMapping("/{maintenanceId}/start")
    public ResponseEntity<String> start(@PathVariable long rideId,
            @PathVariable long maintenanceId,
            @Valid @RequestBody MaintenanceStartRequest request) {
        return render(service.start(rideId, maintenanceId, request));
    }

    @PostMapping("/{maintenanceId}/lock-removals")
    public ResponseEntity<String> removeLock(@PathVariable long rideId,
            @PathVariable long maintenanceId,
            @Valid @RequestBody LockRemovalRequest request) {
        return render(service.removeLock(rideId, maintenanceId, request));
    }

    @PostMapping("/{maintenanceId}/finish")
    public ResponseEntity<String> finish(@PathVariable long rideId,
            @PathVariable long maintenanceId,
            @Valid @RequestBody MaintenanceFinishRequest request) {
        return render(service.finish(rideId, maintenanceId, request));
    }

    private ResponseEntity<String> render(ApiResult result) {
        return ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }
}
