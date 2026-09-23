package com.example.starter.trajectory.web;

import com.example.starter.trajectory.domain.RuleConfig;
import com.example.starter.trajectory.domain.ValidationRequest;
import com.example.starter.trajectory.domain.ValidationResult;
import com.example.starter.trajectory.service.RuleConfigService;
import com.example.starter.trajectory.service.TrajectoryValidationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 规则版本管理与轨迹安全校验接口。
 */
@RestController
@RequestMapping("/api/trajectory")
public class TrajectoryController {

    private final RuleConfigService ruleConfigService;
    private final TrajectoryValidationService validationService;
    private final DtoMapper dtoMapper;

    public TrajectoryController(RuleConfigService ruleConfigService,
                                TrajectoryValidationService validationService,
                                DtoMapper dtoMapper) {
        this.ruleConfigService = ruleConfigService;
        this.validationService = validationService;
        this.dtoMapper = dtoMapper;
    }

    /**
     * 创建草稿规则版本。
     */
    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public RuleConfig createRule(@Valid @RequestBody RuleConfigRequest request) {
        return ruleConfigService.createDraft(dtoMapper.toDomain(request));
    }

    /**
     * 更新草稿规则版本；已发布版本不可修改。
     */
    @PutMapping("/rules/{ruleVersion}")
    public RuleConfig updateRule(@PathVariable String ruleVersion,
                                 @Valid @RequestBody RuleConfigRequest request) {
        return ruleConfigService.updateDraft(ruleVersion, dtoMapper.toDomain(request));
    }

    /**
     * 发布规则版本。
     */
    @PostMapping("/rules/{ruleVersion}/publish")
    public RuleConfig publishRule(@PathVariable String ruleVersion) {
        return ruleConfigService.publish(ruleVersion);
    }

    @GetMapping("/rules/{ruleVersion}")
    public RuleConfig getRule(@PathVariable String ruleVersion) {
        return ruleConfigService.get(ruleVersion);
    }

    @GetMapping("/rules")
    public List<RuleConfig> listRules() {
        return ruleConfigService.list();
    }

    /**
     * 执行轨迹安全校验；同键同参返回原结果，同键异参返回 409。
     */
    @PostMapping("/validate")
    public ValidationResult validate(@Valid @RequestBody ValidationRequestDto request) {
        ValidationRequest domainRequest = dtoMapper.toDomain(request);
        return validationService.validate(domainRequest);
    }
}
