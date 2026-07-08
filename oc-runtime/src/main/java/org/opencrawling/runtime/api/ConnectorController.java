package org.opencrawling.runtime.api;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/connectors")
public class ConnectorController {

    private final List<ConnectorDTO> storage = new ArrayList<>();

    public ConnectorController() {
        // Initial mock data
        storage.add(new ConnectorDTO("FileSystem_Local", "Local File System", "repository", "org.opencrawling.crawler.connectors.filesystem.FileConnector", 10, new HashMap<>()));
        storage.add(new ConnectorDTO("Ollama_Output", "Ollama Vector Store", "output", "org.opencrawling.agents.output.ollama.OllamaOutputConnector", 10, new HashMap<>()));
    }

    @GetMapping("/{type}")
    public List<ConnectorDTO> getConnectors(@PathVariable String type) {
        return storage.stream()
                .filter(c -> c.type().equalsIgnoreCase(type))
                .toList();
    }

    @PostMapping
    public ResponseEntity<Void> createConnector(@RequestBody ConnectorDTO connector) {
        System.out.println("Saving connector: " + connector.name());
        // Simple duplicate check by name
        storage.removeIf(c -> c.name().equals(connector.name()));
        storage.add(connector);
        return ResponseEntity.status(201).build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteConnector(@PathVariable String id) {
        storage.removeIf(c -> c.name().equals(id));
        return ResponseEntity.ok().build();
    }

    public static record ConnectorDTO(
        String name, 
        String description, 
        String type, 
        String className, 
        int maxConnections, 
        Map<String, String> configuration
    ) {}
}
