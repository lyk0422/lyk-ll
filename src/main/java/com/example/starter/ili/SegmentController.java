package com.example.starter.ili;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管段登记接口。
 */
@RestController
@RequestMapping("/api/segments")
public class SegmentController {

    private final SegmentService segmentService;

    public SegmentController(SegmentService segmentService) {
        this.segmentService = segmentService;
    }

    @PostMapping
    public ResponseEntity<Dtos.SegmentResponse> create(@Valid @RequestBody Dtos.CreateSegmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(segmentService.createSegment(request));
    }
}
