package com.uade.exam.cliente;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integración del parcial {@code cliente-service}.
 *
 * <p>Valida el CRUD solo a través del {@code api-gateway} (HTTP) y, además, declara una
 * cola temporal bindeada al exchange topic {@code cliente.exchange} para comprobar que cada
 * operación de escritura (POST/PUT/DELETE) publica un mensaje con la routing key y el JSON
 * esperados, según {@code parcial2_tema2_enunciado.md}.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(ExamReportExtension.class)
class ClienteIntegrationIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final String EXCHANGE = "cliente.exchange";

    private static String gatewayBase;
    private static String examStudentId;
    private static String examMachineId;
    private static String jwt;

    private static AmqpEventCollector amqp;

    private static long clienteId;

    @BeforeAll
    static void setup() throws Exception {
        examStudentId = System.getenv().getOrDefault("EXAM_STUDENT_ID", "NO-ASIGNADO");
        examMachineId = machineId();
        awaitGatewayReady();
        jwt = login();
        amqp = new AmqpEventCollector(EXCHANGE);
    }

    @AfterAll
    static void tearDown() {
        if (amqp != null) {
            amqp.close();
        }
    }

    @Test
    @Order(1)
    void postCliente_returns201() throws Exception {
        String requestJson = """
                {"nombre":"Juan Pérez","telefono":"+54 11 5555-1234"}
                """;
        HttpResponse<String> response = postJson("/api/clientes", requestJson);
        assertEquals(201, response.statusCode(), response.body());

        JsonNode created = JSON.readTree(response.body());
        assertTrue(created.hasNonNull("id"), "Debe incluir id generado");
        assertEquals("Juan Pérez", text(created, "nombre"));
        assertEquals("+54 11 5555-1234", text(created, "telefono"));
        clienteId = created.get("id").asLong();
    }

    @Test
    @Order(2)
    void postCliente_publishesCreatedEvent() {
        JsonNode event = amqp.awaitEvent("cliente.created", clienteId, 20);
        assertEquals("CLIENTE_CREATED", text(event, "eventType"));
        assertEquals(clienteId, event.get("clienteId").asLong());
        assertEquals("Juan Pérez", text(event, "nombre"));
        assertEquals("+54 11 5555-1234", text(event, "telefono"));
        assertTrue(event.hasNonNull("occurredAt"), "El evento debe incluir occurredAt (ISO-8601)");
    }

    @Test
    @Order(3)
    void getClienteById_returns200() throws Exception {
        HttpResponse<String> response = get("/api/clientes/" + clienteId);
        assertEquals(200, response.statusCode(), response.body());

        JsonNode cliente = JSON.readTree(response.body());
        assertEquals(clienteId, cliente.get("id").asLong());
        assertEquals("Juan Pérez", text(cliente, "nombre"));
    }

    @Test
    @Order(4)
    void getClientes_listContainsCreated() throws Exception {
        HttpResponse<String> response = get("/api/clientes");
        assertEquals(200, response.statusCode(), response.body());

        JsonNode list = JSON.readTree(response.body());
        assertTrue(list.isArray(), "La respuesta debe ser un array");
        boolean found = false;
        for (JsonNode item : list) {
            if (item.hasNonNull("id") && item.get("id").asLong() == clienteId) {
                found = true;
                break;
            }
        }
        assertTrue(found, "El listado debe incluir el cliente creado con id " + clienteId);
    }

    @Test
    @Order(5)
    void putCliente_returns200() throws Exception {
        String requestJson = """
                {"nombre":"Juan C. Pérez","telefono":"+54 11 4444-9999"}
                """;
        HttpResponse<String> response = putJson("/api/clientes/" + clienteId, requestJson);
        assertEquals(200, response.statusCode(), response.body());

        JsonNode updated = JSON.readTree(response.body());
        assertEquals(clienteId, updated.get("id").asLong());
        assertEquals("Juan C. Pérez", text(updated, "nombre"));
        assertEquals("+54 11 4444-9999", text(updated, "telefono"));
    }

    @Test
    @Order(6)
    void putCliente_publishesUpdatedEvent() {
        JsonNode event = amqp.awaitEvent("cliente.updated", clienteId, 20);
        assertEquals("CLIENTE_UPDATED", text(event, "eventType"));
        assertEquals(clienteId, event.get("clienteId").asLong());
        assertEquals("Juan C. Pérez", text(event, "nombre"));
        assertEquals("+54 11 4444-9999", text(event, "telefono"));
        assertTrue(event.hasNonNull("occurredAt"), "El evento debe incluir occurredAt (ISO-8601)");
    }

    @Test
    @Order(7)
    void postCliente_emptyNombre_returns400() throws Exception {
        String requestJson = """
                {"nombre":"","telefono":"+54 11 5555-0000"}
                """;
        HttpResponse<String> response = postJson("/api/clientes", requestJson);
        assertEquals(400, response.statusCode(), response.body());
    }

    @Test
    @Order(8)
    void postCliente_missingTelefono_returns400() throws Exception {
        String requestJson = """
                {"nombre":"Sin Telefono"}
                """;
        HttpResponse<String> response = postJson("/api/clientes", requestJson);
        assertEquals(400, response.statusCode(), response.body());
    }

    @Test
    @Order(9)
    void getClienteById_notFound_returns404() throws Exception {
        HttpResponse<String> response = get("/api/clientes/999999999");
        assertEquals(404, response.statusCode(), response.body());
    }

    @Test
    @Order(10)
    void putCliente_notFound_returns404() throws Exception {
        String requestJson = """
                {"nombre":"Fantasma","telefono":"000"}
                """;
        HttpResponse<String> response = putJson("/api/clientes/999999999", requestJson);
        assertEquals(404, response.statusCode(), response.body());
    }

    @Test
    @Order(11)
    void deleteCliente_returns204() throws Exception {
        HttpResponse<String> response = delete("/api/clientes/" + clienteId);
        assertEquals(204, response.statusCode(), response.body());
    }

    @Test
    @Order(12)
    void deleteCliente_publishesDeletedEvent() {
        JsonNode event = amqp.awaitEvent("cliente.deleted", clienteId, 20);
        assertEquals("CLIENTE_DELETED", text(event, "eventType"));
        assertEquals(clienteId, event.get("clienteId").asLong());
        assertTrue(event.hasNonNull("occurredAt"), "El evento debe incluir occurredAt (ISO-8601)");
    }

    @Test
    @Order(13)
    void deleteCliente_notFound_returns404() throws Exception {
        HttpResponse<String> response = delete("/api/clientes/999999999");
        assertEquals(404, response.statusCode(), response.body());
    }

    @Test
    @Order(14)
    void getClientes_withoutJwt_returns401() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(gatewayBase + "/api/clientes"))
                .timeout(Duration.ofSeconds(30))
                .header("X-Exam-Student-Id", examStudentId)
                .header("X-Exam-Machine-Id", examMachineId)
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(401, response.statusCode(), response.body());
    }

    // ---------------------------------------------------------------------
    // Consumidor RabbitMQ
    // ---------------------------------------------------------------------

    /**
     * Declara una cola temporal bindeada al exchange topic con routing key {@code cliente.#}
     * y acumula los mensajes recibidos para poder buscarlos por routing key + clienteId.
     */
    private static final class AmqpEventCollector implements AutoCloseable {

        private final Connection connection;
        private final Channel channel;
        private final String queue;
        private final List<Delivery> buffer = new ArrayList<>();

        private record Delivery(String routingKey, JsonNode body) {}

        AmqpEventCollector(String exchange) {
            try {
                ConnectionFactory factory = new ConnectionFactory();
                factory.setHost(env("RABBITMQ_HOST", "localhost"));
                factory.setPort(Integer.parseInt(env("RABBITMQ_PORT", "5672")));
                factory.setUsername(env("RABBITMQ_USERNAME", "guest"));
                factory.setPassword(env("RABBITMQ_PASSWORD", "guest"));
                this.connection = factory.newConnection("exam-cliente-it");
                this.channel = connection.createChannel();
                channel.exchangeDeclare(exchange, "topic", true);
                this.queue = channel.queueDeclare().getQueue();
                channel.queueBind(queue, exchange, "cliente.#");
            } catch (Exception e) {
                throw new AssertionError("No se pudo conectar a RabbitMQ en "
                        + env("RABBITMQ_HOST", "localhost") + ":" + env("RABBITMQ_PORT", "5672")
                        + " ni declarar el exchange '" + exchange + "'. ¿El broker está corriendo? "
                        + e.getMessage(), e);
            }
        }

        /**
         * Espera hasta {@code timeoutSeconds} a que llegue un mensaje con la routing key indicada
         * y {@code clienteId} coincidente. Devuelve el cuerpo parseado como JSON.
         */
        JsonNode awaitEvent(String expectedRoutingKey, long expectedClienteId, int timeoutSeconds) {
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                drain();
                JsonNode match = findInBuffer(expectedRoutingKey, expectedClienteId);
                if (match != null) {
                    return match;
                }
                sleep(300);
            }
            return fail("No llegó ningún mensaje RabbitMQ con routing key='" + expectedRoutingKey
                    + "' y clienteId=" + expectedClienteId + " al exchange '" + EXCHANGE + "' dentro de "
                    + timeoutSeconds + "s. Verificá que cliente-service publique con la routing key y el JSON del enunciado.");
        }

        private void drain() {
            try {
                GetResponse resp;
                while ((resp = channel.basicGet(queue, true)) != null) {
                    String rk = resp.getEnvelope().getRoutingKey();
                    try {
                        JsonNode body = JSON.readTree(new String(resp.getBody(), StandardCharsets.UTF_8));
                        buffer.add(new Delivery(rk, body));
                    } catch (IOException ignored) {
                        // mensaje cuyo body no es JSON: lo ignoramos
                    }
                }
            } catch (IOException e) {
                fail("Error leyendo de la cola temporal de RabbitMQ: " + e.getMessage());
            }
        }

        private JsonNode findInBuffer(String expectedRoutingKey, long expectedClienteId) {
            for (Delivery d : buffer) {
                if (!expectedRoutingKey.equals(d.routingKey())) {
                    continue;
                }
                JsonNode body = d.body();
                if (body.hasNonNull("clienteId") && body.get("clienteId").asLong() == expectedClienteId) {
                    return body;
                }
            }
            return null;
        }

        private static void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private static String env(String key, String def) {
            String v = System.getenv(key);
            return (v == null || v.isBlank()) ? def : v.trim();
        }

        @Override
        public void close() {
            try {
                if (channel != null && channel.isOpen()) {
                    channel.close();
                }
            } catch (Exception ignored) {
                // best effort
            }
            try {
                if (connection != null && connection.isOpen()) {
                    connection.close();
                }
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    // ---------------------------------------------------------------------
    // Helpers HTTP / gateway
    // ---------------------------------------------------------------------

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
                                ClienteIntegrationIT.gatewayBase = base;
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

    private static HttpResponse<String> putJson(String path, String json) throws Exception {
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

    private static HttpResponse<String> delete(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(gatewayBase + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + jwt)
                .header("X-Exam-Student-Id", examStudentId)
                .header("X-Exam-Machine-Id", examMachineId)
                .DELETE()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
