package com.example.starter.arbitration;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.arbitration.dto.ClaimRequest;
import com.example.starter.arbitration.dto.ClaimResponse;
import com.example.starter.arbitration.dto.CommandRequest;
import com.example.starter.arbitration.dto.CommandResponse;
import com.example.starter.arbitration.dto.ReleaseRequest;
import com.example.starter.arbitration.dto.RenewRequest;
import com.example.starter.arbitration.dto.ResourceView;
import com.example.starter.arbitration.dto.SafeStateEventView;

/**
 * 指令仲裁与安全降级 REST 入口。
 */
@RestController
@RequestMapping("/api")
public class ArbitrationController {

    private final ArbitrationService service;

    public ArbitrationController(ArbitrationService service) {
        this.service = service;
    }

    /**
     * 申请资源租约；同 requestId 同参幂等重放，异参重放 409，抢占失败 409，急停中 423。
     */
    @PostMapping("/claims")
    public ClaimResponse claim(@Valid @RequestBody ClaimRequest req) {
        return service.claim(req);
    }

    /**
     * 租约续期（幂等）。
     */
    @PostMapping("/claims/renew")
    public ClaimResponse renew(@Valid @RequestBody RenewRequest req) {
        return service.renew(req);
    }

    /**
     * 释放租约（幂等）。
     */
    @PostMapping("/claims/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@Valid @RequestBody ReleaseRequest req) {
        service.release(req);
    }

    /**
     * 提交运动指令；校验失败返回 400/409/423 及可区分错误码。
     */
    @PostMapping("/commands")
    public CommandResponse submit(@Valid @RequestBody CommandRequest req) {
        return service.submit(req);
    }

    /**
     * 紧急停止：撤销全部写权限并清空待执行指令。
     */
    @PostMapping("/estop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void estop() {
        service.estop();
    }

    /**
     * 显式恢复：解除紧急停止。
     */
    @PostMapping("/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resume() {
        service.resume();
    }

    /**
     * 触发到期检测与安全降级；返回本次新执行的降级事件（重复触发返回空列表）。
     */
    @PostMapping("/maintenance/degrade")
    public List<SafeStateEventView> degrade() {
        return service.degradeExpired();
    }

    /**
     * 查询资源当前状态。
     */
    @GetMapping("/resources/{resourceId}")
    public ResourceView resource(@PathVariable String resourceId) {
        return service.resourceView(resourceId);
    }

    /**
     * 查询全部安全降级事件。
     */
    @GetMapping("/safe-events")
    public List<SafeStateEventView> safeEvents() {
        return service.safeEvents();
    }
}
