package com.sleeware.communityvoices.entities.ollama;

public record OllamaMessage(
    String role,
    String content
){}
