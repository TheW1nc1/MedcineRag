package org.example;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RAGController {

    @Autowired
    private RAGService ragService;

    @Autowired
    private DocumentParser documentParser;

    @PostMapping("/chat")
    public SseEmitter chat(@RequestBody Map<String, String> request) {
        String userMessage = request.get("message");
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        new Thread(() -> {
            try {
                ragService.chatStream(userMessage, token -> {
                    try {
                        emitter.send(SseEmitter.event().data(token));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }).start();

        return emitter;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, String>> uploadKnowledge(
            @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "请选择要上传的文件。"));
        }
        try {
            Document document = documentParser.parseDocument(file);
            ragService.loadKnowledge(document);
            return ResponseEntity.ok(Map.of("message", "知识库更新成功: " + file.getOriginalFilename()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "知识库更新失败: " + e.getMessage()));
        }
    }

    @GetMapping("/knowledge")
    public ResponseEntity<Map<String, String>> getKnowledge() {
        return ResponseEntity.ok(Map.of("content", ragService.getKnowledgeContent()));
    }

    // 添加知识库内容
    @PostMapping("/knowledge/add")
    public ResponseEntity<Map<String, String>> addKnowledge(@RequestBody Map<String, String> request) {
        String content = request.get("content");
        if (content == null || content.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "内容不能为空"));
        }
        
        try {
            ragService.addKnowledge(content);
            return ResponseEntity.ok(Map.of("message", "知识库内容添加成功"));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "添加失败: " + e.getMessage()));
        }
    }

    // 删除知识库内容
    @DeleteMapping("/knowledge/delete")
    public ResponseEntity<Map<String, String>> deleteKnowledge(@RequestBody Map<String, String> request) {
        String keyword = request.get("keyword");
        if (keyword == null || keyword.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "关键词不能为空"));
        }
        
        try {
            ragService.deleteKnowledge(keyword);
            return ResponseEntity.ok(Map.of("message", "知识库内容删除成功"));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "删除失败: " + e.getMessage()));
        }
    }

    // 更新知识库内容
    @PutMapping("/knowledge/update")
    public ResponseEntity<Map<String, String>> updateKnowledge(@RequestBody Map<String, String> request) {
        String oldContent = request.get("oldContent");
        String newContent = request.get("newContent");
        
        if (oldContent == null || oldContent.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "原内容不能为空"));
        }
        if (newContent == null || newContent.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "新内容不能为空"));
        }
        
        try {
            ragService.updateKnowledge(oldContent, newContent);
            return ResponseEntity.ok(Map.of("message", "知识库内容更新成功"));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "更新失败: " + e.getMessage()));
        }
    }

    // 清空知识库
    @DeleteMapping("/knowledge/clear")
    public ResponseEntity<Map<String, String>> clearKnowledge() {
        try {
            ragService.clearKnowledge();
            return ResponseEntity.ok(Map.of("message", "知识库已清空"));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "清空失败: " + e.getMessage()));
        }
    }
} 