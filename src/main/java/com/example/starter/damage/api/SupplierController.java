package com.example.starter.damage.api;

import com.example.starter.damage.dto.InquiryRequests.CreateSupplierRequest;
import com.example.starter.damage.dto.InquiryRequests.QualificationGrantRequest;
import com.example.starter.damage.dto.InquiryRequests.QualificationRevokeRequest;
import com.example.starter.damage.dto.InquiryViews.SupplierView;
import com.example.starter.damage.service.InquiryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合成供应商与版本化资格 API（密封询价流程）。
 */
@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {

    private final InquiryService service;

    public SupplierController(InquiryService service) {
        this.service = service;
    }

    /** 创建合成供应商（含适用工法） */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierView create(@Valid @RequestBody CreateSupplierRequest request) {
        return service.createSupplier(request);
    }

    /** 查询供应商、适用工法与当前资格 */
    @GetMapping("/{supplierNo}")
    public SupplierView get(@PathVariable String supplierNo) {
        return service.getSupplier(supplierNo);
    }

    /** 授予/续期有效资格版本 */
    @PostMapping("/{supplierNo}/qualifications/grant")
    public SupplierView grant(@PathVariable String supplierNo,
                              @Valid @RequestBody QualificationGrantRequest request) {
        return service.grantQualification(supplierNo, request);
    }

    /** 撤销资格（追加 REVOKED 版本） */
    @PostMapping("/{supplierNo}/qualifications/revoke")
    public SupplierView revoke(@PathVariable String supplierNo,
                               @Valid @RequestBody QualificationRevokeRequest request) {
        return service.revokeQualification(supplierNo, request);
    }
}
