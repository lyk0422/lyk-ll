package com.example.starter.marpol.web;

import com.example.starter.marpol.model.Inspection;
import com.example.starter.marpol.model.InspectionItem;
import com.example.starter.marpol.model.InspectionReview;
import com.example.starter.marpol.service.InspectionService;
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
 * 港口国核查 API：发起、逐条结论、签发、整改关闭、复查补充证据。
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
    public InspectionResponse start(@Valid @RequestBody StartInspectionRequest request) {
        Inspection inspection = inspectionService.start(request.shipImo(), request.officer());
        return inspectionService.toResponse(inspection);
    }

    @PostMapping("/{inspectionNo}/items")
    public InspectionResponse.ItemView decide(@PathVariable String inspectionNo,
                                              @Valid @RequestBody DecisionRequest request) {
        InspectionItem item = inspectionService.decide(inspectionNo, request);
        return new InspectionResponse.ItemView(
                item.id(), item.rootRecordId(), item.pinnedRecordId(), item.pinnedVersionNo(),
                item.decision().name(), item.reason(),
                item.status() == null ? null : item.status().name(),
                item.dueDate(), item.rectifiedAt());
    }

    @PostMapping("/{inspectionNo}/issue")
    public InspectionResponse issue(@PathVariable String inspectionNo) {
        return inspectionService.toResponse(inspectionService.issue(inspectionNo));
    }

    @PostMapping("/{inspectionNo}/items/{itemId}/rectify")
    public InspectionResponse.ItemView rectify(@PathVariable String inspectionNo,
                                               @PathVariable Long itemId) {
        InspectionItem item = inspectionService.rectify(inspectionNo, itemId);
        return new InspectionResponse.ItemView(
                item.id(), item.rootRecordId(), item.pinnedRecordId(), item.pinnedVersionNo(),
                item.decision().name(), item.reason(),
                item.status() == null ? null : item.status().name(),
                item.dueDate(), item.rectifiedAt());
    }

    @PostMapping("/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public InspectionResponse.ReviewView review(@Valid @RequestBody ReviewRequest request) {
        InspectionReview review = inspectionService.review(request);
        return new InspectionResponse.ReviewView(review.id(), review.itemId(),
                review.evidence(), review.createdAt());
    }

    @GetMapping("/{inspectionNo}")
    public InspectionResponse get(@PathVariable String inspectionNo) {
        return inspectionService.toResponse(inspectionService.requireByNo(inspectionNo));
    }
}
