package com.hailin.blogsystem.entity.vo;

import lombok.Data;

import java.util.List;

@Data
public class PageVO<T> {
    private List<T> list;
    private Long total;
    private Long page;
    private Long pageSize;

    public PageVO(){

    }

    public PageVO(List<T> list,Long total,Long page,Long pageSize){
        this.list = list;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
    }
}
