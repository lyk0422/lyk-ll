package com.example.starter.tree;

import com.example.starter.tree.TreeDtos.RegisterTreeRequest;
import com.example.starter.tree.TreeDtos.TreeResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 树木建档接口。
 */
@RestController
@RequestMapping("/api/trees")
public class TreeController {

    private final TreeService treeService;

    public TreeController(TreeService treeService) {
        this.treeService = treeService;
    }

    @PostMapping
    public TreeResponse register(@Valid @RequestBody RegisterTreeRequest request) {
        return treeService.register(request);
    }
}
