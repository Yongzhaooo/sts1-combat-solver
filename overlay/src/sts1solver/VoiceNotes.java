package sts1solver;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;

/** Local microphone notes; no transcription or network access. */
final class VoiceNotes {
    static final AudioFormat FORMAT = new AudioFormat(16000, 16, 1, true, false);
    private volatile boolean stopping;
    private volatile Thread worker;
    private volatile TargetDataLine microphone;
    private volatile String status = I18n.t("F6 录音");

    boolean active() { return worker != null; }
    String status() { return status; }

    synchronized void start(Path directory, String context) {
        if (active()) return;
        stopping = false;
        status = I18n.t("正在打开麦克风");
        worker = new Thread(() -> record(directory, context), "sts1-voice-note");
        worker.setDaemon(true);
        worker.start();
    }

    void stop() {
        stopping = true;
        TargetDataLine line = microphone;
        if (line != null) line.close(); // Unblock a pending read without waiting on the game thread.
    }

    void shutdown() {
        stop();
        Thread thread = worker;
        if (thread != null) try { thread.join(2000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void record(Path directory, String context) {
        Path wav = null;
        long bytes = 0;
        try {
            Files.createDirectories(directory);
            String id = Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID();
            wav = directory.resolve(id + ".wav");
            Files.write(directory.resolve(id + ".json"), context.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW);
            TargetDataLine line = AudioSystem.getTargetDataLine(FORMAT);
            microphone = line;
            try (RandomAccessFile output = new RandomAccessFile(wav.toFile(), "rw")) {
                writeHeader(output, 0);
                if (!stopping) {
                    line.open(FORMAT);
                    if (!stopping) line.start();
                }
                byte[] buffer = new byte[3200];
                // ponytail: five-minute notes cap accidental recordings; use segmented files for longer sessions.
                long limit = 16000L * 2 * 300;
                try {
                    while (!stopping && bytes < limit) {
                        status = I18n.t("录音 ") + bytes / 32000 + I18n.t("秒 · F6");
                        int count = line.read(buffer, 0, (int)Math.min(buffer.length, limit - bytes));
                        if (count <= 0) break;
                        output.write(buffer, 0, count);
                        bytes += count;
                        writeHeader(output, bytes);
                        output.seek(44 + bytes);
                    }
                } finally { writeHeader(output, bytes); }
            }
            status = bytes > 0 ? I18n.t("录音已保存 · F6") : I18n.t("未录到声音 · F6 重试");
        } catch (Exception failure) {
            if (stopping) status = bytes > 0 ? I18n.t("录音已保存 · F6") : I18n.t("录音已取消 · F6");
            else {
                status = I18n.t("录音失败 · 检查麦克风");
                System.err.println("[STS1VoiceNotes] " + failure);
            }
        } finally {
            TargetDataLine line = microphone;
            if (line != null) line.close();
            microphone = null;
            if (wav != null) {
                try {
                    String result = "status=" + status + "\nbytes=" + bytes + "\nended_at=" + Instant.now() + "\n";
                    Files.write(Paths.get(wav.toString() + ".status.txt"), result.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) { System.err.println("[STS1VoiceNotes] " + e); }
            }
            worker = null;
        }
    }

    static void writeHeader(RandomAccessFile file, long bytes) throws IOException {
        file.seek(0);
        file.writeBytes("RIFF"); file.writeInt(Integer.reverseBytes((int)(36 + bytes)));
        file.writeBytes("WAVEfmt "); file.writeInt(Integer.reverseBytes(16));
        file.writeShort(Short.reverseBytes((short)1)); file.writeShort(Short.reverseBytes((short)1));
        file.writeInt(Integer.reverseBytes(16000)); file.writeInt(Integer.reverseBytes(32000));
        file.writeShort(Short.reverseBytes((short)2)); file.writeShort(Short.reverseBytes((short)16));
        file.writeBytes("data"); file.writeInt(Integer.reverseBytes((int)bytes));
    }
}
