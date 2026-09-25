package com.example.starter.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import com.example.starter.domain.Models.AccountEntry;
import com.example.starter.domain.Models.Policy;
import com.example.starter.domain.Models.RecoveryAccount;
import com.example.starter.domain.Models.Treaty;
import com.example.starter.error.ApiException;
import com.example.starter.repo.AccountRepository;
import com.example.starter.repo.PolicyRepository;
import com.example.starter.repo.TreatyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 合约、保单与摊回账户查询服务。
 */
@Service
public class TreatyService {

    private final TreatyRepository treatyRepository;
    private final PolicyRepository policyRepository;
    private final AccountRepository accountRepository;
    private final Clock clock;

    public TreatyService(TreatyRepository treatyRepository, PolicyRepository policyRepository,
                         AccountRepository accountRepository, Clock clock) {
        this.treatyRepository = treatyRepository;
        this.policyRepository = policyRepository;
        this.accountRepository = accountRepository;
        this.clock = clock;
    }

    @Transactional
    public Treaty createTreaty(String treatyNo, String name, BigDecimal cessionRate,
                               BigDecimal limitPerPolicy) {
        if (cessionRate == null || cessionRate.signum() <= 0
                || cessionRate.compareTo(BigDecimal.ONE) > 0) {
            throw ApiException.badRequest("INVALID_RATE", "分保比例须在 (0,1] 区间");
        }
        if (limitPerPolicy == null || limitPerPolicy.signum() < 0) {
            throw ApiException.badRequest("INVALID_LIMIT", "责任限额不得为负");
        }
        treatyRepository.findByTreatyNo(treatyNo).ifPresent(t -> {
            throw ApiException.conflict("TREATY_EXISTS", "合约编号已存在: " + treatyNo);
        });
        Treaty treaty = treatyRepository.insert(treatyNo, name, cessionRate, limitPerPolicy,
                OffsetDateTime.now(clock));
        accountRepository.createAccountIfAbsent(treaty.id(), OffsetDateTime.now(clock));
        return treaty;
    }

    @Transactional
    public Policy createPolicy(String treatyNo, String policyNo, BigDecimal cededPremium) {
        Treaty treaty = treatyRepository.findByTreatyNo(treatyNo)
                .orElseThrow(() -> ApiException.notFound("TREATY_NOT_FOUND", "合约不存在: " + treatyNo));
        if (cededPremium == null || cededPremium.signum() < 0) {
            throw ApiException.badRequest("INVALID_PREMIUM", "分出保费不得为负");
        }
        policyRepository.findByPolicyNo(policyNo).ifPresent(p -> {
            throw ApiException.conflict("POLICY_EXISTS", "保单号已存在: " + policyNo);
        });
        return policyRepository.insert(policyNo, treaty.id(), Money.scale(cededPremium),
                OffsetDateTime.now(clock));
    }

    /** 账户视图：余额与全部分录。 */
    public record AccountView(RecoveryAccount account, List<AccountEntry> entries) {
    }

    @Transactional(readOnly = true)
    public AccountView getAccount(String treatyNo) {
        Treaty treaty = treatyRepository.findByTreatyNo(treatyNo)
                .orElseThrow(() -> ApiException.notFound("TREATY_NOT_FOUND", "合约不存在: " + treatyNo));
        RecoveryAccount account = accountRepository.findByTreatyId(treaty.id())
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "摊回账户不存在: " + treatyNo));
        return new AccountView(account, accountRepository.findEntriesByTreatyId(treaty.id()));
    }
}
