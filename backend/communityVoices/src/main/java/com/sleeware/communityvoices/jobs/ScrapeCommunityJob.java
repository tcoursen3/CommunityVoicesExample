package com.sleeware.communityvoices.jobs;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sleeware.communityvoices.classifiers.RedditPostClassifier;
import com.sleeware.communityvoices.entities.ollama.OllamaClassificationResult;
import com.sleeware.communityvoices.entities.reddit.RedditApiPostData;
import com.sleeware.communityvoices.entities.reddit.RedditApiResponse;
import com.sleeware.communityvoices.entities.reddit.RedditListing;
import com.sleeware.communityvoices.entities.reddit.RedditPost;
import com.sleeware.communityvoices.services.CommunityVoicesDocumentService;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.Builder;
import org.springframework.web.client.RestClientException;

@Component
@ConditionalOnProperty(name = "community-voices.jobs.scrape-community.enabled", havingValue = "true", matchIfMissing = true)
public class ScrapeCommunityJob {

    private static final Logger logger = LoggerFactory.getLogger(ScrapeCommunityJob.class);
    private static final int REDDIT_PAGE_LIMIT = 100;
    private static final int MAX_REDDIT_PAGES = 10;
    private static final int MAX_VECTOR_DOCUMENT_TEXT_LENGTH = 3_000;
    private static final String REDDIT_USER_AGENT =
            "PostmanRuntime/7.54.0";

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final VectorStore vectorStore;
    private final Clock clock;
    private final RestClient redditClient;
    private final RedditPostClassifier redditPostClassifier;
    private final CommunityVoicesDocumentService communityVoicesDocumentService;

    @Autowired
    public ScrapeCommunityJob(
            VectorStore vectorStore,
            RedditPostClassifier redditPostClassifier,
            CommunityVoicesDocumentService communityVoicesDocumentService,
            Builder restClientBuilder) {
        this.vectorStore = vectorStore;
        this.redditPostClassifier = redditPostClassifier;
        this.communityVoicesDocumentService = communityVoicesDocumentService;

        this.redditClient = restClientBuilder
                .baseUrl("https://www.reddit.com")
                .defaultHeader(HttpHeaders.USER_AGENT, REDDIT_USER_AGENT)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9")
                .defaultHeader(HttpHeaders.CACHE_CONTROL, "no-cache")
                .defaultHeader("Cookie","csv=2; edgebucket=6DkEuyAC80F2K9feoF; loid=000000002gz8tx9fdi.2.1782067344176.Z0FBQUFBQnFPRENRSFZ5TFZWOWJkaVJFU05VTy12ZW43UFYtWEszbGVpQjdqNDRraUhSVGVLS0cyNkFRVmZTZmpwTXZhMW1Qdm40cEVCbElSQzVfZjFLb1R0QnZ4bGRFYVRvUU11UmtXVEZBSGstWjBadENiOGF0ZHA0OE83bVhBVkhDdlRwbV96cEs; session_tracker=dqnrbinobcnbhornne.0.1782068273824.Z0FBQUFBQnFPRFF6NGk2dVlQWGRMYmxwd25aS2x5UWVveWVKYTRQeVBWS0g5WWZ3Q25pQ1Z4SnBjbUpqZFRMVURYMHdTZ1c5T1RYSk96ZHVIZmF2LXFTRnlPVWpiOVV1WU40S0RXS0RBZDlkU3RrenhNZ29vekhyWTBCRmRWNnZLYnczeVU2MFBFX2U")
                .build();
        this.clock = Clock.systemUTC();

    }

