package com.example.starter.museum.service;

import com.example.starter.museum.api.ContractResponse;
import com.example.starter.museum.api.HandoverResponse;
import com.example.starter.museum.domain.ContractStatus;
import com.example.starter.museum.domain.HandoverType;
import com.example.starter.museum.domain.Party;
import com.example.starter.museum.domain.po.ArtifactPo;
import com.example.starter.museum.domain.po.HandoverPo;
import com.example.starter.museum.domain.po.LoanContractPo;
import com.example.starter.museum.domain.po.ShowcasePo;
import com.example.starter.museum.repo.CatalogRepository;
import com.example.starter.museum.repo.ContractRepository;
import com.example.starter.museum.repo.EventRepository;
import com.example.starter.museum.repo.HandoverRepository;
import com.example.starter.museum.support.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * 借展申请与流转服务：申请→点交→在展→归还点交→结案，以及续展审批。
 *
 * <p>关键规则：
 * <ul>
 *   <li>一级文物同一时刻仅一个有效合同：artifact_active_loan 主键 + 事务保证并发唯一受理；</li>
 *   <li>借展期限最长 3 年（UTC 日界），恰满 3 年合法；</li>
 *   <li>届满前 60 个自然日起可续展一次，增量不超过原期限一半，恰第 60 日受理；</li>
 *   <li>点交须双方在同一点交单确认，单方确认不生效；</li>
 *   <li>归还点交与续展并发由 version 乐观锁裁决，只形成一个终态。</li>
 * </ul>
 */
@Service
@Transactional
public class LoanService {

    private final ContractRepository contracts;
    private final HandoverRepository handovers;
    private final CatalogRepository catalog;
    private final EventRepository events;
    private final NumberingService numbering;
    private final Clock clock;

    public LoanService(ContractRepository contracts, HandoverRepository handovers,
                       CatalogRepository catalog, EventRepository events,
                       NumberingService numbering, Clock clock) {
        this.contracts = contracts;
        this.handovers = handovers;
        this.catalog = catalog;
        this.events = events;
        this.numbering = numbering;
        this.clock = clock;
    }

    public ContractResponse apply(String artifactNo, String borrower,
                                  LocalDate startDate, LocalDate endDate, String showcaseNo) {
        ArtifactPo artifact = catalog.findArtifactByNo(artifactNo)
                .orElseThrow(() -> ApiException.notFound("文物不存在: " + artifactNo));
        if (startDate == null || endDate == null) {
            throw ApiException.badRequest("startDate/endDate 不能为空");
        }
        if (endDate.isBefore(startDate)) {
            throw ApiException.badRequest("届满日不得早于起始日");
        }
        LocalDate maxEnd = startDate.plusYears(3);
        if (endDate.isAfter(maxEnd)) {
            throw ApiException.badRequest("借展期限最长 3 年（UTC 日界），最晚届满日 " + maxEnd);
        }
        Long showcaseId = null;
        if (showcaseNo != null && !showcaseNo.isBlank()) {
            ShowcasePo showcase = catalog.findShowcaseByNo(showcaseNo)
                    .orElseThrow(() -> ApiException.notFound("展柜不存在: " + showcaseNo));
            if (Boolean.TRUE.equals(showcase.frozen)) {
                throw ApiException.badRequest("展柜 " + showcaseNo + " 已冻结新入藏（存在紧急未处置工单）");
            }
            showcaseId = showcase.id;
        }

        LoanContractPo po = new LoanContractPo();
        po.contractNo = numbering.nextContractNo();
        po.artifactId = artifact.id;
        po.borrower = borrower;
        po.startDate = startDate;
        po.endDate = endDate;
        po.originalDays = (int) ChronoUnit.DAYS.between(startDate, endDate);
        po.status = ContractStatus.APPLIED;
        po.showcaseId = showcaseId;
        po.renewCount = 0;
        Long contractId = contracts.insert(po);

        // 唯一有效合同：占用失败说明已有未结案合同（含并发申请），整笔事务回滚。
        if (!contracts.tryOccupyArtifact(artifact.id, contractId)) {
            throw ApiException.conflict("文物 " + artifactNo + " 已存在有效借展合同，同一时刻仅允许一个");
        }
        events.record(contractId, showcaseId, "LOAN_APPLIED",
                "借展申请受理：" + po.contractNo + "，借入方 " + borrower
                        + "，期限 " + startDate + " 至 " + endDate);
        return toResponse(contracts.getByNo(po.contractNo));
    }

    public HandoverResponse confirmHandover(String contractNo, HandoverType type, Party party) {
        LoanContractPo contract = loadContract(contractNo);
        if (type == HandoverType.OUTBOUND && contract.status != ContractStatus.APPLIED) {
            throw ApiException.badRequest("合同当前状态 " + contract.status + "，出库点交仅在已受理后进行");
        }
        if (type == HandoverType.RETURN) {
            if (contract.status == ContractStatus.SUSPENDED) {
                throw ApiException.badRequest("合同处于环境异常挂起状态，须双方复核恢复后方可归还点交");
            }
            if (contract.status != ContractStatus.IN_EXHIBITION) {
                throw ApiException.badRequest("合同当前状态 " + contract.status + "，归还点交仅在在展期间进行");
            }
        }

        HandoverPo handover = handovers.getOrCreate(contract.id, type);
        boolean lender = handover.lenderConfirmed || party == Party.LENDER;
        boolean borrower = handover.borrowerConfirmed || party == Party.BORROWER;
        handovers.updateConfirmation(handover.id, lender, borrower);

        boolean completed = lender && borrower;
        if (completed) {
            handovers.markCompleted(handover.id);
            if (type == HandoverType.OUTBOUND) {
                contract.status = ContractStatus.IN_EXHIBITION;
                persistOrConflict(contract, "并发冲突：合同状态已变更，出库点交失败");
                events.record(contract.id, contract.showcaseId, "OUTBOUND_COMPLETED",
                        "出库点交双方确认完成，文物进入在展状态");
            } else {
                contract.status = ContractStatus.RETURNED;
                contract.returnedAt = clock.instant();
                persistOrConflict(contract, "并发冲突：续展与归还点交同时发生，合同已被另一流程裁决");
                // 占用名额在结案时才释放：已归还未结案期间仍视为该文物的有效合同。
                events.record(contract.id, contract.showcaseId, "RETURN_COMPLETED",
                        "归还点交双方确认完成，文物已归还，等待结案");
            }
        } else {
            events.record(contract.id, contract.showcaseId, "HANDOVER_PARTY_CONFIRMED",
                    type + " 点交收到 " + party + " 确认，尚待另一方确认，暂不生效");
        }
        return toHandoverResponse(contractNo, handovers.find(contract.id, type).orElseThrow());
    }

