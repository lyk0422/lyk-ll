package com.example.starter.damage.api;

import com.example.starter.damage.dto.Requests.QualificationRevokeRequest;
import com.example.starter.damage.dto.Requests.QualificationUpdateRequest;
import com.example.starter.damage.dto.Requests.SupplierCreateRequest;
import com.example.starter.damage.dto.Views.SupplierDetailView;
import com.example.starter.damage.dto.Views.SupplierView;
import com.example.starter.damage.service.SupplierService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合成供应商与资格版本 API。
 * 资格版本从 1 开始，修改携带 expectedVersion，成功变更加 1。
 */
@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {

    private final SupplierService service;

    public SupplierController(SupplierService service) {
        this.service = service;
    }

    /** 登记供应商：初始资格版本 1 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierView create(@Valid @RequestBody SupplierCreateRequest request) {
        return service.create(request);
    }

    /** 修订资格：适用工法与有效期整体替换为新版本 */
    @PutMapping("/{supplierId}/qualification")
    public SupplierView updateQualification(@PathVariable long supplierId,
                                            @Valid @RequestBody QualificationUpdateRequest request) {
        return service.updateQualification(supplierId, request);
    }

    /** 撤销资格：定标后撤销只形成风险事件 */
    @PostMapping("/{supplierId}/revoke")
    public SupplierView revoke(@PathVariable long supplierId,
                               @Valid @RequestBody QualificationRevokeRequest request) {
        return service.revoke(supplierId, request);
    }

    /** 供应商全量明细：含全部资格版本历史 */
    @GetMapping("/{supplierId}")
    public SupplierDetailView detail(@PathVariable long supplierId) {
        return service.detail(supplierId);
    }
}
