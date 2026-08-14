package com.hailin.blogsystem.config;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Locale;

/**
 * Jackson 配置：仅将 ID 类字段（Long，字段名以 id 结尾）序列化为 String。
 * <p>
 * 背景：雪花 ID 是 19 位，超出 JavaScript Number 安全整数范围（2^53-1 ≈ 16 位），
 * 不转字符串会丢精度。但计数类字段（likeCount、total 等）必须保持 number，
 * 前端对其有算术运算（如 +1），转字符串会导致字符串拼接（"5" + 1 = "51"）。
 * </p>
 */
@Configuration
public class JacksonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer longIdToStringCustomizer() {
        return builder -> builder.postConfigurer(mapper -> {
            SimpleModule module = new SimpleModule();
            module.setSerializerModifier(new BeanSerializerModifier() {
                @Override
                public List<BeanPropertyWriter> changeProperties(
                        SerializationConfig config,
                        BeanDescription beanDesc,
                        List<BeanPropertyWriter> beanProperties) {
                    for (BeanPropertyWriter writer : beanProperties) {
                        if (writer.getType().hasRawClass(Long.class)
                                && writer.getName().toLowerCase(Locale.ROOT).endsWith("id")) {
                            writer.assignSerializer(ToStringSerializer.instance);
                        }
                    }
                    return beanProperties;
                }
            });
            mapper.registerModule(module);
        });
    }
}
