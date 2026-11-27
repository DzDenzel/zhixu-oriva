package com.planlock.app.plugin;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.common.ApiResponse;
import com.planlock.common.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
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

    /** 不支持的插件目录来源（继承 IllegalArgumentException 以兼容既有直调单测，HTTP 层由局部异常处理器映射为明确 404/1003）。 */
    static final class UnknownPluginSourceException extends IllegalArgumentException {
        UnknownPluginSourceException() {
            super("Unknown plugin source");
        }
    }

    @GetMapping("/catalog/{source}")
    public Map<String, Object> catalog(@PathVariable String source) {
        String sourceType = switch (source) {
            case "official" -> "OFFICIAL";
            case "third-party" -> "THIRD_PARTY";
            default -> throw new UnknownPluginSourceException();
        };
        Path sourceRoot = pluginDirectory.resolve(source).normalize();
        Path catalogFile = sourceRoot.resolve("catalog.json");
        int catalogFormatVersion = 1;
        List<Map<String, Object>> plugins = new ArrayList<>();
        if (Files.isRegularFile(catalogFile)) {
            try {
                JsonNode document = mapper.readTree(catalogFile.toFile());
                if (document == null || !document.isObject()) {
                    throw new IllegalArgumentException("Catalog document must be an object");
                }
                if (document.has("catalogFormatVersion")) {
                    JsonNode formatVersion = document.get("catalogFormatVersion");
                    if (formatVersion == null || !formatVersion.isIntegralNumber() || !formatVersion.canConvertToInt()
                            || formatVersion.intValue() < 1) {
                        throw new IllegalArgumentException("Invalid catalogFormatVersion");
                    }
                    catalogFormatVersion = formatVersion.intValue();
                }
                JsonNode entries = document.get("plugins");
                if (entries == null || !entries.isArray()) throw new IllegalArgumentException("plugins must be an array");
                Set<String> ids = new HashSet<>();
                for (JsonNode entry : entries) {
                    if (entry == null || !entry.isObject()) throw new IllegalArgumentException("Catalog entry must be an object");
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
                    String author = optionalText(entry, "author", 200);
                    String homepage = optionalHttpUrl(entry, "homepage");
                    String repository = optionalHttpUrl(entry, "repository");
                    String license = optionalText(entry, "license", 200);
                    Path artifact = sourceRoot.resolve(fileName).normalize();
                    if (!artifact.startsWith(sourceRoot) || !Files.isRegularFile(artifact)) continue;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", id);
                    item.put("name", name);
                    item.put("version", version);
                    item.put("contractVersion", contractVersion.intValue());
                    item.put("entryPoint", entryPoint);
                    item.put("requiredCapabilities", requiredCapabilities);
                    item.put("description", description.textValue());
                    item.put("downloadUrl", "/api/v1/plugins/artifacts/" + source + "/" + fileName);
                    item.put("sha256", sha256(artifact));
                    if (author != null) item.put("author", author);
                    if (homepage != null) item.put("homepage", homepage);
                    if (repository != null) item.put("repository", repository);
                    if (license != null) item.put("license", license);
                    plugins.add(Collections.unmodifiableMap(item));
                }
            } catch (IOException | IllegalArgumentException error) {
                throw new IllegalStateException("Cannot read plugin catalog for " + source, error);
            }
        }
        return Map.of(
                "catalogFormatVersion", catalogFormatVersion,
                "sourceId", source,
                "sourceType", sourceType,
                "plugins", plugins);
    }

    private static String requiredText(JsonNode entry, String field) {
        JsonNode value = entry.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Missing catalog field: " + field);
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode entry, String field, int maxLength) {
        if (!entry.has(field)) return null;
        JsonNode value = entry.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("Invalid catalog field type: " + field);
        String trimmed = value.textValue().trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > maxLength || hasControlChars(trimmed)) {
            throw new IllegalArgumentException("Invalid catalog field value: " + field);
        }
        return trimmed;
    }

    private static String optionalHttpUrl(JsonNode entry, String field) {
        String trimmed = optionalText(entry, field, 2048);
        if (trimmed == null) return null;
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isWhitespace(trimmed.charAt(i))) {
                throw new IllegalArgumentException("Unsafe URL whitespace for " + field);
            }
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid URL for " + field, error);
        }
        String scheme = uri.getScheme();
        if (scheme == null) throw new IllegalArgumentException("Missing URL scheme for " + field);
        String lowerScheme = scheme.toLowerCase(Locale.ROOT);
        if (!lowerScheme.equals("http") && !lowerScheme.equals("https")) {
            throw new IllegalArgumentException("Unsafe URL scheme for " + field);
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Invalid URL authority for " + field);
        }
        return trimmed;
    }

    private static boolean hasControlChars(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }

    @ExceptionHandler(UnknownPluginSourceException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknownSource(UnknownPluginSourceException ignored) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(ApiResponse.error(ErrorCode.NOT_FOUND.getCode(), ErrorCode.NOT_FOUND.getDefaultMessage()));
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
