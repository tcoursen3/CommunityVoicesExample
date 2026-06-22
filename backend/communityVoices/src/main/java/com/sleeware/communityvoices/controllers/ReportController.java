package com.sleeware.communityvoices.controllers;

import com.sleeware.communityvoices.services.CommunityVoicesDocumentService;
import com.sleeware.communityvoices.services.CommunityVoicesNonRagDocumentService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReportController {

    private final CommunityVoicesDocumentService communityVoicesDocumentService;
    private final CommunityVoicesNonRagDocumentService communityVoicesNonRagDocumentService;

    public ReportController(CommunityVoicesDocumentService communityVoicesDocumentService,
                            CommunityVoicesNonRagDocumentService communityVoicesNonRagDocumentService) {
        this.communityVoicesDocumentService = communityVoicesDocumentService;
        this.communityVoicesNonRagDocumentService = communityVoicesNonRagDocumentService;
    }

    @GetMapping(value = "/generate-report", produces = MediaType.TEXT_HTML_VALUE)
    public String generateReport() {
        return communityVoicesDocumentService.GenerateDocument();
    }

    @GetMapping(value = "/generate-non-rag-report", produces = MediaType.TEXT_HTML_VALUE)
    public String generateNonRagReport() {
        return communityVoicesNonRagDocumentService.GenerateDocument();
    }
}
