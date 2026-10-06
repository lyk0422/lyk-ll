package com.example.starter.web;

import com.example.starter.service.QualificationService;
import com.example.starter.web.dto.RenewalRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行资格续期入口。
 */
@RestController
@RequestMapping("/api/routes")
public class RenewalController {

    private final QualificationService qualificationService;

    public RenewalController(QualificationService qualificationService) {
        this.qualificationService = qualificationService;
    }

    /**
     * 资格续期；须在到期前 30 日内申请，恰第 30 日受理，更早或逾期拒绝。
     */
    @PostMapping("/{id}/renewal")
    public RenewalRequest.Result renew(@PathVariable long id,
                                       @Valid @RequestBody RenewalRequest request) {
        return qualificationService.renew(id, request);
    }
}
