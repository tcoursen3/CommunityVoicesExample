package com.sleeware.communityvoices.services;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CommunityVoicesDocumentService {

    private static final Logger logger = LoggerFactory.getLogger(CommunityVoicesDocumentService.class);
    private static final String ALL_DOCUMENTS_QUERY = "community voices";
    private static final int DEFAULT_VECTOR_STORE_TOP_K = 10_000;
    private static final int MAX_CONTEXT_CHARS = 14_000;
    private static final Pattern WORD_SPLIT = Pattern.compile("[^a-z0-9+.#-]+");
    private static final Pattern NUMERIC_TOKEN = Pattern.compile("\\d+");
    private static final Pattern TOPIC_ROW = Pattern.compile("(?:^|,)\\s*(?:[-*]\\s*)?([^,()]+?)\\s*\\((\\d+)\\)");
    private static final Set<String> REPORT_SECTION_HEADINGS = Set.of(
            "Executive Summary",
            "Date Range Covered",
            "Most Active Authors",
            "Top 10 Topics",
            "Trending Topics",
            "Most Discussed",
            "Highest Engagement",
            "Community Signals",
            "Source Notes");
    private static final Set<String> DATA_GRID_SECTION_HEADINGS = Set.of(
            "Date Range Covered",
            "Most Active Authors",
            "Top 10 Topics",
            "Trending Topics",
            "Most Discussed",
            "Highest Engagement");
    private static final Set<String> STOP_WORDS = Set.of(
            "about", "after", "again", "also", "and", "any", "are", "because", "been", "before", "best",
            "but", "can", "could", "does", "for", "from", "get", "had", "has", "have", "help", "how",
            "into", "just", "keyboard", "keyboards", "keycap", "keycaps", "like", "looking", "make",
            "mechanical", "need", "new", "not", "one", "out", "post", "reddit", "should", "some",
            "than", "that", "the", "their", "them", "then", "there", "these", "they", "this", "use",
            "was", "what", "when", "where", "which", "who", "why", "with", "would", "you", "your");
    private static final Set<String> MONTH_WORDS = Set.of(
            "jan", "january", "feb", "february", "mar", "march", "apr", "april", "may", "jun", "june",
            "jul", "july", "aug", "august", "sep", "sept", "september", "oct", "october", "nov",
            "november", "dec", "december");

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final Clock clock;

    @Autowired
    public CommunityVoicesDocumentService(
            VectorStore vectorStore,
            ChatClient.Builder chatClientBuilder) {
        this(vectorStore, chatClientBuilder.build(), Clock.systemUTC());
    }

    CommunityVoicesDocumentService(VectorStore vectorStore, ChatClient chatClient, Clock clock) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClient;
        this.clock = clock;
    }

    public String GenerateDocument() {
        List<Document> documents = retrieveCommunityDocuments();
        String report = buildReport(documents);
        String htmlReport = renderHtmlReport(report);
        logger.info("Generated Community Voices document");
        return htmlReport;
    }

    List<Document> retrieveCommunityDocuments() {
        int topK = vectorStoreDocumentCount();
        if (topK <= 0) {
            return List.of();
        }

        SearchRequest request = SearchRequest.builder()
                .query(ALL_DOCUMENTS_QUERY)
                .topK(topK)
                .similarityThresholdAll()
                .build();

        Set<String> seenIds = new LinkedHashSet<>();
        List<Document> documents = new ArrayList<>();

        List<Document> resultList = vectorStore.similaritySearch(request);

        for (Document document : resultList ) {
            if (document == null) {
                continue;
            }
            if (document.getId() != null && !seenIds.add(document.getId())) {
                continue;
            }
            documents.add(document);
        }

        return documents;
    }

    private int vectorStoreDocumentCount() {
        if (vectorStore instanceof RedisVectorStore redisVectorStore) {
            long count = redisVectorStore.count();
            if (count > Integer.MAX_VALUE) {
                logger.warn(
                        "Redis Vector Store contains {} documents; retrieving the maximum supported {} documents",
                        count,
                        Integer.MAX_VALUE);
                return Integer.MAX_VALUE;
            }
            return (int) count;
        }

        return DEFAULT_VECTOR_STORE_TOP_K;
    }

    private String buildReport(List<Document> documents) {
        if (documents.isEmpty()) {
            return """
                    Community Voices Document
                    Generated: %s

                    Date Range Covered
                    No source post dates were available.

                    Executive Summary
                    No source posts were available to summarize.

                    Most Discussed
                    No comment metadata was available.

                    Highest Engagement
                    No engagement metadata was available.

                    No Redis Vector Store documents were found for the Community Voices report.
                    Run the community scrape job first, then generate the report again.
                    """.formatted(Instant.now(clock));
        }

        CommunityStats stats = summarize(documents);
        String context = buildRagContext(documents);
        String prompt = """
                Create a plain text Community Voices Document for the Reddit Community r/MechanicalKeyboards enhanced with the Redis Vector RAG context.

                Requirements:
                - Title the report "Community Voices Document".
                - Include generated timestamp and total source posts reviewed.
                - Add an "Executive Summary" section immediately after the title and generated timestamp.
                - Add a "Methodology" section immediately after "Executive Summary" explaining that the report is based on Redis Vector Store retrieval.
                - Add a "Dominant Themes" section
                - Add a "Pain Points" section
                - Include "Date Range Covered" after "Executive Summary" using the supplied date range metric.
                - Include "Most Active Authors" and identify which author posted the most.
                - Include "Top 10 Topics" with one topic per line in the format "- topic (count)".
                - Include "Trending Topics" with one topic per line in the format "- topic (count)".
                - Include "Most Discussed" using the supplied comment-count metrics.
                - Include "Highest Engagement" using the supplied engagement metrics.
                - Include "Community Signals" with notable needs, questions, complaints, or buying intent.
                - Include "Predictions" with potential future trends or developments based on the data.
                - Include "Source Notes" explaining that the report is based on Redis Vector Store retrieval.
                - Use concise, factual prose. Do not invent exact counts beyond the metrics supplied below.
                - Return HTML only. No Markdown tables.

                Metrics:
                %s

                Redis Vector RAG Context:
                %s
                """.formatted(stats.toPromptText(), context);

        String generated = chatClient.prompt()
                .system("You write concise community intelligence reports from retrieved Reddit post context.")
                .user(prompt)
                .call()
                .content();

        if (generated == null || generated.isBlank()) {
            throw new IllegalStateException("Ollama returned an empty Community Voices document");
        }

        return generated.strip() + System.lineSeparator();
    }

    CommunityStats summarize(List<Document> documents) {
        Map<String, Long> authorCounts = documents.stream()
                .map(document -> metadataValue(document, "author"))
                .filter(value -> !value.isBlank() && !"unknown".equalsIgnoreCase(value))
                .collect(Collectors.groupingBy(
                        author -> author,
                        LinkedHashMap::new,
                        Collectors.counting()));

        Map<String, Long> topicCounts = documents.stream()
                .flatMap(document -> extractTopicTokens(metadataValue(document, "title")).stream())
                .collect(Collectors.groupingBy(
                        topic -> topic,
                        LinkedHashMap::new,
                        Collectors.counting()));

        List<String> topAuthors = topEntries(authorCounts, 10);
        List<String> topTopics = topEntries(topicCounts, 10);
        List<String> trendingTopics = documents.stream()
                .sorted(Comparator.comparing(this::createdAt).reversed())
                .limit(20)
                .flatMap(document -> extractTopicTokens(metadataValue(document, "title")).stream())
                .collect(Collectors.groupingBy(
                        topic -> topic,
                        LinkedHashMap::new,
                        Collectors.counting()))
                .entrySet()
                .stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(10)
                .map(entry -> "%s (%d)".formatted(entry.getKey(), entry.getValue()))
                .toList();

        String mostActiveAuthor = authorCounts.entrySet()
                .stream()
                .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> "%s (%d posts)".formatted(entry.getKey(), entry.getValue()))
                .orElse("No author metadata available");

        String dateRangeCovered = dateRangeCovered(documents);
        List<String> mostDiscussed = mostDiscussed(documents, 5);
        List<String> highestEngagement = highestEngagement(documents, 5);

        return new CommunityStats(
                documents.size(),
                dateRangeCovered,
                mostActiveAuthor,
                topAuthors,
                topTopics,
                trendingTopics,
                mostDiscussed,
                highestEngagement);
    }

    private String dateRangeCovered(List<Document> documents) {
        List<Instant> dates = documents.stream()
                .map(this::createdAtOrNull)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        if (dates.isEmpty()) {
            return "No createdAt metadata available";
        }

        Instant earliest = dates.getFirst();
        Instant latest = dates.getLast();
        if (earliest.equals(latest)) {
            return earliest.toString();
        }

        return "%s to %s".formatted(earliest, latest);
    }

    private List<String> mostDiscussed(List<Document> documents, int limit) {
        return documents.stream()
                .map(document -> engagementSummary(document, commentCount(document)))
                .filter(summary -> summary.metricValue() > 0)
                .sorted(Comparator.comparingInt(EngagementSummary::metricValue).reversed()
                        .thenComparing(EngagementSummary::title))
                .limit(limit)
                .map(summary -> "%s (%d comments)".formatted(summary.title(), summary.commentCount()))
                .toList();
    }

    private List<String> highestEngagement(List<Document> documents, int limit) {
        return documents.stream()
                .map(document -> engagementSummary(document, engagementScore(document)))
                .filter(summary -> summary.metricValue() > 0)
                .sorted(Comparator.comparingInt(EngagementSummary::metricValue).reversed()
                        .thenComparing(EngagementSummary::title))
                .limit(limit)
                .map(summary -> "%s (engagement %d, %d upvotes, %d comments)".formatted(
                        summary.title(),
                        summary.metricValue(),
                        summary.upvotes(),
                        summary.commentCount()))
                .toList();
    }

    private EngagementSummary engagementSummary(Document document, int metricValue) {
        String title = metadataValue(document, "title");
        if (title.isBlank()) {
            title = Objects.toString(document.getId(), "Untitled source");
        }
        return new EngagementSummary(title, metricValue, numberMetadata(document, "ups"), commentCount(document));
    }

    private int engagementScore(Document document) {
        int score = numberMetadata(document, "score");
        if (score > 0) {
            return score;
        }

        return numberMetadata(document, "ups") - numberMetadata(document, "downs") + commentCount(document);
    }

    private int commentCount(Document document) {
        int comments = numberMetadata(document, "num_comments");
        if (comments > 0) {
            return comments;
        }

        return numberMetadata(document, "comments");
    }

    private int numberMetadata(Document document, String key) {
        Object value = document.getMetadata().get(key);
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.toString().trim()));
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private List<String> topEntries(Map<String, Long> counts, int limit) {
        return counts.entrySet()
                .stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(entry -> "%s (%d)".formatted(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<String> extractTopicTokens(String title) {
        if (title.isBlank()) {
            return List.of();
        }

        Set<String> tokens = new LinkedHashSet<>();
        for (String token : WORD_SPLIT.split(title.toLowerCase(Locale.ROOT))) {
            if (isIgnoredTopicToken(token)) {
                continue;
            }
            tokens.add(token);
        }
        return new ArrayList<>(tokens);
    }

    private boolean isIgnoredTopicToken(String token) {
        return token.length() < 3
                || STOP_WORDS.contains(token)
                || MONTH_WORDS.contains(token)
                || NUMERIC_TOKEN.matcher(token).matches();
    }

    private String buildRagContext(List<Document> documents) {
        StringBuilder context = new StringBuilder();

        for (int i = 0; i < documents.size() && context.length() < MAX_CONTEXT_CHARS; i++) {
            Document document = documents.get(i);
            String title = metadataValue(document, "title");
            String author = metadataValue(document, "author");
            String subreddit = metadataValue(document, "subreddit");
            String createdAt = metadataValue(document, "createdAt");
            String text = Objects.toString(document.getText(), "").replaceAll("\\s+", " ").trim();
            if (text.length() > 700) {
                text = text.substring(0, 700) + "...";
            }

            context.append("Source ").append(i + 1).append('\n')
                    .append("Subreddit: ").append(subreddit).append('\n')
                    .append("Author: ").append(author).append('\n')
                    .append("Created At: ").append(createdAt).append('\n')
                    .append("Title: ").append(title).append('\n')
                    .append("Post: ").append(text.isBlank() ? "[no post body]" : text).append("\n\n");
        }

        return context.toString();
    }

    private String metadataValue(Document document, String key) {
        Object value = document.getMetadata().get(key);
        String metadataValue = value == null ? "" : value.toString().trim();
        if (!metadataValue.isBlank()) {
            return metadataValue;
        }

        return textValue(document, key);
    }

    private String textValue(Document document, String key) {
        String text = Objects.toString(document.getText(), "");
        if (text.isBlank()) {
            return "";
        }

        if ("subreddit".equals(key)) {
            return text.lines()
                    .map(String::trim)
                    .filter(line -> line.startsWith("Reddit post from r/"))
                    .map(line -> line.substring("Reddit post from r/".length()).trim())
                    .findFirst()
                    .orElse("");
        }

        String label = switch (key) {
            case "author" -> "Author:";
            case "createdAt" -> "Created At:";
            case "title" -> "Title:";
            default -> null;
        };
        if (label == null) {
            return "";
        }

        return text.lines()
                .map(String::trim)
                .filter(line -> line.startsWith(label))
                .map(line -> line.substring(label.length()).trim())
                .findFirst()
                .orElse("");
    }

    private Instant createdAt(Document document) {
        Instant parsed = createdAtOrNull(document);
        return parsed == null ? Instant.EPOCH : parsed;
    }

    private Instant createdAtOrNull(Document document) {
        String value = metadataValue(document, "createdAt");
        if (value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    String renderHtmlReport(String report) {
        List<String> reportLines = report.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .toList();
        List<TopicTableRow> topTopics = topicRowsForSection(reportLines, "Top 10 Topics");
        List<TopicTableRow> trendingTopics = topicRowsForSection(reportLines, "Trending Topics");

        StringBuilder html = new StringBuilder();
        html.append("""
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Community Voices Document</title>
                  <style>
                    :root {
                      color-scheme: light;
                      --border: #d6d9de;
                      --header: #eef2f6;
                      --text: #20242a;
                      --muted: #5f6b7a;
                    }
                    body {
                      margin: 0;
                      background: #f7f8fa;
                      color: var(--text);
                      font-family: Arial, Helvetica, sans-serif;
                      line-height: 1.45;
                    }
                    main {
                      max-width: 960px;
                      margin: 0 auto;
                      padding: 40px 28px 56px;
                      background: #fff;
                      min-height: 100vh;
                    }
                    h1 {
                      margin: 0 0 18px;
                      font-size: 30px;
                      line-height: 1.15;
                    }
                    h2 {
                      margin: 28px 0 8px;
                      font-size: 18px;
                      line-height: 1.25;
                      border-bottom: 1px solid var(--border);
                      padding-bottom: 5px;
                    }
                    p {
                      margin: 0 0 8px;
                    }
                    ul {
                      margin: 0 0 12px 22px;
                      padding: 0;
                    }
                    li {
                      margin: 0 0 5px;
                    }
                    table {
                      width: 100%;
                      border-collapse: collapse;
                      margin: 10px 0 18px;
                      table-layout: fixed;
                    }
                    th,
                    td {
                      border: 1px solid var(--border);
                      padding: 8px 10px;
                      text-align: left;
                      vertical-align: top;
                      word-wrap: break-word;
                    }
                    th {
                      background: var(--header);
                      font-weight: 700;
                    }
                    td.count {
                      width: 72px;
                      color: var(--muted);
                      text-align: right;
                    }
                    .report-data-grid {
                      display: grid;
                      grid-template-columns: repeat(2, minmax(0, 1fr));
                      gap: 18px 22px;
                      margin: 24px 0 26px;
                    }
                    .report-data-section h2 {
                      margin-top: 0;
                    }
                    .report-data-section table {
                      margin-bottom: 0;
                    }
                    @media (max-width: 680px) {
                      main {
                        padding: 28px 16px 44px;
                      }
                      .report-data-grid {
                        grid-template-columns: 1fr;
                      }
                      th,
                      td {
                        padding: 7px 8px;
                      }
                    }
                  </style>
                </head>
                <body>
                  <main>
                """);

        boolean titleAdded = false;
        boolean topicTablesAdded = false;
        boolean dataGridAdded = false;
        boolean dataSectionAdded = false;
        boolean executiveSummaryAdded = false;
        boolean communitySignalsAdded = false;
        for (int i = 0; i < reportLines.size(); i++) {
            String line = reportLines.get(i);
            if (!titleAdded) {
                html.append("    <h1>").append(escapeHtml(line)).append("</h1>\n");
                titleAdded = true;
                continue;
            }

            if ("Top 10 Topics".equals(line) || "Trending Topics".equals(line)) {
                if (!topicTablesAdded) {
                    boolean useDataGrid = executiveSummaryAdded && !communitySignalsAdded;
                    if (useDataGrid) {
                        if (!dataGridAdded) {
                            html.append("    <div class=\"report-data-grid\">\n");
                            dataGridAdded = true;
                        }
                        if (dataSectionAdded) {
                            html.append("      </section>\n");
                            dataSectionAdded = false;
                        }
                    }
                    appendTopicTables(html, useDataGrid, topTopics, trendingTopics);
                    topicTablesAdded = true;
                }
                i = skipSection(reportLines, i);
                continue;
            }

            if (REPORT_SECTION_HEADINGS.contains(line)) {
                if ("Executive Summary".equals(line)) {
                    executiveSummaryAdded = true;
                }
                if ("Community Signals".equals(line)) {
                    communitySignalsAdded = true;
                }

                if (DATA_GRID_SECTION_HEADINGS.contains(line) && executiveSummaryAdded && !communitySignalsAdded) {
                    if (!dataGridAdded) {
                        html.append("    <div class=\"report-data-grid\">\n");
                        dataGridAdded = true;
                    }
                    if (dataSectionAdded) {
                        html.append("      </section>\n");
                    }
                    html.append("      <section class=\"report-data-section\">\n");
                    html.append("        <h2>").append(escapeHtml(line)).append("</h2>\n");
                    dataSectionAdded = true;
                } else {
                    if (dataSectionAdded) {
                        html.append("      </section>\n");
                        dataSectionAdded = false;
                    }
                    if (dataGridAdded) {
                        html.append("    </div>\n");
                        dataGridAdded = false;
                    }
                    html.append("    <h2>").append(escapeHtml(line)).append("</h2>\n");
                }
                continue;
            }

            if (line.startsWith("- ")) {
                html.append(dataSectionAdded ? "        " : "    ")
                        .append("<ul><li>").append(escapeHtml(line.substring(2).strip())).append("</li></ul>\n");
            } else {
                html.append(dataSectionAdded ? "        " : "    ")
                        .append("<p>").append(escapeHtml(line)).append("</p>\n");
            }
        }
        if (dataSectionAdded) {
            html.append("      </section>\n");
        }
        if (dataGridAdded) {
            html.append("    </div>\n");
        }

        html.append("""
                  </main>
                </body>
                </html>
                """);
        return html.toString();
    }

    private void appendTopicTables(
            StringBuilder html,
            boolean useDataGrid,
            List<TopicTableRow> topTopics,
            List<TopicTableRow> trendingTopics) {
        String sectionIndent = useDataGrid ? "      " : "    ";
        String contentIndent = useDataGrid ? "        " : "    ";
        String rowIndent = useDataGrid ? "            " : "        ";

        if (useDataGrid) {
            html.append(sectionIndent).append("<section class=\"report-data-section\">\n");
        }
        html.append(contentIndent).append("<h2>Topics</h2>\n");
        html.append(contentIndent).append("<table>\n");
        html.append(contentIndent).append("  <thead><tr><th>Top 10 Topics</th><th>Count</th><th>Trending Topics</th><th>Count</th></tr></thead>\n");
        html.append(contentIndent).append("  <tbody>\n");

        int rowCount = Math.max(topTopics.size(), trendingTopics.size());
        for (int i = 0; i < rowCount; i++) {
            TopicTableRow topTopic = i < topTopics.size() ? topTopics.get(i) : TopicTableRow.empty();
            TopicTableRow trendingTopic = i < trendingTopics.size() ? trendingTopics.get(i) : TopicTableRow.empty();
            html.append(rowIndent).append("<tr>")
                    .append("<td>").append(escapeHtml(topTopic.topic())).append("</td>")
                    .append("<td class=\"count\">").append(escapeHtml(topTopic.count())).append("</td>")
                    .append("<td>").append(escapeHtml(trendingTopic.topic())).append("</td>")
                    .append("<td class=\"count\">").append(escapeHtml(trendingTopic.count())).append("</td>")
                    .append("</tr>\n");
        }

        html.append(contentIndent).append("  </tbody>\n");
        html.append(contentIndent).append("</table>\n");
        if (useDataGrid) {
            html.append(sectionIndent).append("</section>\n");
        }
    }

    private int skipSection(List<String> reportLines, int headingIndex) {
        int nextIndex = headingIndex + 1;
        while (nextIndex < reportLines.size() && !REPORT_SECTION_HEADINGS.contains(reportLines.get(nextIndex))) {
            nextIndex++;
        }
        return nextIndex - 1;
    }

    List<TopicTableRow> topicRowsForSection(List<String> reportLines, String sectionHeading) {
        int sectionIndex = reportLines.indexOf(sectionHeading);
        if (sectionIndex < 0) {
            return List.of();
        }

        List<TopicTableRow> rows = new ArrayList<>();
        for (int i = sectionIndex + 1; i < reportLines.size() && !REPORT_SECTION_HEADINGS.contains(reportLines.get(i)); i++) {
            String line = reportLines.get(i);
            java.util.regex.Matcher matcher = TOPIC_ROW.matcher(line);
            boolean foundMatch = false;
            while (matcher.find()) {
                rows.add(new TopicTableRow(matcher.group(1).strip(), matcher.group(2).strip()));
                foundMatch = true;
            }
            if (!foundMatch && !line.isBlank()) {
                rows.add(new TopicTableRow(cleanTopicLine(line), ""));
            }
        }
        return rows;
    }

    private String cleanTopicLine(String line) {
        return line.replaceFirst("^[-*]\\s*", "").strip();
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    record CommunityStats(
            int sourcePostsReviewed,
            String dateRangeCovered,
            String mostActiveAuthor,
            List<String> topAuthors,
            List<String> topTopics,
            List<String> trendingTopics,
            List<String> mostDiscussed,
            List<String> highestEngagement) {

        String toPromptText() {
            return """
                    Source posts reviewed: %d
                    Date range covered: %s
                    Author posted the most: %s
                    Most active authors: %s
                    Top 10 topics: %s
                    Trending topics: %s
                    Most discussed: %s
                    Highest engagement: %s
                    """.formatted(
                    sourcePostsReviewed,
                    dateRangeCovered,
                    mostActiveAuthor,
                    emptyLabel(topAuthors),
                    emptyLabel(topTopics),
                    emptyLabel(trendingTopics),
                    emptyLabel(mostDiscussed),
                    emptyLabel(highestEngagement));
        }

        private static String emptyLabel(List<String> values) {
            return values.isEmpty() ? "No data available" : String.join(", ", values);
        }
    }

    private record EngagementSummary(String title, int metricValue, int upvotes, int commentCount) {
    }

    record TopicTableRow(String topic, String count) {
        private static TopicTableRow empty() {
            return new TopicTableRow("", "");
        }
    }
}
