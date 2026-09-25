package com.example.starter.cosmetic.port;

import com.example.starter.cosmetic.domain.EfficacyCatalog;
import com.example.starter.cosmetic.domain.RestrictedIngredient;

import java.util.List;
import java.util.Optional;

/**
 * 合成基础目录端口：功效目录与限用成分限值。
 */
public interface CatalogPort {

    /** 按编码查询功效目录条目。 */
    Optional<EfficacyCatalog> findEfficacy(String code);

    /** 全部功效目录。 */
    List<EfficacyCatalog> listEfficacies();

    /** 按成分编码查询限用成分限值。 */
    Optional<RestrictedIngredient> findRestricted(String ingredientCode);
}
