package com.example.starter.reinsurance.service;

import com.example.starter.reinsurance.api.CreatePolicyRequest;
import com.example.starter.reinsurance.api.CreateTreatyRequest;
import com.example.starter.reinsurance.api.PolicyResponse;
import com.example.starter.reinsurance.api.TreatyResponse;
import com.example.starter.reinsurance.common.ApiException;
import com.example.starter.reinsurance.common.ErrorCode;
import com.example.starter.reinsurance.common.Money;
import com.example.starter.reinsurance.domain.Policy;
import com.example.starter.reinsurance.domain.Treaty;
import com.example.starter.reinsurance.repository.PolicyRepository;
import com.example.starter.reinsurance.repository.ReinsuranceAccountRepository;
import com.example.starter.reinsurance.repository.TreatyRepository;
import java.math.BigDecimal;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 主数据服务：比例合约与分出保单（合成数据）维护。
 */
@Service
public class ReferenceDataService {

    private final TreatyRepository treatyRepository;
    private final PolicyRepository policyRepository;
    private final ReinsuranceAccountRepository accountRepository;

    public ReferenceDataService(
            TreatyRepository treatyRepository,
            PolicyRepository policyRepository,
            ReinsuranceAccountRepository accountRepository) {
        this.treatyRepository = treatyRepository;
        this.policyRepository = policyRepository;
        this.accountRepository = accountRepository;
    }

    /** 创建比例合约，并开立摊回账户（余额 0）。 */
    @Transactional
    public TreatyResponse createTreaty(CreateTreatyRequest request) {
        BigDecimal rate = Money.validateRate(request.cessionRate());
        BigDecimal limit = request.perPolicyLimit() == null
                ? null
                : Money.requireNonNegative(request.perPolicyLimit(), "perPolicyLimit");
        Treaty treaty = new Treaty();
        treaty.setContractNo(request.contractNo());
        treaty.setCessionRate(rate);
        treaty.setPerPolicyLimit(limit);
        try {
            treatyRepository.insert(treaty);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE, "合约号已存在: " + request.contractNo());
        }
        accountRepository.getOrCreate(treaty.getId());
        return new TreatyResponse(treaty.getContractNo(), treaty.getCessionRate(), treaty.getPerPolicyLimit());
    }

    public TreatyResponse getTreaty(String contractNo) {
        Treaty treaty = treatyRepository.getByContractNo(contractNo);
        return new TreatyResponse(treaty.getContractNo(), treaty.getCessionRate(), treaty.getPerPolicyLimit());
    }

    /** 创建分出保单。 */
    @Transactional
    public PolicyResponse createPolicy(CreatePolicyRequest request) {
        Treaty treaty = treatyRepository.getByContractNo(request.contractNo());
        BigDecimal premium = Money.requireNonNegative(request.premium(), "premium");
        Policy policy = new Policy();
        policy.setPolicyNo(request.policyNo());
        policy.setContractId(treaty.getId());
        policy.setPremium(premium);
        policy.setEffectiveDate(request.effectiveDate());
        try {
            policyRepository.insert(policy);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE, "保单号已存在: " + request.policyNo());
        }
        return new PolicyResponse(
                policy.getPolicyNo(),
                treaty.getContractNo(),
                policy.getPremium(),
                policy.getEffectiveDate(),
                null);
    }
}
