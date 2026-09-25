package com.example.starter.museum.repo;

import com.example.starter.museum.domain.po.ArtifactPo;
import com.example.starter.museum.domain.po.ShowcasePo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** 文物、展柜目录查询（合成数据只读）。 */
@Repository
public class CatalogRepository {

    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ArtifactPo> findArtifactByNo(String artifactNo) {
        return jdbc.query("SELECT * FROM artifact WHERE artifact_no = ?", RowMappers.ARTIFACT, artifactNo)
                .stream().findFirst();
    }

    public ArtifactPo findArtifactById(Long id) {
        return jdbc.query("SELECT * FROM artifact WHERE id = ?", RowMappers.ARTIFACT, id)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("文物不存在: id=" + id));
    }

    public ArtifactPo getArtifactByNo(String artifactNo) {
        return findArtifactByNo(artifactNo)
                .orElseThrow(() -> new IllegalArgumentException("文物不存在: " + artifactNo));
    }

    public Optional<ShowcasePo> findShowcaseByNo(String showcaseNo) {
        return jdbc.query("SELECT * FROM showcase WHERE showcase_no = ?", RowMappers.SHOWCASE, showcaseNo)
                .stream().findFirst();
    }

    public ShowcasePo getShowcaseByNo(String showcaseNo) {
        return findShowcaseByNo(showcaseNo)
                .orElseThrow(() -> new IllegalArgumentException("展柜不存在: " + showcaseNo));
    }

    public Optional<ShowcasePo> findShowcaseById(Long id) {
        return jdbc.query("SELECT * FROM showcase WHERE id = ?", RowMappers.SHOWCASE, id)
                .stream().findFirst();
    }

    public int setFrozen(Long showcaseId, boolean frozen, String reason) {
        return jdbc.update(
                "UPDATE showcase SET frozen = ?, frozen_reason = ? WHERE id = ?",
                frozen, reason, showcaseId);
    }
}
