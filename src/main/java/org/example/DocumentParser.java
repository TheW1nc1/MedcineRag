package org.example;

import dev.langchain4j.data.document.Document;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

@Component
public class DocumentParser {

    public Document parseDocument(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        String content = "";

        if (fileName == null) {
            throw new IOException("文件名不能为空");
        }

        String fileExtension = getFileExtension(fileName).toLowerCase();

        switch (fileExtension) {
            case "pdf":
                content = parsePdf(file.getInputStream());
                break;
            case "docx":
                content = parseDocx(file.getInputStream());
                break;
            case "doc":
                content = parseDoc(file.getInputStream());
                break;
            case "txt":
                content = parseTxt(file.getInputStream());
                break;
            default:
                throw new IOException("不支持的文件格式: " + fileExtension);
        }

        return new Document(content);
    }

    private String parsePdf(InputStream inputStream) throws IOException {
        try (PDDocument document = PDDocument.load(inputStream)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private String parseDocx(InputStream inputStream) throws IOException {
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            XWPFWordExtractor extractor = new XWPFWordExtractor(document);
            return extractor.getText();
        }
    }

    private String parseDoc(InputStream inputStream) throws IOException {
        try (HWPFDocument document = new HWPFDocument(inputStream)) {
            WordExtractor extractor = new WordExtractor(document);
            return extractor.getText();
        }
    }

    private String parseTxt(InputStream inputStream) throws IOException {
        byte[] bytes = inputStream.readAllBytes();
        
        // 尝试多种编码
        String[] encodings = {"UTF-8", "GBK", "GB2312", "ISO-8859-1"};
        
        for (String encoding : encodings) {
            try {
                String content = new String(bytes, encoding);
                // 检查是否包含乱码字符
                if (!content.contains("") && !content.contains("")) {
                    return content;
                }
            } catch (Exception e) {
                // 继续尝试下一种编码
            }
        }
        
        // 如果所有编码都失败，使用UTF-8作为默认编码
        return new String(bytes, "UTF-8");
    }

    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            return fileName.substring(lastDotIndex + 1);
        }
        return "";
    }
} 