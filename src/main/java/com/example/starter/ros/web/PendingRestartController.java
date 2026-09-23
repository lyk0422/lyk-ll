package com.example.starter.ros.web;

import com.example.starter.ros.dto.PendingRestartRequest;
import com.example.starter.ros.repo.PendingRestartJdbcRepository;
import com.example.starter.ros.service.PendingRestartService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 待重启配置：静态参数变更在此登记与查询，未重启前不会出现在已应用值中。 */
@RestController
@RequestMapping("/api/ros")
public class PendingRestartController {

    private final PendingRestartJdbcRepository pendingRestartRepository;
    private final PendingRestartService pendingRestartService;

    public PendingRestartController(PendingRestartJdbcRepository pendingRestartRepository,
                                    PendingRestartService pendingRestartService) {
        this.pendingRestartRepository = pendingRestartRepository;
        this.pendingRestartService = pendingRestartService;
    }

    /** 登记静态参数的待重启值；混入动态参数或静态参数走热更新都会被 409 拒绝。 */
    @PostMapping("/pending-restart")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@Valid @RequestBody PendingRestartRequest request) {
        List<String> registered = pendingRestartService.register(request);
        return Map.of("node", request.node(), "registered", registered,
                "status", "PENDING_RESTART");
    }

    @GetMapping("/nodes/{node}/pending-restart")
    public Map<String, String> pending(@PathVariable String node) {
        return pendingRestartRepository.findByNode(node);
    }

    @GetMapping("/pending-restart")
    public List<Map<String, Object>> all() {
        return pendingRestartRepository.findAll();
    }
}
