package com.example.starter.command;

import com.example.starter.api.dto.CommandResponse;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 降掺指令存储操作：触发创建、超时升级、执行确认、查询。
 */
public interface ReductionCommandRepository {

    /**
     * 超标小时触发并下发指令。
     *
     * @param planId          方案 ID
     * @param hourStart       超标小时起点
     * @param targetHeatRatio 触发降掺目标替代率（按步长，不低于保底）
     * @param issuedAt        下发时刻
     * @return 创建的指令（含下发后 30 分钟确认时限）
     */
    CommandResponse create(Long planId, LocalDateTime hourStart,
                           java.math.BigDecimal targetHeatRatio, LocalDateTime issuedAt);

    /**
     * 将所有已下发且恰满/超过确认时限的指令升级，并把方案替代率强制降为保底比例。
     *
     * @param now 当前时刻
     * @return 本次升级的指令数
     */
    int escalateDue(LocalDateTime now);

    /**
     * 确认前先升级所有已到时限指令；返回指定指令最新视图。
     */
    CommandResponse find(Long planId, Long commandId);

    List<CommandResponse> findByPlan(Long planId);

    /**
     * 时限内确认指令；已升级指令不可再确认。
     */
    CommandResponse confirm(Long planId, Long commandId, LocalDateTime confirmedAt);
}
