package com.example.starter.damage.service;

import com.example.starter.damage.dto.Requests.QualificationRevokeRequest;
import com.example.starter.damage.dto.Requests.QualificationUpdateRequest;
import com.example.starter.damage.dto.Requests.SupplierCreateRequest;
import com.example.starter.damage.dto.Views.SupplierDetailView;
import com.example.starter.damage.dto.Views.SupplierView;
import org.springframework.stereotype.Service;

/**
 * 供应商门面服务：写操作以 requestId 幂等，
 * 指纹覆盖 requestId 与全部业务字段（含 expectedVersion、工法集合与精确 UTC 时刻）。
 */
@Service
public class SupplierService {

    private final SupplierTxService txService;
    private final IdempotencyExecutor idempotency;

    public SupplierService(SupplierTxService txService, IdempotencyExecutor idempotency) {
        this.txService = txService;
        this.idempotency = idempotency;
    }

    /** 登记供应商（幂等） */
    public SupplierView create(SupplierCreateRequest request) {
        return idempotency.execute("SUPPLIER_CREATE", request.requestId(), request, SupplierView.class,
                () -> txService.create(request));
    }

    /** 修订资格（幂等；同键异参 409） */
    public SupplierView updateQualification(long supplierId, QualificationUpdateRequest request) {
        return idempotency.execute("SUPPLIER_QUAL_UPDATE#" + supplierId, request.requestId(), request,
                SupplierView.class, () -> txService.updateQualification(supplierId, request));
    }

    /** 撤销资格（幂等） */
    public SupplierView revoke(long supplierId, QualificationRevokeRequest request) {
        return idempotency.execute("SUPPLIER_QUAL_REVOKE#" + supplierId, request.requestId(), request,
                SupplierView.class, () -> txService.revoke(supplierId, request));
    }

    /** 供应商全量明细 */
    public SupplierDetailView detail(long supplierId) {
        return txService.detail(supplierId);
    }
}
