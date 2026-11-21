import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

public final class Main {
    public static void main(String[] args) throws IOException {
        String mode = args.length > 0 ? args[0] : "--fcgi";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : (mode.equals("--http") ? 8080 : 9000);
        PointService service = new PointService();
        if (mode.equals("--fcgi")) {
            new FastCgiServer(service).run(port);
        } else if (mode.equals("--http")) {
            preview(service, port);
        } else {
            throw new IllegalArgumentException("Usage: java -jar build/lab2.jar [--fcgi|--http] [port]");
        }
    }

    private static void preview(PointService service, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/", exchange -> {
            try (exchange) {
                PointService.Response response = service.handle(exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst("Cookie"));
                byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.getResponseHeaders().set("Set-Cookie", PointService.setCookie(response));
                if (response.status() == 405) {
                    exchange.getResponseHeaders().set("Allow",
                        exchange.getRequestURI().getPath().equals("/api/history") ? "GET, DELETE" : "GET");
                }
                exchange.sendResponseHeaders(response.status(), body.length);
                exchange.getResponseBody().write(body);
            }
        });
        Map<String, String> files = Map.of("/", "index.html", "/index.html", "index.html",
            "/lab2.css", "lab2.css", "/lab2.js", "lab2.js");
        server.createContext("/", exchange -> {
            try (exchange) {
                String file = files.get(exchange.getRequestURI().getPath());
                if (!exchange.getRequestMethod().equals("GET")) {
                    exchange.getResponseHeaders().set("Allow", "GET");
                    exchange.sendResponseHeaders(405, -1);
                } else if (file == null || !Files.isRegularFile(Path.of("public", file))) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    String type = file.endsWith(".css") ? "text/css" : file.endsWith(".js") ? "text/javascript" : "text/html";
                    byte[] body = Files.readAllBytes(Path.of("public", file));
                    exchange.getResponseHeaders().set("Content-Type", type + "; charset=UTF-8");
                    exchange.getResponseHeaders().set("Cache-Control", "no-store");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            }
        });
        server.start();
        System.out.println("Local: http://127.0.0.1:" + port + "/");
    }
}
