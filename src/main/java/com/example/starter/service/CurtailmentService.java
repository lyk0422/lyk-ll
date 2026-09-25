package com.example.starter.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.starter.domain.AllocationRow;
import com.example.starter.domain.CommandRow;
import com.example.starter.domain.TurbineRow;
import com.example.starter.repo.AllocationRepository;
import com.example.starter.repo.CommandRepository;
import com.example.starter.repo.FarmRepository;
import com.example.starter.repo.TurbineRepository;
import com.example.starter.util.Fingerprints;
import com.example.starter.web.ApiException;
import com.example.starter.web.dto.CommandAccepted;
import com.example.starter.web.dto.CommandDetail;
import com.example.starter.web.dto.CommandRequest;
import com.example.starter.web.dto.SealAccepted;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 限电指令服务：接收、分摊、并发唯一受理、新旧原子切换、日结封存。
 */
@Service
public class CurtailmentService {

    private final FarmRepository farmRepository;
    private final TurbineRepository turbineRepository;
    private final CommandRepository commandRepository;
    private final AllocationRepository allocationRepository;
    private final Clock clock;

    public CurtailmentService(FarmRepository farmRepository, TurbineRepository turbineRepository,
                              CommandRepository commandRepository,
                              AllocationRepository allocationRepository, Clock clock) {
        this.farmRepository = farmRepository;
        this.turbineRepository = turbineRepository;
        this.commandRepository = commandRepository;
        this.allocationRepository = allocationRepository;
        this.clock = clock;
    }

    /**
     * 接收限电指令。同一风场同时刻只允许一个执行中指令；新指令生效与旧指令作废在同一事务内
     * 完成；requestId 幂等，同键同参返回首次结果，同键异参返回 409。
     */
    @Transactional
    public CommandAccepted receive(CommandRequest request) {
        LocalDateTime issuedAt = request.issuedAt() != null ? request.issuedAt()
                : LocalDateTime.now(clock);
        String fingerprint = Fingerprints.of(request.requestId(), String.valueOf(request.farmId()),
                String.valueOf(request.targetKw()), issuedAt.toString());

        var existing = commandRepository.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            CommandRow command = existing.get();
            if (!command.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在但业务字段不一致");
            }
            return toAccepted(command, true);
        }

        farmRepository.findById(request.farmId())
                .orElseThrow(() -> ApiException.notFound("风场不存在: " + request.farmId()));
        List<TurbineRow> turbines = turbineRepository.findByFarmId(request.farmId());
        if (turbines.isEmpty()) {
            throw ApiException.unprocessable("风场下没有机组: " + request.farmId());
        }

        // 同事务内先作废旧指令，再生效新指令，不得双指令同时生效
        Long supersededId = commandRepository.findExecutingByFarmId(request.farmId())
                .map(CommandRow::id)
                .orElse(null);
        if (supersededId != null) {
            commandRepository.supersede(supersededId);
        }

        long commandId;
        try {
            commandId = commandRepository.insert(request.farmId(), request.requestId(),
                    fingerprint, request.targetKw(), issuedAt);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("同一风场已存在执行中限电指令，并发接收仅唯一受理");
        }

