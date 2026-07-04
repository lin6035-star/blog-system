package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.entity.Tags;
import com.hailin.blogsystem.mapper.TagsMapper;
import com.hailin.blogsystem.service.TagsService;
import com.hailin.blogsystem.entity.vo.TagsVO;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TagsServiceImpl extends ServiceImpl<TagsMapper, Tags> implements TagsService {


    @Override
    public List<TagsVO> getTags() { //获取标签列表

        return lambdaQuery().list()
                .stream()
                .map(TagsVO::from)
                .toList();
    }
}
