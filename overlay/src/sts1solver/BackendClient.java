package sts1solver;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The backend process and its one-JSON-object-per-line pipe. A reader thread queues replies; the game thread
 * polls them. Requests and their bookkeeping belong to the caller.
 */
final class BackendClient {
    private final Gson gson = new Gson();
    private final ConcurrentLinkedQueue<JsonObject> replies = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<JsonObject> debugReplies = new ConcurrentLinkedQueue<>();
    private Process process;
    private BufferedWriter writer;
    private volatile boolean runLog = true;

    /** Starts the process unless it is running; a restarted backend gets the run-log setting again. */
    synchronized void start() throws IOException {
        if (process != null && process.isAlive()) return;
        Properties properties = new Properties();
        try (InputStream stream = getClass().getResourceAsStream("/solver.properties")) {
            if (stream == null) throw new IOException(I18n.t("缺少求解器路径配置，请重新安装。"));
            properties.load(stream);
        }
        Path log = BackendRuntime.dataDirectory(properties).resolve("backend.log");
        Files.createDirectories(log.getParent());
        ProcessBuilder pb = BackendRuntime.process(properties);
        pb.environment().put("PYTHONUTF8", "1");
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
        process = pb.start();
        final Process child = process;
        writer = new BufferedWriter(new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8));
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    JsonObject reply = new JsonParser().parse(line).getAsJsonObject();
                    if (reply.has("status") && reply.get("status").getAsString().startsWith("debug_")) debugReplies.add(reply);
                    else replies.add(reply);
                }
            } catch (Exception failure) {
                JsonObject reply = new JsonObject();
                reply.addProperty("id", -1);
                reply.addProperty("status", "transport_error");
                reply.addProperty("message", I18n.t("后台连接中断：") + failure.getMessage());
                replies.add(reply);
            }
        }, "sts1-solver-reader");
        reader.setDaemon(true);
        reader.start();
        if (!runLog) sendRunLog();
    }

    /** Writes to the current process without starting one; call start() first when a reply is needed. */
    void write(JsonObject request) throws IOException {
        writer.write(gson.toJson(request));
        writer.newLine();
        writer.flush();
    }

    boolean started() { return writer != null; }
    boolean alive() { return process != null && process.isAlive(); }
    JsonObject reply() { return replies.poll(); }
    /** Replies to export requests, which carry no request id. */
    JsonObject debugReply() { return debugReplies.poll(); }

    boolean runLog() { return runLog; }

    void setRunLog(boolean enabled) {
        runLog = enabled;
        sendRunLog();
    }

    private void sendRunLog() {
        if (writer == null) return;
        try {
            JsonObject request = new JsonObject();
            request.addProperty("op", "run_log");
            request.addProperty("enabled", runLog);
            write(request);
        } catch (IOException failure) { System.err.println("[STS1Solver] " + failure); }
    }

    /** EOF lets the supervisor terminate and reap its native worker. */
    void close() {
        try { if (writer != null) writer.close(); } catch (IOException ignored) { }
    }
}
