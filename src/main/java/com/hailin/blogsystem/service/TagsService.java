package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.Tags;
import com.hailin.blogsystem.entity.vo.TagsVO;

import java.util.List;

public interface TagsService extends IService<Tags> {
    List<TagsVO> getTags();
}
