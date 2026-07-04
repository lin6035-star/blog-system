package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.service.TagsService;
import com.hailin.blogsystem.utils.Result;
import com.hailin.blogsystem.entity.vo.TagsVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TagsController {

    private final TagsService tagsService;

    @GetMapping("/tags")
    public Result getTags(){  //获取标签列表
        List<TagsVO> data = tagsService.getTags();

        return Result.success(data);
    }
}
