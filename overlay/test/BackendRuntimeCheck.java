package sts1solver;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Exercises the real launcher without starting the game or executing game actions. */
public class BackendRuntimeCheck {
    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        String originalOS = System.getProperty("os.name");
        Path temp = Files.createTempDirectory("solver 启动 check ").toRealPath();
        try {
            Path script = Files.createDirectory(temp.resolve("overlay")).resolve("backend probe.py");
            Files.write(script, Arrays.asList("import os", "print('native-ok')",
                "print(os.environ['STS_SOLVER_DATA'])", "print(os.getcwd())"), StandardCharsets.UTF_8);
            Properties p = new Properties();
            p.setProperty("runtime", "native");
            p.setProperty("python", args[0]);
            p.setProperty("backend", script.toString());
            for (String os : new String[]{"Mac OS X", "Linux", "Windows 11"}) {
                System.setProperty("os.name", os);
                require(BackendRuntime.dataDirectory(p).isAbsolute(), "absolute data directory: " + os);
                ProcessBuilder builder = BackendRuntime.process(p);
                require(builder.command().get(0).equals(args[0]), "native interpreter: " + os);
                require(!builder.command().contains("wsl.exe"), "native must not use WSL");
                require(!builder.environment().containsKey("PYTHONHOME") &&
                    !builder.environment().containsKey("PYTHONPATH"), "inherited Python overrides removed");
            }
            System.setProperty("os.name", originalOS);
            p.setProperty("log", temp.resolve("backend.log").toString());
            ProcessBuilder builder = BackendRuntime.process(p);
            require(builder.environment().get("STS_SOLVER_DATA").equals(temp.toString()), "data override");
            Path output = temp.resolve("output.txt");
            Process child = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try {
                require(child.waitFor(10, TimeUnit.SECONDS), "launcher timed out");
                require(child.exitValue() == 0, "native interpreter failed");
                require(Files.readAllLines(output, StandardCharsets.UTF_8).equals(
                    Arrays.asList("native-ok", temp.toString(), script.getParent().toString())), "cwd, UTF-8 and protocol: " +
                    Files.readAllLines(output, StandardCharsets.UTF_8));
            } finally { child.destroyForcibly(); }
            p.setProperty("python", temp.resolve("missing-python").toString());
            try { BackendRuntime.process(p); throw new AssertionError("missing interpreter accepted"); }
            catch (IOException expected) { require(expected.getMessage().contains("python"), "actionable missing interpreter"); }
            p.setProperty("python", "relative-python");
            try { BackendRuntime.process(p); throw new AssertionError("relative interpreter accepted"); }
            catch (IOException expected) { require(expected.getMessage().contains("absolute"), "absolute paths required"); }
            p.setProperty("runtime", "typo");
            try { BackendRuntime.process(p); throw new AssertionError("unknown runtime accepted"); }
            catch (IOException expected) { require(expected.getMessage().contains("runtime"), "unknown mode"); }
            p.remove("runtime");
            System.setProperty("os.name", "Windows 11");
            require(BackendRuntime.process(p).command().get(0).equals("wsl.exe"), "legacy Windows retained");
            System.setProperty("os.name", "Mac OS X");
            p.setProperty("runtime", "windows-bundled");
            try { BackendRuntime.process(p); throw new AssertionError("Windows binaries accepted on Mac"); }
            catch (IOException expected) { require(expected.getMessage().contains("Windows"), "Windows bundle guard"); }
            System.setProperty("os.name", originalOS);
            try (InputStream config = BackendRuntimeCheck.class.getResourceAsStream("/solver.properties")) {
                if (config != null) {
                    Properties installed = new Properties();
                    installed.load(config);
                    if ("native".equals(installed.getProperty("runtime"))) {
                        ProcessBuilder configured = BackendRuntime.process(installed);
                        require(configured.command().get(0).equals(args[0]), "packaged interpreter round trip");
                        require(configured.directory().toPath().resolve("backend.py").equals(
                            Paths.get(installed.getProperty("backend"))), "packaged backend round trip");
                    }
                }
            }
            System.out.println("PASS: native OS routing, real Python launch, Unicode paths, cwd, data, invalid config and legacy Windows");
        } finally {
            System.setProperty("os.name", originalOS);
            try (java.util.stream.Stream<Path> paths = Files.walk(temp)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.delete(path);
            }
        }
    }
}
