package com.hailin.blogsystem.service;

import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.PageContextDTO;

public interface AiIntentClassifier {
    AiIntent classify(String message, PageContextDTO pageContext);
}
