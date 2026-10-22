package com.acme.care.resident.web;

import java.util.List;
import java.util.UUID;

import com.acme.care.resident.service.ResidentDocumentService;
import com.acme.care.resident.service.ResidentDocumentService.DocumentInfo;
import com.acme.care.resident.service.ResidentQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ResidentDocumentController extends AbstractResidentController {

    private final ResidentDocumentService documents;

    public ResidentDocumentController(ResidentQueryService residents, ResidentDocumentService documents) {
        super(residents);
        this.documents = documents;
    }

    @GetMapping("/documents")
    public List<DocumentInfo> listDocuments(@PathVariable UUID residentId) {
        return documents.list(residentId);
    }

    @PostMapping(path = "/documents", consumes = "multipart/form-data")
    public DocumentInfo uploadDocument(@PathVariable UUID residentId,
                                       @RequestParam("file") MultipartFile file,
                                       @RequestParam("category") String category) {
        requireActive(residentId);
        return documents.store(residentId, category, file);
    }

    @DeleteMapping("/documents/{documentId}")
    public ResponseEntity<Void> deleteDocument(@PathVariable UUID residentId, @PathVariable UUID documentId) {
        documents.delete(residentId, documentId);
        return ResponseEntity.noContent().build();
    }
}
