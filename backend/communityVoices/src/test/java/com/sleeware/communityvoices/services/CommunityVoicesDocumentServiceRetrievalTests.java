package com.sleeware.communityvoices.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

class CommunityVoicesDocumentServiceRetrievalTests {

    @TempDir
    private Path tempDir;

    @Test
    void writeReportCreatesHtmlFile() throws Exception {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-06-20T12:34:56Z"), ZoneOffset.UTC);
        CommunityVoicesDocumentService service =
                new CommunityVoicesDocumentService(null, null, fixedClock, tempDir);

        Path reportPath = service.writeReport("""
                Community Voices Document
                Generated: 2026-06-20T12:34:56Z

                Executive Summary
                - Strong interest in compact keyboard builds.
                - Buyers are comparing tactile switch options.

                Date Range Covered
                2026-06-19T10:00:00Z to 2026-06-20T12:00:00Z

                Most Active Authors
                alice (2 posts)

                Top 10 Topics
                - switches (4)
                - keycaps (3)

                Trending Topics
                - firmware (2)
                - wireless (1)

                Most Discussed
                - Switch help (24 comments)

                Highest Engagement
                - Keycap help (engagement 43, 45 upvotes, 12 comments)

                Community Signals
                Buyers are asking for quieter tactile switch recommendations.

                Source Notes
                Based on Redis Vector Store retrieval.
                """);

        assertThat(reportPath)
                .exists()
                .isRegularFile();
        assertThat(reportPath.getFileName().toString())
                .isEqualTo("community-voices-20260620-123456.html");
        assertThat(Files.readString(reportPath, StandardCharsets.UTF_8))
                .startsWith("<!doctype html>")
                .contains("<h1>Community Voices Document</h1>")
                .contains("<div class=\"report-data-grid\">")
                .contains("<section class=\"report-data-section\">")
                .contains("<h2>Date Range Covered</h2>")
                .contains("<h2>Topics</h2>")
                .contains("<th>Top 10 Topics</th>")
                .contains("<td>switches</td>")
                .contains("<td>firmware</td>")
                .contains("</div>\n    <h2>Community Signals</h2>");
    }

    @Test
    void topicRowsForSectionParsesBulletsAndCommaSeparatedMetrics() {
        CommunityVoicesDocumentService service =
                new CommunityVoicesDocumentService(null, null, Clock.systemUTC(), tempDir);

        List<CommunityVoicesDocumentService.TopicTableRow> rows = service.topicRowsForSection(List.of(
                "Community Voices Document",
                "Top 10 Topics",
                "- switches (4)",
                "keycaps (3), firmware (2)",
                "Trending Topics"), "Top 10 Topics");

        assertThat(rows)
                .containsExactly(
                        new CommunityVoicesDocumentService.TopicTableRow("switches", "4"),
                        new CommunityVoicesDocumentService.TopicTableRow("keycaps", "3"),
                        new CommunityVoicesDocumentService.TopicTableRow("firmware", "2"));
    }

    @Test
    void retrieveCommunityDocumentsReturnsEveryDocumentReturnedByVectorStore() {
        List<Document> sourceDocuments = IntStream.rangeClosed(1, 75)
                .mapToObj(index -> Document.builder()
                        .id("doc-%d".formatted(index))
                        .text("Document %d".formatted(index))
                        .build())
                .toList();
        CapturingVectorStore vectorStore = new CapturingVectorStore(sourceDocuments);
        CommunityVoicesDocumentService documentService =
                new CommunityVoicesDocumentService(vectorStore, null, Clock.systemUTC(), Path.of("target/test"));

        List<Document> documents = documentService.retrieveCommunityDocuments();

        assertThat(documents)
                .hasSize(75)
                .containsExactlyElementsOf(sourceDocuments);
        assertThat(vectorStore.requests)
                .singleElement()
                .satisfies(request -> {
                    assertThat(request.getTopK()).isEqualTo(10_000);
                    assertThat(request.getSimilarityThreshold()).isZero();
                });
    }

    @Test
    void summarizeUsesAuthorFromDocumentTextWhenRedisMetadataIsMissing() {
        CommunityVoicesDocumentService documentService =
                new CommunityVoicesDocumentService(new CapturingVectorStore(List.of()), null, Clock.systemUTC(), Path.of("target/test"));
        List<Document> documents = List.of(
                redditDocument("doc-1", "Build check", "alice", "2026-06-19T10:00:00Z"),
                redditDocument("doc-2", "Switch help", "bob", "2026-06-19T11:00:00Z"),
                redditDocument("doc-3", "Keycap help", "alice", "2026-06-19T12:00:00Z"));

        CommunityVoicesDocumentService.CommunityStats stats = documentService.summarize(documents);

        assertThat(stats.mostActiveAuthor()).isEqualTo("alice (2 posts)");
        assertThat(stats.dateRangeCovered()).isEqualTo("2026-06-19T10:00:00Z to 2026-06-19T12:00:00Z");
        assertThat(stats.topAuthors()).containsExactly("alice (2)", "bob (1)");
        assertThat(stats.toPromptText()).contains("Most active authors: alice (2), bob (1)");
        assertThat(stats.toPromptText()).contains("Date range covered: 2026-06-19T10:00:00Z to 2026-06-19T12:00:00Z");
    }

