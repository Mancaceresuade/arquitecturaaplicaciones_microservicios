package com.uade.exam.cliente;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Genera {@code target/exam-report/resultado-examen.json} al finalizar la suite.
 */
class ExamReportExtension implements TestWatcher, AfterAllCallback {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<TestResult> RESULTS = new ArrayList<>();

    private record TestResult(String nombre, String estado, String detalle) {}

    @Override
    public void testSuccessful(ExtensionContext context) {
        RESULTS.add(new TestResult(displayName(context), "PASSED", null));
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        RESULTS.add(new TestResult(displayName(context), "FAILED", cause.getMessage()));
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        RESULTS.add(new TestResult(displayName(context), "ABORTED", cause.getMessage()));
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        RESULTS.add(new TestResult(displayName(context), "SKIPPED",
                reason.orElse("deshabilitado")));
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        boolean allPassed = RESULTS.stream().allMatch(r -> "PASSED".equals(r.estado()));
        ObjectNode report = JSON.createObjectNode();
        report.put("resultado", allPassed && !RESULTS.isEmpty() ? "APROBADO" : "REPROBADO");
        report.put("timestamp", Instant.now().toString());
        report.put("examStudentId", System.getenv().getOrDefault("EXAM_STUDENT_ID", "NO-ASIGNADO"));
        report.put("examMachineId", machineHostname());
        report.put("machineFingerprint", fingerprint());
        report.put("suite", context.getRequiredTestClass().getSimpleName());

        ArrayNode tests = report.putArray("tests");
        for (TestResult r : RESULTS) {
            ObjectNode node = tests.addObject();
            node.put("nombre", r.nombre());
            node.put("estado", r.estado());
            if (r.detalle() != null) {
                node.put("detalle", r.detalle());
            }
        }

        Path outDir = Path.of("target", "exam-report");
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve("resultado-examen.json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(outFile.toFile(), report);
    }

    private static String displayName(ExtensionContext context) {
        return context.getDisplayName().isBlank()
                ? context.getRequiredTestMethod().getName()
                : context.getDisplayName();
    }

    private static String machineHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return "unknown-host";
        }
    }

    /** Indicador estable por PC (no es seguridad): hash corto de hostname + usuario + SO. */
    private static String fingerprint() {
        String raw = machineHostname() + "|"
                + System.getProperty("user.name") + "|"
                + System.getProperty("os.name") + "|"
                + System.getProperty("os.arch");
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "no-fingerprint";
        }
    }
}
