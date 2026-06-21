package com.sleeware.communityvoices.clients;


import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class RedditClient {

    private static final String TOKEN_URL = "https://www.reddit.com/api/v1/access_token";
    private static final String API_BASE = "https://oauth.reddit.com";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final String clientId;
    private final String clientSecret;
    private final String userAgent;

    private String accessToken;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public RedditClient(String clientId, String clientSecret, String userAgent) {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.userAgent = userAgent;
    }

    public List<RedditPost> fetchSubredditPosts(
            String subreddit,
            ListingType listingType,
            int maxPosts
    ) throws IOException, InterruptedException {

        ensureAccessToken();

        List<RedditPost> posts = new ArrayList<>();

        String after = null;
        int count = 0;
        int limit = Math.min(100, maxPosts);

        while (posts.size() < maxPosts) {
            String url = buildListingUrl(subreddit, listingType, limit, after, count);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                accessToken = null;
                ensureAccessToken();
                continue;
            }

            if (response.statusCode() == 429) {
                throw new RuntimeException("Reddit rate limit hit: " + response.body());
            }

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException(
                        "Reddit API error " + response.statusCode() + ": " + response.body()
                );
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");
            JsonNode children = data.path("children");

            if (!children.isArray() || children.isEmpty()) {
                break;
            }

            for (JsonNode child : children) {
                JsonNode post = child.path("data");

                posts.add(new RedditPost(
                        post.path("name").asText(),              // fullname, e.g. t3_abc123
                        post.path("id").asText(),
                        post.path("subreddit").asText(),
                        post.path("title").asText(),
                        post.path("selftext").asText(""),
                        post.path("author").asText(""),
                        post.path("link_flair_text").asText(""),
                        post.path("score").asInt(),
                        post.path("num_comments").asInt(),
                        Instant.ofEpochSecond(post.path("created_utc").asLong()),
                        "https://www.reddit.com" + post.path("permalink").asText()
                ));

                if (posts.size() >= maxPosts) {
                    break;
                }
            }

            after = data.path("after").isNull() ? null : data.path("after").asText();
            count += children.size();

            if (after == null || after.isBlank()) {
                break;
            }
        }

        return posts;
    }

    private void ensureAccessToken() throws IOException, InterruptedException {
        if (accessToken != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(60))) {
            return;
        }

        String credentials = clientId + ":" + clientSecret;
        String basicAuth = Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        String body = "grant_type=client_credentials";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Authorization", "Basic " + basicAuth)
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException(
                    "Failed to get Reddit OAuth token: " + response.statusCode() + " " + response.body()
            );
        }

        JsonNode json = objectMapper.readTree(response.body());

        this.accessToken = json.path("access_token").asText();
        long expiresIn = json.path("expires_in").asLong(3600);

        this.tokenExpiresAt = Instant.now().plusSeconds(expiresIn);
    }

    private String buildListingUrl(
            String subreddit,
            ListingType listingType,
            int limit,
            String after,
            int count
    ) {
        StringBuilder url = new StringBuilder();

        url.append(API_BASE)
                .append("/r/")
                .append(encode(subreddit))
                .append("/")
                .append(listingType.path)
                .append("?limit=")
                .append(limit)
                .append("&raw_json=1")
                .append("&count=")
                .append(count);

        if (after != null && !after.isBlank()) {
            url.append("&after=").append(encode(after));
        }

        if (listingType.timeRange != null) {
            url.append("&t=").append(listingType.timeRange);
        }

        return url.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public enum ListingType {
        HOT("hot", null),
        NEW("new", null),
        TOP_DAY("top", "day"),
        TOP_WEEK("top", "week"),
        TOP_MONTH("top", "month"),
        TOP_YEAR("top", "year");

        private final String path;
        private final String timeRange;

        ListingType(String path, String timeRange) {
            this.path = path;
            this.timeRange = timeRange;
        }
    }

    public record RedditPost(
            String fullname,
            String id,
            String subreddit,
            String title,
            String body,
            String author,
            String flair,
            int score,
            int numComments,
            Instant createdUtc,
            String permalink
    ) {}
}