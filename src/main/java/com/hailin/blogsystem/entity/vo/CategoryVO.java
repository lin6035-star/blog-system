package com.hailin.blogsystem.entity.vo;

import com.hailin.blogsystem.entity.Category;
import lombok.Data;

@Data
public class CategoryVO {

    private Long id;
    private String name;
    private String code;
    private String description;

    public static CategoryVO from(Category category) {
        CategoryVO vo = new CategoryVO();
        vo.setId(category.getId());
        vo.setName(category.getName());
        vo.setCode(category.getCode());
        vo.setDescription(category.getDescription());
        return vo;
    }
}
