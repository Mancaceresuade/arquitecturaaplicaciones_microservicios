package com.uade.exam.cliente;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Extensión JUnit 5 que registra el resultado de cada test y al finalizar
 * escribe un reporte JSON en target/exam-report/resultado-examen.json
 * con el legajo del alumno, el serial number de la PC y el detalle de cada test.
 */
public class ExamReportExtension implements TestWatcher, AfterAllCallback {

    private final List<TestResult> results = new ArrayList<>();

    @Override
    public void testSuccessful(ExtensionContext context) {
        results.add(new TestResult(context.getDisplayName(), "PASSED", null));
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        results.add(new TestResult(context.getDisplayName(), "FAILED", cause.getMessage()));
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        results.add(new TestResult(context.getDisplayName(), "ABORTED", cause.getMessage()));
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        results.add(new TestResult(context.getDisplayName(), "DISABLED", reason.orElse(null)));
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        String studentId = System.getenv().getOrDefault("EXAM_STUDENT_ID", "NO-ASIGNADO");
        String hostname  = resolveHostname();
        String serial    = resolveSerialNumber();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        long passed = results.stream().filter(r -> "PASSED".equals(r.status)).count();
        long total  = results.size();
        boolean approved = passed == total && total > 0;

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"timestamp\": \"").append(timestamp).append("\",\n");
        json.append("  \"studentId\": \"").append(studentId).append("\",\n");
        json.append("  \"hostname\": \"").append(hostname).append("\",\n");
        json.append("  \"serialNumber\": \"").append(serial).append("\",\n");
        json.append("  \"result\": \"").append(approved ? "APROBADO" : "DESAPROBADO").append("\",\n");
        json.append("  \"passed\": ").append(passed).append(",\n");
        json.append("  \"total\": ").append(total).append(",\n");
        json.append("  \"tests\": [\n");

        for (int i = 0; i < results.size(); i++) {
            TestResult r = results.get(i);
            json.append("    {\n");
            json.append("      \"name\": \"").append(escape(r.name)).append("\",\n");
            json.append("      \"status\": \"").append(r.status).append("\"");
            if (r.errorMessage != null) {
                json.append(",\n      \"error\": \"").append(escape(r.errorMessage)).append("\"");
            }
            json.append("\n    }").append(i < results.size() - 1 ? "," : "").append("\n");
        }
        json.append("  ]\n");
        json.append("}\n");

        Path reportDir = Paths.get("target", "exam-report");
        Files.createDirectories(reportDir);
        Path reportFile = reportDir.resolve("resultado-examen.json");
        Files.writeString(reportFile, json.toString());

        System.out.println("\n========================================");
        System.out.println("  REPORTE DE EXAMEN GENERADO");
        System.out.println("  Legajo  : " + studentId);
        System.out.println("  Hostname: " + hostname);
        System.out.println("  Serial  : " + serial);
        System.out.println("  Tests   : " + passed + "/" + total + " OK");
        System.out.println("  Resultado: " + (approved ? "APROBADO" : "DESAPROBADO"));
        System.out.println("  Archivo : " + reportFile.toAbsolutePath());
        System.out.println("========================================\n");
    }

    private String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "desconocido";
        }
    }

    private String resolveSerialNumber() {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                return runCommand("wmic", "bios", "get", "serialnumber", "/value");
            } else if (os.contains("mac")) {
                return runCommand("system_profiler", "SPHardwareDataType");
            } else {
                // Linux — puede requerir sudo; se intenta de todas formas
                return runCommand("dmidecode", "-s", "system-serial-number");
            }
        } catch (IOException | InterruptedException e) {
            return "no-disponible (" + e.getMessage() + ")";
        }
    }

    private String runCommand(String... cmd) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        process.waitFor();

        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            // wmic devuelve "SerialNumber=XXXXX"
            for (String line : output.split("[\\r\\n]+")) {
                if (line.startsWith("SerialNumber=")) {
                    String value = line.substring("SerialNumber=".length()).trim();
                    return value.isEmpty() ? "no-disponible" : value;
                }
            }
        } else if (os.contains("mac")) {
            for (String line : output.split("[\\r\\n]+")) {
                if (line.trim().startsWith("Serial Number")) {
                    String[] parts = line.split(":");
                    return parts.length > 1 ? parts[1].trim() : "no-disponible";
                }
            }
        } else {
            return output.isEmpty() ? "no-disponible" : output;
        }
        return "no-disponible";
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private record TestResult(String name, String status, String errorMessage) {}
}
