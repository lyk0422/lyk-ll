package com.example.starter.web;

import com.example.starter.dto.CustomsBindRequest;
import com.example.starter.dto.CustomsRegisterRequest;
import com.example.starter.dto.CustomsReviseRequest;
import com.example.starter.dto.CustomsRevokeRequest;
import com.example.starter.dto.CustomsWithdrawRequest;
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
 * 进口通关单登记、子批绑定与数量核销 REST 入口（合成数据）。
 */
@RestController
@RequestMapping("/api/customs/declarations")
public class CustomsController {

    private final CustomsService customsService;

    public CustomsController(CustomsService customsService) {
        this.customsService = customsService;
    }

    /** 登记合成通关单（requestId 幂等，版本从 1 开始）。 */
    @PostMapping
    public ResponseEntity<Object> register(@Valid @RequestBody CustomsRegisterRequest request) {
        return toEntity(customsService.register(request));
    }

    /** 绑定包装子批：预占申报支数，企业品种须一致，累计不超总数。 */
    @PostMapping("/{declarationNo}/bindings")
    public ResponseEntity<Object> bind(@PathVariable String declarationNo,
                                       @Valid @RequestBody CustomsBindRequest request) {
        return toEntity(customsService.bind(declarationNo, request));
    }

    /** 签发前撤回子批：释放预占且只释放一次。 */
    @PostMapping("/{declarationNo}/bindings/{batchNo}/withdrawal")
    public ResponseEntity<Object> withdraw(@PathVariable String declarationNo,
                                           @PathVariable String batchNo,
                                           @Valid @RequestBody CustomsWithdrawRequest request) {
        return toEntity(customsService.withdraw(declarationNo, batchNo, request));
    }

    /** 撤销通关单：释放全部未签发预占并阻断签发，已签发证据保留并记风险事件。 */
    @PostMapping("/{declarationNo}/revocation")
    public ResponseEntity<Object> revoke(@PathVariable String declarationNo,
                                         @Valid @RequestBody CustomsRevokeRequest request) {
        return toEntity(customsService.revoke(declarationNo, request));
    }

    /** 修订通关单：仅允许调增总支数以增加未占余量。 */
    @PostMapping("/{declarationNo}/revision")
    public ResponseEntity<Object> revise(@PathVariable String declarationNo,
                                         @Valid @RequestBody CustomsReviseRequest request) {
        return toEntity(customsService.revise(declarationNo, request));
    }

    /** 查询通关单余量台账（总数、预占、已核销、未占余量及子批绑定明细）。 */
    @GetMapping("/{declarationNo}")
    public ResponseEntity<Object> ledger(@PathVariable String declarationNo) {
        return ResponseEntity.ok(customsService.getLedger(declarationNo));
    }

    private static ResponseEntity<Object> toEntity(ServiceResult result) {
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }
}
