package com.hailin.blogsystem.ai.rag;

import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.dto.ArticleRagRebuildResult;
import com.hailin.blogsystem.entity.dto.ArticleRagRepairResult;
import com.hailin.blogsystem.entity.vo.ArticleRagConsistencyReportVO;
import com.hailin.blogsystem.utils.Result;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ai/rag/articles")
@RequiredArgsConstructor
public class ArticleRagController {

    private final ArticleRagIndexService articleRagIndexService;
    private final ArticleRagRetrieveService articleRagRetrieveService;
    private final ArticleRagConsistencyService articleRagConsistencyService;
    private final BlogAiProperties blogAiProperties;

    @PostMapping("/rebuild")
    public Result<ArticleRagRebuildResult> rebuildArticleIndex(){
        var rebuild = blogAiProperties.getRag().getRebuild();
        if (!rebuild.isEnabled()) {
            return Result.error(403, "索引重建已关闭");
        }

        Long currentUserId = UserContext.get();
        if (currentUserId == null || !rebuild.getAllowedUserIds().contains(currentUserId)) {
            return Result.error(403, "无权操作");
        }

        int indexedChunkCount = articleRagIndexService.indexPublishedArticles();
        return Result.success(new ArticleRagRebuildResult(indexedChunkCount));
    }

    @GetMapping("/search")  //RAG 检索调试接口
    public Result<List<ArticleRagContext>> searchArticleRag(@RequestParam String question){
        List<ArticleRagContext> contexts = articleRagRetrieveService.retrieve(question);

        return Result.success(contexts);
    }

    @GetMapping("/consistency")  //一致性巡检
    public Result<ArticleRagConsistencyReportVO> checkConsistency() {
        return Result.success(articleRagConsistencyService.check());
    }

    @PostMapping("/consistency/repair")  //手动修复接口
    public Result<ArticleRagRepairResult> repairConsistency() {
        return Result.success(articleRagConsistencyService.repair());
    }

}
