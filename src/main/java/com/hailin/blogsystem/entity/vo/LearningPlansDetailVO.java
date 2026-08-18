package com.hailin.blogsystem.entity.vo;

import com.hailin.blogsystem.entity.LearningPlans;
import lombok.Data;

import java.util.List;

@Data
public class LearningPlansDetailVO {

    LearningPlans plan;
    List<StageProgress> stages;  // 每个 stage 带 tasks 解析后的 List 和 done 计数
    int doneTasks;
    int totalTasks;      // 聚合进度

    @Data
    public static class StageProgress {
        private Long id;
        private Integer orderNum;
        private String title;
        private List<TaskItem> tasks;
    }

    @Data
    public static class TaskItem {
        private String title;
        private boolean done;
    }
}
