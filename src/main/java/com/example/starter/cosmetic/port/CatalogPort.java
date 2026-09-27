package com.example.starter.cosmetic.port;

import com.example.starter.cosmetic.domain.AgeBandRule;
import com.example.starter.cosmetic.domain.EfficacyCatalog;
import com.example.starter.cosmetic.domain.RestrictedIngredient;

import java.util.List;
import java.util.Optional;

/**
 * 合成基础目录端口：功效目录、限用成分限值与儿童分龄规则。
 */
public interface CatalogPort {

    /** 按编码查询功效目录条目。 */
    Optional<EfficacyCatalog> findEfficacy(String code);

    /** 全部功效目录。 */
    List<EfficacyCatalog> listEfficacies();

    /** 按成分编码查询限用成分限值。 */
    Optional<RestrictedIngredient> findRestricted(String ingredientCode);

    /** 当前生效的儿童分龄规则版本号（合成初始化）。 */
    String currentAgeRuleVersion();

    /** 指定规则版本下的全部分龄档定义（按下限升序）。 */
    List<AgeBandRule> listAgeBands(String ruleVersion);
}