    @Test
    void summarizeIncludesEngagementSignalsFromMetadata() {
        CommunityVoicesDocumentService documentService =
                new CommunityVoicesDocumentService(new CapturingVectorStore(List.of()), null, Clock.systemUTC(), Path.of("target/test"));
        List<Document> documents = List.of(
                redditDocument("doc-1", "Build check", "alice", "2026-06-19T10:00:00Z",
                        Map.of("ups", 20, "downs", 1, "num_comments", 5, "score", 19)),
                redditDocument("doc-2", "Switch help", "bob", "2026-06-19T11:00:00Z",
                        Map.of("ups", 10, "downs", 0, "num_comments", 24, "score", 10)),
                redditDocument("doc-3", "Keycap help", "alice", "2026-06-19T12:00:00Z",
                        Map.of("ups", 45, "downs", 2, "comments", 12, "score", 43)));

        CommunityVoicesDocumentService.CommunityStats stats = documentService.summarize(documents);

        assertThat(stats.mostDiscussed())
                .containsExactly(
                        "Switch help (24 comments)",
                        "Keycap help (12 comments)",
                        "Build check (5 comments)");
        assertThat(stats.highestEngagement())
                .containsExactly(
                        "Keycap help (engagement 43, 45 upvotes, 12 comments)",
                        "Build check (engagement 19, 20 upvotes, 5 comments)",
                        "Switch help (engagement 10, 10 upvotes, 24 comments)");
        assertThat(stats.toPromptText())
                .contains("Most discussed: Switch help (24 comments), Keycap help (12 comments), Build check (5 comments)")
                .contains("Highest engagement: Keycap help (engagement 43, 45 upvotes, 12 comments)");
    }

    @Test
    void summarizeIgnoresMonthsYearsAndStandaloneNumbersInTopics() {
        CommunityVoicesDocumentService documentService =
                new CommunityVoicesDocumentService(new CapturingVectorStore(List.of()), null, Clock.systemUTC(), Path.of("target/test"));
        List<Document> documents = List.of(
                redditDocument("doc-1", "June 2026 65 GMK67 build", "alice", "2026-06-19T10:00:00Z"),
                redditDocument("doc-2", "March 2025 100 Boba U4 switches", "bob", "2026-06-19T11:00:00Z"));

        CommunityVoicesDocumentService.CommunityStats stats = documentService.summarize(documents);

        assertThat(stats.topTopics())
                .contains("gmk67 (1)", "boba (1)", "switches (1)")
                .doesNotContain("june (1)", "march (1)", "2026 (1)", "2025 (1)", "100 (1)");
        assertThat(stats.trendingTopics())
                .doesNotContain("june (1)", "march (1)", "2026 (1)", "2025 (1)", "100 (1)");
    }

    private static Document redditDocument(String id, String title, String author, String createdAt) {
        return redditDocument(id, title, author, createdAt, Map.of());
    }

    private static Document redditDocument(
            String id,
            String title,
            String author,
            String createdAt,
            Map<String, Object> metadata) {
        Map<String, Object> documentMetadata = new java.util.LinkedHashMap<>(metadata);
        documentMetadata.putIfAbsent("title", title);
        documentMetadata.putIfAbsent("author", author);
        documentMetadata.putIfAbsent("createdAt", createdAt);

        return Document.builder()
                .id(id)
                .text("""
                        Reddit post from r/MechanicalKeyboards
                        Title: %s
                        Author: %s
                        Created At: %s

                        Body text
                        """.formatted(title, author, createdAt).strip())
                .metadata(documentMetadata)
                .build();
    }

    private static class CapturingVectorStore implements VectorStore {

        private final List<Document> documents;
        private final List<SearchRequest> requests = new ArrayList<>();

        private CapturingVectorStore(List<Document> documents) {
            this.documents = documents;
        }

        @Override
        public void add(List<Document> documents) {
        }

        @Override
        public void delete(List<String> idList) {
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            requests.add(request);
            return documents.stream()
                    .limit(request.getTopK())
                    .toList();
        }
    }
}
