package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.service.CategoryService;
import com.hailin.blogsystem.utils.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CategoriesController {

    private final CategoryService categoryService;

    @GetMapping("/categories")
    public Result getCategories() {  //获取分类列表
        return Result.success(categoryService.listPublicCategories());
    }
}
