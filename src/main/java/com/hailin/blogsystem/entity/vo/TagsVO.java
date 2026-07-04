package com.hailin.blogsystem.entity.vo;

import com.hailin.blogsystem.entity.Tags;
import lombok.Data;

@Data
public class TagsVO {

    private Long id;
    private String name;

    public static TagsVO from(Tags tags){
        TagsVO tagsVO = new TagsVO();
        tagsVO.setId(tags.getId());
        tagsVO.setName(tags.getName());

        return tagsVO;
    }
}
