package com.example.starter.compliance.bootstrap;

import com.example.starter.compliance.repo.ComplianceRepository;
import java.math.BigDecimal;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 合成基础数据初始化（幂等执行，仅写入目录与初始限量，不写入任何配方/备案业务数据）：
 * <ul>
 *   <li>食品分类：BEV01 风味饮料、BAK01 糕点、MEAT01 熟肉制品；</li>
 *   <li>添加剂：E202 山梨酸钾/防腐剂、E211 苯甲酸钠/防腐剂、E102 柠檬黄/着色剂、
 *       E129 诱惑红/着色剂、E300 抗坏血酸/抗氧化剂；</li>
 *   <li>复合配料：ING_SAUCE 复合调味酱（含 E202 2.0 g/kg、E211 4.0 g/kg）、
 *       ING_SYRUP 果味糖浆（含 E102 0.5 g/kg）；</li>
 *   <li>初始限量（g/kg 终产品）：BEV01 下 E202=0.5、E211=1.0、E102=0.1、E129=0.1、E300=0.5；
 *       BAK01 下 E202=1.0、E211=1.0、E300=2.0；MEAT01 下 E202=1.5、E300=0.5。</li>
 * </ul>
 */
@Component
@Order(0)
public class SyntheticDataInitializer implements ApplicationRunner {

    private final ComplianceRepository repository;

    public SyntheticDataInitializer(ComplianceRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repository.countCategories() > 0) {
            return;
        }

        repository.insertCategory("BEV01", "风味饮料");
        repository.insertCategory("BAK01", "糕点");
        repository.insertCategory("MEAT01", "熟肉制品");

        repository.insertAdditive("E202", "山梨酸钾", "PRESERVATIVE");
        repository.insertAdditive("E211", "苯甲酸钠", "PRESERVATIVE");
        repository.insertAdditive("E102", "柠檬黄", "COLORANT");
        repository.insertAdditive("E129", "诱惑红", "COLORANT");
        repository.insertAdditive("E300", "抗坏血酸", "ANTIOXIDANT");

        repository.insertIngredient("ING_SAUCE", "复合调味酱");
        repository.insertIngredient("ING_SYRUP", "果味糖浆");
        repository.insertIngredientAdditive("ING_SAUCE", "E202", new BigDecimal("2.000000"));
        repository.insertIngredientAdditive("ING_SAUCE", "E211", new BigDecimal("4.000000"));
        repository.insertIngredientAdditive("ING_SYRUP", "E102", new BigDecimal("0.500000"));

        long now = System.currentTimeMillis();
        repository.insertLimit("E202", "BEV01", new BigDecimal("0.500000"), now);
        repository.insertLimit("E211", "BEV01", new BigDecimal("1.000000"), now);
        repository.insertLimit("E102", "BEV01", new BigDecimal("0.100000"), now);
        repository.insertLimit("E129", "BEV01", new BigDecimal("0.100000"), now);
        repository.insertLimit("E300", "BEV01", new BigDecimal("0.500000"), now);
        repository.insertLimit("E202", "BAK01", new BigDecimal("1.000000"), now);
        repository.insertLimit("E211", "BAK01", new BigDecimal("1.000000"), now);
        repository.insertLimit("E300", "BAK01", new BigDecimal("2.000000"), now);
        repository.insertLimit("E202", "MEAT01", new BigDecimal("1.500000"), now);
        repository.insertLimit("E300", "MEAT01", new BigDecimal("0.500000"), now);
    }
}
