package com.sleeware.communityvoices.classifiers;

import com.sleeware.communityvoices.entities.ollama.OllamaChatRequest;
import com.sleeware.communityvoices.entities.ollama.OllamaChatResponse;
import com.sleeware.communityvoices.entities.ollama.OllamaClassificationResult;
import com.sleeware.communityvoices.entities.ollama.OllamaMessage;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

@Service
public class RedditPostClassifier {
    private static final String OLLAMA_URL = "http://sleeai.sleeware.com:11434/api/chat";
    private static final String MODEL = "llama3.1:latest";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public OllamaClassificationResult classify(String postText) throws Exception {

        String prompt = """
                Classify this mechanical keyboard Reddit post.
                
                Allowed categories:
                - Gaming Keyboards
                - Office Keyboards
                - Silent Keyboards
                - Silent Switches
                - Manufacturers
                - Switches
                - Keycaps
                - Keyboard Builds
                - PCB
                - Cases
                - Stabilizers
                - Lubing
                - Group Buys
                - Vendors
                - Troubleshooting
                - Recommendations
                - Desk Setup
                - Wireless
                - Firmware
                - Accessories
                - Other
                
                Topic rules:
                - Do not include month names as topics, such as January, Feb, March, or Dec.
                - Do not include standalone years as topics, such as 2024, 2025, or 2026.
                - Do not include standalone numbers without product or keyboard context.
                - Keep meaningful keyboard terms that contain numbers when the number is part of the term, such as 60%%, 75%%, GMK67, Boba U4, or MX2A.
                                
                Return JSON only with this shape:
                {
                  "category": "Switches",
                  "confidence": 0.0,
                  "topics": ["silent tactile", "Boba U4"],
                  "intent": "Recommendation Request",
                  "sentiment": "Neutral"
                }
                
                Post:
                %s
                """.formatted(postText);

        OllamaChatRequest requestBody = new OllamaChatRequest(
                MODEL,
                false,
                "json",
                List.of(
                        new OllamaMessage("system", "You are a strict JSON classifier for mechanical keyboard Reddit posts."),
                        new OllamaMessage("user", prompt)
                )
        );

        String json = mapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("Ollama error: " + response.body());
        }

        OllamaChatResponse ollamaResponse =
                mapper.readValue(response.body(), OllamaChatResponse.class);

        String content = ollamaResponse.message().content();

        return mapper.readValue(content, OllamaClassificationResult.class);

    }
}
