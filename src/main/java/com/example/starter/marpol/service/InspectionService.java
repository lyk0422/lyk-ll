package com.example.starter.marpol.service;

import com.example.starter.marpol.domain.Decision;
import com.example.starter.marpol.domain.InspectionState;
import com.example.starter.marpol.domain.ItemStatus;
import com.example.starter.marpol.error.ConflictException;
import com.example.starter.marpol.error.NotFoundException;
import com.example.starter.marpol.error.RuleViolationException;
import com.example.starter.marpol.model.EmissionRecord;
import com.example.starter.marpol.model.Inspection;
import com.example.starter.marpol.model.InspectionItem;
import com.example.starter.marpol.model.InspectionReview;
import com.example.starter.marpol.model.Ship;
import com.example.starter.marpol.repo.EmissionRecordRepository;
import com.example.starter.marpol.repo.InspectionRepository;
import com.example.starter.marpol.repo.ReferenceRepository;
import com.example.starter.marpol.support.ServiceClock;
import com.example.starter.marpol.web.DecisionRequest;
import com.example.starter.marpol.web.InspectionResponse;
import com.example.starter.marpol.web.ReviewRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 港口国核查服务。
 *
 * <p>关键口径：</p>
 * <ul>
 *   <li>逐条结论在作出时刻钉住所依据的具体版本；与修正并发时由行锁决定先后，结论不随后续修正漂移；</li>
 *   <li>签发为终态：重复签发幂等成功；签发后修改结论一律 409，补充证据走复查并引用原核查号；</li>
 *   <li>标注违规即生成缺陷项，整改截止日=核查日（UTC 日界）+14 自然日，恰第 14 日关闭有效。</li>
 * </ul>
 */
@Service
public class InspectionService {

    private final InspectionRepository inspections;
    private final EmissionRecordRepository records;
    private final ReferenceRepository refs;
    private final ServiceClock clock;
    private final int rectifyDays;
    private final TransactionTemplate requiresNewTx;

