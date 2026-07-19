package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.Category;
import com.hailin.blogsystem.mapper.CategoryMapper;
import com.hailin.blogsystem.service.CategoryService;
import com.hailin.blogsystem.entity.vo.CategoryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl extends ServiceImpl<CategoryMapper, Category> implements CategoryService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Override  //获取分类列表
    public List<CategoryVO> listPublicCategories() {

        String json = null;

        try{
            json = stringRedisTemplate.opsForValue()
                    .get(RedisConstants.CATEGORY_LIST_KEY);
        }catch(Exception e){
            //Redis读取失败不影响接口返回，继续查数据库
        }

        if(json != null && !json.isBlank()){
            try{
                return objectMapper.readValue(json,new TypeReference<List<CategoryVO>>(){});
            }catch(JsonProcessingException e){
                try{
                    stringRedisTemplate.delete(RedisConstants.CATEGORY_LIST_KEY);
                }catch(Exception ignored){
                    //Redis删除失败不影响，继续查数据库
                }
            }

        }

        List<CategoryVO> list = lambdaQuery()
                .orderByAsc(Category::getSortOrder)
                .list()
                .stream()
                .map(CategoryVO::from)
                .toList();

        try{
            stringRedisTemplate.opsForValue()
                    .set(
                          RedisConstants.CATEGORY_LIST_KEY,
                          objectMapper.writeValueAsString(list),
                          RedisConstants.COMMON_LIST_TTL_MINUTES,
                          TimeUnit.MINUTES
                    );

        }catch(Exception e){
            //缓存失败不影响
        }

        return list;
    }
}
