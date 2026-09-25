package com.example.starter.dye.service;

import com.example.starter.dye.domain.RecipeItemPO;
import com.example.starter.dye.domain.RecipePO;
import com.example.starter.dye.domain.RecipeStatus;
import com.example.starter.dye.repo.DyestuffRepository;
import com.example.starter.dye.repo.RecipeRepository;
import com.example.starter.dye.web.BusinessRuleException;
import com.example.starter.dye.web.ConflictException;
import com.example.starter.dye.web.NotFoundException;
import com.example.starter.dye.web.dto.RecipeCreateRequest;
import com.example.starter.dye.web.dto.RecipeItemRequest;
import com.example.starter.dye.web.dto.RecipeItemResponse;
import com.example.starter.dye.web.dto.RecipeNewVersionRequest;
import com.example.starter.dye.web.dto.RecipeResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 配方版本服务。
 * 发布（PUBLISHED）后冻结：不允许原地修改条目；任何修改都基于最新发布版本派生新草稿版本，
 * 旧版本原样保留、可被已排缸次持续引用。
 */
@Service
public class RecipeService {

    private final RecipeRepository recipeRepository;
    private final DyestuffRepository dyestuffRepository;
    private final Clock clock;

    public RecipeService(RecipeRepository recipeRepository, DyestuffRepository dyestuffRepository,
                         Clock clock) {
        this.recipeRepository = recipeRepository;
        this.dyestuffRepository = dyestuffRepository;
        this.clock = clock;
    }

    @Transactional
    public RecipeResponse createDraft(RecipeCreateRequest request) {
        if (recipeRepository.findLatestAny(request.recipeCode()).isPresent()) {
            throw new ConflictException("配方编码已存在: " + request.recipeCode()
                    + "，修改请基于已发布版本创建新版本");
        }
        validateItems(request.items());
        LocalDateTime now = LocalDateTime.now(clock);
        long id = recipeRepository.insert(request.recipeCode(), 1, request.name(), request.liquorRatio(),
                RecipeStatus.DRAFT, now);
        insertItems(id, request.items());
        return toResponse(recipeRepository.find(request.recipeCode(), 1).orElseThrow());
    }

    /**
     * 发布冻结。草稿发布后不可再改；重复发布同一已发布版本按幂等成功返回。
     */
    @Transactional
    public RecipeResponse publish(String recipeCode, Integer version) {
        RecipePO po = recipeRepository.find(recipeCode, version == null ? 0 : version)
                .orElseThrow(() -> new NotFoundException("配方版本不存在: " + recipeCode + " V" + version));
        if (po.getStatus() == RecipeStatus.DRAFT) {
            recipeRepository.publish(po.getId(), LocalDateTime.now(clock));
        }
        return toResponse(recipeRepository.find(recipeCode, po.getVersion()).orElseThrow());
    }

    /**
     * 基于最新已发布版本派生新版本（草稿）。旧版本保留不动。
     */
    @Transactional
    public RecipeResponse newVersion(String recipeCode, RecipeNewVersionRequest request) {
        RecipePO base = recipeRepository.findLatestPublished(recipeCode)
                .orElseThrow(() -> new BusinessRuleException("配方尚无已发布版本，不能派生新版本: "
                        + recipeCode));
        validateItems(request.items());
        int newVersion = recipeRepository.maxVersion(recipeCode) + 1;
        String name = request.name() == null || request.name().isBlank() ? base.getName() : request.name();
        int liquorRatio = request.liquorRatio() == null ? base.getLiquorRatio() : request.liquorRatio();
        if (liquorRatio <= 0) {
            throw new BusinessRuleException("浴比必须为正整数");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        long id = recipeRepository.insert(recipeCode, newVersion, name, liquorRatio, RecipeStatus.DRAFT, now);
        insertItems(id, request.items());
        return toResponse(recipeRepository.find(recipeCode, newVersion).orElseThrow());
    }

    public RecipeResponse get(String recipeCode, Integer version) {
        RecipePO po = version == null
                ? recipeRepository.findLatestAny(recipeCode).orElseThrow(() -> notFound(recipeCode))
                : recipeRepository.find(recipeCode, version).orElseThrow(() -> notFound(recipeCode + " V"
                        + version));
        return toResponse(po);
    }

    public List<RecipeResponse> list() {
        return recipeRepository.findAll().stream().map(this::toResponse).toList();
    }

    private void validateItems(List<RecipeItemRequest> items) {
        long distinct = items.stream().map(RecipeItemRequest::dyestuffCode).distinct().count();
        if (distinct != items.size()) {
            throw new BusinessRuleException("配方条目染化料不可重复");
        }
        for (RecipeItemRequest item : items) {
            if (dyestuffRepository.findByCode(item.dyestuffCode()).isEmpty()) {
                throw new BusinessRuleException("配方引用了未建档染化料: " + item.dyestuffCode());
            }
        }
    }

    private void insertItems(long recipeId, List<RecipeItemRequest> items) {
        int seq = 1;
        for (RecipeItemRequest item : items) {
            recipeRepository.insertItem(recipeId, item.dyestuffCode(), item.gramsPerLiter(), seq++);
        }
    }

    private RecipeResponse toResponse(RecipePO po) {
        List<RecipeItemResponse> items = recipeRepository.findItems(po.getId()).stream()
                .map(this::toItemResponse)
                .toList();
        return new RecipeResponse(po.getId(), po.getRecipeCode(), po.getVersion(), po.getName(),
                po.getLiquorRatio(), po.getStatus().name(), po.getPublishedAt(), items);
    }

    private RecipeItemResponse toItemResponse(RecipeItemPO po) {
        return new RecipeItemResponse(po.getDyestuffCode(), po.getGramsPerLiter());
    }

    private NotFoundException notFound(String key) {
        return new NotFoundException("配方不存在: " + key);
    }
}
