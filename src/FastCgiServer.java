import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class FastCgiServer {
    private static final int BEGIN = 1, ABORT = 2, END = 3, PARAMS = 4, STDIN = 5, STDOUT = 6;
    private final PointService service;

    public FastCgiServer(PointService service) {

        this.service = service;

    }

    public void run(int port) throws IOException {
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress("127.0.0.1", port));
            System.out.println("FastCGI: 127.0.0.1:" + port);
            while (!Thread.currentThread().isInterrupted()) {
                Socket socket = server.accept();
                workers.execute(() -> {
                    try (socket) {
                        socket.setSoTimeout(15000);
                        handle(socket);
                    } catch (IOException exception) {
                        System.err.println("FastCGI connection: " + exception.getMessage());
                    }
                });
            }
        } finally {
            workers.shutdownNow();
        }
    }

    private void handle(Socket socket) throws IOException {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        OutputStream output = socket.getOutputStream();
        ByteArrayOutputStream params = new ByteArrayOutputStream();
        int activeId = 0;
        boolean keepConnection = false;
        boolean paramsComplete = false;
        while (true) {
            int version = input.read();
            if (version == -1) {
                return;
            }
            if (version != 1) {
                throw new IOException("Unsupported FastCGI version");
            }
            int type = input.readUnsignedByte();
            int id = input.readUnsignedShort();
            int length = input.readUnsignedShort();
            int padding = input.readUnsignedByte();
            input.readUnsignedByte();
            byte[] body = new byte[length];
            input.readFully(body);
            input.skipNBytes(padding);
            if (id == 0) {
                if (type == 9) {
                    record(output, 10, 0, new byte[0]);
                } else record(output, 11, 0, new byte[]{(byte) type, 0, 0, 0, 0, 0, 0, 0});
                output.flush();
                continue;
            }
            if (type == BEGIN) {
                if (activeId != 0) {
                    end(output, id, 1);
                    output.flush();
                    continue;
                }
                if (length != 8) {
                    throw new IOException("Invalid BEGIN_REQUEST");
                }
                if (body[0] != 0 || body[1] != 1) {
                    end(output, id, 3);
                    output.flush();
                    return;
                }
                activeId = id;
                keepConnection = (body[2] & 1) != 0;
                params.reset();
                paramsComplete = false;
            } else if (id == activeId && type == ABORT) {
                end(output, id, 0);
                output.flush();
                if (!keepConnection) {
                    return;
                }
                activeId = 0;
            } else if (id == activeId && type == PARAMS) {
                if (paramsComplete || params.size() + length > 65536) {
                    throw new IOException("Invalid PARAMS stream");
                }
                params.write(body);
                if (length == 0) {
                    paramsComplete = true;
                }
            } else if (id == activeId && type == STDIN && length == 0) {
                if (!paramsComplete) {
                    throw new IOException("Incomplete PARAMS stream");
                }
                Map<String, String> env = decodeParams(params.toByteArray());
                String uri = env.getOrDefault("REQUEST_URI", "/api/check");
                String path = uri.split("\\?", 2)[0];
                PointService.Response response = service.handle(
                    env.getOrDefault("REQUEST_METHOD", "GET"), path,
                    env.getOrDefault("QUERY_STRING", ""), env.get("HTTP_COOKIE"));
                byte[] json = response.body().getBytes(StandardCharsets.UTF_8);
                String headers = "Status: " + response.status() + " " + reason(response.status()) + "\r\n"
                    + "Content-Type: application/json; charset=UTF-8\r\n"
                    + "Content-Length: " + json.length + "\r\n"
                    + "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n"
                    + "Set-Cookie: " + PointService.setCookie(response) + "\r\n"
                    + (response.status() == 405 ? "Allow: " + (path.equals("/api/history") ? "GET, DELETE" : "GET") + "\r\n" : "")
                    + "\r\n";
                writeStream(output, id, headers.getBytes(StandardCharsets.UTF_8));
                writeStream(output, id, json);
                record(output, STDOUT, id, new byte[0]);
                end(output, id, 0);
                output.flush();
                if (!keepConnection) {
                    return;
                }
                activeId = 0;
            }
        }
    }

    private static Map<String, String> decodeParams(byte[] bytes) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
        Map<String, String> result = new HashMap<>();
        while (input.available() > 0) {
            int nameLength = readLength(input);
            int valueLength = readLength(input);
            if (nameLength > input.available() || valueLength > input.available() - nameLength) {
                throw new IOException("Truncated PARAMS");
            }
            String name = new String(input.readNBytes(nameLength), StandardCharsets.UTF_8);
            String value = new String(input.readNBytes(valueLength), StandardCharsets.UTF_8);
            result.put(name, value);
        }
        return result;
    }

    private static int readLength(DataInputStream input) throws IOException {
        int first = input.readUnsignedByte();
        if ((first & 128) == 0) {
            return first;
        }
        return ((first & 127) << 24) | (input.readUnsignedByte() << 16)
            | (input.readUnsignedByte() << 8) | input.readUnsignedByte();
    }

    private static void writeStream(OutputStream output, int id, byte[] bytes) throws IOException {
        for (int offset = 0; offset < bytes.length; offset += 65535) {
            record(output, STDOUT, id, Arrays.copyOfRange(bytes, offset, Math.min(offset + 65535, bytes.length)));
        }
    }

    private static void record(OutputStream output, int type, int id, byte[] body) throws IOException {
        output.write(new byte[]{1, (byte) type, (byte) (id >>> 8), (byte) id,
            (byte) (body.length >>> 8), (byte) body.length, 0, 0});
        output.write(body);
    }

    private static void end(OutputStream output, int id, int status) throws IOException {
        record(output, END, id, new byte[]{0, 0, 0, 0, (byte) status, 0, 0, 0});
    }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            default -> "Internal Server Error";
        };
    }
}
