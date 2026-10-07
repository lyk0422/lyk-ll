package com.example.starter.compliance.web;

import com.example.starter.compliance.service.ReworkResults;
import com.example.starter.compliance.service.ReworkService;
import com.example.starter.compliance.service.ServiceResult;
import com.example.starter.compliance.web.Requests.RegisterReworkRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 返工料 HTTP 入口：登记（新增/修改）、料账查询（来源、原量、消费、余额勾稽）与消费流水查询。
 * 返工料投用经由 /api/compliance/line-control/controlled-productions 的 reworks 字段提交。
 */
@RestController
@RequestMapping("/api/compliance/rework")
public class ReworkController {

    private final ReworkService service;

    public ReworkController(ReworkService service) {
        this.service = service;
    }

    /** 返工料登记：expectedVersion 新增传 0，修改传当前料账版本，成功后版本加 1。 */
    @PostMapping("/materials")
    public ResponseEntity<ReworkResults.ReworkMaterialView> register(
            @RequestBody RegisterReworkRequest request) {
        ServiceResult<ReworkResults.ReworkMaterialView> result = service.registerRework(request);
        return ResponseEntity.status(result.status())
                .header("X-Idempotent-Replay", String.valueOf(result.replayed()))
                .body(result.body());
    }

    /** 料账查询：来源、冻结过敏原、原量、累计消费与余额。 */
    @GetMapping("/materials/{materialCode}")
    public ReworkResults.ReworkMaterialView material(@PathVariable String materialCode) {
        return service.getMaterial(materialCode);
    }

    /** 消费流水查询：历史消费不可改，按发生先后升序。 */
    @GetMapping("/materials/{materialCode}/consumptions")
    public ReworkResults.ReworkConsumptionListView consumptions(
            @PathVariable String materialCode) {
        return service.getConsumptions(materialCode);
    }
}
