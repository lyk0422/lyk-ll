package com.example.starter.irrigation.api;

import com.example.starter.irrigation.api.PlanDtos.PlanVersionResponse;
import com.example.starter.irrigation.api.PlanDtos.PublishPlanRequest;
import com.example.starter.irrigation.service.PlanService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配水计划发布与版本查询接口。
 */
@RestController
@RequestMapping("/api/irrigation/plans")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    /** 发布计划（同键重放返回首次结果，同键异参 409）。 */
    @PostMapping("/publish")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanVersionResponse publish(@Valid @RequestBody PublishPlanRequest request) {
        return planService.publish(request);
    }

    /** 查同轮同组当前生效版本。 */
    @GetMapping("/groups/{groupCode}/turns/{turnNo}/effective")
    public PlanVersionResponse effective(@PathVariable String groupCode, @PathVariable long turnNo) {
        return planService.getEffective(turnNo, groupCode);
    }

    /** 查指定版本（旧版本可查）。 */
    @GetMapping("/groups/{groupCode}/turns/{turnNo}/versions/{versionNo}")
    public PlanVersionResponse version(@PathVariable String groupCode, @PathVariable long turnNo,
                                       @PathVariable int versionNo) {
        return planService.getVersion(turnNo, groupCode, versionNo);
    }

    /** 列同轮同组全部版本。 */
    @GetMapping("/groups/{groupCode}/turns/{turnNo}/versions")
    public List<PlanVersionResponse> versions(@PathVariable String groupCode, @PathVariable long turnNo) {
        return planService.listVersions(turnNo, groupCode);
    }
}
