package com.uade.exam.proveedor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integración del parcial {@code proveedor-service}.
 *
 * <p>Valida el CRUD solo a través del {@code api-gateway} (HTTP) y, además, abre un
 * consumidor Kafka real sobre el topic {@code proveedor.events} para comprobar que cada
 * operación de escritura (POST/PUT/DELETE) publica un registro con la key y el JSON
 * esperados, según {@code parcial2_tema1_enunciado.md}.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(ExamReportExtension.class)
class ProveedorIntegrationIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final String TOPIC = "proveedor.events";

    private static String gatewayBase;
    private static String examStudentId;
    private static String examMachineId;
    private static String jwt;

    private static KafkaEventCollector kafka;

    private static long proveedorId;

    @BeforeAll
    static void setup() throws Exception {
        examStudentId = System.getenv().getOrDefault("EXAM_STUDENT_ID", "NO-ASIGNADO");
        examMachineId = machineId();
        awaitGatewayReady();
        jwt = login();
        kafka = new KafkaEventCollector(bootstrapServers(), TOPIC);
    }

    @AfterAll
    static void tearDown() {
        if (kafka != null) {
            kafka.close();
        }
    }

    @Test
    @Order(1)
    void postProveedor_returns201() throws Exception {
        String requestJson = """
                {"nombre":"ACME S.A.","telefono":"+54 11 5555-1234"}
                """;
        HttpResponse<String> response = postJson("/api/proveedores", requestJson);
        assertEquals(201, response.statusCode(), response.body());

        JsonNode created = JSON.readTree(response.body());
        assertTrue(created.hasNonNull("id"), "Debe incluir id generado");
        assertEquals("ACME S.A.", text(created, "nombre"));
        assertEquals("+54 11 5555-1234", text(created, "telefono"));
        proveedorId = created.get("id").asLong();
    }

    @Test
    @Order(2)
    void postProveedor_publishesCreatedEvent() {
        JsonNode event = kafka.awaitEvent("proveedor.created", proveedorId, 20);
        assertEquals("PROVEEDOR_CREATED", text(event, "eventType"));
        assertEquals(proveedorId, event.get("proveedorId").asLong());
        assertEquals("ACME S.A.", text(event, "nombre"));
        assertEquals("+54 11 5555-1234", text(event, "telefono"));
        assertTrue(event.hasNonNull("occurredAt"), "El evento debe incluir occurredAt (ISO-8601)");
    }

    @Test
    @Order(3)
    void getProveedorById_returns200() throws Exception {
        HttpResponse<String> response = get("/api/proveedores/" + proveedorId);
        assertEquals(200, response.statusCode(), response.body());

        JsonNode proveedor = JSON.readTree(response.body());
        assertEquals(proveedorId, proveedor.get("id").asLong());
        assertEquals("ACME S.A.", text(proveedor, "nombre"));
    }

    @Test
    @Order(4)
    void getProveedores_listContainsCreated() throws Exception {
        HttpResponse<String> response = get("/api/proveedores");
        assertEquals(200, response.statusCode(), response.body());

        JsonNode list = JSON.readTree(response.body());
        assertTrue(list.isArray(), "La respuesta debe ser un array");
        boolean found = false;
        for (JsonNode item : list) {
            if (item.hasNonNull("id") && item.get("id").asLong() == proveedorId) {
                found = true;
                break;
            }
        }
        assertTrue(found, "El listado debe incluir el proveedor creado con id " + proveedorId);
    }

    @Test
    @Order(5)
    void putProveedor_returns200() throws Exception {
        String requestJson = """
                {"nombre":"ACME S.R.L.","telefono":"+54 11 4444-9999"}
                """;
        HttpResponse<String> response = putJson("/api/proveedores/" + proveedorId, requestJson);
        assertEquals(200, response.statusCode(), response.body());

        JsonNode updated = JSON.readTree(response.body());
        assertEquals(proveedorId, updated.get("id").asLong());
        assertEquals("ACME S.R.L.", text(updated, "nombre"));
        assertEquals("+54 11 4444-9999", text(updated, "telefono"));
    }

    @Test
    @Order(6)
    void putProveedor_publishesUpdatedEvent() {
        JsonNode event = kafka.awaitEvent("proveedor.updated", proveedorId, 20);
        assertEquals("PROVEEDOR_UPDATED", text(event, "eventType"));
        assertEquals(proveedorId, event.get("proveedorId").asLong());
        assertEquals("ACME S.R.L.", text(event, "nombre"));
        assertEquals("+54 11 4444-9999", text(event, "telefono"));
        assertTrue(event.hasNonNull("occurredAt"), "El evento debe incluir occurredAt (ISO-8601)");
    }

    @Test
    @Order(7)
    void postProveedor_emptyNombre_returns400() throws Exception {
        String requestJson = """
                {"nombre":"","telefono":"+54 11 5555-0000"}
                """;
        HttpResponse<String> response = postJson("/api/proveedores", requestJson);
        assertEquals(400, response.statusCode(), response.body());
    }

    @Test
    @Order(8)
    void postProveedor_missingTelefono_returns400() throws Exception {
        String requestJson = """
                {"nombre":"Sin Telefono"}
                """;
        HttpResponse<String> response = postJson("/api/proveedores", requestJson);
        assertEquals(400, response.statusCode(), response.body());
    }

    @Test
    @Order(9)
    void getProveedorById_notFound_returns404() throws Exception {
        HttpResponse<String> response = get("/api/proveedores/999999999");
        assertEquals(404, response.statusCode(), response.body());
    }

    @Test
    @Order(10)
    void putProveedor_notFound_returns404() throws Exception {
        String requestJson = """
                {"nombre":"Fantasma","telefono":"000"}
                """;
        HttpResponse<String> response = putJson("/api/proveedores/999999999", requestJson);
        assertEquals(404, response.statusCode(), response.body());
    }

    @Test
    @Order(11)
    void deleteProveedor_returns204() throws Exception {
        HttpResponse<String> response = delete("/api/proveedores/" + proveedorId);
        assertEquals(204, response.statusCode(), response.body());
    }

    @Test
    @Order(12)
    void deleteProveedor_publishesDeletedEvent() {
        JsonNode event = kafka.awaitEvent("proveedor.deleted", proveedorId, 20);
        assertEquals("PROVEEDOR_DELETED", text(event, "eventType"));
        assertEquals(proveedorId, event.get("proveedorId").asLong());
        assertTrue(event.hasNonNull("occurredAt"), "El evento debe incluir occurredAt (ISO-8601)");
    }

    @Test
    @Order(13)
    void deleteProveedor_notFound_returns404() throws Exception {
        HttpResponse<String> response = delete("/api/proveedores/999999999");
        assertEquals(404, response.statusCode(), response.body());
    }

    @Test
    @Order(14)
    void getProveedores_withoutJwt_returns401() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(gatewayBase + "/api/proveedores"))
                .timeout(Duration.ofSeconds(30))
                .header("X-Exam-Student-Id", examStudentId)
                .header("X-Exam-Machine-Id", examMachineId)
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(401, response.statusCode(), response.body());
    }

    // ---------------------------------------------------------------------
    // Consumidor Kafka
    // ---------------------------------------------------------------------

    /**
     * Consumidor Kafka que se posiciona al final del log al iniciar (solo ve eventos
     * nuevos) y acumula los registros recibidos para poder buscarlos por key + proveedorId.
     */
    private static final class KafkaEventCollector implements AutoCloseable {

        private final KafkaConsumer<String, String> consumer;
        private final List<ConsumerRecord<String, String>> buffer = new ArrayList<>();

        KafkaEventCollector(String bootstrapServers, String topic) {
            Properties props = new Properties();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "exam-proveedor-" + UUID.randomUUID());
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
            this.consumer = new KafkaConsumer<>(props);
            this.consumer.subscribe(List.of(topic));
            warmUp();
        }

        /** Fuerza el join al grupo y posiciona el consumidor al final antes de producir. */
        private void warmUp() {
            long deadline = System.currentTimeMillis() + 30_000;
            while (consumer.assignment().isEmpty() && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500));
            }
            if (consumer.assignment().isEmpty()) {
                fail("No se pudo obtener asignación de particiones del topic " + TOPIC
                        + ". ¿El broker Kafka está en " + bootstrapServers() + " y el topic existe?");
            }
            consumer.seekToEnd(consumer.assignment());
            consumer.poll(Duration.ofMillis(200));
        }

        /**
         * Espera hasta {@code timeoutSeconds} a que llegue un evento con la key indicada y
         * {@code proveedorId} coincidente. Devuelve el value parseado como JSON.
         */
        JsonNode awaitEvent(String expectedKey, long expectedProveedorId, int timeoutSeconds) {
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                records.forEach(buffer::add);
                JsonNode match = findInBuffer(expectedKey, expectedProveedorId);
                if (match != null) {
                    return match;
                }
            }
            return fail("No llegó ningún evento Kafka con key='" + expectedKey + "' y proveedorId="
                    + expectedProveedorId + " en el topic '" + TOPIC + "' dentro de " + timeoutSeconds
                    + "s. Verificá que proveedor-service publique con la key y el JSON del enunciado.");
        }

        private JsonNode findInBuffer(String expectedKey, long expectedProveedorId) {
            for (ConsumerRecord<String, String> record : buffer) {
                if (!expectedKey.equals(record.key())) {
                    continue;
                }
                if (record.value() == null) {
                    continue;
                }
                try {
                    JsonNode value = JSON.readTree(record.value());
                    if (value.hasNonNull("proveedorId")
                            && value.get("proveedorId").asLong() == expectedProveedorId) {
                        return value;
                    }
                } catch (IOException ignored) {
                    // value que no es JSON: lo ignoramos y seguimos buscando
                }
            }
            return null;
        }

        @Override
        public void close() {
            consumer.close(Duration.ofSeconds(5));
        }
    }

    private static String bootstrapServers() {
        String env = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
        return (env == null || env.isBlank()) ? "127.0.0.1:9092" : env.trim();
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
                                ProveedorIntegrationIT.gatewayBase = base;
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
