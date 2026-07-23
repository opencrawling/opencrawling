/*
 * Copyright © 2026 the original author or authors (piergiorgio@apache.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencrawling.runtime.api.copilot;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class TemplateGenerationCopilot {

    private static final Logger log = LoggerFactory.getLogger(TemplateGenerationCopilot.class);

    private final Map<String, ChatModel> chatModels;
    private final String copilotEngine;

    @Autowired
    public TemplateGenerationCopilot(
            @Autowired(required = false) Map<String, ChatModel> chatModels,
            @Value("${spring.ai.copilot.engine:ollama}") String copilotEngine
    ) {
        this.chatModels = chatModels != null ? chatModels : Map.of();
        this.copilotEngine = copilotEngine;
        log.info("Initialized TemplateGenerationCopilot with default engine: '{}'. Registered models: {}", 
                copilotEngine, this.chatModels.keySet());
    }

    public TemplateCopilotResponse generate(SchemaContextRequest request) {
        ChatModel chatModel = selectChatModel();
        
        if (chatModel == null) {
            log.warn("Spring AI ChatModel is not available for engine '{}'. Falling back to deterministic template generation.", copilotEngine);
            return generateFallback(request);
        }

        try {
            ChatClient chatClient = ChatClient.builder(chatModel).build();
            
            String schemaDescription = request.fields().stream()
                    .map(f -> String.format("- Field Name: '%s', Type: '%s', Description: '%s'", f.name(), f.type(), f.description()))
                    .collect(Collectors.joining("\n"));

            String systemPrompt = """
                    You are an expert Enterprise AI Data Engineer specializing in Retrieval-Augmented Generation (RAG).
                    Your task is to take a structured data schema (fields, types, and descriptions) and generate a natural language Mustache template.
                    
                    Mustache Template Rules:
                    1. Use variables in double curly braces, e.g., {{variable_name}}.
                    2. Write a single, cohesive, readable paragraph that narrates the data to create a high-quality text chunk for vector embeddings.
                    3. Do not output raw JSON or key-value listings in the template. Convert them into a human-readable description (e.g. "On {{date}}, the {{region}} region sold products...").
                    
                    You must also generate a mock dataset (as a JSON map) that matches all variables used in your template, assigning realistic sample values based on the field descriptions and types.
                    
                    Return the result strictly as a JSON object matching this structure:
                    {
                      "template": "A descriptive Mustache template paragraph using double curly braces...",
                      "mockData": {
                         "key1": "value1",
                         "key2": 123.45
                      }
                    }
                    """;

            String userMessage = String.format(
                    "Connector Type: %s\nSchema Fields:\n%s",
                    request.connectorType(),
                    schemaDescription
            );

            log.info("Invoking Spring AI ChatModel for template auto-narrativization copilot.");
            
            // Execute Spring AI call with explicit timeout or fallback on delay/failure
            java.util.concurrent.CompletableFuture<TemplateCopilotResponse> future = 
                java.util.concurrent.CompletableFuture.supplyAsync(() -> 
                    chatClient.prompt()
                            .system(systemPrompt)
                            .user(userMessage)
                            .call()
                            .entity(TemplateCopilotResponse.class)
                );

            return future.get(8, java.util.concurrent.TimeUnit.SECONDS);

        } catch (Exception e) {
            log.warn("Spring AI ChatModel call timed out or failed ({}). Falling back to deterministic template generation.", e.getMessage());
            return generateFallback(request);
        }
    }

    private ChatModel selectChatModel() {
        if (chatModels.isEmpty()) {
            return null;
        }
        if ("openai".equalsIgnoreCase(copilotEngine)) {
            ChatModel model = chatModels.get("openAiChatModel");
            if (model != null) {
                return model;
            }
        }
        // Default / fallback to Ollama
        ChatModel model = chatModels.get("ollamaChatModel");
        if (model != null) {
            return model;
        }
        // Fallback to any available ChatModel
        return chatModels.values().iterator().next();
    }

    private TemplateCopilotResponse generateFallback(SchemaContextRequest request) {
        StringBuilder templateBuilder = new StringBuilder();
        templateBuilder.append("Structured document ingested from ").append(request.connectorType()).append(": ");
        
        Map<String, Object> mockData = new HashMap<>();
        
        for (int i = 0; i < request.fields().size(); i++) {
            SchemaContextRequest.FieldDto field = request.fields().get(i);
            templateBuilder.append(field.name()).append(" is {{").append(field.name()).append("}}");
            if (i < request.fields().size() - 1) {
                templateBuilder.append(", ");
            } else {
                templateBuilder.append(".");
            }
            
            mockData.put(field.name(), getMockValue(field.type(), field.name()));
        }
        
        return new TemplateCopilotResponse(templateBuilder.toString(), mockData);
    }

    private Object getMockValue(String type, String name) {
        if (type == null) {
            return "sample_value";
        }
        String lowerType = type.toLowerCase();
        if (lowerType.contains("int") || lowerType.contains("long")) {
            return 42;
        } else if (lowerType.contains("double") || lowerType.contains("float") || lowerType.contains("num")) {
            return 123.45;
        } else if (lowerType.contains("bool")) {
            return true;
        } else if (lowerType.contains("date") || lowerType.contains("time")) {
            return "2026-07-23T12:00:00Z";
        } else {
            return "Sample " + name;
        }
    }
}
