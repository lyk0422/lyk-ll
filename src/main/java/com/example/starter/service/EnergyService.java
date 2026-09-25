package com.example.starter.service;

import com.example.starter.api.dto.CommandDtos;
import com.example.starter.domain.CommandType;
import com.example.starter.repo.BudgetRepository;
import com.example.starter.repo.CommandRepository;
import com.example.starter.support.IdempotencySupport;
import com.example.starter.web.BusinessRuleException;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 能耗预算与环控指令服务：
 * <ul>
 *     <li>每日（UTC 日界）预算按分区分配；</li>
 *     <li>加热/补光下发前行锁校验剩余预算，恰用尽（剩余为 0）允许把最后一份预算用掉，
 *     之后只允许通风类零能耗指令；</li>
 *     <li>指令落账与预算扣减在同一事务完成，并发下由行锁 + requestId 唯一约束保证唯一结果。</li>
 * </ul>
 */
@Service
public class EnergyService {

    private final BudgetRepository budgetRepository;
    private final CommandRepository commandRepository;
    private final SetupService setupService;
    private final IdempotencySupport idempotencySupport;
    private final Clock clock;

    public EnergyService(BudgetRepository budgetRepository, CommandRepository commandRepository,
                         SetupService setupService, IdempotencySupport idempotencySupport, Clock clock) {
        this.budgetRepository = budgetRepository;
        this.commandRepository = commandRepository;
        this.setupService = setupService;
        this.idempotencySupport = idempotencySupport;
        this.clock = clock;
    }

    @Transactional
    public CommandDtos.BudgetResponse allocateBudget(CommandDtos.BudgetRequest request) {
        setupService.requireZone(request.zoneCode());
        if (request.budgetKwh().signum() < 0) {
            throw new BusinessRuleException("能耗预算不得为负");
        }
        if (budgetRepository.find(request.zoneCode(), request.budgetDate()).isPresent()) {
            throw new ConflictException("该分区当日预算已分配："
                    + request.zoneCode() + " " + request.budgetDate());
        }
        budgetRepository.insert(request.zoneCode(), request.budgetDate(),
                request.budgetKwh().setScale(3));
        return toBudgetResponse(budgetRepository.find(request.zoneCode(), request.budgetDate()).orElseThrow());
    }

    @Transactional(readOnly = true)
    public CommandDtos.BudgetResponse getBudget(String zoneCode, LocalDate date) {
        return budgetRepository.find(zoneCode, date)
                .map(this::toBudgetResponse)
                .orElseThrow(() -> new NotFoundException("当日预算不存在：" + zoneCode + " " + date));
    }

    @Transactional
    public CommandDtos.CommandResponse issueCommand(CommandDtos.CommandRequest request) {
        setupService.requireZone(request.zoneCode());
        CommandType commandType;
        try {
            commandType = CommandType.valueOf(request.commandType());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("未知指令类型：" + request.commandType());
        }
        BigDecimal energy = commandType.normalizeEnergy(request.energyKwh()).setScale(3);
        Instant issuedAt = request.issuedAt() != null ? request.issuedAt() : clock.instant();
        LocalDate budgetDate = issuedAt.atZone(ZoneOffset.UTC).toLocalDate();

        record CommandPayload(String zoneCode, String commandType, BigDecimal energyKwh,
                              LocalDate budgetDate, Instant issuedAt) {
        }
        return idempotencySupport.execute(request.requestId(), "CONTROL_COMMAND",
                new CommandPayload(request.zoneCode(), commandType.name(), energy, budgetDate, issuedAt),
                CommandDtos.CommandResponse.class, 200,
                () -> doIssue(request.requestId(), request.zoneCode(), commandType, energy,
                        budgetDate, issuedAt));
    }

    private CommandDtos.CommandResponse doIssue(String requestId, String zoneCode, CommandType commandType,
                                                BigDecimal energy, LocalDate budgetDate, Instant issuedAt) {
        BigDecimal remaining;
        if (commandType.isEnergyConsuming()) {
            BudgetRepository.Budget budget = budgetRepository.findForUpdate(zoneCode, budgetDate);
            if (budget.remaining().compareTo(energy) < 0) {
                throw new BusinessRuleException("当日剩余预算 " + budget.remaining()
                        + "kWh 不足本次能耗 " + energy + "kWh");
            }
            // 指令计量与预算扣减同一事务提交，杜绝超预算执行。
            long commandId = commandRepository.insert(zoneCode, commandType.name(), energy,
                    budgetDate, requestId, issuedAt);
            budgetRepository.addConsumed(budget.id(), energy);
            remaining = budget.remaining().subtract(energy);
            return new CommandDtos.CommandResponse(commandId, requestId, zoneCode, commandType.name(),
                    energy, budgetDate, issuedAt, remaining.setScale(3));
        }
        // 零能耗通风：预算恰尽甚至未分配预算时都允许。
        long commandId = commandRepository.insert(zoneCode, commandType.name(), energy,
                budgetDate, requestId, issuedAt);
        remaining = budgetRepository.find(zoneCode, budgetDate)
                .map(BudgetRepository.Budget::remaining)
                .orElse(BigDecimal.ZERO.setScale(3));
        return new CommandDtos.CommandResponse(commandId, requestId, zoneCode, commandType.name(),
                energy, budgetDate, issuedAt, remaining.setScale(3));
    }

    private CommandDtos.BudgetResponse toBudgetResponse(BudgetRepository.Budget b) {
        return new CommandDtos.BudgetResponse(b.zoneCode(), b.budgetDate(),
                b.budgetKwh(), b.consumedKwh(), b.remaining());
    }
}
