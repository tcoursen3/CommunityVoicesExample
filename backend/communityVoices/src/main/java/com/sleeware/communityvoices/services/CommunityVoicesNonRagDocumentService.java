package com.sleeware.communityvoices.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class CommunityVoicesNonRagDocumentService {

    private static final Logger logger = LoggerFactory.getLogger(CommunityVoicesNonRagDocumentService.class);
    private final ChatClient chatClient;

    CommunityVoicesNonRagDocumentService(ChatClient.Builder chatClientBuilder){
        this.chatClient = chatClientBuilder.build();
    }

    public String GenerateDocument(){
        return buildReport();
    }

    private String buildReport() {


        String prompt = """
                Generate a Community Voices Document based on the last week of activity at Reddit r/MechanicalKeyboards group. Report should be in HTML format.  Should include an Exective Summary, Domminant Themes, Representative Community Voices, Pain Points, Predictions
                 Rules:
                 - Return only the HTML document
               """;

        String generated = chatClient.prompt()
                .system("You write concise community voices documents")
                .user(prompt)
                .call()
                .content();

        if (generated == null || generated.isBlank()) {
            throw new IllegalStateException("Ollama returned an empty Community Voices document");
        }

        return generated.strip() + System.lineSeparator();
    }

}
