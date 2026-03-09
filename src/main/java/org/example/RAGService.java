package org.example;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.annotation.PostConstruct;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static dev.langchain4j.data.document.loader.FileSystemDocumentLoader.loadDocument;

@Service
public class RAGService {

    private final String apiKey = "sk-407f7c2468c447d097971ef8419672e4";
    private final String apiUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";

    private final EmbeddingModel embeddingModel;
    private final OkHttpClient httpClient;
    private final Gson gson;

    // Thread-safe holders for knowledge base
    private final AtomicReference<String> knowledgeContent = new AtomicReference<>("");
    private final AtomicReference<List<TextSegment>> documents = new AtomicReference<>(new ArrayList<>());
    private final AtomicReference<List<float[]>> embeddings = new AtomicReference<>(new ArrayList<>());


    public RAGService() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS) // Important for streaming
                .build();
        this.gson = new Gson();
        this.embeddingModel = new AllMiniLmL6V2EmbeddingModel();
    }

    @PostConstruct
    public void init() {
        // Load the default document from resources on startup
        try {
            URL resourceUrl = getClass().getClassLoader().getResource("knowledge.txt");
            if (resourceUrl == null) {
                throw new RuntimeException("Resource not found: knowledge.txt");
            }
            
            // 使用自定义的DocumentParser来正确处理中文编码
            try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("knowledge.txt")) {
                if (inputStream == null) {
                    throw new RuntimeException("Cannot read knowledge.txt");
                }
                
                // 尝试多种编码读取文件
                byte[] bytes = inputStream.readAllBytes();
                String[] encodings = {"UTF-8", "GBK", "GB2312", "ISO-8859-1"};
                String content = null;
                
                for (String encoding : encodings) {
                    try {
                        content = new String(bytes, encoding);
                        // 检查是否包含乱码字符
                        if (!content.contains("") && !content.contains("")) {
                            break;
                        }
                    } catch (Exception e) {
                        // 继续尝试下一种编码
                    }
                }
                
                if (content == null) {
                    content = new String(bytes, "UTF-8");
                }
                
                Document document = new Document(content);
                loadKnowledge(document);
            }
        } catch (Exception e) {
            throw new RuntimeException("Error loading default knowledge file", e);
        }
    }

    public void loadKnowledge(Document document) throws IOException {
        // Store raw content
        this.knowledgeContent.set(document.text());

        // Split the document into segments
        List<TextSegment> segments = DocumentSplitters.recursive(300, 0).split(document);

        // Create new lists for thread-safe update
        List<TextSegment> newDocuments = new ArrayList<>();
        List<float[]> newEmbeddings = new ArrayList<>();

        // Store documents and their embeddings
        for (TextSegment segment : segments) {
            newDocuments.add(segment);
            Embedding embedding = embeddingModel.embed(segment.text()).content();
            newEmbeddings.add(embedding.vector());
        }

        // Atomically update the knowledge base
        this.documents.set(newDocuments);
        this.embeddings.set(newEmbeddings);
    }

    public String getKnowledgeContent() {
        return this.knowledgeContent.get();
    }

    // 添加新的知识库内容
    public void addKnowledge(String newContent) throws IOException {
        String currentContent = this.knowledgeContent.get();
        String updatedContent = currentContent + "\n\n" + newContent;
        
        // 创建新的Document并重新加载
        Document document = new Document(updatedContent);
        loadKnowledge(document);
    }

    // 删除知识库内容（通过关键词匹配）
    public void deleteKnowledge(String keyword) throws IOException {
        String currentContent = this.knowledgeContent.get();
        String[] lines = currentContent.split("\n");
        StringBuilder newContent = new StringBuilder();
        
        boolean shouldSkip = false;
        for (String line : lines) {
            // 如果当前行包含关键词，跳过这一行
            if (line.contains(keyword)) {
                shouldSkip = true;
                continue;
            }
            
            // 如果之前跳过了内容，且遇到空行，重置跳过标志
            if (shouldSkip && line.trim().isEmpty()) {
                shouldSkip = false;
                continue;
            }
            
            if (!shouldSkip) {
                newContent.append(line).append("\n");
            }
        }
        
        // 创建新的Document并重新加载
        Document document = new Document(newContent.toString().trim());
        loadKnowledge(document);
    }

    // 更新知识库内容
    public void updateKnowledge(String oldContent, String newContent) throws IOException {
        String currentContent = this.knowledgeContent.get();
        String updatedContent = currentContent.replace(oldContent, newContent);
        
        // 创建新的Document并重新加载
        Document document = new Document(updatedContent);
        loadKnowledge(document);
    }

    // 清空知识库
    public void clearKnowledge() throws IOException {
        Document document = new Document("");
        loadKnowledge(document);
    }

    public void chatStream(String userMessage, Consumer<String> tokenConsumer) {
        try {
            // Embed the user's question
            Embedding questionEmbedding = embeddingModel.embed(userMessage).content();
            float[] questionVector = questionEmbedding.vector();

            // Find the most relevant segments using cosine similarity from the current state
            List<float[]> currentEmbeddings = this.embeddings.get();
            List<Map.Entry<Integer, Double>> similarities = new ArrayList<>();
            for (int i = 0; i < currentEmbeddings.size(); i++) {
                double similarity = cosineSimilarity(questionVector, currentEmbeddings.get(i));
                similarities.add(new AbstractMap.SimpleEntry<>(i, similarity));
            }

            // Sort by similarity and get top 3
            similarities.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

            List<TextSegment> currentDocuments = this.documents.get();
            StringBuilder context = new StringBuilder();
            for (int i = 0; i < Math.min(3, similarities.size()); i++) {
                int docIndex = similarities.get(i).getKey();
                context.append(currentDocuments.get(docIndex).text()).append("\n\n");
            }

            // Build the prompt
            String prompt = String.format("基于以下信息回答问题:\n%s\n\n问题: %s", context.toString(), userMessage);

            // Call the Aliyun API with streaming
            callAliyunAPIStream(prompt, tokenConsumer);

        } catch (Exception e) {
            tokenConsumer.accept("抱歉，处理您的问题时出现了错误: " + e.getMessage());
        }
    }

    private void callAliyunAPIStream(String prompt, Consumer<String> tokenConsumer) throws IOException {
        JsonObject requestBody = new JsonObject();
        JsonObject input = new JsonObject();
        JsonObject parameters = new JsonObject();
        JsonObject message = new JsonObject();

        message.addProperty("role", "user");
        message.addProperty("content", prompt);
        
        parameters.addProperty("stream", true);

        JsonArray messages = new JsonArray();
        messages.add(message);

        input.add("messages", messages);
        requestBody.add("input", input);
        requestBody.addProperty("model", "qwen-plus");
        requestBody.add("parameters", parameters);

        RequestBody body = RequestBody.create(
            gson.toJson(requestBody),
            MediaType.get("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("API call failed: " + response.code() + " " + response.body().string());
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                String line;
                String previousContent = "";
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        String data = line.substring(5).trim();
                        JsonObject jsonResponse = gson.fromJson(data, JsonObject.class);

                        if (jsonResponse.has("output") && jsonResponse.getAsJsonObject("output").has("text")) {
                            String fullContent = jsonResponse.getAsJsonObject("output").get("text").getAsString();
                            String newContent = fullContent.substring(previousContent.length());
                            tokenConsumer.accept(newContent);
                            previousContent = fullContent;
                        }
                    }
                }
            }
        }
    }

    private double cosineSimilarity(float[] vectorA, float[] vectorB) {
        if (vectorA == null || vectorB == null || vectorA.length != vectorB.length) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += vectorA[i] * vectorA[i];
            normB += vectorB[i] * vectorB[i];
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
} 