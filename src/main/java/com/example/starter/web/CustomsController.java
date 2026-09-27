package com.example.starter.web;

import com.example.starter.dto.CustomsAmendRequest;
import com.example.starter.dto.CustomsBindRequest;
import com.example.starter.dto.CustomsRegisterRequest;
import com.example.starter.dto.CustomsRevokeRequest;
import com.example.starter.dto.ServiceResult;
import com.example.starter.dto.SubbatchWithdrawRequest;
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
 * 进口通关单核销衔接 REST 入口：通关登记、子批绑定预占、撤销、余量修订、签发前撤回与余量台账。
 * 国内批签发流程仍走 {@link BatchReleaseController}，本控制器不改变其门禁。
 */
@RestController
@RequestMapping("/api/customs")
public class CustomsController {

    private final CustomsService customsService;

    public CustomsController(CustomsService customsService) {
        this.customsService = customsService;
    }

    /** 通关单登记（requestId 幂等；版本从 1 开始）。 */
    @PostMapping("/declarations")
    public ResponseEntity<Object> register(@Valid @RequestBody CustomsRegisterRequest request) {
        return toEntity(customsService.register(request));
    }

    /** 包装子批绑定通关单并预占申报支数（requestId 幂等；携带 expectedVersion）。 */
    @PostMapping("/declarations/{customsNo}/bindings")
    public ResponseEntity<Object> bind(@PathVariable String customsNo,
                                       @Valid @RequestBody CustomsBindRequest request) {
        // 路径单号优先，避免路径与请求体不一致造成歧义。
        CustomsBindRequest resolved = new CustomsBindRequest(request.requestId(), customsNo,
                request.batchNo(), request.declaredQuantity(), request.expectedVersion(), request.at());
        return toEntity(customsService.bind(resolved));
    }

    /** 撤销通关单（requestId 幂等）：释放未签发预占并阻断签发，已签发证据不变。 */
    @PostMapping("/declarations/{customsNo}/revocation")
    public ResponseEntity<Object> revoke(@PathVariable String customsNo,
                                         @Valid @RequestBody CustomsRevokeRequest request) {
        CustomsRevokeRequest resolved = new CustomsRevokeRequest(request.requestId(), customsNo,
                request.expectedVersion(), request.at());
        return toEntity(customsService.revoke(resolved));
    }

    /** 修订通关单（requestId 幂等）：仅允许增加未占余量，不改历史核销企业品种。 */
    @PostMapping("/declarations/{customsNo}/amendments")
    public ResponseEntity<Object> amend(@PathVariable String customsNo,
                                        @Valid @RequestBody CustomsAmendRequest request) {
        CustomsAmendRequest resolved = new CustomsAmendRequest(request.requestId(), customsNo,
                request.newTotalQuantity(), request.expectedVersion(), request.at());
        return toEntity(customsService.amend(resolved));
    }

    /** 签发前撤回包装子批（requestId 幂等）：释放预占且只释放一次。 */
    @PostMapping("/subbatches/{batchNo}/withdrawal")
    public ResponseEntity<Object> withdraw(@PathVariable String batchNo,
                                           @Valid @RequestBody SubbatchWithdrawRequest request) {
        SubbatchWithdrawRequest resolved = new SubbatchWithdrawRequest(request.requestId(), batchNo,
                request.expectedVersion(), request.at());
        return toEntity(customsService.withdraw(resolved));
    }

    /** 查询通关单余量台账及子批绑定明细。 */
    @GetMapping("/declarations/{customsNo}/ledger")
    public ResponseEntity<Object> ledger(@PathVariable String customsNo) {
        return ResponseEntity.ok(customsService.getLedger(customsNo));
    }

    private static ResponseEntity<Object> toEntity(ServiceResult result) {
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }
}
