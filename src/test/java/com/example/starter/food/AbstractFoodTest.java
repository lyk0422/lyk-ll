package com.example.starter.food;

import com.example.starter.StarterApplication;
import com.example.starter.food.repo.FoodRepository;
import com.example.starter.food.service.FoodService;
import com.example.starter.food.service.IdempotencyService;
import com.example.starter.food.web.Dtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/**
 * 集成测试基类：真实 H2（MODE=MySQL）+ Spring 全栈，每个用例前由 reset.sql 清理并重置合成数据。
 */
@SpringBootTest(classes = StarterApplication.class)
@Import(TestClockConfig.class)
@Sql(scripts = "/reset.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public abstract class AbstractFoodTest {

    @Autowired
    protected FoodService service;
    @Autowired
    protected FoodRepository repo;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected ObjectMapper mapper;
    @Autowired
    protected ControllableClock clock;

    protected Dtos.RecipeResponse submit(String requestId, String product, String version, String category,
                                         List<Dtos.AdditiveInput> additives,
                                         List<Dtos.IngredientInput> ingredients) {
        Dtos.SubmitRecipeRequest req = new Dtos.SubmitRecipeRequest(requestId, product, version, category,
                additives == null ? List.of() : additives, ingredients == null ? List.of() : ingredients);
        IdempotencyService.Result r = service.submitRecipe(req);
        return (Dtos.RecipeResponse) r.body();
    }

    protected Dtos.RecipeResponse review(String reviewRequestId, long recipeId, String standardVersion) {
        IdempotencyService.Result r = service.reviewRecipe(
                new Dtos.ReviewRecipeRequest(reviewRequestId, standardVersion), recipeId);
        return (Dtos.RecipeResponse) r.body();
    }

    protected Dtos.RecipeResponse approve(String requestId, long recipeId) {
        IdempotencyService.Result r = service.approveRecipe(requestId, recipeId);
        return (Dtos.RecipeResponse) r.body();
    }

    protected Dtos.FilingResponse file(String requestId, long recipeId) {
        IdempotencyService.Result r = service.fileFiling(new Dtos.FileFilingRequest(requestId, recipeId));
        return (Dtos.FilingResponse) r.body();
    }

    protected Dtos.AdditiveInput add(String code, String amount) {
        return new Dtos.AdditiveInput(code, new BigDecimal(amount));
    }

    protected Dtos.IngredientInput ing(String compound, String proportion) {
        return new Dtos.IngredientInput(compound, new BigDecimal(proportion));
    }
}
