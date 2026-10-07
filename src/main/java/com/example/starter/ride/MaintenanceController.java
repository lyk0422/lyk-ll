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
import com.example.starter.ride.dto.RideDtos.FinishMaintenanceRequest;
import com.example.starter.ride.dto.RideDtos.RemoveLockRequest;
import com.example.starter.ride.dto.RideDtos.StartMaintenanceRequest;

/**
 * 检修挂牌 API：建单（设备维度）→ 启动 → 逐人双人签认撤锁 → 结束；
 * 原开放/复开入口在挂牌未清期间一律拒绝，不替代日检、证书、监督检验与试运行门禁。
 */
@RestController
@RequestMapping("/api/maintenance-orders")
public class MaintenanceController {

    private final RideService service;

    public MaintenanceController(RideService service) {
        this.service = service;
    }

    /** 启动检修：整机停运并为每人建立唯一未撤锁；携带 expectedVersion。 */
    @PostMapping("/{orderId}/start")
    public ResponseEntity<String> start(@PathVariable long orderId,
            @Valid @RequestBody StartMaintenanceRequest request) {
        return render(service.startMaintenance(orderId, request));
    }

    /** 撤锁：本人与一名不同监护员共同确认撤除，单人或重复身份不生效。 */
    @PostMapping("/{orderId}/locks/remove")
    public ResponseEntity<String> removeLock(@PathVariable long orderId,
            @Valid @RequestBody RemoveLockRequest request) {
        return render(service.removeLock(orderId, request));
    }

    /** 结束检修：全部锁撤除后方可结束，仅清除挂牌阻断，不自动开放。 */
    @PostMapping("/{orderId}/finish")
    public ResponseEntity<String> finish(@PathVariable long orderId,
            @Valid @RequestBody FinishMaintenanceRequest request) {
        return render(service.finishMaintenance(orderId, request));
    }

    /** 查询检修单：显示冻结清单及每个未撤锁人。 */
    @GetMapping("/{orderId}")
    public Map<String, Object> get(@PathVariable long orderId) {
        return service.getMaintenanceOrder(orderId);
    }

    private ResponseEntity<String> render(ApiResult result) {
        return ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }
}
