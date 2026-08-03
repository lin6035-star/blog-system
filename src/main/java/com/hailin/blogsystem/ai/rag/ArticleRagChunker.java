package com.hailin.blogsystem.ai.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;


@Component
public class ArticleRagChunker {

    /** 字符上限：段落合并、字符硬切兜底使用 */
    private static final int MAX_CHUNK_CHARS = 800;
    /** token 上限：TokenTextSplitter 使用 */
    private static final int CHUNK_TOKENS = 800;
    private static final int CHUNK_OVERLAP_CHARS = 100;
    private static final String PARAGRAPH_SEPARATOR_REGEX = "\\R\\s*\\R+";
    private static final String SENTENCE_BOUNDARIES = "。！？；.!?;";
    private static final String COMMA_BOUNDARIES = "，,";

    private final TokenTextSplitter tokenTextSplitter = TokenTextSplitter.builder()
            .withChunkSize(CHUNK_TOKENS)
            .withMinChunkSizeChars(100)
            .withMinChunkLengthToEmbed(5)
            .withMaxNumChunks(1000)
            .withKeepSeparator(true)
            .build();

    //切块器
    public List<String> chunk(String title,String summary,String content){
        String text = buildText(title,summary,content);

        if(text.isBlank()){
            return List.of();
        }

        return packSegments(splitParagraphs(text));
    }

    private String buildText(String title, String summary, String content){
        StringBuilder builder = new StringBuilder();

        appendIfNotBlank(builder, "标题", title);
        appendIfNotBlank(builder, "摘要", summary);
        appendIfNotBlank(builder, "正文", content);

        return builder.toString();
    }

    private void appendIfNotBlank(StringBuilder builder, String label, String value){
        if (value == null || value.isBlank()) {
            return;
        }

        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }

        builder.append(label).append("：").append(value.trim());

    }

    private List<String> splitParagraphs(String text) {
        String[] paragraphs = text.split(PARAGRAPH_SEPARATOR_REGEX);
        List<String> segments = new ArrayList<>();

        for (String paragraph : paragraphs) {
            addSegment(segments, paragraph);
        }

        return segments;
    }

    private List<String> splitLongSegment(String segment) {
        List<String> sentenceSegments = splitByBoundaries(segment, SENTENCE_BOUNDARIES);
        if (sentenceSegments.size() > 1) {
            return packSegments(sentenceSegments);
        }

        List<String> commaSegments = splitByBoundaries(segment, COMMA_BOUNDARIES);
        if (commaSegments.size() > 1) {
            return packSegments(commaSegments);
        }

        return splitByTokenTextSplitter(segment);
    }

    private List<String> splitByBoundaries(String text, String boundaries) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            current.append(ch);

            if (boundaries.indexOf(ch) >= 0) {
                addSegment(segments, current.toString());
                current.setLength(0);
            }
        }

        addSegment(segments, current.toString());

        return segments;
    }

    private List<String> splitByTokenTextSplitter(String text) {
        List<Document> documents = tokenTextSplitter.split(new Document(text));
        List<String> segments = new ArrayList<>();

        for (Document document : documents) {
            String segment = document.getText();
            // token 估算器按字符比例粗估（英文约 4 字符/token），
            // 英文长文本切出的块可能远超 MAX_CHUNK_CHARS，需按字符再兜底一次
            if (segment != null && segment.length() > MAX_CHUNK_CHARS) {
                segments.addAll(splitByCharacters(segment));
            } else {
                addSegment(segments, segment);
            }
        }

        // 极端兜底：token 切分无产出时按字符硬切
        if (segments.isEmpty()) {
            return splitByCharacters(text);
        }

        return segments;
    }

    private List<String> splitByCharacters(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;

        while(start < text.length()){
            int end = Math.min(start + MAX_CHUNK_CHARS,text.length());
            addSegment(chunks, text.substring(start,end));

            if(end == text.length()){
                break;
            }

            start = Math.max(start + 1, end - CHUNK_OVERLAP_CHARS);
        }

        return chunks;
    }

    private List<String> packSegments(List<String> segments) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                continue;
            }

            String normalizedSegment = segment.trim();

            if (normalizedSegment.length() > MAX_CHUNK_CHARS) {
                flushChunk(chunks, current);
                chunks.addAll(splitLongSegment(normalizedSegment));
                continue;
            }

            if (current.isEmpty()) {
                current.append(normalizedSegment);
                continue;
            }

            int nextLength = current.length() + 2 + normalizedSegment.length();
            if (nextLength <= MAX_CHUNK_CHARS) {
                current.append("\n\n").append(normalizedSegment);
            } else {
                flushChunk(chunks, current);
                current.append(normalizedSegment);
            }
        }

        flushChunk(chunks, current);

        return chunks;
    }

    private void flushChunk(List<String> chunks, StringBuilder current) {
        if (current.isEmpty()) {
            return;
        }

        chunks.add(current.toString());
        current.setLength(0);
    }

    private void addSegment(List<String> segments, String value) {
        if (value == null || value.isBlank()) {
            return;
        }

        segments.add(value.trim());
    }

}
