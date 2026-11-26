import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Cryptographic JAR verification helper aligned with Android SignedJarPluginPackageVerifier.
 * Reads every payload entry to EOF via JarFile(file, true), extracts per-entry leaf
 * X.509 CodeSigners, enforces strict uppercase META-INF/ signature metadata boundaries,
 * and computes the common signer intersection across all payload entries.
 */
public final class JarSignerVerifier {
    private static final long MAX_PACKAGE_BYTES = 25L * 1024 * 1024;
    private static final long MAX_ENTRY_BYTES = 20L * 1024 * 1024;
    private static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 50L * 1024 * 1024;
    private static final int MAX_ARCHIVE_MEMBERS = 2048;
    private static final Pattern META_SIG_PATTERN =
        Pattern.compile("^META-INF/([A-Za-z0-9_-]{1,64})\\.(SF|RSA|DSA|EC)$");

    private static final class SignerInfo {
        final String certSha256;
        final String spkiSha256;
        final String subject;

        SignerInfo(String certSha256, String spkiSha256, String subject) {
            this.certSha256 = certSha256;
            this.spkiSha256 = spkiSha256;
            this.subject = subject;
        }
    }

    private static final class PayloadResult {
        final String name;
        final long size;
        final String sha256;
        final List<SignerInfo> signers;

