package com.example.starter.dye.domain;

import java.time.LocalDateTime;

/**
 * 配方版本 PO。PUBLISHED 后冻结，修改须新建版本。
 */
public class RecipePO {
    private Long id;
    private String recipeCode;
    private int version;
    private String name;
    private int liquorRatio;
    private RecipeStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRecipeCode() { return recipeCode; }
    public void setRecipeCode(String recipeCode) { this.recipeCode = recipeCode; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getLiquorRatio() { return liquorRatio; }
    public void setLiquorRatio(int liquorRatio) { this.liquorRatio = liquorRatio; }
    public RecipeStatus getStatus() { return status; }
    public void setStatus(RecipeStatus status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
}
