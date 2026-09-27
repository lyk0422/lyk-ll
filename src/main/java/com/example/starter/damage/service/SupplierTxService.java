package com.example.starter.damage.service;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.dto.Requests.QualificationRevokeRequest;
import com.example.starter.damage.dto.Requests.QualificationUpdateRequest;
import com.example.starter.damage.dto.Requests.SupplierCreateRequest;
import com.example.starter.damage.dto.Views.SupplierDetailView;
import com.example.starter.damage.dto.Views.SupplierView;
import com.example.starter.damage.dto.Views.QualificationView;
import com.example.starter.damage.store.SupplierRepository;
import com.example.starter.damage.store.SupplierRepository.QualificationRow;
import com.example.starter.damage.store.SupplierRepository.SupplierRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 供应商事务内业务服务：资格版本从 1 开始、成功变更加 1；
 * 修改携带 expectedVersion 乐观校验；定标后撤销只形成风险事件，不改历史中标证据。
 */
@Service
public class SupplierTxService {

    private final SupplierRepository supplierRepository;
    private final IdempotencyExecutor idempotency;
    private final Clock clock;

    public SupplierTxService(SupplierRepository supplierRepository,
                             IdempotencyExecutor idempotency,
                             Clock clock) {
        this.supplierRepository = supplierRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /** 登记供应商：初始资格版本 1；编号唯一冲突返回 409 */
    @Transactional
    public SupplierView create(SupplierCreateRequest request) {
        validateMethods(request.methods());
        validateValidityWindow(request.validFrom(), request.validUntil());
        LocalDateTime now = LocalDateTime.now(clock);
        long supplierId;
        try {
            supplierId = supplierRepository.insertSupplier(request.supplierNo(), request.name(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("供应商编号已存在: " + request.supplierNo());
        }
        long qualId = supplierRepository.insertQualification(supplierId, 1,
                request.validFrom(), request.validUntil(), "ACTIVE", now);
        for (String method : distinct(request.methods())) {
            supplierRepository.insertQualMethod(qualId, method);
        }
        SupplierView view = toView(supplierId);
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /** 修订资格：expectedVersion 须等于当前版本，成功变更加 1，旧版本置 SUPERSEDED */
    @Transactional
    public SupplierView updateQualification(long supplierId, QualificationUpdateRequest request) {
        SupplierRow supplier = lockSupplier(supplierId);
        requireVersion(supplier.version(), request.expectedVersion());
        validateMethods(request.methods());
        validateValidityWindow(request.validFrom(), request.validUntil());
        LocalDateTime now = LocalDateTime.now(clock);
        supplierRepository.supersedeActiveQualifications(supplierId);
        int newVersion = supplier.version() + 1;
        long qualId = supplierRepository.insertQualification(supplierId, newVersion,
                request.validFrom(), request.validUntil(), "ACTIVE", now);
        for (String method : distinct(request.methods())) {
            supplierRepository.insertQualMethod(qualId, method);
        }
        supplierRepository.updateVersion(supplierId, newVersion, now);
        SupplierView view = toView(supplierId);
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /**
     * 撤销资格：当前版本置 REVOKED 并生成新版本号；
     * 定标（批复）后撤销只形成风险事件，不改历史中标证据。
     */
    @Transactional
    public SupplierView revoke(long supplierId, QualificationRevokeRequest request) {
        SupplierRow supplier = lockSupplier(supplierId);
        requireVersion(supplier.version(), request.expectedVersion());
        QualificationRow current = supplierRepository.findCurrentQualification(supplierId, supplier.version());
        if (current == null || !"ACTIVE".equals(current.status())) {
            throw ApiException.unprocessable("当前资格已撤销，无需重复撤销");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        supplierRepository.supersedeActiveQualifications(supplierId);
        int newVersion = supplier.version() + 1;
        long qualId = supplierRepository.insertQualification(supplierId, newVersion,
                current.validFrom(), current.validUntil(), "REVOKED", now);
        for (String method : supplierRepository.listQualMethods(current.id())) {
            supplierRepository.insertQualMethod(qualId, method);
        }
        supplierRepository.updateVersion(supplierId, newVersion, now);
        if (supplierRepository.hasFrozenQualification(supplierId)) {
            var awarded = supplierRepository.findAwardedInquiryForSupplier(supplierId).orElse(null);
            supplierRepository.insertRiskEvent(
                    awarded == null ? null : awarded.caseId(),
                    awarded == null ? null : awarded.inquiryId(),
                    supplierId,
                    "QUALIFICATION_REVOKED_AFTER_AWARD",
                    "定标后撤销资格，仅记录风险事件，历史中标证据不变", now);
        }
        SupplierView view = toView(supplierId);
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /** 供应商全量明细：含全部资格版本历史 */
    @Transactional(readOnly = true)
    public SupplierDetailView detail(long supplierId) {
        SupplierRow supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> ApiException.notFound("供应商不存在: " + supplierId));
        List<QualificationView> qualifications = supplierRepository.listQualifications(supplierId).stream()
                .map(supplierRepository::toQualificationView)
                .toList();
        return new SupplierDetailView(supplier.id(), supplier.supplierNo(), supplier.name(),
                supplier.version(), qualifications, supplierRepository.listRiskEventsBySupplier(supplierId));
    }

    private SupplierRow lockSupplier(long supplierId) {
        return supplierRepository.findByIdForUpdate(supplierId)
                .orElseThrow(() -> ApiException.notFound("供应商不存在: " + supplierId));
    }

    private void requireVersion(int currentVersion, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion != currentVersion) {
            throw ApiException.conflict("版本不一致：期望 " + expectedVersion + "，当前 " + currentVersion);
        }
    }

    private void validateMethods(List<String> methods) {
        for (String method : distinct(methods)) {
            if (!supplierRepository.methodExists(method)) {
                throw ApiException.badRequest("未知工法代码: " + method);
            }
        }
    }

    private void validateValidityWindow(LocalDateTime validFrom, LocalDateTime validUntil) {
        if (validUntil.isBefore(validFrom)) {
            throw ApiException.badRequest("资格失效时刻不得早于生效时刻");
        }
    }

    private Set<String> distinct(List<String> methods) {
        return new LinkedHashSet<>(methods);
    }

    private SupplierView toView(long supplierId) {
        SupplierRow supplier = supplierRepository.findById(supplierId).orElseThrow();
        QualificationRow current = supplierRepository.findCurrentQualification(supplierId, supplier.version());
        return new SupplierView(supplier.id(), supplier.supplierNo(), supplier.name(), supplier.version(),
                current == null ? null : supplierRepository.toQualificationView(current));
    }
}
