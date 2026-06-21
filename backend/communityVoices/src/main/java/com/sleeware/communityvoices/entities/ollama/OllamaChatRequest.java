package com.sleeware.communityvoices.entities.ollama;

import java.util.List;

public record OllamaChatRequest(
    String model,
    boolean stream,
    Object format,
    List<OllamaMessage> messages
){}
