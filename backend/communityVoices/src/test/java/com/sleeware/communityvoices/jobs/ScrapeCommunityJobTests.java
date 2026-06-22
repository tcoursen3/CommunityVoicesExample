package com.sleeware.communityvoices.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.ArrayList;
import java.util.List;

import com.sleeware.communityvoices.classifiers.RedditPostClassifier;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ScrapeCommunityJobTests {

    @Test
    void parseRedditChannelReturnsRecentPostTitles() throws Exception {
        RestClient.Builder restClientBuilder = RestClient.builder();

        CapturingVectorStore vectorStore = new CapturingVectorStore();
        RedditPostClassifier redditPostClassifier = new RedditPostClassifier();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();

        ScrapeCommunityJob job = new ScrapeCommunityJob(
                vectorStore,
                redditPostClassifier,
                null,
                restClientBuilder
                );

        server.expect(requestTo("https://www.reddit.com/r/java/new.json?limit=100&raw_json=1"))
                .andExpect(header(HttpHeaders.USER_AGENT,
                        "PostmanRuntime/7.54.0"))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9"))
                .andRespond(withSuccess("""
                        {
                          "data": {
                            "after": "t3_next",
                            "children": [
                              {
                                "data": {
                                  "title": "Recent topic",
                                  "created_utc": 1782082800
                                }
                              },
                              {
                                "data": {
                                  "title": "Recent topic",
                                  "created_utc": 1782079200
                                }
                              },
                              {
                                "data": {
                                  "title": "Older topic",
                                  "created_utc": 1781800000
                                }
                              }
                            ]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        List<String> topics = job.parseRedditChannel("r/java", 2);

        assertThat(topics).containsExactly("Recent topic");
        assertThat(vectorStore.documents()).hasSize(2);
        assertThat(vectorStore.documents())
                .extracting(document -> document.getMetadata().get("subreddit"))
                .containsOnly("java");
        assertThat(vectorStore.documents())
                .extracting(Document::getText)
                .containsExactly("Recent topic", "Recent topic");
        server.verify();
    }

    @Test
    void parseRedditChannelAddsLongPostBodiesAsSingleVectorDocument() throws Exception {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        CapturingVectorStore vectorStore = new CapturingVectorStore();
        ScrapeCommunityJob job = new ScrapeCommunityJob(vectorStore, null, null, restClientBuilder);

        String longBody = "Long body sentence. ".repeat(500);

        server.expect(requestTo("https://www.reddit.com/r/java/new.json?limit=100&raw_json=1"))
                .andExpect(header(HttpHeaders.USER_AGENT,
                        "PostmanRuntime/7.54.0"))
                .andRespond(withSuccess("""
                        {
                          "data": {
                            "children": [
                              {
                                "data": {
                                  "id": "abc123",
                                  "title": "Recent long topic",
                                  "author": "test-author",
                                  "selftext": "%s",
                                  "created_utc": 1782082800
                                }
                              }
                            ]
                          }
                        }
                        """.formatted(longBody), MediaType.APPLICATION_JSON));

        job.parseRedditChannel("r/java", 2);

        assertThat(vectorStore.documents()).hasSize(1);
        assertThat(vectorStore.documents())
                .extracting(Document::getText)
                .containsExactly("Recent long topic");
        assertThat(vectorStore.documents())
                .extracting(document -> document.getMetadata().get("author"))
                .containsOnly("test-author");
        assertThat(vectorStore.documents())
                .extracting(document -> document.getMetadata().get("redditId"))
                .containsOnly("abc123:1.7820828E9");
        server.verify();
    }

    @Test
    void parseRedditChannelKeepsMultiplePostsFromSameAuthor() throws Exception {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        CapturingVectorStore vectorStore = new CapturingVectorStore();
        ScrapeCommunityJob job = new ScrapeCommunityJob(vectorStore, null, null, restClientBuilder);

        server.expect(requestTo("https://www.reddit.com/r/java/new.json?limit=100&raw_json=1"))
                .andExpect(header(HttpHeaders.USER_AGENT,
                        "PostmanRuntime/7.54.0"))
                .andRespond(withSuccess("""
                        {
                          "data": {
                            "children": [
                              {
                                "data": {
                                  "id": "post1",
                                  "name": "t3_post1",
                                  "title": "First topic",
                                  "author": "same-author",
                                  "created_utc": 1782082800
                                }
                              },
                              {
                                "data": {
                                  "id": "post2",
                                  "name": "t3_post2",
                                  "title": "Second topic",
                                  "author": "same-author",
                                  "created_utc": 1782079200
                                }
                              }
                            ]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        job.parseRedditChannel("r/java", 2);

        assertThat(vectorStore.documents()).hasSize(2);
        assertThat(vectorStore.documents())
                .extracting(document -> document.getMetadata().get("author"))
                .containsOnly("same-author");
        assertThat(vectorStore.documents())
                .extracting(Document::getId)
                .containsExactly("r:post1:1.7820828E9", "r:post2:1.7820792E9");
        server.verify();
    }

    private static class CapturingVectorStore implements VectorStore {

        private final List<Document> documents = new ArrayList<>();

        @Override
        public void add(List<Document> documents) {
            this.documents.addAll(documents);
        }

        @Override
        public void delete(List<String> idList) {
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            return List.of();
        }

        private List<Document> documents() {
            return documents;
        }

    }
}
