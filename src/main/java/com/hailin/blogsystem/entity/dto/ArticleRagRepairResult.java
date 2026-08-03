package com.hailin.blogsystem.entity.dto;

public record ArticleRagRepairResult(
        int missingFixed,  //补入索引的文章数
        int extraDeleted,  //从ES删除的多余文章数
        int chunkReindexed  //实际写入的chunk总数
) {
    public static ArticleRagRepairResult empty(){
        return new ArticleRagRepairResult(0,0,0);
    }
}