    public ContractResponse renew(String contractNo, LocalDate newEndDate, boolean approved) {
        LoanContractPo contract = loadContract(contractNo);
        if (contract.status == ContractStatus.SUSPENDED) {
            throw ApiException.badRequest("合同处于环境异常挂起状态，复核恢复前不得续展");
        }
        if (contract.status != ContractStatus.IN_EXHIBITION) {
            throw ApiException.badRequest("合同当前状态 " + contract.status + "，仅在展期间可申请续展");
        }
        if (contract.renewCount >= 1) {
            throw ApiException.badRequest("本合同已续展一次，不可再次续展");
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        long daysToEnd = ChronoUnit.DAYS.between(today, contract.endDate);
        if (daysToEnd < 0) {
            throw ApiException.badRequest("合同已届满，不可续展");
        }
        if (daysToEnd > 60) {
            throw ApiException.badRequest("距届满日还有 " + daysToEnd + " 个自然日，届满前 60 个自然日起方可续展");
        }
        if (!approved) {
            events.record(contract.id, contract.showcaseId, "RENEW_REJECTED",
                    "续展申请被审批驳回，届满日维持 " + contract.endDate);
            return toResponse(contract);
        }
        if (newEndDate == null || !newEndDate.isAfter(contract.endDate)) {
            throw ApiException.badRequest("续展后届满日必须晚于当前届满日 " + contract.endDate);
        }
        long extensionDays = ChronoUnit.DAYS.between(contract.endDate, newEndDate);
        if (extensionDays * 2 > contract.originalDays) {
            throw ApiException.badRequest("续展增量 " + extensionDays + " 天超过原期限 "
                    + contract.originalDays + " 天的一半");
        }
        LocalDate maxEnd = contract.startDate.plusYears(3);
        if (newEndDate.isAfter(maxEnd)) {
            throw ApiException.badRequest("续展后总期限不得超过 3 年，最晚届满日 " + maxEnd);
        }
        contract.endDate = newEndDate;
        contract.renewCount = 1;
        persistOrConflict(contract, "并发冲突：归还点交与续展审批同时发生，合同已被另一流程裁决");
        events.record(contract.id, contract.showcaseId, "RENEW_APPROVED",
                "续展批准，新届满日 " + newEndDate + "，增量 " + extensionDays + " 天");
        return toResponse(contracts.getByNo(contractNo));
    }

    public ContractResponse close(String contractNo) {
        LoanContractPo contract = loadContract(contractNo);
        if (contract.status == ContractStatus.CLOSED) {
            // 结案为终态：重复结案返回幂等成功。
            return toResponse(contract);
        }
        if (contract.status != ContractStatus.RETURNED) {
            throw ApiException.badRequest("合同当前状态 " + contract.status + "，须先完成归还点交方可结案");
        }
        contract.status = ContractStatus.CLOSED;
        contract.closedAt = clock.instant();
        int rows = contracts.updateWithVersion(contract);
        if (rows == 0) {
            throw ApiException.conflict("并发冲突：结案时合同状态已变更");
        }
        contracts.releaseArtifact(contract.id);
        events.record(contract.id, contract.showcaseId, "LOAN_CLOSED", "借展合同结案（终态）");
        return toResponse(contracts.getByNo(contractNo));
    }

    public ContractResponse getContract(String contractNo) {
        return toResponse(loadContract(contractNo));
    }

    private LoanContractPo loadContract(String contractNo) {
        return contracts.findByNo(contractNo)
                .orElseThrow(() -> ApiException.notFound("借展合同不存在: " + contractNo));
    }

    private void persistOrConflict(LoanContractPo contract, String message) {
        int rows = contracts.updateWithVersion(contract);
        if (rows == 0) {
            throw ApiException.conflict(message);
        }
    }

    private HandoverResponse toHandoverResponse(String contractNo, HandoverPo po) {
        return new HandoverResponse(contractNo, po.type, po.lenderConfirmed, po.borrowerConfirmed,
                po.completedAt != null, po.completedAt);
    }

    private ContractResponse toResponse(LoanContractPo po) {
        ArtifactPo artifact = catalog.findArtifactById(po.artifactId);
        String showcaseNo = po.showcaseId == null ? null
                : catalog.findShowcaseById(po.showcaseId).map(s -> s.showcaseNo).orElse(null);
        return new ContractResponse(po.contractNo, artifact.artifactNo, po.borrower,
                po.startDate, po.endDate, po.status, showcaseNo, po.renewCount,
                po.suspendedAt, po.resumedAt, po.returnedAt, po.closedAt, po.version);
    }
}
