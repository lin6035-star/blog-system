package com.hailin.blogsystem.ai.workflow;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 质量防线工具：大纲式输出检测 + 检查问题转模型反馈文本。
 * 三个 Workflow 的 auto-retry 共用；requirement 参数承载各业务的输出要求文案。
 */
@Component
public class WorkflowQualitySupport {

    //大纲式输出检测：标题多 + 列表多 + 缺少长段落
    public boolean looksLikeOutline(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return false;
        }

        String[] lines = markdown.split("\\R");

        int headingCount = 0;
        int listCount = 0;
        int longParagraphCount = 0;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("#")) {
                headingCount++;
            } else if (trimmed.startsWith("- ") || trimmed.matches("^\\d+\\.\\s+.*")) {
                listCount++;
            } else if (trimmed.length() >= 80) {
                longParagraphCount++;
            }
        }

        return headingCount >= 3 && listCount >= 5 && longParagraphCount < 3;
    }

    //List<?> → List<String>，过滤空项
    public List<String> getStringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        return List.of();
    }

    //检查问题 → 模型反馈文本（auto-retry 用）
    public String buildQualityFeedbackForModel(Map<String, Object> qualityCheck, String requirement) {
        List<String> issues = getStringList(qualityCheck.get("issues"));
        List<String> suggestions = getStringList(qualityCheck.get("suggestions"));

        StringBuilder feedback = new StringBuilder();
        feedback.append("系统质量检查未通过，请重新生成").append(requirement).append("。\n");

        if (!issues.isEmpty()) {
            feedback.append("必须修复的问题：\n");
            for (String issue : issues) {
                feedback.append("- ").append(issue).append("\n");
            }
        }

        if (!suggestions.isEmpty()) {
            feedback.append("建议改进：\n");
            for (String suggestion : suggestions) {
                feedback.append("- ").append(suggestion).append("\n");
            }
        }

        return feedback.toString();
    }
}
