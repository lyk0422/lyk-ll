package com.example.starter.museum.api;

import com.example.starter.museum.domain.HandoverType;
import com.example.starter.museum.service.EnvironmentService;
import com.example.starter.museum.service.IdempotentService;
import com.example.starter.museum.service.LoanService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 借展申请与流转接口。申请、点交、续展、结案均以 requestId 幂等。
 */
@RestController
@RequestMapping("/api/loans")
public class LoanController {

    private final LoanService loanService;
    private final IdempotentService idempotent;

    public LoanController(LoanService loanService, IdempotentService idempotent) {
        this.loanService = loanService;
        this.idempotent = idempotent;
    }

    @PostMapping("/apply")
    public ResponseEntity<ContractResponse> apply(@Valid @RequestBody LoanApplyRequest req) {
        ContractResponse resp = idempotent.execute(req.requestId(), req, ContractResponse.class,
                () -> loanService.apply(req.artifactNo(), req.borrower(),
                        req.startDate(), req.endDate(), req.showcaseNo()));
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @PostMapping("/handover/confirm")
    public HandoverResponse confirmHandover(@Valid @RequestBody HandoverConfirmRequest req) {
        HandoverType type = HandoverType.valueOf(req.type().name());
        return idempotent.execute(req.requestId(), req, HandoverResponse.class,
                () -> loanService.confirmHandover(req.contractNo(), type, req.party()));
    }

    @PostMapping("/renew")
    public ContractResponse renew(@Valid @RequestBody RenewRequest req) {
        return idempotent.execute(req.requestId(), req, ContractResponse.class,
                () -> loanService.renew(req.contractNo(), req.newEndDate(), req.approved()));
    }

    @PostMapping("/close")
    public ContractResponse close(@Valid @RequestBody CloseRequest req) {
        return idempotent.execute(req.requestId(), req, ContractResponse.class,
                () -> loanService.close(req.contractNo()));
    }
}
