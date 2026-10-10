package com.planlock.app.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.JarFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginCatalogControllerTest {
    @TempDir Path directory;

    @Test
    void discoversSecondDirectionFromDeployedCatalogWithoutControllerChanges() throws Exception {
        Path official = Files.createDirectories(directory.resolve("official"));
        Files.writeString(official.resolve("focus-advice-1.0.0.plp"), "declaration-only catalog fixture, not an installable package");
        Files.writeString(official.resolve("catalog.json"), """
                {"plugins":[{"artifact":"focus-advice-1.0.0.plp","id":"focus-advice",
                "name":"专注建议","version":"1.0.0","contractVersion":1,
                "entryPoint":"example.focus.AdvicePlugin","requiredCapabilities":[],
                "description":"根据观测记录提供专注建议，不执行应用限制。"}]}
                """);
        PluginCatalogController controller = new PluginCatalogController(directory.toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plugins = (List<Map<String, Object>>) controller.catalog("official").get("plugins");
        assertEquals(1, plugins.size());
        assertEquals("focus-advice", plugins.getFirst().get("id"));
        assertEquals("专注建议", plugins.getFirst().get("name"));
        assertEquals(List.of(), plugins.getFirst().get("requiredCapabilities"));
        assertNotNull(controller.artifact("official", "focus-advice-1.0.0.plp").getBody());
    }

    @Test
    void catalogHashAlwaysMatchesTheExternalArtifact() throws Exception {
        Path official = Files.createDirectories(directory.resolve("official"));
        Files.writeString(official.resolve("force-blocker-1.0.0.plp"), "signed-plugin-placeholder");
        writeCatalog(official, "force-blocker", "force-blocker-1.0.0.plp");
        PluginCatalogController controller = new PluginCatalogController(directory.toString());

        Map<String, Object> catalog = controller.catalog("official");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plugins = (List<Map<String, Object>>) catalog.get("plugins");
        assertEquals(1, plugins.size());
        assertEquals(64, plugins.getFirst().get("sha256").toString().length());
        assertNotNull(controller.artifact("official", "force-blocker-1.0.0.plp").getBody());
        assertEquals(404, controller.artifact("official", "missing.plp").getStatusCode().value());
    }

    @Test
    void thirdPartyCatalogExposesAnIndependentlySignedDownloadableArtifact() throws Exception {
        Path thirdParty = Files.createDirectories(directory.resolve("third-party"));
        Files.writeString(thirdParty.resolve("community-force-blocker-1.0.0.plp"), "third-party-signed-placeholder");
        writeCatalog(thirdParty, "community-force-blocker", "community-force-blocker-1.0.0.plp");
        PluginCatalogController controller = new PluginCatalogController(directory.toString());

        Map<String, Object> catalog = controller.catalog("third-party");
        assertEquals("third-party", catalog.get("sourceId"));
        assertEquals("THIRD_PARTY", catalog.get("sourceType"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plugins = (List<Map<String, Object>>) catalog.get("plugins");
        assertEquals(1, plugins.size());
        assertEquals("community-force-blocker", plugins.getFirst().get("id"));
        assertEquals(64, plugins.getFirst().get("sha256").toString().length());
        assertNotNull(controller.artifact("third-party", "community-force-blocker-1.0.0.plp").getBody());
    }

    @Test
    void deploymentChangesAreReadWithoutRestartAndMissingArtifactsAreOmitted() throws Exception {
        Path official = Files.createDirectories(directory.resolve("official"));
        PluginCatalogController controller = new PluginCatalogController(directory.toString());
        assertEquals(List.of(), controller.catalog("official").get("plugins"));
        writeCatalog(official, "focus-advice", "focus.plp");
        assertEquals(List.of(), controller.catalog("official").get("plugins"));
        Files.writeString(official.resolve("focus.plp"), "declaration-only fixture");
        @SuppressWarnings("unchecked")
        var plugins = (List<Map<String, Object>>) controller.catalog("official").get("plugins");
        assertEquals(1, plugins.size());
        String hashBefore = plugins.getFirst().get("sha256").toString();
        Files.writeString(official.resolve("focus.plp"), "changed deployment bytes");
        assertTrue(!controller.catalog("official").toString().contains(hashBefore));
    }

    @Test
    void invalidCatalogAndArtifactPathsFailClosed() throws Exception {
        Path official = Files.createDirectories(directory.resolve("official"));
        PluginCatalogController controller = new PluginCatalogController(directory.toString());
        Files.writeString(official.resolve("catalog.json"), "{\"plugins\":{}}");
        assertThrows(IllegalStateException.class, () -> controller.catalog("official"));
        writeCatalog(official, "focus-advice", "../outside.plp");
        assertThrows(IllegalStateException.class, () -> controller.catalog("official"));
        assertEquals(400, controller.artifact("official", "../outside.plp").getStatusCode().value());
        assertEquals(404, controller.artifact("unknown", "sample.plp").getStatusCode().value());
        assertThrows(IllegalArgumentException.class, () -> controller.catalog("unknown"));
    }

    @Test
    void multipleDirectionEntriesAndEditedMetadataComeFromOnlyTheCatalog() throws Exception {
        Path official = Files.createDirectories(directory.resolve("official"));
        Files.writeString(official.resolve("one.plp"), "first declaration fixture");
        Files.writeString(official.resolve("two.plp"), "second declaration fixture");
        ObjectMapper mapper = new ObjectMapper();
        writeCatalog(official, "one", "one.plp");
        var document = mapper.readTree(official.resolve("catalog.json").toFile());
        var entries = (com.fasterxml.jackson.databind.node.ArrayNode) document.get("plugins");
        var second = entries.get(0).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) second).put("id", "two").put("artifact", "two.plp").put("name", "Edited advice");
        entries.add(second);
        Files.writeString(official.resolve("catalog.json"), mapper.writeValueAsString(document));
        PluginCatalogController controller = new PluginCatalogController(directory.toString());
        @SuppressWarnings("unchecked")
        var plugins = (List<Map<String, Object>>) controller.catalog("official").get("plugins");
        assertEquals(2, plugins.size());
        assertEquals("Edited advice", plugins.get(1).get("name"));
        assertEquals("/api/v1/plugins/artifacts/official/two.plp", plugins.get(1).get("downloadUrl"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) second).put("id", "one");
        entries.set(1, second);
        Files.writeString(official.resolve("catalog.json"), mapper.writeValueAsString(document));
        assertThrows(IllegalStateException.class, () -> controller.catalog("official"));
    }

    @Test
    void malformedMetadataIsRejectedInsteadOfCreatingAnUnusableEntry() throws Exception {
        Path official = Files.createDirectories(directory.resolve("official"));
        PluginCatalogController controller = new PluginCatalogController(directory.toString());
        for (String malformed : List.of("", "null", "{", "{\"plugins\":[{}]}",
                "{\"plugins\":[null]}")) {
            Files.writeString(official.resolve("catalog.json"), malformed);
            assertThrows(IllegalStateException.class, () -> controller.catalog("official"), malformed);
        }
        for (String field : List.of("id", "name", "version", "entryPoint", "contractVersion", "requiredCapabilities", "description")) {
            writeCatalog(official, "sample", "sample.plp");
            var mapper = new ObjectMapper();
            var document = mapper.readTree(official.resolve("catalog.json").toFile());
            ((com.fasterxml.jackson.databind.node.ObjectNode) document.get("plugins").get(0)).putNull(field);
            Files.writeString(official.resolve("catalog.json"), mapper.writeValueAsString(document));
            assertThrows(IllegalStateException.class, () -> controller.catalog("official"), field);
        }
    }

    @Test
    void deployedSignedPackagesKeepBothSourceContractsSignersAndActualDownloadHashes() throws Exception {
        Path deployed = Path.of("../plugins").toAbsolutePath().normalize();
        PluginCatalogController controller = new PluginCatalogController(deployed.toString());
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> trusted = Map.of(
                "official", "C59E81658F310829B423BE242DC328B376CBCE1BC61C1FF231316112412B8C44",
                "third-party", "71DCCF31C42DEE6804AA18200C5BE1E37B077C599E54925FDEC921F16E4BEF67");
        for (String source : List.of("official", "third-party")) {
            var catalog = controller.catalog(source);
            assertEquals(Set.of("sourceId", "sourceType", "plugins"), catalog.keySet());
            @SuppressWarnings("unchecked")
            var plugins = (List<Map<String, Object>>) catalog.get("plugins");
            assertEquals(source.equals("official") ? 2 : 1, plugins.size());
            for (var item : plugins) {
            assertEquals(Set.of("id", "name", "version", "contractVersion", "entryPoint", "requiredCapabilities", "description", "downloadUrl", "sha256"), item.keySet());
            String url = item.get("downloadUrl").toString();
            Path file = controller.artifact(source, url.substring(url.lastIndexOf('/') + 1)).getBody().getFile().toPath();
            assertEquals(HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))), item.get("sha256"));
            try (JarFile jar = new JarFile(file.toFile(), true)) {
                var payloads = jar.stream().filter(entry -> !entry.isDirectory() && !entry.getName().startsWith("META-INF/")).toList();
                var signedManifest = mapper.readTree(jar.getInputStream(jar.getJarEntry("plugin.json")));
                String codeEntry = signedManifest.path("type").asText("dex").equals("js") ? "index.js" : "classes.dex";
                assertTrue(payloads.stream().anyMatch(entry -> entry.getName().equals(codeEntry)));
                for (var entry : payloads) {
                    try (var input = jar.getInputStream(entry)) { input.readAllBytes(); }
                    assertNotNull(entry.getCertificates());
                    assertEquals(trusted.get(source), HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(entry.getCertificates()[0].getEncoded())));
                }
                var manifest = mapper.readTree(jar.getInputStream(jar.getJarEntry("plugin.json")));
                assertEquals(source, manifest.get("sourceId").asText());
                assertEquals(catalog.get("sourceType"), manifest.get("sourceType").asText());
                // Existing discovery descriptions differ from signed package descriptions;
                // installation treats the signed manifest as authoritative.
                for (String field : List.of("id", "name", "version", "entryPoint")) {
                    assertEquals(item.get(field), manifest.get(field).asText());
                }
                assertEquals(item.get("contractVersion"), manifest.get("contractVersion").asInt());
                assertEquals(mapper.valueToTree(item.get("requiredCapabilities")), manifest.get("requiredCapabilities"));
            }
            }
        }
    }

    private static void writeCatalog(Path source, String id, String artifact) throws Exception {
        Files.writeString(source.resolve("catalog.json"), """
                {"plugins":[{"artifact":"%s","id":"%s","name":"Sample","version":"1.0.0",
                "contractVersion":1,"entryPoint":"example.SamplePlugin","requiredCapabilities":[],"description":"sample"}]}
                """.formatted(artifact, id));
    }
}