    @Scheduled(fixedDelayString = "${community-voices.jobs.scrape-community.fixed-delay:PT5H}")
    public void scrapeCommunity() throws Exception {
        if (!running.compareAndSet(false, true)) {
            logger.info("Skipping community scrape job because a previous execution is still running");
            return;
        }

        long startedAt = System.nanoTime();
        logger.info("Starting community scrape job");

        try {
            parseRedditChannel( "r/MechanicalKeyboards", 7 );

            // Generate Report
            String reportHtml = communityVoicesDocumentService.GenerateDocument();
            logger.info("Generated Community Voices report HTML ({} chars)", reportHtml.length());

        } finally {
            running.set(false);

            long durationMinutes = TimeUnit.NANOSECONDS.toMinutes(System.nanoTime() - startedAt);
            logger.info("Finished community scrape job in {} minutes", durationMinutes);
        }
    }

    List<String> parseRedditChannel(String channelName, int numberOfDays) throws Exception {
        if (numberOfDays <= 0) {
            return List.of();
        }


        String subreddit = normalizeRedditChannelName(channelName);
        Instant cutoff = Instant.now(clock).minus(numberOfDays, ChronoUnit.DAYS);
        Set<String> topics = new LinkedHashSet<>();
        String after = null;

        int recordsFound = 0;


        for (int page = 0; page < MAX_REDDIT_PAGES; page++) {
            RedditListing listing = fetchRedditListing(subreddit, after);
            recordsFound += listing.children().size();
            if (listing.children().isEmpty()) {
                break;
            }

            boolean reachedCutoff = false;

            List<Document> redditTopics = new ArrayList<>();
            for (RedditPost post : listing.children()) {

                String logString = String.format("Date : %s Author %s", post.createdAt().toString(), post.author());
                logger.info( logString );

                if (post.createdAt().isBefore(cutoff)) {
                    reachedCutoff = true;
                    continue;
                }

                Document document = toAiDocument( subreddit, post );
                redditTopics.add( document );

                // TODO : Move Up Classify to before and add the classify results to redis metadata
                classifyPost(post);

                if (!post.title().isBlank()) {
                    topics.add(post.title().trim());
                }
            }

            if (!redditTopics.isEmpty()) {

                Document tempDoc = null;
                try {

                    vectorStore.add( redditTopics );

                    //for( Document doc : redditTopics ){
                    //    tempDoc = doc;
                    //    List<Document> tempList = new ArrayList<>();
                    //    tempList.add( doc );
                    //    vectorStore.add( tempList );
                    // }
                } catch ( Exception ex ){
                    logger.error( String.format( "Size : %d", tempDoc.getText().length() ));
                    logger.error( ex.getMessage() );
                }

                logger.info("Added {} Reddit posts from r/{} to Redis Vector Store", redditTopics.size(), subreddit);
            }

            after = listing.after();
            if (reachedCutoff || after == null || after.isBlank()) {
                break;
            }
        }


        logger.info( "Records Found : %d".formatted(recordsFound));

        return new ArrayList<>(topics);
    }

    private List<Document> toAiDocuments(String subreddit, RedditPost post){
        String text = redditDocumentText(subreddit, post);
        List<String> chunks = splitText(text, MAX_VECTOR_DOCUMENT_TEXT_LENGTH);
        List<Document> documents = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            String documentId = "r:%s:chunk:%d".formatted(post.id(), i + 1);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", "reddit");
            metadata.put("subreddit", subreddit);
            metadata.put("redditId", post.id());
            metadata.put("title", post.title());
            metadata.put("author", post.author());
            metadata.put("createdAt", post.createdAt().toString());
            metadata.put("ups", post.ups());
            metadata.put("downs", post.downs());
            metadata.put("comments", post.num_comments());
            metadata.put("chunkIndex", i + 1);
            metadata.put("chunkCount", chunks.size());

            documents.add(Document.builder()
                    .id(documentId)
                    .text(chunks.get(i))
                    .metadata(metadata)
                    .build());
        }

