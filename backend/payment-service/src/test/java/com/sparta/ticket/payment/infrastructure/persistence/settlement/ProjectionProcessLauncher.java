package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class ProjectionProcessLauncher {
    private ProjectionProcessLauncher() {}

    static Process start(
            Path temporary,
            String jdbcUrl,
            String user,
            String password,
            String bootstrap,
            Path state,
            String prefix,
            Path log,
            List<String> options)
            throws Exception {
        var arguments = new ArrayList<>(List.of(jdbcUrl, user, password, bootstrap, state.toString(), prefix));
        arguments.addAll(options);
        return startMain(temporary, ProjectionProcessMain.class.getName(), arguments, log);
    }

    static Process startMain(Path temporary, String main, List<String> arguments, Path log) throws Exception {
        Path args = temporary.resolve("args-" + UUID.randomUUID() + ".txt");
        var values = new ArrayList<>(List.of(
                "-Xmx512m",
                "-cp",
                Files.readString(Path.of(System.getProperty("projection.test.classpath.file"))),
                main));
        values.addAll(arguments);
        Files.writeString(
                args,
                values.stream()
                        .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                        .collect(java.util.stream.Collectors.joining("\n")));
        String java = Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                .toString();
        return new ProcessBuilder(java, "@" + args)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
    }
}
