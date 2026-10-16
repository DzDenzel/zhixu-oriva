import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** JRE-only readiness probe; never passes database credentials on the command line. */
public final class Healthcheck {
    public static void main(String[] args) throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/api/v1/health"))
                .timeout(Duration.ofSeconds(3)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || !response.body().matches("(?s).*\"status\"\\s*:\\s*\"UP\".*")) {
            System.exit(1);
        }
        if (args.length == 1 && args[0].equals("--json")) System.out.println(response.body());
    }
}
