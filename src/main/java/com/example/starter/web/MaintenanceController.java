package com.example.starter.web;

import com.example.starter.service.MaintenanceService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 维护节拍接口：驱动回执超时升级与传感器失联判定。
 */
@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {

    /**
     * 节拍请求；now 缺省取服务器当前时间。
     */
    public record TickRequest(LocalDateTime now) {
    }

    private final MaintenanceService maintenanceService;
    private final JsonSupport jsonSupport;
    private final Clock clock;

    public MaintenanceController(MaintenanceService maintenanceService, JsonSupport jsonSupport,
                                 Clock clock) {
        this.maintenanceService = maintenanceService;
        this.jsonSupport = jsonSupport;
        this.clock = clock;
    }

    @PostMapping("/tick")
    public ResponseEntity<String> tick(@RequestBody(required = false) TickRequest request) {
        LocalDateTime now = request != null && request.now() != null ? request.now()
                : LocalDateTime.now(clock);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(jsonSupport.toJson(maintenanceService.tick(now)));
    }
}
