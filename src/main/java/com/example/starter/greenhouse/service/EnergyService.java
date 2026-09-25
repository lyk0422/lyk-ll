package com.example.starter.greenhouse.service;

import com.example.starter.greenhouse.domain.CommandType;
import com.example.starter.greenhouse.domain.IdempotentBizType;
import com.example.starter.greenhouse.dto.BudgetAllocateRequest;
import com.example.starter.greenhouse.dto.BudgetResponse;
import com.example.starter.greenhouse.dto.CommandIssueRequest;
import com.example.starter.greenhouse.dto.CommandResponse;
import com.example.starter.greenhouse.po.EnergyBudget;
import com.example.starter.greenhouse.po.EnergyCommand;
import com.example.starter.greenhouse.po.Zone;
import com.example.starter.greenhouse.repo.EnergyBudgetRepository;
import com.example.starter.greenhouse.repo.EnergyCommandRepository;
import com.example.starter.greenhouse.repo.ZoneRepository;
import com.example.starter.greenhouse.support.Fingerprints;
import com.example.starter.greenhouse.web.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 每日（UTC 日界）能耗预算分配与指令下发：
 * 耗能指令下发前在同事务内校验并扣减剩余预算，恰用尽后只允许零能耗通风指令；
 * requestId 幂等，并发扣减由行锁 + 条件更新串行化，不可能超预算。
 */
@Service
public class EnergyService {

    private final EnergyBudgetRepository budgetRepository;
    private final EnergyCommandRepository commandRepository;
    private final ZoneRepository zoneRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public EnergyService(EnergyBudgetRepository budgetRepository,
                         EnergyCommandRepository commandRepository,
                         ZoneRepository zoneRepository,
                         IdempotencyService idempotencyService,
                         Clock clock) {
        this.budgetRepository = budgetRepository;
        this.commandRepository = commandRepository;
        this.zoneRepository = zoneRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    @Transactional
    public BudgetResponse allocate(BudgetAllocateRequest req) {
        Zone zone = zoneRepository.findByCode(req.zoneCode())
                .orElseThrow(() -> ApiException.notFound("分区不存在: " + req.zoneCode()));
        LocalDate day = parseDay(req.budgetDay());
        if (req.budgetKwh().signum() < 0) {
            throw ApiException.badRequest("预算不得为负");
        }
        EnergyBudget budget = new EnergyBudget();
        budget.setZoneId(zone.getId());
        budget.setBudgetDay(day);
        budget.setBudgetKwh(req.budgetKwh());
        budget.setRemainingKwh(req.budgetKwh());
        budget.setCreatedAt(Instant.now(clock));
        try {
            budgetRepository.insert(budget);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("该分区当日预算已分配: " + req.zoneCode() + " " + day);
        }
        return new BudgetResponse(budget.getId(), zone.getZoneCode(), day,
                budget.getBudgetKwh(), budget.getRemainingKwh());
    }

    public CommandResponse issue(CommandIssueRequest req) {
        Zone zone = zoneRepository.findByCode(req.zoneCode())
                .orElseThrow(() -> ApiException.notFound("分区不存在: " + req.zoneCode()));
        CommandType type = parseCommandType(req.commandType());
        Instant issuedAt = parseInstant(req.issuedAt());
        if (type == CommandType.VENTILATION && req.energyKwh().signum() != 0) {
            throw ApiException.badRequest("通风类指令能耗必须为 0");
        }
        if (type != CommandType.VENTILATION && req.energyKwh().signum() <= 0) {
            throw ApiException.badRequest("耗能指令能耗必须大于 0");
        }
        LocalDate day = LocalDate.ofInstant(issuedAt, ZoneOffset.UTC);
        String fingerprint = Fingerprints.sha256(req.requestId(), "COMMAND",
                zone.getId(), type.name(), req.energyKwh().stripTrailingZeros().toPlainString(),
                issuedAt.toString());

        IdempotencyService.ActionResult<CommandResponse> result = idempotencyService.execute(
                IdempotentBizType.COMMAND_ISSUE, req.requestId(), fingerprint,
                () -> new IdempotencyService.ActionResult<>(200,
                        doIssue(zone, type, req.energyKwh(), issuedAt, day, req.requestId())),
                CommandResponse.class);
        return result.body();
    }

    @Transactional
    public CommandResponse doIssue(Zone zone, CommandType type, BigDecimal energyKwh,
                                   Instant issuedAt, LocalDate day, String requestId) {
        BigDecimal remainingAfter;
        if (type.isEnergyConsuming()) {
            // 行锁串行化并发扣减；预算不存在视为未分配。
            EnergyBudget budget = budgetRepository.findByZoneAndDayForUpdate(zone.getId(), day)
                    .orElseThrow(() -> ApiException.unprocessable("当日能耗预算未分配，无法下发耗能指令: " + day));
            // 条件更新：剩余不足（含恰用尽）时 0 行，指令不写入、预算不变。
            if (!budgetRepository.deductIfSufficient(zone.getId(), day, energyKwh)) {
                throw ApiException.unprocessable("当日剩余能耗预算不足，仅允许通风类零能耗指令，剩余 "
                        + budget.getRemainingKwh() + " kWh，申请 " + energyKwh + " kWh");
            }
            remainingAfter = budget.getRemainingKwh().subtract(energyKwh);
        } else {
            remainingAfter = budgetRepository.findByZoneAndDay(zone.getId(), day)
                    .map(EnergyBudget::getRemainingKwh).orElse(null);
        }

        EnergyCommand command = new EnergyCommand();
        command.setZoneId(zone.getId());
        command.setBudgetDay(day);
        command.setCommandType(type);
        command.setEnergyKwh(energyKwh);
        command.setRequestId(requestId);
        command.setIssuedAt(issuedAt);
        command.setCreatedAt(Instant.now(clock));
        try {
            commandRepository.insert(command);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("指令 requestId 已存在: " + requestId);
        }
        return new CommandResponse(command.getId(), zone.getZoneCode(), day,
                type.name(), energyKwh, remainingAfter, issuedAt);
    }

    static CommandType parseCommandType(String text) {
        try {
            return CommandType.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("未知指令类型: " + text + "，允许 HEATING/LIGHTING/VENTILATION");
        }
    }

    static LocalDate parseDay(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("日期格式需为 yyyy-MM-dd(UTC): " + text);
        }
    }

    static Instant parseInstant(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("时间格式需为 UTC ISO-8601，如 2026-09-25T01:00:00Z: " + text);
        }
    }
}
