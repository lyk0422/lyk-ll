package com.example.starter.web;

import com.example.starter.dto.CustomsAmendRequest;
import com.example.starter.dto.CustomsBindRequest;
import com.example.starter.dto.CustomsRegisterRequest;
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
 * 进口包装子批通关单核销 REST 入口：登记、绑定预占、签发前撤回、撤销、修订与余量台账。
 */
@RestController
@RequestMapping("/api/customs")
public class CustomsController {

    private final CustomsService customsService;

    public CustomsController(CustomsService customsService) {
        this.customsService = customsService;
    }

    /** 登记合成通关单（requestId 幂等，版本自 1 起）。 */
    @PostMapping("/declarations")
    public ResponseEntity<Object> register(@Valid @RequestBody CustomsRegisterRequest request) {
        return toEntity(customsService.register(request));
    }

    /** 包装子批绑定通关单：预占申报支数，累计不超总数，任一资料不符整笔拒绝。 */
    @PostMapping("/bindings")
    public ResponseEntity<Object> bind(@Valid @RequestBody CustomsBindRequest request) {
        return toEntity(customsService.bind(request));
    }

    /** 签发前撤回子批：释放预占且仅释放一次，子批流程转撤回终态。 */
    @PostMapping("/withdrawals")
    public ResponseEntity<Object> withdraw(@Valid @RequestBody CustomsWithdrawRequest request) {
        return toEntity(customsService.withdraw(request));
    }

    /** 撤销通关单：全部未签发预占释放并阻断签发，已签发证据保留并记风险事件。 */
    @PostMapping("/revocations")
    public ResponseEntity<Object> revoke(@Valid @RequestBody CustomsRevokeRequest request) {
        return toEntity(customsService.revoke(request));
    }

    /** 修订通关单：仅允许增加未占余量，不改历史核销与企业品种。 */
    @PostMapping("/amendments")
    public ResponseEntity<Object> amend(@Valid @RequestBody CustomsAmendRequest request) {
        return toEntity(customsService.amend(request));
    }

    /** 通关单余量台账：总数、预占、已核销、剩余可预占。 */
    @GetMapping("/declarations/{declarationNo}")
    public ResponseEntity<Object> ledger(@PathVariable String declarationNo) {
        return ResponseEntity.ok(customsService.getDeclaration(declarationNo));
    }

    /** 通关单下全部子批预占绑定台账。 */
    @GetMapping("/declarations/{declarationNo}/reservations")
    public ResponseEntity<Object> reservations(@PathVariable String declarationNo) {
        return ResponseEntity.ok(customsService.listReservations(declarationNo));
    }

    /** 通关单风险事件流水（撤销、释放、阻断等证据）。 */
    @GetMapping("/declarations/{declarationNo}/events")
    public ResponseEntity<Object> events(@PathVariable String declarationNo) {
        return ResponseEntity.ok(customsService.listRiskEvents(declarationNo));
    }

    private static ResponseEntity<Object> toEntity(ServiceResult result) {
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }
}
