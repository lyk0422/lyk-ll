package com.example.starter.plan;

import com.example.starter.api.dto.DeclarePlanRequest;
import com.example.starter.api.dto.PlanResponse;
import com.example.starter.api.dto.ReviewRequest;

/**
 * 掺烧方案流转：申报→审核→执行（切换先停旧后启新，同一事务）。
 */
public interface PlanService {

    PlanResponse declare(DeclarePlanRequest request);

    PlanResponse review(Long planId, ReviewRequest request);

    /**
     * 下发执行；若同窑口已有执行中方案，在同一事务内先停旧后启新。
     */
    PlanResponse execute(Long planId);

    PlanResponse get(Long planId);
}
