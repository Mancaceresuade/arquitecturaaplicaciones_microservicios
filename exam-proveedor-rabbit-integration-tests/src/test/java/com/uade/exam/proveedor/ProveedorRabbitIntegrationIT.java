package com.uade.exam.proveedor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integración: login y CRUD solo vía API Gateway; verificación AMQP según
 * {@code ejercicio-como-parcial-proveedor-rabbitmq.md}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProveedorRabbitIntegrationIT {

    private static final String EXCHANGE = "proveedor.exchange";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static Connection rabbitConnection;
    private static Channel rabbitChannel;
    private static String auditQueueName;
    private static String gatewayBase;
    private static String examStudentId;
    private static String examMachineId;
    private static String jwt;

    private static long proveedorId;

    @BeforeAll
    static void setup() throws Exception {
        examStudentId = System.getenv().getOrDefault("EXAM_STUDENT_ID", "NO-ASIGNADO");
        examMachineId = machineId();

        awaitGatewayReady();

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbitHostForConnection());
        factory.setPort(Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_PORT", "5672")));
        factory.setUsername(System.getenv().getOrDefault("RABBITMQ_USERNAME", "guest"));
        factory.setPassword(System.getenv().getOrDefault("RABBITMQ_PASSWORD", "guest"));
        rabbitConnection = factory.newConnection();
        rabbitChannel = rabbitConnection.createChannel();
        rabbitChannel.exchangeDeclarePassive(EXCHANGE);
        auditQueueName = rabbitChannel.queueDeclare("", false, true, true, null).getQueue();
        rabbitChannel.queueBind(auditQueueName, EXCHANGE, "proveedor.#");

        jwt = login();
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

    /**
     * En WSL2, {@code 127.0.0.1} es el loopback de la VM Linux, no el Windows donde suele correr el gateway en el IDE.
     * El primer {@code nameserver} de {@code /etc/resolv.conf} suele ser la IP del host Windows.
     */
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

    private static String normalizeLoopbackHost(String host) {
        return host != null && host.equalsIgnoreCase("localhost") ? "127.0.0.1" : host;
    }

    /** Si {@code RABBITMQ_HOST} no está definido y corremos en WSL, el broker en Windows/Docker suele estar en la IP del host. */
    private static String rabbitHostForConnection() {
        String env = System.getenv("RABBITMQ_HOST");
        if (env != null && !env.isBlank()) {
            return normalizeLoopbackHost(env.trim());
        }
        if (isProbablyWsl()) {
            String w = wslWindowsHostFromResolv();
            if (w != null) {
                return w;
            }
        }
        return "127.0.0.1";
    }

    /**
     * Espera a que el proceso del gateway acepte HTTP. No exige 200 en health: Spring Cloud suele devolver
     * {@code 503} en {@code /actuator/health} mientras Eureka o dependencias no están UP; igual el gateway ya enruta.
     * Prueba varias URLs base (WSL → host Windows, etc.).
     */
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
                                ProveedorRabbitIntegrationIT.gatewayBase = base;
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
                                    + ". Levantá api-gateway en el puerto 8080 o definí GATEWAY_BASE_URL con la URL exacta "
                                    + "(misma máquina donde corre Maven). Si Maven está en WSL y el gateway en Windows, "
                                    + "debería usarse solo el candidato con la IP del host; si falla, exportá GATEWAY_BASE_URL manualmente. "
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

    @AfterAll
    static void tearDown() throws Exception {
        if (rabbitChannel != null && rabbitChannel.isOpen()) {
            rabbitChannel.close();
        }
        if (rabbitConnection != null && rabbitConnection.isOpen()) {
            rabbitConnection.close();
        }
    }

    @Test
    @Order(1)
    void postProveedor_emitsCreated() throws Exception {
        String requestJson = """
                {"nombre":"Proveedor Ejemplo SRL","telefono":"+54 11 1234-5678"}
                """;
        HttpResponse<String> response = postJson("/api/proveedores", requestJson);
        assertEquals(201, response.statusCode(), response.body());

        JsonNode created = JSON.readTree(response.body());
        assertTrue(created.hasNonNull("id"));
        assertTrue(created.hasNonNull("nombre"));
        assertTrue(created.hasNonNull("telefono"));
        proveedorId = created.get("id").asLong();

        JsonNode event = awaitEvent("proveedor.created");
        assertEquals("PROVEEDOR_CREATED", text(event, "eventType"));
        assertEquals(proveedorId, event.get("proveedorId").asLong());
        assertEquals("Proveedor Ejemplo SRL", text(event, "nombre"));
        assertEquals("+54 11 1234-5678", text(event, "telefono"));
        assertValidOccurredAt(event);
    }

    @Test
    @Order(2)
    void putProveedor_emitsUpdated() throws Exception {
        String requestJson = """
                {"nombre":"Proveedor Editado SA","telefono":"+54 11 9999-0000"}
                """;
        HttpResponse<String> response = putJson("/api/proveedores/" + proveedorId, requestJson);
        assertEquals(200, response.statusCode(), response.body());

        JsonNode updated = JSON.readTree(response.body());
        assertEquals(proveedorId, updated.get("id").asLong());

        JsonNode event = awaitEvent("proveedor.updated");
        assertEquals("PROVEEDOR_UPDATED", text(event, "eventType"));
        assertEquals(proveedorId, event.get("proveedorId").asLong());
        assertEquals("Proveedor Editado SA", text(event, "nombre"));
        assertEquals("+54 11 9999-0000", text(event, "telefono"));
        assertValidOccurredAt(event);
    }

    @Test
    @Order(3)
    void deleteProveedor_emitsDeleted() throws Exception {
        HttpResponse<String> response = delete("/api/proveedores/" + proveedorId);
        assertEquals(204, response.statusCode(), response.body());

        JsonNode event = awaitEvent("proveedor.deleted");
        assertEquals("PROVEEDOR_DELETED", text(event, "eventType"));
        assertEquals(proveedorId, event.get("proveedorId").asLong());
        assertTrue(event.hasNonNull("nombre"));
        assertTrue(event.hasNonNull("telefono"));
        assertValidOccurredAt(event);
    }

    private static String text(JsonNode node, String field) {
        assertTrue(node.hasNonNull(field), "Falta campo JSON: " + field);
        return node.get(field).asText();
    }

    private static void assertValidOccurredAt(JsonNode event) {
        String raw = text(event, "occurredAt");
        Instant.parse(raw);
    }

    private JsonNode awaitEvent(String expectedRoutingKey) {
        final JsonNode[] holder = new JsonNode[1];
        Awaitility.await()
                .atMost(45, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> {
                    GetResponse get = rabbitChannel.basicGet(auditQueueName, false);
                    if (get == null) {
                        return false;
                    }
                    long deliveryTag = get.getEnvelope().getDeliveryTag();
                    if (!expectedRoutingKey.equals(get.getEnvelope().getRoutingKey())) {
                        rabbitChannel.basicNack(deliveryTag, false, true);
                        return false;
                    }
                    AMQP.BasicProperties props = get.getProps();
                    if (props != null && props.getContentType() != null) {
                        assertTrue(props.getContentType().contains("json"),
                                "Content-Type debería ser application/json: " + props.getContentType());
                    }
                    byte[] body = get.getBody();
                    holder[0] = JSON.readTree(new String(body, StandardCharsets.UTF_8));
                    rabbitChannel.basicAck(deliveryTag, false);
                    return true;
                });
        assertNotNull(holder[0]);
        return holder[0];
    }

    private HttpResponse<String> postJson(String path, String json) throws Exception {
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

    private HttpResponse<String> putJson(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(gatewayBase + path))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + jwt)
                .header("Content-Type", "application/json")
                .header("X-Exam-Student-Id", examStudentId)
                .header("X-Exam-Machine-Id", examMachineId)
                .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> delete(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(gatewayBase + path))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + jwt)
                .header("X-Exam-Student-Id", examStudentId)
                .header("X-Exam-Machine-Id", examMachineId)
                .DELETE()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