        AllocationCalculator.Result result = allocate(turbines, request.targetKw());
        allocationRepository.batchInsert(commandId, 1, result.allocations());
        return new CommandAccepted(commandId, supersededId, CommandRow.STATUS_EXECUTING,
                request.targetKw(), result.totalAvailableKw(), result.reductionKw(),
                result.curtailed(), issuedAt, toViews(turbines, result.allocations()), false);
    }

    /**
     * 日结封存（终态）。重复封存返回幂等成功。
     */
    @Transactional
    public SealAccepted seal(long commandId) {
        CommandRow command = commandRepository.findById(commandId)
                .orElseThrow(() -> ApiException.notFound("限电指令不存在: " + commandId));
        if (CommandRow.STATUS_SEALED.equals(command.status())) {
            return new SealAccepted(command.id(), command.status(), command.sealedAt(), true);
        }
        LocalDateTime sealedAt = LocalDateTime.now(clock);
        commandRepository.seal(commandId, sealedAt);
        return new SealAccepted(commandId, CommandRow.STATUS_SEALED, sealedAt, false);
    }

    /**
     * 指令详情：含全部分摊轮次，可复算。
     */
    @Transactional(readOnly = true)
    public CommandDetail detail(long commandId) {
        CommandRow command = commandRepository.findById(commandId)
                .orElseThrow(() -> ApiException.notFound("限电指令不存在: " + commandId));
        Map<Long, TurbineRow> turbines = turbineRepository.findByFarmId(command.farmId()).stream()
                .collect(Collectors.toMap(TurbineRow::id, Function.identity()));
        Map<Integer, List<AllocationRow>> byRound = allocationRepository.findByCommandId(commandId)
                .stream()
                .collect(Collectors.groupingBy(AllocationRow::round));
        List<CommandDetail.RoundAllocations> rounds = byRound.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new CommandDetail.RoundAllocations(e.getKey(),
                        e.getValue().stream()
                                .map(a -> toView(turbines.get(a.turbineId()), a))
                                .toList()))
                .toList();
        return new CommandDetail(command.id(), command.farmId(), command.requestId(),
                command.targetKw(), command.status(), command.issuedAt(), command.sealedAt(),
                rounds);
    }

    /**
     * 计算分摊；目标不可行时转为 422。
     */
    AllocationCalculator.Result allocate(List<TurbineRow> turbines, int targetKw) {
        List<AllocationCalculator.Unit> units = turbines.stream()
                .map(t -> new AllocationCalculator.Unit(t.id(), t.availableKw(), t.minKw()))
                .toList();
        try {
            return AllocationCalculator.allocate(units, targetKw);
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable(e.getMessage());
        }
    }

    private CommandAccepted toAccepted(CommandRow command, boolean replayed) {
        List<TurbineRow> turbines = turbineRepository.findByFarmId(command.farmId());
        Map<Long, TurbineRow> byId = turbines.stream()
                .collect(Collectors.toMap(TurbineRow::id, Function.identity()));
        // 重放返回首次受理结果：首轮分摊
        List<AllocationRow> firstRound = allocationRepository.findByCommandIdAndRound(command.id(), 1);
        int totalAvailable = firstRound.stream().mapToInt(AllocationRow::availableKw).sum();
        int totalTarget = firstRound.stream().mapToInt(AllocationRow::targetKw).sum();
        boolean curtailed = firstRound.stream()
                .anyMatch(a -> a.targetKw() < a.availableKw());
        List<CommandAccepted.AllocationView> views = firstRound.stream()
                .map(a -> toView(byId.get(a.turbineId()), a))
                .toList();
        return new CommandAccepted(command.id(), null, command.status(), command.targetKw(),
                totalAvailable, totalAvailable - totalTarget, curtailed, command.issuedAt(),
                views, replayed);
    }

    private List<CommandAccepted.AllocationView> toViews(List<TurbineRow> turbines,
                                                         List<AllocationCalculator.Allocation> allocations) {
        Map<Long, TurbineRow> byId = turbines.stream()
                .collect(Collectors.toMap(TurbineRow::id, Function.identity()));
        return allocations.stream()
                .map(a -> new CommandAccepted.AllocationView(a.turbineId(),
                        byId.get(a.turbineId()).code(), a.availableKw(), a.minKw(),
                        a.headroomKw(), a.targetKw()))
                .toList();
    }

    private CommandAccepted.AllocationView toView(TurbineRow turbine, AllocationRow row) {
        return new CommandAccepted.AllocationView(row.turbineId(),
                turbine == null ? null : turbine.code(), row.availableKw(), row.minKw(),
                row.headroomKw(), row.targetKw());
    }
}
