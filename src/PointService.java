import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

public final class PointService {
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)");
    private static final String COOKIE = "lab2_session";
    private final Map<String, List<Result>> sessions = new HashMap<>();

    public record Response(int status, String body, String session) {}

    private record Result(int x, BigDecimal y, int r, boolean hit, Instant time, long elapsedNs) {
        String json() {
            StringJoiner hits = new StringJoiner(",", "[", "]");
            for (int radius = 1; radius <= 5; radius++) {
                hits.add(String.valueOf(contains(x, y, radius)));
            }
            return "{\"x\":" + x + ",\"y\":" + quote(y.toPlainString()) + ",\"r\":" + r
                + ",\"hit\":" + hit + ",\"hits\":" + hits + ",\"time\":" + quote(time.toString())
                + ",\"elapsedMs\":" + BigDecimal.valueOf(elapsedNs, 6).toPlainString() + "}";
        }
    }

    public synchronized Response handle(String method, String path, String query, String cookie) {
        long start = System.nanoTime();
        String session = sessionId(cookie);
        try {
            if (!path.equals("/api/check") && !path.equals("/api/history")) {
                return error(404, "Адрес API не найден.", session);
            }
            if (!(method.equals("GET") || (method.equals("DELETE") && path.equals("/api/history")))) {
                return error(405, "Для проверки используйте GET; для очистки истории — DELETE.", session);
            }
            Map<String, List<String>> params = parseQuery(query);
            if (path.equals("/api/check")) {
                if (!params.keySet().equals(Set.of("x", "y", "r"))) {
                    throw new IllegalArgumentException("Нужны только параметры x, y и r.");
                }
                List<String> rawXs = params.get("x");
                if (rawXs.size() > 9) {
                    throw new IllegalArgumentException("Выберите не более 9 значений X.");
                }
                List<Integer> xs = new ArrayList<>();
                for (String text : rawXs) {
                    if (!text.matches("-?[0-5]")) {
                        throw new IllegalArgumentException("Недопустимое значение X.");
                    }
                    int x = Integer.parseInt(text);
                    if (x < -5 || x > 3 || xs.contains(x)) {
                        throw new IllegalArgumentException("X: разные целые значения от −5 до 3.");
                    }
                    xs.add(x);
                }
                String rawY = single(params, "y").trim().replace(',', '.');
                if (rawY.length() > 64 || !DECIMAL.matcher(rawY).matches()) {
                    throw new IllegalArgumentException("Y должен быть обычным десятичным числом (до 64 символов).");
                }
                BigDecimal y = new BigDecimal(rawY);
                if (y.compareTo(BigDecimal.valueOf(-3)) <= 0 || y.compareTo(BigDecimal.valueOf(5)) >= 0) {
                    throw new IllegalArgumentException("Y должен быть строго между −3 и 5.");
                }
                String rawR = single(params, "r");
                if (!rawR.matches("[1-5]")) {
                    throw new IllegalArgumentException("R должен быть целым от 1 до 5.");
                }
                int r = Integer.parseInt(rawR);
                List<Result> history = sessions.computeIfAbsent(session, ignored -> new ArrayList<>());
                for (int x : xs) {
                    boolean hit = contains(x, y, r);
                    history.add(new Result(x, y, r, hit, Instant.now(), System.nanoTime() - start));
                }
            } else {
                if (!params.isEmpty()) {
                    throw new IllegalArgumentException("История не принимает параметры.");
                }
                if (method.equals("DELETE")) {
                    sessions.remove(session);
                }
            }
            List<Result> history = sessions.getOrDefault(session, List.of());
            StringJoiner json = new StringJoiner(",", "{\"results\":[", "]}");
            for (int i = history.size() - 1; i >= 0; i--) {
                json.add(history.get(i).json());
            }
            return new Response(200, json.toString(), session);
        } catch (IllegalArgumentException exception) {
            return error(400, exception.getMessage(), session);
        }
    }

    public static boolean contains(int x, BigDecimal y, int r) {
        BigDecimal bx = BigDecimal.valueOf(x);
        BigDecimal br = BigDecimal.valueOf(r);
        boolean rectangle = x >= 0 && x <= r && y.signum() >= 0 && y.compareTo(br.divide(TWO)) <= 0;
        boolean circle = x <= 0 && y.signum() >= 0
            && bx.multiply(bx).add(y.multiply(y)).compareTo(br.multiply(br)) <= 0;
        boolean triangle = x <= 0 && y.signum() <= 0
            && y.multiply(TWO).compareTo(bx.add(br).negate()) >= 0;
        return rectangle || circle || triangle;
    }

    private static String single(Map<String, List<String>> params, String name) {
        List<String> values = params.get(name);
        if (values == null || values.size() != 1) {
            throw new IllegalArgumentException("Нужно одно значение " + name + ".");
        }
        return values.get(0);
    }

    private static Map<String, List<String>> parseQuery(String query) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }
        if (query.length() > 4096) {
            throw new IllegalArgumentException("Слишком длинный запрос.");
        }
        for (String pair : query.split("&", -1)) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Некорректная строка запроса.");
            }
            String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            params.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        return params;
    }

    private static String sessionId(String cookie) {
        if (cookie != null) {
            for (String part : cookie.split(";")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && pair[0].equals(COOKIE)
                    && pair[1].matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
                    return pair[1];
                }
            }
        }
        return UUID.randomUUID().toString();
    }

    public static String setCookie(Response response) {
        return COOKIE + "=" + response.session() + "; Path=/; HttpOnly; SameSite=Lax";
    }

    private static Response error(int status, String message, String session) {
        return new Response(status, "{\"error\":" + quote(message) + "}", session);
    }

    private static String quote(String text) {
        StringBuilder output = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') {
                output.append('\\').append(c);
            } else if (c < 32) {
                output.append(String.format("\\u%04x", (int) c));
            } else {
                output.append(c);
            }
        }
        return output.append('"').toString();
    }
}
