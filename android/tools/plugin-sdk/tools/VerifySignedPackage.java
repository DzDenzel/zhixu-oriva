import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.jar.*;

/** Standalone real JarFile verifier. Checks every non-META-INF payload against a public source anchor. */
class VerifySignedPackage {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: VerifySignedPackage <package> <source-anchor.cer>");
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        java.security.cert.Certificate anchor;
        try (InputStream input = Files.newInputStream(Path.of(args[1]))) { anchor = factory.generateCertificate(input); }
        byte[] anchorBytes = anchor.getEncoded();
        String fingerprint = HexFormat.ofDelimiter(":").withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(anchorBytes));
        List<String> names = new ArrayList<>();
        try (JarFile jar = new JarFile(args[0], true)) {
            Set<String> unique = new HashSet<>();
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!unique.add(entry.getName())) throw new SecurityException("Duplicate entry: " + entry.getName());
                if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) continue;
                byte[] payload;
                try (InputStream input = jar.getInputStream(entry)) { payload = input.readAllBytes(); }
                if (payload.length == 0) throw new SecurityException("Empty payload: " + entry.getName());
                var certificates = entry.getCertificates();
                if (certificates == null || certificates.length == 0) throw new SecurityException("Unsigned payload: " + entry.getName());
                boolean trusted = false;
                for (var certificate : certificates) if (Arrays.equals(certificate.getEncoded(), anchorBytes)) trusted = true;
                if (!trusted) throw new SecurityException("Untrusted signer: " + entry.getName());
                names.add(entry.getName());
            }
        }
        if (!names.contains("plugin.json") || !names.contains("index.js")) throw new SecurityException("Missing JS plugin payload");
        Collections.sort(names);
        System.out.println("VERIFIED_PAYLOADS=" + String.join(",", names));
        System.out.println("SIGNER_SHA256=" + fingerprint);
    }
}
