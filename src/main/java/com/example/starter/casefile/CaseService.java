package com.example.starter.casefile;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.common.ApiException;

/**
 * 案件服务：结案为终态，重复结案幂等成功。
 */
@Service
public class CaseService {

    private final CaseRepository cases;
    private final Clock clock;

    public CaseService(CaseRepository cases, Clock clock) {
        this.cases = cases;
        this.clock = clock;
    }

    /**
     * 结案。已结案时直接返回当前状态（幂等成功），结案后案件不可再变更。
     */
    @Transactional
    public CaseResponse close(long caseId) {
        CaseRecord current = cases.findById(caseId)
                .orElseThrow(() -> ApiException.notFound("案件不存在: " + caseId));
        if ("CLOSED".equals(current.status())) {
            return toResponse(current, true);
        }
        cases.closeIfOpen(caseId, Instant.now(clock));
        return toResponse(cases.findById(caseId).orElseThrow(), false);
    }

    /**
     * 按编号查询案件。
     */
    @Transactional(readOnly = true)
    public CaseResponse get(long caseId) {
        return toResponse(cases.findById(caseId)
                .orElseThrow(() -> ApiException.notFound("案件不存在: " + caseId)), false);
    }

    private static CaseResponse toResponse(CaseRecord c, boolean replay) {
        return new CaseResponse(c.id(), c.penaltyId(), c.plateNo(), c.status(), c.closedAt(), replay);
    }
}
