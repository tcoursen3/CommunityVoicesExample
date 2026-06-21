package com.sleeware.communityvoices.entities.ollama;

import java.util.List;

public record OllamaClassificationResult(
        String category,
        double confidence,
        List<String> topics,
        String intent,
        String sentiment) {
}