        return documents;
    }

    private Document toAiDocument(String subreddit, RedditPost post){

        String documentId = "r:%s".formatted(post.id() );

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "reddit");
        metadata.put("subreddit", subreddit);
        metadata.put("redditId", post.id());
        metadata.put("title", post.title());
        metadata.put("author", post.author());
        metadata.put("createdAt", post.createdAt().toString());
        metadata.put("ups", post.ups() != null ? post.ups() : 0 );
        metadata.put("downs", post.downs() != null ? post.downs() : 0 );
        metadata.put("num_comments", post.num_comments() != null ? post.num_comments() : 0 );
        metadata.put("score", post.score() != null ? post.score() : 0 );
        metadata.put("views", post.view_count() != null ? post.view_count() : 0 );

        return Document.builder()
                    .id(documentId)
                    .text( post.title() )
                    .metadata(metadata)
                    .build();

    }

    private String redditDocumentText(String subreddit, RedditPost post) {
        String body = post.selftext().isBlank() ? "[no post body]" : post.selftext();

        return """
                Reddit post from r/%s
                Title: %s
                Author: %s
                Created At: %s

                %s
                """.formatted(subreddit, post.title(), post.author(), post.createdAt(), body).strip();
    }

    private List<String> splitText(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return List.of(text);
        }

        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < text.length(); start += maxLength) {
            chunks.add(text.substring(start, Math.min(start + maxLength, text.length())));
        }
        return chunks;
    }

    private void classifyPost(RedditPost post) {
        if (redditPostClassifier == null) {
            return;
        }

        try {
            OllamaClassificationResult result = redditPostClassifier.classify(post.selftext());
            logger.debug("Classified Reddit post {} as {}", post.id(), result.category());
        } catch (Exception ex) {
            logger.warn("Unable to classify Reddit post {}", post.id(), ex);
        }
    }

    private RedditListing fetchRedditListing(String subreddit, String after) {
        try {
            String afterToken = after;
            RedditApiResponse response = redditClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder
                                .path("/r/{subreddit}/new.json")
                                .queryParam("limit", REDDIT_PAGE_LIMIT)
                                .queryParam("raw_json", 1);
                        if (afterToken != null && !afterToken.isBlank()) {
                            builder.queryParam("after", afterToken);
                        }
                        return builder.build(subreddit);
                    })
                    .retrieve()
                    .body(RedditApiResponse.class);

            if (response == null || response.data() == null || response.data().children() == null) {
                return new RedditListing(null, List.of());
            }

            List<RedditPost> posts = response.data().children().stream()
                    .filter(child -> child.data() != null)
                    .map(child -> new RedditPost(
                            redditPostId(child.data()),
                            child.data().title() == null ? "" : child.data().title(),
                            child.data().author() == null? "" : child.data().author(),
                            child.data().selftext() == null ? "" : child.data().selftext(),
                            Instant.ofEpochSecond((long) child.data().created_utc()),
                            child.data().ups(),
                            child.data().downs(),
                            child.data().view_count(),
                            child.data().score(),
                            child.data().num_comments()))
                    .toList();

            return new RedditListing(response.data().after(), posts);
        } catch (RestClientException ex) {
            logger.warn("Unable to fetch posts from r/{}", subreddit, ex);
            return new RedditListing(null, List.of());
        }
    }

    private String redditPostId(RedditApiPostData postData) {
        String fallbackKey = "%s:%s".formatted( postData.id(),  postData.created_utc());
        return fallbackKey;
    }

    private String normalizeRedditChannelName(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            throw new IllegalArgumentException("channelName must not be blank");
        }

        String normalized = channelName.trim();
        int redditPathIndex = normalized.indexOf("/r/");
        if (redditPathIndex >= 0) {
            normalized = normalized.substring(redditPathIndex + 3);
        } else if (normalized.startsWith("r/")) {
            normalized = normalized.substring(2);
        }

        int pathEnd = normalized.indexOf('/');
        if (pathEnd >= 0) {
            normalized = normalized.substring(0, pathEnd);
        }

        if (!normalized.matches("[A-Za-z0-9_]{3,21}")) {
            throw new IllegalArgumentException("Invalid reddit channel name: " + channelName);
        }

        return normalized;
    }

}
