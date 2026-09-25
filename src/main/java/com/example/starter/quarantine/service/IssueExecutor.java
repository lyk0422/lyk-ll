package com.example.starter.quarantine.service;

import com.example.starter.quarantine.api.ApiDtos.CertificateView;
import com.example.starter.quarantine.api.ApiDtos.IssueCmd;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 出证执行器：出证事务因幂等键/批次占用唯一约束冲突回滚后，在新事务中重放，
 * 由 {@link QuarantineService#issue} 的幂等分支返回先提交结果（同参）或 409（异参）。
 */
@Component
public class IssueExecutor {

    private final QuarantineService service;

    public IssueExecutor(QuarantineService service) {
        this.service = service;
    }

    /**
     * 执行出证；唯一键并发冲突时在新事务中重放一次。
     */
    public CertificateView execute(IssueCmd cmd) {
        try {
            return service.issue(cmd);
        } catch (DuplicateKeyException e) {
            return service.issue(cmd);
        }
    }
}
