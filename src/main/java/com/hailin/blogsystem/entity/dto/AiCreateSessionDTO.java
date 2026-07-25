package com.hailin.blogsystem.entity.dto;

import lombok.Data;

@Data
public class AiCreateSessionDTO {
    private String title;  //title可传也可不传，不传就是新会话
}
