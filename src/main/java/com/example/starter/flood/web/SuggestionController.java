package com.example.starter.flood.web;

import com.example.starter.flood.service.SuggestionService;
import com.example.starter.flood.web.dto.ConfirmSuggestionRequest;
import com.example.starter.flood.web.dto.SuggestionView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 在途指令冲突替代建议入口：查询留痕与人工确认（幂等）。
 */
@RestController
@RequestMapping("/api")
public class SuggestionController {

    private final SuggestionService suggestionService;

    public SuggestionController(SuggestionService suggestionService) {
        this.suggestionService = suggestionService;
    }

    /**
     * 查询指令的全部替代建议（留痕可查）。
     */
    @GetMapping("/orders/{orderNo}/suggestions")
    public List<SuggestionView> listByOrder(@PathVariable String orderNo) {
        return suggestionService.listByOrder(orderNo);
    }

    /**
     * 人工确认替代建议（requestId 幂等，同键异参 409）。
     */
    @PostMapping("/suggestions/{suggestionNo}/confirm")
    public SuggestionView confirm(@PathVariable String suggestionNo,
                                  @Valid @RequestBody ConfirmSuggestionRequest request) {
        return suggestionService.confirm(suggestionNo, request);
    }
}
