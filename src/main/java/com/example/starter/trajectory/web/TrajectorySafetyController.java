package com.example.starter.trajectory.web;

import com.example.starter.trajectory.dto.CreateRuleVersionRequest;
import com.example.starter.trajectory.dto.ValidationRequest;
import com.example.starter.trajectory.dto.ValidationResult;
import com.example.starter.trajectory.service.TrajectorySafetyService;
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
 * 轨迹安全校验 HTTP 入口。
 */
@RestController
@RequestMapping("/api/trajectory-safety")
public class TrajectorySafetyController {

    private final TrajectorySafetyService service;

    public TrajectorySafetyController(TrajectorySafetyService service) {
        this.service = service;
    }

    /** 发布不可变规则版本，版本重复返回 409。 */
    @PostMapping("/rule-versions")
    @ResponseStatus(HttpStatus.CREATED)
    public void publishRuleVersion(@Valid @RequestBody CreateRuleVersionRequest request) {
        service.publishRuleVersion(request);
    }

    /** 提交轨迹安全校验；同键同参返回原结果，同键异参返回 409。 */
    @PostMapping("/validations")
    public ValidationResult validate(@Valid @RequestBody ValidationRequest request) {
        return service.validate(request);
    }

    /** 按幂等键读取已有校验结果，不存在返回 404。 */
    @GetMapping("/validations/{requestId}")
    public ValidationResult getValidation(@PathVariable String requestId) {
        return service.getByRequestId(requestId);
    }
}
