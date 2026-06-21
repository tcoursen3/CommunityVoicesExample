package com.sleeware.communityvoices.controllers;

import com.sleeware.communityvoices.services.CommunityVoicesDocumentService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReportController {

    private final CommunityVoicesDocumentService communityVoicesDocumentService;

    public ReportController(CommunityVoicesDocumentService communityVoicesDocumentService) {
        this.communityVoicesDocumentService = communityVoicesDocumentService;
    }

    @GetMapping(value = "/generate-report", produces = MediaType.TEXT_HTML_VALUE)
    public String generateReport() {
        return communityVoicesDocumentService.generateDocument();
    }
}
