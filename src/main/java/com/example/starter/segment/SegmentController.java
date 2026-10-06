package com.example.starter.segment;

import com.example.starter.segment.SegmentDtos.CreateSegmentRequest;
import com.example.starter.segment.SegmentDtos.SegmentResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 路段建档接口。
 */
@RestController
@RequestMapping("/api/segments")
public class SegmentController {

    private final SegmentService segmentService;

    public SegmentController(SegmentService segmentService) {
        this.segmentService = segmentService;
    }

    @PostMapping
    public SegmentResponse create(@Valid @RequestBody CreateSegmentRequest request) {
        return segmentService.create(request);
    }
}
