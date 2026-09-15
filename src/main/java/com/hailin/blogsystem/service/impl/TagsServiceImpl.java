package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.component.CacheTtlSupport;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.Tags;
import com.hailin.blogsystem.mapper.TagsMapper;
import com.hailin.blogsystem.service.TagsService;
import com.hailin.blogsystem.entity.vo.TagsVO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TagsServiceImpl extends ServiceImpl<TagsMapper, Tags> implements TagsService {

    private final StringRedisTemplate stringRedisTemplate;
    private final CacheTtlSupport cacheTtlSupport;
    private final ObjectMapper objectMapper;

    @Override
    public List<TagsVO> getTags() { //获取标签列表

        String json = null;

        try{
            json = stringRedisTemplate.opsForValue()
                    .get(RedisConstants.TAG_LIST_KEY);
        }catch(Exception e){
            //Redis读取失败不影响接口返回，继续查数据库
        }

        if(json != null && !json.isBlank()){

            try{
                return objectMapper.readValue(json, new TypeReference<List<TagsVO>>() {});
            }catch (JsonProcessingException e){
                try{
                    stringRedisTemplate.delete(RedisConstants.TAG_LIST_KEY);
                }catch(Exception ignored){
                    //Redis删除失败不影响，继续查数据库
                }
            }
        }

        List<TagsVO> list = lambdaQuery().list()
                .stream()
                .map(TagsVO::from)
                .toList();

        try{
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.TAG_LIST_KEY,
                    objectMapper.writeValueAsString(list),
                    cacheTtlSupport.jitter(Duration.ofMinutes(RedisConstants.COMMON_LIST_TTL_MINUTES))
            );
        }catch (Exception e){
            //缓存失败不影响接口返回
        }

        return list;
    }
}