        PayloadResult(String name, long size, String sha256, List<SignerInfo> signers) {
            this.name = name;
            this.size = size;
            this.sha256 = sha256;
            this.signers = signers;
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: JarSignerVerifier <package-path>");
            System.exit(2);
        }
        try {
            Path target = Path.of(args[0]);
            if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new SecurityException("Package path must be a regular non-symlink file");
            }
            long fileSize = Files.size(target);
            if (fileSize <= 0 || fileSize > MAX_PACKAGE_BYTES) {
                throw new SecurityException("Package size out of bounds: " + fileSize);
            }
            String json = verifyAndInspect(target);
            System.out.println(json);
        } catch (Throwable err) {
            String msg = err.getMessage();
            System.err.println(err.getClass().getSimpleName() + (msg == null ? "" : ": " + msg));
            System.exit(1);
        }
    }

    private static String verifyAndInspect(Path target) throws Exception {
        Set<String> seenNames = new HashSet<>();
        Set<String> sfAliases = new TreeSet<>();
        Set<String> sigAliases = new TreeSet<>();
        Map<String, String> sigEntryByAlias = new LinkedHashMap<>();
        Set<String> ciAliases = new HashSet<>();
        boolean hasManifest = false;
        List<String> metaInfFiles = new ArrayList<>();
        List<PayloadResult> payloads = new ArrayList<>();
        Map<String, Set<String>> payloadCertsByName = new LinkedHashMap<>();
        Map<String, SignerInfo> signerCatalog = new LinkedHashMap<>();
        Set<String> commonCertSigners = null;
        long totalUncompressed = 0;
        int memberCount = 0;

        try (JarFile jar = new JarFile(target.toFile(), true)) {
            Enumeration<JarEntry> entries = jar.entries();
            byte[] buffer = new byte[65536];
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                memberCount++;
                if (memberCount > MAX_ARCHIVE_MEMBERS) {
                    throw new SecurityException("Archive exceeds max member count: " + MAX_ARCHIVE_MEMBERS);
                }

                String name = entry.getName();
                validateEntryName(name);
                if (!seenNames.add(name)) {
                    throw new SecurityException("Duplicate archive entry: " + name);
                }

                if (entry.isDirectory()) {
                    if (name.toLowerCase(Locale.ROOT).startsWith("meta-inf/") && !name.startsWith("META-INF/")) {
                        throw new SecurityException("Case-variant META-INF directory is forbidden: " + name);
                    }
                    if (entry.getSize() > 0) {
                        throw new SecurityException("Directory entry has non-zero declared size: " + name);
                    }
                    try (InputStream in = jar.getInputStream(entry)) {
                        if (in.read(buffer) != -1) {
                            throw new SecurityException("Directory entry contains payload bytes: " + name);
                        }
                    }
                    continue;
                }

                long declaredSize = entry.getSize();
                if (declaredSize > MAX_ENTRY_BYTES) {
                    throw new SecurityException("Entry exceeds max size: " + name);
                }

                if (name.toLowerCase(Locale.ROOT).startsWith("meta-inf/")) {
                    if (!name.startsWith("META-INF/")) {
                        throw new SecurityException("Case-variant META-INF entry is forbidden: " + name);
                    }
                    if (name.equals("META-INF/MANIFEST.MF")) {
                        hasManifest = true;
                    } else {
                        Matcher m = META_SIG_PATTERN.matcher(name);
                        if (!m.matches()) {
                            throw new SecurityException("Disallowed file inside META-INF/: " + name);
                        }
                        String alias = m.group(1);
                        String ext = m.group(2);
                        String upperAlias = alias.toUpperCase(Locale.ROOT);
                        if (ext.equals("SF")) {
                            if (!sfAliases.add(alias)) {
                                throw new SecurityException("Duplicate SF alias: " + alias);
                            }
                        } else {
                            if (!sigAliases.add(alias)) {
                                throw new SecurityException("Duplicate signature block for alias: " + alias);
                            }
                            sigEntryByAlias.put(alias, name);
                        }
                        ciAliases.add(upperAlias);
                    }
                    long metaRead = drainBounded(jar.getInputStream(entry), buffer, name, totalUncompressed);
                    if (metaRead <= 0) {
                        throw new SecurityException("Empty META-INF metadata entry is forbidden: " + name);
                    }
                    totalUncompressed += metaRead;
                    metaInfFiles.add(name);
                    continue;
                }

                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long readBytes = readAndDigestBounded(jar.getInputStream(entry), buffer, digest, name, totalUncompressed);
                totalUncompressed += readBytes;
                if (readBytes <= 0) {
                    throw new SecurityException("Empty signed payload entry is forbidden: " + name);
                }

                CodeSigner[] signers = entry.getCodeSigners();
                if (signers == null || signers.length == 0) {
                    throw new SecurityException("Unsigned or unverified payload entry: " + name);
                }

                List<SignerInfo> entrySigners = new ArrayList<>();
                Set<String> entryCertSet = new LinkedHashSet<>();
                for (CodeSigner cs : signers) {
                    List<? extends Certificate> certs = cs.getSignerCertPath().getCertificates();
                    if (certs.isEmpty() || !(certs.get(0) instanceof X509Certificate leaf)) {
                        throw new SecurityException("Signer certificate chain has no X509 leaf for: " + name);
                    }
                    String certFp = sha256Colon(leaf.getEncoded());
                    String spkiFp = sha256Colon(leaf.getPublicKey().getEncoded());
                    String subject = leaf.getSubjectX500Principal().getName();
                    SignerInfo info = new SignerInfo(certFp, spkiFp, subject);
                    signerCatalog.putIfAbsent(certFp, info);
                    if (entryCertSet.add(certFp)) {
                        entrySigners.add(info);
                    }
                }

                if (commonCertSigners == null) {
                    commonCertSigners = new LinkedHashSet<>(entryCertSet);
                } else {
                    commonCertSigners.retainAll(entryCertSet);
                }
                payloadCertsByName.put(name, entryCertSet);
                payloads.add(new PayloadResult(name, readBytes, sha256Hex(digest.digest()), entrySigners));
            }
        }

        if (!hasManifest) {
            throw new SecurityException("Missing META-INF/MANIFEST.MF");
        }
        if (sfAliases.isEmpty() || !sfAliases.equals(sigAliases)) {
            throw new SecurityException("Mismatched or missing META-INF signature pairs");
        }
        if (ciAliases.size() != sfAliases.size()) {
            throw new SecurityException("Case-colliding META-INF signature aliases are forbidden");
        }
        boolean hasPluginJson = payloads.stream().anyMatch(p -> p.name.equals("plugin.json"));
        if (!hasPluginJson) {
            throw new SecurityException("Plugin package is missing plugin.json");
        }
        if (sfAliases.size() > 1) {
            verifyEverySignaturePairAttributed(target, sfAliases, sigEntryByAlias, payloadCertsByName);
        }
        if (commonCertSigners == null) {
            commonCertSigners = Collections.emptySet();
        }

        return formatJson(payloads, commonCertSigners, signerCatalog, metaInfFiles);
    }

    /**
     * When multiple META-INF signature pairs exist, verify each pair independently in an isolated
     * JAR so JDK cannot silently skip a corrupt, disabled, or unattributed auxiliary signature pair.
     */
    private static void verifyEverySignaturePairAttributed(
        Path target,
        Set<String> sfAliases,
        Map<String, String> sigEntryByAlias,
        Map<String, Set<String>> payloadCertsByName
    ) throws Exception {
        byte[] buffer = new byte[65536];
        try (ZipFile rawZip = new ZipFile(target.toFile())) {
            for (String alias : sfAliases) {
                String sfName = "META-INF/" + alias + ".SF";
                String sigName = sigEntryByAlias.get(alias);
                if (sigName == null) {
                    throw new SecurityException("Missing signature block for alias: " + alias);
                }
                Path isoJar = Files.createTempFile("oriva-sig-iso-", ".jar");
                try {
                    try (ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(isoJar))) {
                        Enumeration<? extends ZipEntry> rawEntries = rawZip.entries();
                        while (rawEntries.hasMoreElements()) {
                            ZipEntry src = rawEntries.nextElement();
                            if (src.isDirectory()) {
                                continue;
                            }
                            String name = src.getName();
                            boolean keep = !name.startsWith("META-INF/")
                                || name.equals("META-INF/MANIFEST.MF")
                                || name.equals(sfName)
                                || name.equals(sigName);
                            if (!keep) {
                                continue;
                            }
                            ZipEntry dst = new ZipEntry(name);
                            zout.putNextEntry(dst);
                            try (InputStream in = rawZip.getInputStream(src)) {
                                int n;
                                while ((n = in.read(buffer)) != -1) {
                                    zout.write(buffer, 0, n);
                                }
                            }
                            zout.closeEntry();
                        }
                    }

                    int attributedPayloads = 0;
                    try (JarFile iso = new JarFile(isoJar.toFile(), true)) {
                        Enumeration<JarEntry> isoEntries = iso.entries();
                        while (isoEntries.hasMoreElements()) {
                            JarEntry entry = isoEntries.nextElement();
                            if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) {
                                continue;
                            }
                            drainBounded(iso.getInputStream(entry), buffer, entry.getName(), 0);
                            CodeSigner[] signers = entry.getCodeSigners();
                            if (signers != null && signers.length > 0) {
                                attributedPayloads++;
                                Set<String> fullEntryCerts = payloadCertsByName.getOrDefault(entry.getName(), Collections.emptySet());
                                for (CodeSigner cs : signers) {
                                    List<? extends Certificate> certs = cs.getSignerCertPath().getCertificates();
                                    if (certs.isEmpty() || !(certs.get(0) instanceof X509Certificate leaf)) {
                                        throw new SecurityException("Auxiliary signature pair lacks X509 leaf: " + alias);
                                    }
                                    String certFp = sha256Colon(leaf.getEncoded());
                                    if (!fullEntryCerts.contains(certFp)) {
                                        throw new SecurityException("Inconsistent signer attribution for alias: " + alias);
                                    }
                                }
                            }
                        }
                    }
                    if (attributedPayloads <= 0) {
                        throw new SecurityException("Unattributed or ineffective META-INF signature pair: " + alias);
                    }
                } finally {
                    Files.deleteIfExists(isoJar);
                }
            }
        }
    }

    private static void validateEntryName(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new SecurityException("Unsafe archive entry name: " + name);
        }
        if (name.startsWith("/") || (name.length() >= 2 && name.charAt(1) == ':')) {
            throw new SecurityException("Absolute archive path forbidden: " + name);
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new SecurityException("Control char in archive entry name: " + name);
            }
        }
        String trimmed = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        String[] parts = trimmed.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new SecurityException("Unsafe path segment in archive entry: " + name);
            }
        }
    }

    private static long drainBounded(InputStream in, byte[] buffer, String name, long currentTotal) throws IOException {
        try (in) {
            long count = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                count += n;
                if (count > MAX_ENTRY_BYTES || currentTotal + count > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                    throw new SecurityException("Archive entry exceeds uncompressed budget: " + name);
                }
            }
            return count;
        }
    }

    private static long readAndDigestBounded(
        InputStream in, byte[] buffer, MessageDigest digest, String name, long currentTotal
    ) throws IOException {
        try (in) {
            long count = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                count += n;
                if (count > MAX_ENTRY_BYTES || currentTotal + count > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                    throw new SecurityException("Archive entry exceeds uncompressed budget: " + name);
                }
                digest.update(buffer, 0, n);
            }
            return count;
        }
    }

    private static String sha256Hex(byte[] raw) {
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(String.format(Locale.ROOT, "%02X", b));
        }
        return sb.toString();
    }

    private static String sha256Colon(byte[] der) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
        StringBuilder sb = new StringBuilder(digest.length * 3);
        for (int i = 0; i < digest.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format(Locale.ROOT, "%02X", digest[i]));
        }
        return sb.toString();
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String formatSignerJson(SignerInfo s) {
        return "{\"certSha256\":" + escapeJson(s.certSha256)
            + ",\"spkiSha256\":" + escapeJson(s.spkiSha256)
            + ",\"subject\":" + escapeJson(s.subject) + "}";
    }

    private static String formatJson(
        List<PayloadResult> payloads,
        Set<String> commonCertSigners,
        Map<String, SignerInfo> signerCatalog,
        List<String> metaInfFiles
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"payloadEntries\":[");
        for (int i = 0; i < payloads.size(); i++) {
            if (i > 0) sb.append(',');
            PayloadResult p = payloads.get(i);
            sb.append("{\"name\":").append(escapeJson(p.name))
              .append(",\"size\":").append(p.size)
              .append(",\"sha256\":").append(escapeJson(p.sha256))
              .append(",\"signers\":[");
            for (int j = 0; j < p.signers.size(); j++) {
                if (j > 0) sb.append(',');
                sb.append(formatSignerJson(p.signers.get(j)));
            }
            sb.append("]}");
        }
        sb.append("],\"commonSigners\":[");
        int idx = 0;
        for (String fp : commonCertSigners) {
            if (idx++ > 0) sb.append(',');
            sb.append(formatSignerJson(signerCatalog.get(fp)));
        }
        sb.append("],\"metaInfEntries\":[");
        for (int i = 0; i < metaInfFiles.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(escapeJson(metaInfFiles.get(i)));
        }
        sb.append("]}");
        return sb.toString();
    }
}
