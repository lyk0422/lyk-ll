package com.example.starter.carbon;

import com.example.starter.carbon.ApiDtos.AllowanceGrantRequest;
import com.example.starter.carbon.ApiDtos.BatchView;
import com.example.starter.carbon.ApiDtos.CertificateView;
import com.example.starter.carbon.ApiDtos.ComplianceStatusView;
import com.example.starter.carbon.ApiDtos.EmissionAssessRequest;
import com.example.starter.carbon.ApiDtos.EnterpriseCreateRequest;
import com.example.starter.carbon.ApiDtos.EnterpriseView;
import com.example.starter.carbon.ApiDtos.OffsetCreditRequest;
import com.example.starter.carbon.ApiDtos.SurrenderApplyRequest;
import com.example.starter.carbon.ApiDtos.SurrenderResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 碳市场履约 REST 接口：企业、核定排放、配额/抵消批次、清缴申请、凭证与履约状态。
 */
@RestController
@RequestMapping("/api")
public class CarbonController {

    private final SurrenderService service;

    public CarbonController(SurrenderService service) {
        this.service = service;
    }

    /**
     * 创建企业（合成数据）。
     */
    @PostMapping("/enterprises")
    @ResponseStatus(HttpStatus.CREATED)
    public EnterpriseView createEnterprise(@Valid @RequestBody EnterpriseCreateRequest req) {
        return service.createEnterprise(req);
    }

    /**
     * 核定企业年度排放量。
     */
    @PostMapping("/emissions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assessEmission(@Valid @RequestBody EmissionAssessRequest req) {
        service.assessEmission(req);
    }

    /**
     * 发放配额批次。
     */
    @PostMapping("/allowances")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchView grantAllowance(@Valid @RequestBody AllowanceGrantRequest req) {
        return service.grantAllowance(req);
    }

    /**
     * 签发核证抵消信用批次。
     */
    @PostMapping("/offsets")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchView issueOffset(@Valid @RequestBody OffsetCreditRequest req) {
        return service.issueOffsetCredit(req);
    }

    /**
     * 查询企业配额批次（含剩余量与状态）。
     */
    @GetMapping("/allowances")
    public List<BatchView> listAllowances(@RequestParam String enterpriseCode) {
        return service.listAllowances(enterpriseCode);
    }

    /**
     * 查询企业抵消信用批次（含剩余量与状态）。
     */
    @GetMapping("/offsets")
    public List<BatchView> listOffsets(@RequestParam String enterpriseCode) {
        return service.listOffsets(enterpriseCode);
    }

    /**
     * 提交清缴申请（配额+抵消组合，原子扣减并注销，按 requestId 幂等）。
     */
    @PostMapping("/surrenders")
    public SurrenderResponse surrender(@Valid @RequestBody SurrenderApplyRequest req) {
        return service.surrender(req);
    }

    /**
     * 按凭证号查询清缴凭证（结果稳定，可重复查询）。
     */
    @GetMapping("/certificates/{certificateNo}")
    public CertificateView getCertificate(@PathVariable String certificateNo) {
        return service.getCertificate(certificateNo);
    }

    /**
     * 查询企业年度履约状态。
     */
    @GetMapping("/compliance")
    public ComplianceStatusView getCompliance(@RequestParam String enterpriseCode,
                                              @RequestParam int complianceYear) {
        return service.getComplianceStatus(enterpriseCode, complianceYear);
    }
}
