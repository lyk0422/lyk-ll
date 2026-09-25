package com.example.starter.irrigation.service;

import com.example.starter.irrigation.dto.MeteringContracts.MeteringRequest;
import com.example.starter.irrigation.dto.MeteringContracts.MeteringView;
import com.example.starter.irrigation.error.BizException;
import com.example.starter.irrigation.repo.GroupRepository;
import com.example.starter.irrigation.repo.MeteringRepository;
import com.example.starter.irrigation.repo.PlanRepository;
import com.example.starter.irrigation.repo.SettlementRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 实供计量服务：按斗口+轮次去重，重复上报不重复累计；封存后仅记迟到事件。
 */
@Service
public class MeteringService {

    /** 计量状态：已确认。 */
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    /** 计量状态：迟到（轮次已封存）。 */
    public static final String STATUS_LATE = "LATE";

    private final GroupRepository groupRepository;
    private final PlanRepository planRepository;
    private final MeteringRepository meteringRepository;
    private final SettlementRepository settlementRepository;

    public MeteringService(GroupRepository groupRepository,
                           PlanRepository planRepository,
                           MeteringRepository meteringRepository,
                           SettlementRepository settlementRepository) {
        this.groupRepository = groupRepository;
        this.planRepository = planRepository;
        this.meteringRepository = meteringRepository;
        this.settlementRepository = settlementRepository;
    }

    /**
     * 上报实供计量快照。与封存共用生效指针行锁，保证结转以该轮最后一次已确认计量快照为准。
     */
    @Transactional
    public MeteringView report(MeteringRequest request) {
        GroupRepository.OutletRow outlet = groupRepository.findOutletById(request.outletId())
                .orElseThrow(() -> BizException.notFound("斗口不存在: " + request.outletId()));
        long groupId = outlet.groupId();

        planRepository.lockEffectivePlanVersionId(groupId, request.roundNo())
                .orElseThrow(() -> BizException.conflict(
                        "该轮次无生效计划，无法上报计量: group=" + groupId + ", round=" + request.roundNo()));

        if (settlementRepository.findByGroupAndRound(groupId, request.roundNo()).isPresent()) {
            meteringRepository.insertLateEvent(groupId, request.outletId(), request.roundNo(),
                    request.amountM3());
            return new MeteringView(request.outletId(), request.roundNo(), request.amountM3(), STATUS_LATE);
        }

        int updated = meteringRepository.updateAmount(request.outletId(), request.roundNo(), request.amountM3());
        if (updated == 0) {
            try {
                meteringRepository.insert(groupId, request.outletId(), request.roundNo(), request.amountM3());
            } catch (DuplicateKeyException e) {
                // 并发同键上报：唯一约束兜底，转为快照更新，不重复累计。
                meteringRepository.updateAmount(request.outletId(), request.roundNo(), request.amountM3());
            }
        }
        return new MeteringView(request.outletId(), request.roundNo(), request.amountM3(), STATUS_CONFIRMED);
    }
}
