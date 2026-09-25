package com.example.starter.berth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 靠泊窗口核心业务：申请（幂等）、审批（冲突校验/引航员分配）、状态机流转。
 */
@Service
public class BerthWindowService {

    private final BerthWindowRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public BerthWindowService(BerthWindowRepository repository,
                              TransactionTemplate transactionTemplate,
                              Clock clock) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * 提交靠泊窗口申请。
     * <p>校验顺序：时间区间 → 泊位存在与限深（超限直接拒绝且不产生记录）→ 允许时段 → 改期来源。
     * 以 requestId 幂等：同键同参返回首次结果，同键异参返回 409；并发同键由唯一约束保证仅一条记录。</p>
     */
    public WindowResponse apply(ApplyWindowRequest req) {
        if (!req.endUtc().isAfter(req.startUtc())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TIME_RANGE",
                    "结束时刻必须晚于起始时刻");
        }
        BigDecimal maxDraft = repository.findBerthMaxDraft(req.berthId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BERTH_NOT_FOUND",
                        "泊位不存在: " + req.berthId()));
        if (req.draftMeters().compareTo(maxDraft) > 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DRAFT_EXCEEDS_LIMIT",
                    "吃水 " + req.draftMeters() + " 米超过泊位限深 " + maxDraft + " 米，申请被拒绝");
        }
        if (!repository.isWithinAllowedWindow(req.startUtc(), req.endUtc())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OUTSIDE_ALLOWED_WINDOW",
                    "申请起止未完整落在任何一段每日允许靠泊时段内");
        }
        if (req.replacesRequestId() != null && repository.findByRequestId(req.replacesRequestId()).isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REPLACES_NOT_FOUND",
                    "改期来源窗口不存在: " + req.replacesRequestId());
        }

        String fingerprint = Fingerprints.of(req);
        var existing = repository.findByRequestId(req.requestId());
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }
        try {
            BerthWindow saved = transactionTemplate.execute(
                    status -> repository.insert(req, fingerprint, Instant.now(clock)));
            return toResponse(saved);
        } catch (DuplicateKeyException e) {
            // 并发同键：唯一约束仅放行一条，其余读取已存在的记录按幂等规则处理
            BerthWindow current = repository.findByRequestId(req.requestId()).orElseThrow(() -> e);
            return replay(current, fingerprint);
        }
    }

    /**
     * 审批通过：PENDING → APPROVED。同一泊位同一时段仅允许一个已批准/已生效窗口；
     * 每名引航员同一时段最多服务一艘船。已批准窗口重复审批返回幂等成功。
     */
    public WindowResponse approve(String requestId) {
        return transactionTemplate.execute(status -> {
            repository.lockApprovals();
            BerthWindow window = mustFind(requestId);
            if (window.status() == WindowStatus.APPROVED) {
                return toResponse(window);
            }
            if (window.status() != WindowStatus.PENDING) {
                throw invalidState("批准", window);
            }
            int conflicts = repository.countConflictingApprovedOrActive(
                    window.berthId(), window.startUtc(), window.endUtc(),
                    window.requestId(), window.replacesRequestId());
            if (conflicts > 0) {
                throw new ApiException(HttpStatus.CONFLICT, "BERTH_WINDOW_CONFLICT",
                        "泊位 " + window.berthId() + " 在该时段已存在已批准或已生效窗口");
            }
            List<String> available = repository.findAvailablePilotIds(window.startUtc(), window.endUtc());
            if (available.size() < window.pilotsRequired()) {
                throw new ApiException(HttpStatus.CONFLICT, "PILOT_SHORTAGE",
                        "该时段可用引航员 " + available.size() + " 名，不足所需 " + window.pilotsRequired() + " 名");
            }
            List<String> assigned = available.subList(0, window.pilotsRequired());
            repository.assignPilots(window.id(), assigned);
            repository.updateStatus(window.id(), WindowStatus.APPROVED, Instant.now(clock));
            return toResponse(repository.findById(window.id()).orElseThrow());
        });
    }

    /**
     * 审批驳回：PENDING → REJECTED（终态）。重复驳回返回幂等成功。
     */
    public WindowResponse reject(String requestId) {
        return transition(requestId, WindowStatus.REJECTED, "驳回");
    }

    /**
     * 申请人取消：PENDING/APPROVED → CANCELLED（终态）。重复取消返回幂等成功。
     */
    public WindowResponse cancel(String requestId) {
        return transition(requestId, WindowStatus.CANCELLED, "取消");
    }

    /**
     * 登记离泊：ACTIVE → COMPLETED（终态）。重复登记返回幂等成功。
     */
    public WindowResponse depart(String requestId) {
        return transition(requestId, WindowStatus.COMPLETED, "登记离泊");
    }

    /**
     * 按幂等请求编号查询窗口；不存在返回 404。
     */
    public WindowResponse get(String requestId) {
        return toResponse(mustFind(requestId));
    }

    /**
     * 将到达起始时刻的已批准窗口转为已生效（由定时任务或测试触发），返回转换条数。
     */
    public int activateDueWindows() {
        Integer updated = transactionTemplate.execute(
                status -> repository.activateDue(Instant.now(clock)));
        return updated == null ? 0 : updated;
    }

    private WindowResponse transition(String requestId, WindowStatus target, String action) {
        return transactionTemplate.execute(status -> {
            repository.lockApprovals();
            BerthWindow window = mustFind(requestId);
            if (window.status() == target) {
                // 终态重复相同操作：幂等成功
                return toResponse(window);
            }
            boolean allowed = switch (target) {
                case REJECTED -> window.status() == WindowStatus.PENDING;
                case CANCELLED -> window.status() == WindowStatus.PENDING
                        || window.status() == WindowStatus.APPROVED;
                case COMPLETED -> window.status() == WindowStatus.ACTIVE;
                default -> false;
            };
            if (!allowed) {
                throw invalidState(action, window);
            }
            repository.updateStatus(window.id(), target, Instant.now(clock));
            return toResponse(repository.findById(window.id()).orElseThrow());
        });
    }

    private WindowResponse replay(BerthWindow current, String fingerprint) {
        if (!current.fingerprint().equals(fingerprint)) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT",
                    "requestId 已存在但业务参数不一致");
        }
        return toResponse(current);
    }

    private BerthWindow mustFind(String requestId) {
        return repository.findByRequestId(requestId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WINDOW_NOT_FOUND",
                        "窗口不存在: " + requestId));
    }

    private ApiException invalidState(String action, BerthWindow window) {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_STATE",
                "当前状态 " + window.status() + " 不允许" + action
                        + (window.status().isTerminal() ? "（终态窗口不得复活）" : ""));
    }

    private WindowResponse toResponse(BerthWindow window) {
        return new WindowResponse(
                window.requestId(), window.vesselName(), window.berthId(),
                window.startUtc(), window.endUtc(), window.draftMeters(), window.pilotsRequired(),
                window.status(), repository.findAssignedPilotIds(window.id()),
                window.replacesRequestId(), window.createdUtc(), window.updatedUtc());
    }
}
