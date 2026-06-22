package com.sleeware.communityvoices.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "community-voices.jobs.scrape-community.enabled=false",
        "community-voices.documents.output-directory=target/community-voices-report-test"
})
@EnabledIfSystemProperty(
        named = "community.voices.generateReport",
        matches = "true",
        disabledReason = "Manual report generator. Requires existing Redis Vector Store data and configured Ollama access.")
class CommunityVoicesDocumentServiceTests {

    @Autowired
    private CommunityVoicesDocumentService documentService;

    @Autowired
    private CommunityVoicesNonRagDocumentService nonRagDocumentService;

    @Test
    @Tag("manual")
    void generateReportFromExistingData() throws Exception {

        String reportString = documentService.GenerateDocument();
        assertThat(reportString)
                .isNotNull();

    }

    @Test
    @Tag("manual")
    void generateNonRagReportFromExistingData() throws Exception {

        String reportString = nonRagDocumentService.GenerateDocument();
        assertThat(reportString)
                .isNotNull();
    }
}
