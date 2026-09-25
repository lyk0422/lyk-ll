package com.example.starter.margin.api;

import com.example.starter.margin.service.AccountSetupService;
import com.example.starter.margin.service.RiskService;
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
 * 融资融券追保与强平阶梯服务 HTTP 入口。
 */
@RestController
@RequestMapping("/api/margin")
public class MarginController {

    private final RiskService riskService;
    private final AccountSetupService setupService;

    public MarginController(RiskService riskService, AccountSetupService setupService) {
        this.riskService = riskService;
        this.setupService = setupService;
    }

    /** 装配合成账户（账户+逐笔负债+首条已确认快照）。 */
    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountView setup(@Valid @RequestBody SetupAccountRequest request) {
        return setupService.setup(request);
    }

    /** 查询账户全景与最新比例。 */
    @GetMapping("/accounts/{accountNo}")
    public AccountView view(@PathVariable String accountNo) {
        return setupService.view(accountNo);
    }

    /** 追加一条已确认快照（合成行情/账务更新）。 */
    @PostMapping("/accounts/{accountNo}/snapshots")
    public AccountView snapshot(@PathVariable String accountNo,
                                @Valid @RequestBody SnapshotRequest request) {
        return setupService.addSnapshot(accountNo, request);
    }

    /** 以最新已确认快照评估风控：追保或直接强平。 */
    @PostMapping("/accounts/{accountNo}/assess")
    public RiskAssessmentResponse assess(@PathVariable String accountNo) {
        return riskService.assess(accountNo);
    }

    /** 逾期巡检：过截止仍未补足则转强平（OVERDUE）。 */
    @PostMapping("/accounts/{accountNo}/overdue")
    public RiskAssessmentResponse overdue(@PathVariable String accountNo) {
        return riskService.processOverdue(accountNo);
    }

    /** 追加保证金入账（requestId 幂等；同键异参 409）。 */
    @PostMapping("/deposits")
    public DepositResponse deposit(@Valid @RequestBody DepositRequest request) {
        return riskService.deposit(request);
    }

    /** 查询强平作业与已生成的终态指令（只读，不触发执行）。 */
    @GetMapping("/liquidations/{jobId}")
    public LiquidationResponse getJob(@PathVariable long jobId) {
        return riskService.getJob(jobId);
    }

    /** 执行强平作业（PENDING→EXECUTED 终态，阶梯即止）。 */
    @PostMapping("/liquidations/{jobId}/execute")
    public LiquidationResponse execute(@PathVariable long jobId) {
        return riskService.executeJob(jobId);
    }
}
