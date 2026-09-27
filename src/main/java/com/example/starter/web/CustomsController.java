package com.example.starter.web;

import com.example.starter.dto.BindSubBatchRequest;
import com.example.starter.dto.RegisterDeclarationRequest;
import com.example.starter.dto.ReviseDeclarationRequest;
import com.example.starter.dto.RevokeDeclarationRequest;
import com.example.starter.dto.ServiceResult;
import com.example.starter.service.CustomsService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 进口通关单与签发数量核销 REST 入口（合成数据）。
 */
@RestController
@RequestMapping("/api/customs-declarations")
public class CustomsController {

    private final CustomsService customsService;

    public CustomsController(CustomsService customsService) {
        this.customsService = customsService;
    }

    /** 通关登记（requestId 幂等，失败不占键；版本从 1 开始）。 */
    @PostMapping
    public ResponseEntity<Object> register(@Valid @RequestBody RegisterDeclarationRequest request) {
        return toEntity(customsService.register(request));
    }

    /** 子批绑定：预占申报支数，累计不超总数，恰占满合法；任一资料不符整笔拒绝。 */
    @PostMapping("/{declarationNo}/bindings")
    public ResponseEntity<Object> bind(@PathVariable String declarationNo,
                                       @Valid @RequestBody BindSubBatchRequest request) {
        return toEntity(customsService.bind(declarationNo, request));
    }

    /** 通关单撤销：释放全部未签发子批预占并阻断签发，已签发证据保留并记风险事件。 */
    @PostMapping("/{declarationNo}/revocation")
    public ResponseEntity<Object> revoke(@PathVariable String declarationNo,
                                         @Valid @RequestBody RevokeDeclarationRequest request) {
        return toEntity(customsService.revoke(declarationNo, request));
    }

    /** 通关单修订：仅允许调增可核销总数（增加未占余量），不改历史核销与企业品种。 */
    @PostMapping("/{declarationNo}/revision")
    public ResponseEntity<Object> revise(@PathVariable String declarationNo,
                                         @Valid @RequestBody ReviseDeclarationRequest request) {
        return toEntity(customsService.revise(declarationNo, request));
    }

    /** 查询通关单余量台账（总数 = 已预占 + 已核销 + 未占余量，含全部子批绑定）。 */
    @GetMapping("/{declarationNo}")
    public ResponseEntity<Object> get(@PathVariable String declarationNo) {
        return ResponseEntity.ok(customsService.getDeclaration(declarationNo));
    }

    /** 查询通关单事件流水（登记、绑定、核销、释放、撤销、修订、风险事件）。 */
    @GetMapping("/{declarationNo}/events")
    public ResponseEntity<Object> events(@PathVariable String declarationNo) {
        return ResponseEntity.ok(customsService.listEvents(declarationNo));
    }

    private static ResponseEntity<Object> toEntity(ServiceResult result) {
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }
}
