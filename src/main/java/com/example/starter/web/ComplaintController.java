package com.example.starter.web;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.ComplaintService;
import com.example.starter.web.dto.ComplaintRequest;
import com.example.starter.web.dto.ComplaintResponse;

/**
 * 干扰投诉登记入口。
 */
@RestController
@RequestMapping("/api/complaints")
public class ComplaintController {

    private final ComplaintService complaintService;

    public ComplaintController(ComplaintService complaintService) {
        this.complaintService = complaintService;
    }

    @PostMapping
    public ComplaintResponse register(@Valid @RequestBody ComplaintRequest request) {
        return complaintService.register(request);
    }
}
