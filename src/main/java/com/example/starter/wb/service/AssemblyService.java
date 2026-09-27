package com.example.starter.wb.service;

import com.example.starter.wb.dto.AssemblyPlanView;
import com.example.starter.wb.dto.AssemblyRequest;

import java.util.List;

/**
 * 集装器装配业务边界：装配图提交（乐观版本 + requestId 幂等）、查询与关闭放行校验。
 */
public interface AssemblyService {

    /**
     * 提交装配图：匹配舱单与装配图版本，整版校验通过后原子写新图、
     * 更新货运力矩、释放旧图占用并使旧双岗复核失效。
     */
    AssemblyPlanView submitAssembly(long flightId, AssemblyRequest request);

    /** 当前有效装配图；无图抛 404。 */
    AssemblyPlanView getActivePlan(long flightId);

    /** 航班全部装配图版本（含已作废），按版本号升序，历史图只读。 */
    List<AssemblyPlanView> listPlans(long flightId);

    /**
     * 关闭放行前校验：有效装配图必须仍匹配当前舱单版本，且以实物装配与当前油量
     * 交既有重心规则复算合法；未启用装配控制（从无装配图）返回 false 走既有兼容路径。
     *
     * @return 有效装配图 ID（校验通过）；未启用装配控制返回 null
     */
    Long validateForClose(long flightId);

    /** 关闭放行同事务原子释放有效图实物占用并加盖释放时间。 */
    void releaseOnClose(long flightId, Long planId, java.time.LocalDateTime closedAt);
}
