package com.hailin.blogsystem.ai.tool;

import com.hailin.blogsystem.entity.AiArticleActionCommand;
import com.hailin.blogsystem.entity.AiEditorCommand;
import com.hailin.blogsystem.entity.AiNavigateCommand;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
/*
* 可以把 AiToolActionRegistry 理解成一个 临时快递柜
* 每一轮 AI 请求都有一个 requestId
* 工具调用时，把动作先存进去
* setNavigate("abc123", ...)
* setEditor("abc123", ...)
* setArticleAction("abc123", ...)
*
* 等AI回复结束时，在用同一个requestId取出来
* getNavigate("abc123")
* getEditor("abc123")
* getArticleAction("abc123")
* */


@Component
public class AiToolActionRegistry {

    //路由跳转
    private final Map<String, AiNavigateCommand> navigateMap = new ConcurrentHashMap<>();
    public void setNavigate(String requestId,String target,String param){
        navigateMap.put(requestId,new AiNavigateCommand(target,param));
    }
    public AiNavigateCommand getNavigate(String requestId) {
        return navigateMap.get(requestId);
    }


    //编辑器的填充和保存草稿与发布文章
    private final Map<String, AiEditorCommand> editorMap = new ConcurrentHashMap<>();
    public void setEditor(String requestId, AiEditorCommand command)
    {
        editorMap.put(requestId,command);
    }
    public AiEditorCommand getEditor(String requestId){
        return editorMap.get(requestId);
    }

    //关于文章详情页的点赞收藏的tool
    private final Map<String, AiArticleActionCommand> articleActionMap = new ConcurrentHashMap<>();
    public void setArticleAction(String requestId,AiArticleActionCommand command){
        articleActionMap.put(requestId,command);
    }
    public AiArticleActionCommand getArticleAction(String requestId){
        return articleActionMap.get(requestId);
    }


    public void clear(String requestId){
        navigateMap.remove(requestId);
        editorMap.remove(requestId);
        articleActionMap.remove(requestId);
    }

}
