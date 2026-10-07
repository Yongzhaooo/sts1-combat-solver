package sts1solver;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import com.evacipated.cardcrawl.modthespire.Loader;
import com.evacipated.cardcrawl.modthespire.ModInfo;

public class BundledRuntimeCheck {
    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static void zip(Path path, String... names) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            for (String name : names) {
                out.putNextEntry(new ZipEntry(name));
                out.write(name.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("bundle 中文 check ");
        String os = System.getProperty("os.name"), arch = System.getProperty("os.arch");
        try {
            Path archive = temp.resolve("bundle.zip"), data = temp.resolve("data");
            zip(archive, "python/bin/python3", "overlay/backend.py");
            Path first = BackendRuntime.unpack(archive, data);
            require(Files.isExecutable(first.resolve("python/bin/python3")), "executable bit restored");
            require(first.equals(BackendRuntime.unpack(archive, data)), "cache reuse");
            zip(archive, "python/bin/python3", "overlay/backend.py", "new-version");
            require(!first.equals(BackendRuntime.unpack(archive, data)), "update has independent cache");
            zip(archive, "../escaped");
            try { BackendRuntime.unpack(archive, data); throw new AssertionError("zip traversal accepted"); }
            catch (IOException expected) { require(!Files.exists(data.resolve("escaped")), "no escaped file"); }
            zip(archive, "overlay/backend.py");
            try { BackendRuntime.unpack(archive, data); throw new AssertionError("incomplete bundle accepted"); }
            catch (IOException expected) { require(expected.getMessage().contains("Incomplete"), "incomplete error"); }
            try (java.util.stream.Stream<Path> paths = Files.list(data.resolve("runtimes"))) {
                require(paths.noneMatch(p -> p.getFileName().toString().startsWith("unpack-")), "failed staging cleaned");
            }
            ModInfo info = new ModInfo();
            info.ID = "sts1solver";
            info.jarURL = temp.resolve("STS1CombatSolver.jar").toUri().toURL();
            Loader.MODINFOS = new ModInfo[]{info};
            Properties p = new Properties();
            p.setProperty("runtime", "bundled");
            p.setProperty("log", data.resolve("backend.log").toString());
            System.setProperty("os.name", "Mac OS X");
            for (String cpu : new String[]{"aarch64", "arm64", "amd64", "x86_64"}) {
                System.setProperty("os.arch", cpu);
                Path file = temp.resolve("sts1-solver-macos-" + BackendRuntime.macArchitecture(cpu) + ".zip");
                zip(file, "python/bin/python3", "overlay/backend.py");
                ProcessBuilder process = BackendRuntime.process(p);
                require(process.command().get(0).endsWith("python3"), "bundled Mac routing");
                require(!process.environment().containsKey("PYTHONHOME"), "clean Python environment");
            }
            try { BackendRuntime.macArchitecture("ppc"); throw new AssertionError("unknown CPU accepted"); }
            catch (IOException expected) { }
            System.setProperty("os.name", "Windows 11");
            Path runtime = temp.resolve("sts1-solver-runtime");
            Files.createDirectories(runtime.resolve("python"));
            Files.createDirectories(runtime.resolve("overlay"));
            Files.createFile(runtime.resolve("python/python.exe"));
            Files.createFile(runtime.resolve("overlay/backend.py"));
            require(BackendRuntime.process(p).directory().toPath().equals(runtime), "Windows bundle unchanged");
            System.out.println("PASS: Windows/Mac bundle selection, Rosetta routing, cache/update, permissions, invalid archives");
        } finally {
            System.setProperty("os.name", os);
            System.setProperty("os.arch", arch);
            try (java.util.stream.Stream<Path> paths = Files.walk(temp)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }
}
