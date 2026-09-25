package com.example.starter.epidemic.api;

import com.example.starter.epidemic.service.EpidemicCardService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 传染病直报报告卡接口。
 */
@RestController
@RequestMapping("/api/cards")
public class EpidemicCardController {

    private final EpidemicCardService service;

    public EpidemicCardController(EpidemicCardService service) {
        this.service = service;
    }

    @PostMapping("/submit")
    public CardResponse submit(@Valid @RequestBody SubmitCardRequest request) {
        return service.submit(request);
    }

    @PostMapping("/correct")
    public CardResponse correct(@Valid @RequestBody CorrectCardRequest request) {
        return service.correct(request);
    }

    @PostMapping("/{cardNo}/review")
    public ActionResponse review(@PathVariable String cardNo, @Valid @RequestBody ReviewRequest request) {
        return service.review(cardNo, request);
    }

    @PostMapping("/{cardNo}/withdraw")
    public ActionResponse withdraw(@PathVariable String cardNo, @Valid @RequestBody WithdrawRequest request) {
        return service.withdraw(cardNo, request);
    }

    @PostMapping("/{cardNo}/archive")
    public ActionResponse archive(@PathVariable String cardNo, @Valid @RequestBody ArchiveRequest request) {
        return service.archive(cardNo, request);
    }

    @PostMapping("/special")
    public SpecialCorrectionResponse startSpecial(@Valid @RequestBody SpecialCorrectRequest request) {
        return service.startSpecial(request);
    }

    @PostMapping("/special/{specialId}/confirm")
    public SpecialCorrectionResponse confirmSpecial(@PathVariable Long specialId,
                                                    @Valid @RequestBody SpecialConfirmBody request) {
        return service.confirmSpecial(new SpecialConfirmRequest(specialId, request.reviewer()));
    }

    @GetMapping("/{cardNo}")
    public CardResponse get(@PathVariable String cardNo) {
        return service.getCard(cardNo);
    }

    @GetMapping("/{cardNo}/trails")
    public List<AuditTrailResponse> trails(@PathVariable String cardNo) {
        return service.getTrails(cardNo);
    }

    @GetMapping("/{cardNo}/versions")
    public List<CardVersionResponse> versions(@PathVariable String cardNo) {
        return service.getVersions(cardNo);
    }
}
