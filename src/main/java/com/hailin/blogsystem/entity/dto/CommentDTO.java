package com.hailin.blogsystem.entity.dto;

import lombok.Data;

@Data
public class CommentDTO {

    private String content;
    private Long parentId;
    private String ip;
    private String ipLocation;

}
