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
 * 传染病直报 REST 入口（本地无外部直报平台）。
 */
@RestController
@RequestMapping("/api/epidemic/cards")
public class EpidemicCardController {

    private final EpidemicCardService service;

    public EpidemicCardController(EpidemicCardService service) {
        this.service = service;
    }

    /** 初报。 */
    @PostMapping("/initial")
    public SubmitResult initial(@Valid @RequestBody InitialReportRequest request) {
        return service.submitInitial(request);
    }

    /** 普通订正。 */
    @PostMapping("/correction")
    public SubmitResult correction(@Valid @RequestBody CorrectionRequest request) {
        return service.submitCorrection(request);
    }

    /** 初审通过。 */
    @PostMapping("/{cardNumber}/review")
    public ActionResult review(@PathVariable String cardNumber, @Valid @RequestBody ReviewRequest request) {
        return service.review(cardNumber, request.reviewer(), request.opinion());
    }

    /** 审核通过前撤回（终态）。 */
    @PostMapping("/{cardNumber}/withdraw")
    public ActionResult withdraw(@PathVariable String cardNumber, @Valid @RequestBody WithdrawRequest request) {
        return service.withdraw(cardNumber, request.operator(), request.reason());
    }

    /** 终审归档（重复归档幂等成功）。 */
    @PostMapping("/{cardNumber}/archive")
    public ActionResult archive(@PathVariable String cardNumber, @Valid @RequestBody ArchiveRequest request) {
        return service.archive(cardNumber, request.reviewer(), request.opinion());
    }

    /** 发起归档后特殊订正。 */
    @PostMapping("/special-corrections")
    public SpecialCorrectionView submitSpecial(@Valid @RequestBody SpecialCorrectionRequest request) {
        return service.submitSpecial(request);
    }

    /** 特殊订正双人确认（逐次调用，满两名不同审核员后生效）。 */
    @PostMapping("/special-corrections/{requestId}/confirm")
    public SpecialCorrectionView confirmSpecial(@PathVariable String requestId,
                                                @Valid @RequestBody SpecialConfirmRequest request) {
        return service.confirmSpecial(requestId, request);
    }

    /** 查询特殊订正进度。 */
    @GetMapping("/special-corrections/{requestId}")
    public SpecialCorrectionView getSpecial(@PathVariable String requestId) {
        return service.getSpecial(requestId);
    }

    /** 查询卡片（默认返回最新版本，附完整版本链与审核轨迹）。 */
    @GetMapping("/{cardNumber}")
    public CardView get(@PathVariable String cardNumber) {
        return service.getCard(cardNumber);
    }

    /** 列出全部卡片。 */
    @GetMapping
    public List<CardView> list() {
        return service.listCards();
    }
}
