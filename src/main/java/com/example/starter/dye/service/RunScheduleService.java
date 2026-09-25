package com.example.starter.dye.service;

import com.example.starter.dye.domain.DyeVatPO;
import com.example.starter.dye.domain.RecipePO;
import com.example.starter.dye.domain.RecipeStatus;
import com.example.starter.dye.domain.VatRunPO;
import com.example.starter.dye.domain.RunStatus;
import com.example.starter.dye.repo.DyeVatRepository;
import com.example.starter.dye.repo.RecipeRepository;
import com.example.starter.dye.repo.VatRunRepository;
import com.example.starter.dye.web.BusinessRuleException;
import com.example.starter.dye.web.ConflictException;
import com.example.starter.dye.web.NotFoundException;
import com.example.starter.dye.web.dto.ScheduleRunRequest;
import com.example.starter.dye.web.dto.VatRunResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 排缸与并缸。
 *
 * <p>同一染缸同时刻只允许一个执行中缸次：插入缸次后以
 * {@code update dye_vat ... where active_run_id is null} 条件占缸，并发下仅一方成功，
 * 失败方事务回滚（缸次不留痕），返回 409。</p>
 *
 * <p>并缸生产须同配方（同 recipe_code）同浴比；配方不同或浴比不同一律拒绝（422）。</p>
 */
@Service
public class RunScheduleService {

    private final VatRunRepository vatRunRepository;
    private final DyeVatRepository dyeVatRepository;
    private final RecipeRepository recipeRepository;
    private final java.time.Clock clock;

    public RunScheduleService(VatRunRepository vatRunRepository, DyeVatRepository dyeVatRepository,
                              RecipeRepository recipeRepository, java.time.Clock clock) {
        this.vatRunRepository = vatRunRepository;
        this.dyeVatRepository = dyeVatRepository;
        this.recipeRepository = recipeRepository;
        this.clock = clock;
    }

    @Transactional
    public VatRunResponse schedule(ScheduleRunRequest request) {
        if (vatRunRepository.findByNo(request.runNo()).isPresent()) {
            throw new ConflictException("缸次编号已存在: " + request.runNo());
        }
        DyeVatPO vat = dyeVatRepository.findByCode(request.vatCode())
                .orElseThrow(() -> new NotFoundException("染缸不存在: " + request.vatCode()));
        if (request.fabricKg().compareTo(vat.getCapacityKg()) > 0) {
            throw new BusinessRuleException("织物重量 " + request.fabricKg() + "kg 超过染缸额定容量 "
                    + vat.getCapacityKg() + "kg");
        }
        RecipePO recipe = resolvePublishedRecipe(request.recipeCode(), request.recipeVersion());

        VatRunPO partner = null;
        if (request.mergeWithRunNo() != null && !request.mergeWithRunNo().isBlank()) {
            partner = vatRunRepository.findByNoForUpdate(request.mergeWithRunNo())
                    .orElseThrow(() -> new NotFoundException("并缸伙伴缸次不存在: "
                            + request.mergeWithRunNo()));
            validateMerge(recipe, partner);
        }

        VatRunPO run = new VatRunPO();
        run.setRunNo(request.runNo());
        run.setVatId(vat.getId());
        run.setRecipeId(recipe.getId());
        run.setRecipeCode(recipe.getRecipeCode());
        run.setRecipeVersion(recipe.getVersion());
        run.setLiquorRatio(recipe.getLiquorRatio());
        run.setFabricKg(request.fabricKg());
        run.setMergedRunNo(partner == null ? null : partner.getRunNo());
        run.setStatus(RunStatus.PLANNED);
        run.setRepairCount(0);
        long runId = vatRunRepository.insert(run, LocalDateTime.now(clock));

        if (!dyeVatRepository.occupy(vat.getId(), runId)) {
            // 条件占缸失败：染缸已被并发缸次占用，整事务回滚
            throw new ConflictException("染缸 " + request.vatCode() + " 同时刻已有执行中缸次，并发排缸仅受理一个");
        }
        if (partner != null) {
            vatRunRepository.updateMergedRunNo(partner.getId(), request.runNo());
        }
        return toResponse(vatRunRepository.findByNo(request.runNo()).orElseThrow(), vat.getVatCode());
    }

    public VatRunResponse get(String runNo) {
        VatRunPO run = vatRunRepository.findByNo(runNo)
                .orElseThrow(() -> new NotFoundException("缸次不存在: " + runNo));
        String vatCode = dyeVatRepository.findById(run.getVatId()).map(DyeVatPO::getVatCode).orElse(null);
        return toResponse(run, vatCode);
    }

    private RecipePO resolvePublishedRecipe(String recipeCode, Integer version) {
        RecipePO recipe = version == null
                ? recipeRepository.findLatestPublished(recipeCode).orElse(null)
                : recipeRepository.find(recipeCode, version).orElse(null);
        if (recipe == null) {
            throw new NotFoundException("已发布配方版本不存在: " + recipeCode
                    + (version == null ? "（最新发布版本）" : " V" + version));
        }
        if (recipe.getStatus() != RecipeStatus.PUBLISHED) {
            throw new BusinessRuleException("仅已发布冻结配方可投用: " + recipeCode + " V"
                    + recipe.getVersion() + " 当前为 " + recipe.getStatus());
        }
        return recipe;
    }

    private void validateMerge(RecipePO recipe, VatRunPO partner) {
        if (partner.getStatus() == RunStatus.ARCHIVED) {
            throw new BusinessRuleException("伙伴缸次已归档终态，不能并缸: " + partner.getRunNo());
        }
        if (!partner.getRecipeCode().equals(recipe.getRecipeCode())) {
            throw new BusinessRuleException("并缸拒绝：配方不同（" + partner.getRecipeCode() + " vs "
                    + recipe.getRecipeCode() + "）");
        }
        if (partner.getLiquorRatio() != recipe.getLiquorRatio()) {
            throw new BusinessRuleException("并缸拒绝：浴比不同（伙伴 1:" + partner.getLiquorRatio()
                    + " vs 本缸 1:" + recipe.getLiquorRatio() + "）");
        }
    }

    private VatRunResponse toResponse(VatRunPO po, String vatCode) {
        return new VatRunResponse(po.getId(), po.getRunNo(), vatCode, po.getRecipeCode(),
                po.getRecipeVersion(), po.getLiquorRatio(), po.getFabricKg(), po.getMergedRunNo(),
                po.getStatus().name(), po.getRepairCount(), po.getOutDeltaE(),
                po.getFinalDisposition() == null ? null : po.getFinalDisposition().name(),
                po.getJudgedAt(), po.getCreatedAt());
    }
}
