package com.example.starter.carbon;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.carbon.dto.ComplianceView;
import com.example.starter.carbon.dto.CreateEnterpriseRequest;
import com.example.starter.carbon.dto.IssueBatchRequest;
import com.example.starter.carbon.dto.SetEmissionsRequest;
import com.example.starter.carbon.model.AccountRow;

/**
 * 履约基础数据服务：企业、配额/抵消信用批次签发、排放量核定与履约状态查询。
 */
@Service
public class CarbonService {

    private final CarbonRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public CarbonService(CarbonRepository repository,
            TransactionTemplate transactionTemplate, Clock clock) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public long createEnterprise(CreateEnterpriseRequest request) {
        return repository.insertEnterprise(request.name(), clock.instant());
    }

    /**
     * 签发配额批次（冻结在企业账户内，清缴时按规则扣减注销）。
     */
    public long issueAllowance(IssueBatchRequest request) {
        requireEnterprise(request.enterpriseId());
        return repository.insertAllowanceBatch(request.enterpriseId(), request.complianceYear(),
                request.quantity(), request.expiryDate(), clock.instant());
    }

    /**
     * 签发核证抵消信用批次。
     */
    public long issueOffset(IssueBatchRequest request) {
        requireEnterprise(request.enterpriseId());
        return repository.insertOffsetBatch(request.enterpriseId(), request.complianceYear(),
                request.quantity(), request.expiryDate(), clock.instant());
    }

    /**
     * 核定企业年度排放量并设置履约截止时刻（UTC）。
     */
    public void setEmissions(long enterpriseId, int year, SetEmissionsRequest request) {
        requireEnterprise(enterpriseId);
        repository.upsertAccount(enterpriseId, year, request.emissions(), request.deadlineAt());
    }

    /**
     * 查询履约状态；截止时刻已过且未足额清缴的企业在此标记为“未履约”（终态）。
     */
    public ComplianceView getCompliance(long enterpriseId, int year) {
        return transactionTemplate.execute(status -> {
            AccountRow account = repository.findAccountForUpdate(enterpriseId, year)
                    .orElseThrow(() -> ApiException.notFound(
                            "COMPLIANCE_ACCOUNT_NOT_FOUND", "该企业该履约年度尚未核定排放量"));
            if ("PENDING".equals(account.status())
                    && clock.instant().isAfter(account.deadlineAt())) {
                repository.markNonCompliant(enterpriseId, year);
                account = repository.findAccount(enterpriseId, year).orElseThrow();
            }
            return toView(account);
        });
    }

    private void requireEnterprise(long enterpriseId) {
        if (!repository.enterpriseExists(enterpriseId)) {
            throw ApiException.notFound("ENTERPRISE_NOT_FOUND", "企业不存在: " + enterpriseId);
        }
    }

    private ComplianceView toView(AccountRow account) {
        return new ComplianceView(
                account.enterpriseId(), account.complianceYear(), account.emissions(),
                account.surrenderedAllowance(), account.surrenderedOffset(),
                account.status(), account.deadlineAt());
    }
}
