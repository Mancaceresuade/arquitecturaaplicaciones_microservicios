package com.uade.exam.pedido;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integración: login y CRUD de pedidos solo vía API Gateway, según {@code parcial2.md}.
 * El test 3 valida el flujo Kafka (productor → consumidor → estado CONFIRMADO).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(ExamReportExtension.class)
class PedidoIntegrationIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static String gatewayBase;
    private static String examStudentId;
    private static String examMachineId;
    private static String jwt;

    private static long pedidoId;

    @BeforeAll
    static void setup() throws Exception {
        examStudentId = System.getenv().getOrDefault("EXAM_STUDENT_ID", "NO-ASIGNADO");
        examMachineId = machineId();
        awaitGatewayReady();
        jwt = login();
    }

    @Test
    @Order(1)
    void postPedido_returns201WithPendiente() throws Exception {
        String requestJson = """
                {"descripcion":"Laptop Dell XPS","cantidad":2}
                """;
        HttpResponse<String> response = postJson("/api/pedidos", requestJson);
        assertEquals(201, response.statusCode(), response.body());

        JsonNode created = JSON.readTree(response.body());
        assertTrue(created.hasNonNull("id"), "Debe incluir id generado");
        assertEquals("Laptop Dell XPS", text(created, "descripcion"));
        assertEquals(2, created.get("cantidad").asInt());
        assertEquals("PENDIENTE", text(created, "estado"));
        pedidoId = created.get("id").asLong();
    }

    @Test
    @Order(2)
    void getPedidoById_immediate_returnsPendiente() throws Exception {
        HttpResponse<String> response = get("/api/pedidos/" + pedidoId);
        assertEquals(200, response.statusCode(), response.body());

        JsonNode pedido = JSON.readTree(response.body());
        assertEquals(pedidoId, pedido.get("id").asLong());
        assertEquals("PENDIENTE", text(pedido, "estado"));
    }

    @Test
    @Order(3)
    void getPedidoById_afterKafkaProcessing_returnsConfirmado() {
        Awaitility.await()
                .atMost(15, TimeUnit.SECONDS)
                .pollDelay(3, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    HttpResponse<String> response = get("/api/pedidos/" + pedidoId);
                    assertEquals(200, response.statusCode(), response.body());
                    JsonNode pedido = JSON.readTree(response.body());
                    assertEquals(pedidoId, pedido.get("id").asLong());
                    assertEquals("CONFIRMADO", text(pedido, "estado"),
                            "Kafka debe procesar el evento y confirmar el pedido");
                });
    }

    @Test
    @Order(4)
    void getPedidos_listContainsCreated() throws Exception {
        HttpResponse<String> response = get("/api/pedidos");
        assertEquals(200, response.statusCode(), response.body());

        JsonNode list = JSON.readTree(response.body());
        assertTrue(list.isArray(), "La respuesta debe ser un array");
        boolean found = false;
        for (JsonNode item : list) {
            if (item.hasNonNull("id") && item.get("id").asLong() == pedidoId) {
                found = true;
                assertEquals("CONFIRMADO", text(item, "estado"));
                break;
            }
        }
        assertTrue(found, "El listado debe incluir el pedido creado con id " + pedidoId);
    }

    @Test
    @Order(5)
    void postPedido_emptyDescripcion_returns400() throws Exception {
        String requestJson = """
                {"descripcion":"","cantidad":2}
                """;
        HttpResponse<String> response = postJson("/api/pedidos", requestJson);
        assertEquals(400, response.statusCode(), response.body());
    }

    @Test
    @Order(6)
    void postPedido_cantidadZero_returns400() throws Exception {
        String requestJson = """
                {"descripcion":"Monitor","cantidad":0}
                """;
        HttpResponse<String> response = postJson("/api/pedidos", requestJson);
        assertEquals(400, response.statusCode(), response.body());
    }

    @Test
    @Order(7)
    void getPedidoById_notFound_returns404() throws Exception {
        HttpResponse<String> response = get("/api/pedidos/999999999");
        assertEquals(404, response.statusCode(), response.body());
    }

    @Test
    @Order(8)
    void getPedidos_withoutJwt_returns401() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(gatewayBase + "/api/pedidos"))
                .timeout(Duration.ofSeconds(30))
                .header("X-Exam-Student-Id", examStudentId)
                .header("X-Exam-Machine-Id", examMachineId)
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(401, response.statusCode(), response.body());
    }

    private static String text(JsonNode node, String field) {
        assertTrue(node.hasNonNull(field), "Falta campo JSON: " + field);
        return node.get(field).asText();
    }

    private static String machineId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }

    private static String normalizeGatewayUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return "http://127.0.0.1:8080";
        }
        String u = raw.trim().replaceAll("/$", "");
        u = u.replace("://localhost", "://127.0.0.1");
        u = u.replace("://LOCALHOST", "://127.0.0.1");
        return u;
    }

    private static boolean isProbablyWsl() {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")) {
            return false;
        }
        try {
            String v = Files.readString(Path.of("/proc/version")).toLowerCase(Locale.ROOT);
            return v.contains("microsoft") || v.contains("wsl");
        } catch (IOException e) {
            return false;
        }
    }

    private static String wslWindowsHostFromResolv() {
        if (!isProbablyWsl()) {
            return null;
        }
        Path p = Path.of("/etc/resolv.conf");
        if (!Files.isReadable(p)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(p)) {
                String t = line.trim();
                if (t.startsWith("nameserver ")) {
                    String ip = t.substring("nameserver ".length()).trim();
                    if (ip.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
                        return ip;
                    }
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private static List<String> gatewayBaseCandidates() {
        Set<String> ordered = new LinkedHashSet<>();
        String env = System.getenv("GATEWAY_BASE_URL");
        if (env != null && !env.isBlank()) {
            ordered.add(normalizeGatewayUrl(env));
        }
        ordered.add("http://127.0.0.1:8080");
        ordered.add("http://localhost:8080");
        String wslHost = wslWindowsHostFromResolv();
        if (wslHost != null) {
            ordered.add("http://" + wslHost + ":8080");
        }
        return new ArrayList<>(ordered);
    }

    private static void awaitGatewayReady() {
        List<String> candidates = gatewayBaseCandidates();
        Awaitility.await()
                .atMost(90, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    AssertionError last = null;
                    for (String base : candidates) {
                        String healthUrl = base + "/actuator/health";
                        try {
                            HttpRequest req = HttpRequest.newBuilder()
                                    .uri(URI.create(healthUrl))
                                    .timeout(Duration.ofSeconds(10))
                                    .GET()
                                    .build();
                            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                            int code = resp.statusCode();
                            if (code >= 200 && code < 600) {
                                PedidoIntegrationIT.gatewayBase = base;
                                return;
                            }
                            last = new AssertionError("HTTP " + code + " en " + healthUrl + " body=" + resp.body());
                        } catch (Exception e) {
                            last = new AssertionError("Sin conexión a " + healthUrl + ": " + e.getClass().getSimpleName()
                                    + ": " + e.getMessage(), e);
                        }
                    }
                    throw new AssertionError(
                            "Ningún candidato de gateway respondió. Probados: " + candidates
                                    + ". Levantá api-gateway en el puerto 8080 o definí GATEWAY_BASE_URL. "
                                    + (last != null ? "Último intento: " + last.getMessage() : ""),
                            last);
                });
    }

    private static String login() throws Exception {
        String body = """
                {"username":"admin","password":"admin123"}
                """;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(gatewayBase + "/auth/login"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), "Login vía gateway debe responder 200: " + response.body());
        JsonNode node = JSON.readTree(response.body());
        assertTrue(node.hasNonNull("token"), "Respuesta de login debe incluir token");
        return node.get("token").asText();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(gatewayBase + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + jwt)
                .header("X-Exam-Student-Id", examStudentId)
                .header("X-Exam-Machine-Id", examMachineId)
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> postJson(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(gatewayBase + path))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + jwt)
                .header("Content-Type", "application/json")
                .header("X-Exam-Student-Id", examStudentId)
                .header("X-Exam-Machine-Id", examMachineId)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
