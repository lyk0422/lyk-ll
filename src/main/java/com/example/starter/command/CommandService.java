package com.example.starter.command;

import com.example.starter.api.dto.CommandResponse;

/**
 * 降掺指令链：触发→下发→执行确认；恰满 30 分钟未确认自动升级并强制降掺至保底比例。
 */
public interface CommandService {

    CommandResponse confirm(Long planId, Long commandId, String requestId);

    /**
     * 扫描所有已下发未确认指令，对到达确认时限者执行升级（定时任务与懒触发共用）。
     *
     * @return 本次升级的指令数
     */
    int sweepTimeouts();
}
