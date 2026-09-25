package com.example.starter.command;

import com.example.starter.api.dto.CommandResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 降掺指令链服务：确认前先懒升级所有到时指令；定时扫描兜底恰满 30 分钟自动升级。
 *
 * <p>本类不声明事务：升级与确认各自由仓储事务提交。这样恰满时限的确认请求中，
 * 升级（含强制保底）已独立落库，随后确认失败不会回滚升级结果。</p>
 */
@Service
public class JdbcCommandService implements CommandService {

    private final ReductionCommandRepository repository;
    private final Clock clock;

    public JdbcCommandService(ReductionCommandRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public CommandResponse confirm(Long planId, Long commandId, String requestId) {
        LocalDateTime now = LocalDateTime.now(clock);
        // 先升级到时指令（独立事务提交），再判定确认，保证恰满 30 分钟边界准确。
        repository.escalateDue(now);
        return repository.confirm(planId, commandId, now);
    }

    @Override
    public int sweepTimeouts() {
        return repository.escalateDue(LocalDateTime.now(clock));
    }

    /** 每 30 秒扫描一次已下发未确认且到时的指令，自动升级。 */
    @Scheduled(fixedDelayString = "${cement.command-sweep-delay-ms:30000}")
    public void scheduledSweep() {
        sweepTimeouts();
    }
}
