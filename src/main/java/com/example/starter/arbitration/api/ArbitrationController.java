package com.example.starter.arbitration.api;

import com.example.starter.arbitration.service.EstopService;
import com.example.starter.arbitration.service.LeaseArbitrationService;
import com.example.starter.arbitration.service.CommandService;
import com.example.starter.arbitration.service.ResourceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仲裁服务 REST 入口。
 */
@RestController
@RequestMapping("/api/arbitration")
public class ArbitrationController {

    private final ResourceService resourceService;
    private final LeaseArbitrationService leaseArbitrationService;
    private final CommandService commandService;
    private final EstopService estopService;
    private final SystemStatusService systemStatusService;

    public ArbitrationController(ResourceService resourceService,
                                 LeaseArbitrationService leaseArbitrationService,
                                 CommandService commandService,
                                 EstopService estopService,
                                 SystemStatusService systemStatusService) {
        this.resourceService = resourceService;
        this.leaseArbitrationService = leaseArbitrationService;
        this.commandService = commandService;
        this.estopService = estopService;
        this.systemStatusService = systemStatusService;
    }

    /** 登记关节/执行器资源（边界、限速限加速度、安全动作）。 */
    @PostMapping("/resources")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<String> registerResource(@Valid @RequestBody RegisterResourceRequest request) {
        resourceService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body("RESOURCE_REGISTERED");
    }

    /** 控制器心跳。 */
    @PostMapping("/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> heartbeat(@Valid @RequestBody HeartbeatRequest request) {
        resourceService.heartbeat(request);
        return ResponseEntity.noContent().build();
    }

    /** 申请写权限租约（含抢占）。 */
    @PostMapping("/leases/acquire")
    public LeaseResponse acquire(@Valid @RequestBody AcquireLeaseRequest request) {
        return leaseArbitrationService.acquire(request);
    }

    /** 租约续期，重复续期幂等。 */
    @PostMapping("/leases/renew")
    public LeaseResponse renew(@Valid @RequestBody RenewLeaseRequest request) {
        return leaseArbitrationService.renew(request);
    }

    /** 租约释放，重复释放幂等。 */
    @PostMapping("/leases/release")
    public LeaseResponse release(@Valid @RequestBody ReleaseLeaseRequest request) {
        return leaseArbitrationService.release(request);
    }

    /** 提交运动指令（校验失败 400/409，非法/过期指令不入队）。 */
    @PostMapping("/commands")
    public CommandResponse submitCommand(@Valid @RequestBody CommandRequest request) {
        return commandService.submit(request);
    }

    /** 触发紧急停止（重复触发幂等）。 */
    @PostMapping("/estop/trigger")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> triggerEstop() {
        estopService.trigger();
        return ResponseEntity.noContent().build();
    }

    /** 显式恢复（未急停时 409）。 */
    @PostMapping("/estop/recover")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> recoverEstop() {
        estopService.recover();
        return ResponseEntity.noContent().build();
    }

    /** 系统状态快照（有效租约、待执行指令、安全事件）。 */
    @GetMapping("/status")
    public SystemStatusResponse status() {
        return systemStatusService.snapshot();
    }
}
