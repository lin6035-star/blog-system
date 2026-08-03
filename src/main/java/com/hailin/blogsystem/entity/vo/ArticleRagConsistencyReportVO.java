package com.hailin.blogsystem.entity.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
//一致性巡检
public class ArticleRagConsistencyReportVO {

    private Long publishedArticleCount;
    private Long indexedArticleCount;

    //MySQL 已发布，但是ES没有
    private List<Long> missingInEs;
    //ES 有，但是MySQL不是已发布
    private List<Long> extraInEs;
    private LocalDateTime checkedAt;
}
