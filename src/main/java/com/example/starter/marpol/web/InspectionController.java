package com.example.starter.marpol.web;

import com.example.starter.marpol.service.InspectionService;
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
 * 港口国核查接口：发起、逐条确认/标注违规、签发、缺陷整改。
 */
@RestController
@RequestMapping("/api/inspections")
public class InspectionController {

    private final InspectionService inspectionService;

    public InspectionController(InspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InspectionResponse create(@Valid @RequestBody InspectionCreateRequest request) {
        return inspectionService.create(request);
    }

    @PostMapping("/{inspectionNo}/decisions")
    public InspectionItemResponse decide(@PathVariable String inspectionNo,
                                         @Valid @RequestBody InspectionDecisionRequest request) {
        return inspectionService.decide(inspectionNo, request);
    }

    @PostMapping("/{inspectionNo}/issue")
    public InspectionResponse issue(@PathVariable String inspectionNo) {
        return inspectionService.issue(inspectionNo);
    }

    @GetMapping("/{inspectionNo}")
    public InspectionResponse get(@PathVariable String inspectionNo) {
        return inspectionService.get(inspectionNo);
    }

    @GetMapping("/{inspectionNo}/items")
    public List<InspectionItemResponse> listItems(@PathVariable String inspectionNo) {
        return inspectionService.listItems(inspectionNo);
    }

    @GetMapping("/{inspectionNo}/defects")
    public List<DefectResponse> listDefects(@PathVariable String inspectionNo) {
        return inspectionService.listDefects(inspectionNo);
    }

    @PostMapping("/defects/{defectId}/close")
    public DefectResponse closeDefect(@PathVariable long defectId) {
        return inspectionService.closeDefect(defectId);
    }
}