    public InspectionService(InspectionRepository inspections,
                             EmissionRecordRepository records,
                             ReferenceRepository refs,
                             ServiceClock clock,
                             PlatformTransactionManager txManager,
                             @Value("${marpol.rectify-days:14}") int rectifyDays) {
        this.inspections = inspections;
        this.records = records;
        this.refs = refs;
        this.clock = clock;
        this.rectifyDays = rectifyDays;
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 按船舶发起核查，核查日取当前 UTC 日期。
     */
    @Transactional
    public Inspection start(String shipImo, String officer) {
        Ship ship = refs.findShipByImo(shipImo);
        if (ship == null) {
            throw new NotFoundException("船舶不存在: " + shipImo);
        }
        Instant now = clock.now();
        LocalDate inspectionDate = clock.todayUtc();
        Long id = inspections.insertInspection(ship.id(), officer, inspectionDate, now);
        String no = "PSC-" + inspectionDate.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + id;
        inspections.updateInspectionNo(id, no);
        return inspections.findByNo(no);
    }

    /**
     * 逐条确认/标注违规。核查须未签发；结论钉住当前最新有效版本。
     */
    @Transactional
    public InspectionItem decide(String inspectionNo, DecisionRequest req) {
        Inspection inspection = lockInspection(inspectionNo);
        ensureOpen(inspection);

        EmissionRecord referenced = records.findById(req.recordId());
        if (referenced == null) {
            throw new NotFoundException("排放记录不存在: " + req.recordId());
        }
        if (!referenced.shipId().equals(inspection.shipId())) {
            throw new RuleViolationException("记录不属于本次核查的船舶");
        }
        // 锁定版本族最新有效版本：与并发修正互斥，钉住结论所依据的版本。
        EmissionRecord active = records.findActiveByRootForUpdate(referenced.rootId());
        if (active == null) {
            throw new ConflictException("记录版本族状态异常，无有效版本");
        }
        Decision decision = Decision.valueOf(req.decision());
        Instant now = clock.now();
        LocalDate dueDate = decision == Decision.VIOLATION
                ? inspection.inspectionDate().plusDays(rectifyDays) : null;

        InspectionItem existing = inspections.findItemForUpdate(inspection.id(), active.rootId());
        if (existing == null) {
            InspectionItem draft = new InspectionItem(
                    null, inspection.id(), active.rootId(), active.id(), active.versionNo(),
                    decision, req.reason(),
                    decision == Decision.VIOLATION ? ItemStatus.OPEN : null,
                    dueDate, null, now, now);
            Long itemId = inspections.insertItem(draft);
            return inspections.findItemById(itemId);
        }
        InspectionItem updated = new InspectionItem(
                existing.id(), existing.inspectionId(), existing.rootRecordId(),
                active.id(), active.versionNo(), decision, req.reason(),
                decision == Decision.VIOLATION ? ItemStatus.OPEN : null,
                dueDate, existing.rectifiedAt(), existing.createdAt(), now);
        inspections.updateItemDecision(updated);
        return inspections.findItemById(existing.id());
    }

    /**
     * 签发核查（终态）。重复签发返回幂等成功；签发后结论冻结。
     */
    @Transactional
    public Inspection issue(String inspectionNo) {
        Inspection inspection = lockInspection(inspectionNo);
        if (inspection.status() == InspectionState.ISSUED) {
            return inspection;
        }
        inspections.markIssued(inspection.id(), clock.now());
        return inspections.findByNo(inspectionNo);
    }

    /**
     * 缺陷项整改关闭：截止日（含，恰第 14 个自然日）前有效；超过截止日判逾期并拒绝关闭。
     * 逾期状态在独立事务中落库，避免外层回滚导致状态丢失。
     */
    @Transactional
    public InspectionItem rectify(String inspectionNo, Long itemId) {
        Inspection inspection = lockInspection(inspectionNo);
        InspectionItem item = inspections.findItemById(itemId);
        if (item == null || !item.inspectionId().equals(inspection.id())) {
            throw new NotFoundException("缺陷项不存在: " + itemId);
        }
        if (item.status() != ItemStatus.OPEN) {
            return item;
        }
        LocalDate today = clock.todayUtc();
        if (today.isAfter(item.dueDate())) {
            requiresNewTx.executeWithoutResult(status ->
                    inspections.markOverdue(item.id(), clock.now()));
            throw new ConflictException("已超过整改截止日 " + item.dueDate() + "（UTC），缺陷判为逾期");
        }
        inspections.markRectified(item.id(), clock.now());
        return inspections.findItemById(itemId);
    }

    /**
     * 签发后补充证据：不改原结论，落复查记录并引用原核查号。
     */
    @Transactional
    public InspectionReview review(ReviewRequest req) {
        Inspection inspection = inspections.findByNo(req.inspectionNo());
        if (inspection == null) {
            throw new NotFoundException("核查不存在: " + req.inspectionNo());
        }
        if (inspection.status() != InspectionState.ISSUED) {
            throw new ConflictException("核查尚未签发；签发前可直接修改结论，无需复查");
        }
        if (req.itemId() != null) {
            InspectionItem item = inspections.findItemById(req.itemId());
            if (item == null || !item.inspectionId().equals(inspection.id())) {
                throw new NotFoundException("缺陷项不存在: " + req.itemId());
            }
        }
        Long id = inspections.insertReview(inspection.id(), inspection.inspectionNo(),
                req.itemId(), req.evidence(), clock.now());
        return inspections.findReviews(inspection.id()).stream()
                .filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public InspectionResponse toResponse(Inspection inspection) {
        Ship ship = refs.findShipById(inspection.shipId());
        List<InspectionResponse.ItemView> items = inspections.findItems(inspection.id()).stream()
                .map(i -> new InspectionResponse.ItemView(
                        i.id(), i.rootRecordId(), i.pinnedRecordId(), i.pinnedVersionNo(),
                        i.decision().name(), i.reason(),
                        i.status() == null ? null : i.status().name(),
                        i.dueDate(), i.rectifiedAt()))
                .toList();
        List<InspectionResponse.ReviewView> reviews = inspections.findReviews(inspection.id()).stream()
                .map(r -> new InspectionResponse.ReviewView(r.id(), r.itemId(), r.evidence(), r.createdAt()))
                .toList();
        return new InspectionResponse(
                inspection.id(), inspection.inspectionNo(), ship.imo(), inspection.officer(),
                inspection.status().name(), inspection.inspectionDate(), inspection.issuedAt(),
                items, reviews);
    }

    @Transactional(readOnly = true)
    public Inspection requireByNo(String inspectionNo) {
        Inspection inspection = inspections.findByNo(inspectionNo);
        if (inspection == null) {
            throw new NotFoundException("核查不存在: " + inspectionNo);
        }
        return inspection;
    }

    private Inspection lockInspection(String inspectionNo) {
        Inspection inspection = inspections.findByNoForUpdate(inspectionNo);
        if (inspection == null) {
            throw new NotFoundException("核查不存在: " + inspectionNo);
        }
        return inspection;
    }

    private void ensureOpen(Inspection inspection) {
        if (inspection.status() == InspectionState.ISSUED) {
            throw new ConflictException("核查 " + inspection.inspectionNo() + " 已签发为终态，结论不可修改");
        }
    }
}
