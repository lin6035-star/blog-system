package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.entity.dto.AiChatDTO;
import com.hailin.blogsystem.entity.dto.AiCreateSessionDTO;
import com.hailin.blogsystem.entity.vo.*;
import com.hailin.blogsystem.service.AiConversationSummaryService;
import com.hailin.blogsystem.service.AiMessageService;
import com.hailin.blogsystem.service.AiSessionService;
import com.hailin.blogsystem.utils.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiSessionService aiSessionService;
    private final AiMessageService aiMessageService;
    private final AiConversationSummaryService aiConversationSummaryService;

    @PostMapping("/conversations")  //1.新建会话
    public Result<AiSessionVO> buildNewSession(@RequestBody(required = false) AiCreateSessionDTO aiCreateSessionDTO){
        AiSessionVO aiSessionVO = aiSessionService.createSession(aiCreateSessionDTO);
        return Result.success(aiSessionVO);
    }

    @GetMapping("/conversations")  //2.查询该用户的历史会话
    public Result<PageVO<AiSessionVO>> getHistoricalSession(@RequestParam(defaultValue = "1") Long page,
                                        @RequestParam(defaultValue = "10") Long pageSize){
        PageVO<AiSessionVO> pageResult = aiSessionService.getHistoricalSession(page,pageSize);

        return Result.success(pageResult);
    }

    @DeleteMapping("/conversations/{id}")  //3.删除会话
    public Result deleteSession(@PathVariable String id){
        aiSessionService.deleteSession(id);

        return Result.success();
    }

    @GetMapping("/conversations/{id}/messages")  //4.查询某个会话的消息列表
    public Result<List<AiMessageVO>> getMessages(@PathVariable String id){
        List<AiMessageVO> messages = aiMessageService.getMessages(id);
        return Result.success(messages);
    }

    // 【已废弃】非流式接口，前端已全面切到流式，暂时注释，后续删除
    // @PostMapping("/chat")  //5.发送聊天消息
    // public Result<AiChatVO> chat(@RequestBody AiChatDTO aiChatDTO){
    //     AiChatVO result = aiMessageService.chat(aiChatDTO);
    //     return Result.success(result);
    // }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AiChatEventVO> streamChat(@RequestBody AiChatDTO aiChatDTO) {
        return aiMessageService.streamChat(aiChatDTO);
    }

    @DeleteMapping("/conversations/{sessionId}/messages/{messageId}")
    public Result<Void> deleteMessage(@PathVariable Long sessionId, @PathVariable Long messageId) {
        aiMessageService.deleteMessage(sessionId, messageId);
        return Result.success();
    }

    // 会话压缩状态：前端据此显示"正在自动压缩上下文 / 已自动压缩上下文"
    @GetMapping("/conversations/{sessionId}/summary-status")
    public Result<AiConversationSummaryStatusVO> getSummaryStatus(@PathVariable Long sessionId) {
        return Result.success(aiConversationSummaryService.getSummaryStatus(sessionId));
    }

}
