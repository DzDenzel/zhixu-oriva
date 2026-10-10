package com.planlock.app.plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public catalogs describe external signed artifacts; plugin bytecode is never packaged in the Android APK. */
@RestController
@RequestMapping("/api/v1/plugins")
public class PluginCatalogController {
    private final Path pluginDirectory;
    private final ObjectMapper mapper = new ObjectMapper();

    public PluginCatalogController(@Value("${planlock.plugins.directory:plugins}") String pluginDirectory) {
        this.pluginDirectory = Path.of(pluginDirectory).toAbsolutePath().normalize();
    }

    @GetMapping("/catalog/{source}")
    public Map<String, Object> catalog(@PathVariable String source) {
        String sourceType = switch (source) {
            case "official" -> "OFFICIAL";
            case "third-party" -> "THIRD_PARTY";
            default -> throw new IllegalArgumentException("Unknown plugin source");
        };
        Path sourceRoot = pluginDirectory.resolve(source).normalize();
        Path catalogFile = sourceRoot.resolve("catalog.json");
        List<Map<String, Object>> plugins = new ArrayList<>();
        if (Files.isRegularFile(catalogFile)) {
            try {
                JsonNode document = mapper.readTree(catalogFile.toFile());
                JsonNode entries = document == null ? null : document.get("plugins");
                if (entries == null || !entries.isArray()) throw new IllegalArgumentException("plugins must be an array");
                Set<String> ids = new HashSet<>();
                for (JsonNode entry : entries) {
                    String fileName = requiredText(entry, "artifact");
                    if (!fileName.matches("[A-Za-z0-9._-]+") || fileName.equals(".") || fileName.equals("..")) {
                        throw new IllegalArgumentException("Invalid catalog artifact name");
                    }
                    String id = requiredText(entry, "id");
                    if (!ids.add(id)) throw new IllegalArgumentException("Duplicate catalog plugin id");
                    String name = requiredText(entry, "name");
                    String version = requiredText(entry, "version");
                    String entryPoint = requiredText(entry, "entryPoint");
                    JsonNode contractVersion = entry.get("contractVersion");
                    if (contractVersion == null || !contractVersion.isIntegralNumber() || !contractVersion.canConvertToInt()
                            || contractVersion.intValue() < 1) throw new IllegalArgumentException("Invalid contractVersion");
                    JsonNode capabilities = entry.get("requiredCapabilities");
                    if (capabilities == null || !capabilities.isArray()) throw new IllegalArgumentException("requiredCapabilities must be an array");
                    List<String> requiredCapabilities = new ArrayList<>();
                    for (JsonNode capability : capabilities) {
                        if (!capability.isTextual() || capability.textValue().isBlank()) throw new IllegalArgumentException("Invalid capability");
                        requiredCapabilities.add(capability.textValue());
                    }
                    JsonNode description = entry.get("description");
                    if (description == null || !description.isTextual()) throw new IllegalArgumentException("description must be text");
                    Path artifact = sourceRoot.resolve(fileName).normalize();
                    if (!artifact.startsWith(sourceRoot) || !Files.isRegularFile(artifact)) continue;
                    plugins.add(Map.ofEntries(
                            Map.entry("id", id), Map.entry("name", name), Map.entry("version", version),
                            Map.entry("contractVersion", contractVersion.intValue()), Map.entry("entryPoint", entryPoint),
                            Map.entry("requiredCapabilities", requiredCapabilities), Map.entry("description", description.textValue()),
                            Map.entry("downloadUrl", "/api/v1/plugins/artifacts/" + source + "/" + fileName),
                            Map.entry("sha256", sha256(artifact))));
                }
            } catch (IOException | IllegalArgumentException error) {
                throw new IllegalStateException("Cannot read plugin catalog for " + source, error);
            }
        }
        return Map.of("sourceId", source, "sourceType", sourceType, "plugins", plugins);
    }

    private static String requiredText(JsonNode entry, String field) {
        JsonNode value = entry.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Missing catalog field: " + field);
        }
        return value.textValue();
    }

    @GetMapping("/artifacts/{source}/{fileName:.+}")
    public ResponseEntity<Resource> artifact(@PathVariable String source, @PathVariable String fileName) {
        if (!source.equals("official") && !source.equals("third-party")) return ResponseEntity.notFound().build();
        if (!fileName.matches("[A-Za-z0-9._-]+")) return ResponseEntity.badRequest().build();
        Path sourceRoot = pluginDirectory.resolve(source).normalize();
        Path file = sourceRoot.resolve(fileName).normalize();
        if (!file.startsWith(sourceRoot) || !Files.isRegularFile(file)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(file));
    }

    private static String sha256(Path file) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) value.append(String.format("%02X", item));
            return value.toString();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot hash plugin artifact", error);
        }
    }
}
