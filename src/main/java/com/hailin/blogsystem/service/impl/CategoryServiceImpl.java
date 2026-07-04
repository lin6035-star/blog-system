package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.entity.Category;
import com.hailin.blogsystem.mapper.CategoryMapper;
import com.hailin.blogsystem.service.CategoryService;
import com.hailin.blogsystem.entity.vo.CategoryVO;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CategoryServiceImpl extends ServiceImpl<CategoryMapper, Category> implements CategoryService {

    @Override
    public List<CategoryVO> listPublicCategories() {  //获取分类列表
        return lambdaQuery()
                .orderByAsc(Category::getSortOrder)
                .list()
                .stream()
                .map(CategoryVO::from)
                .toList();
    }
}
