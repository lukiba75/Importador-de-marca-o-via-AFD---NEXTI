package br.com.lucas.automacaoapi;

import java.io.*;
import java.net.http.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Base64;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class AfdProcessor {

    private static final String BASE_URL = "https://api.nexti.com";
    private static final String CLIENT_ID = "";
    private static final String CLIENT_SECRET = "";

    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    private static BufferedWriter logWriter; // para escrever o log em arquivo

    public static void main(String[] args) throws Exception {
        File afdFile = new File("C:\\Users\\batis\\AUTOMAÇÃO AFD\\AFD\\West Cargo\\West Cargo\\AFD\\consolidado 2.txt");
        File logFile = new File(afdFile.getParent(), "log_execucao.txt");

        // Inicializa o writer do log
        logWriter = new BufferedWriter(new FileWriter(logFile, true));
        logToFile("===============================================");
        logToFile("🕓 Iniciando processamento: " + LocalDateTime.now());
        logToFile("Arquivo AFD: " + afdFile.getAbsolutePath());
        logToFile("===============================================");

        try {
            String authToken = getOAuth2Token();
            try (BufferedReader br = new BufferedReader(new FileReader(afdFile))) {
                String line;
                int lineNumber = 0;

                while ((line = br.readLine()) != null) {
                    lineNumber++;
                    if (lineNumber <= 1) {
                        log("⏭ Ignorando linha inicial (" + lineNumber + "): " + line);
                        continue;
                    }

                    if (line.startsWith("999999")) {
                        log("⏭ Ignorando linha de rodapé: " + line);
                        continue;
                    }

                    if (line.matches("^[A-Za-z].*")) {
                        log("⏭ Ignorando linha com letra inicial: " + line);
                        continue;
                    }

                    if (line.length() < 45) {
                        logError("[ERRO] Linha " + lineNumber + " inválida ou incompleta: " + line);
                        continue;
                    }

                    log("\n------------------------------");
                    log("🔍 Lendo linha " + lineNumber + ": " + line);

                    String ano = line.substring(10, 14);
                    String mes = line.substring(15, 17);
                    String dia = line.substring(18, 20);
                    String hora = line.substring(21, 23);
                    String minuto = line.substring(24, 26);
                    String cpf = line.substring(34, 45).trim();

                    log("📅 Dados extraídos: " + ano + "-" + mes + "-" + dia + " " + hora + ":" + minuto + " | CPF: " + cpf);

                    int personId = getPersonIdByCpf(cpf, authToken);
                    if (personId == -1) {
                        logError("[ERRO] Não achou colaborador para CPF " + cpf);
                        continue;
                    }

                    log("✅ Colaborador encontrado. personId=" + personId);

                    int workplaceId = getLatestWorkplace(personId, authToken);
                    if (workplaceId == -1) {
                        logError("[ERRO] Não achou workplace para personId " + personId);
                        continue;
                    }

                    log("🏢 Último workplaceId: " + workplaceId);

                    String timezone = getTimezone(workplaceId, authToken);
                    log("🌎 Timezone: " + timezone);

                    LocalDateTime localDateTime = LocalDateTime.of(
                            Integer.parseInt(ano),
                            Integer.parseInt(mes),
                            Integer.parseInt(dia),
                            Integer.parseInt(hora),
                            Integer.parseInt(minuto)
                    );

                    ZoneId workplaceZone = ZoneId.of(timezone);
                    ZonedDateTime zonedDateTime = localDateTime.atZone(workplaceZone);
                    ZonedDateTime utcDateTime = zonedDateTime.withZoneSameInstant(ZoneId.of("UTC"));

                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("ddMMyyyyHHmmss");
                    String clockingDate = utcDateTime.format(formatter);

                    log("🕒 Clocking ajustado (UTC): " + clockingDate);

                    ObjectNode body = mapper.createObjectNode();
                    body.put("personId", personId);
                    body.put("clockingDate", clockingDate);
                    body.put("directionType", "ENTRANCE");
                    body.put("timezone", timezone);

                    postClocking(body, authToken);
                }
            }

            logToFile("✅ Processamento concluído com sucesso.");
        } catch (Exception e) {
            logError("💥 ERRO FATAL: " + e.getMessage());
            e.printStackTrace();
        } finally {
            logToFile("🕓 Fim da execução: " + LocalDateTime.now());
            logToFile("===============================================");
            logWriter.close();
        }
    }

    private static void log(String message) throws IOException {
        System.out.println(message);
        logToFile(message);
    }

    private static void logError(String message) throws IOException {
        System.err.println(message);
        logToFile(message);
    }

    private static void logToFile(String message) throws IOException {
        logWriter.write(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " - " + message);
        logWriter.newLine();
        logWriter.flush();
    }

    private static String getOAuth2Token() throws Exception {
        String tokenUrl = BASE_URL + "/security/oauth/token";

        String auth = CLIENT_ID + ":" + CLIENT_SECRET;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .header("Authorization", "Basic " + encodedAuth)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build();

        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

        if (res.statusCode() == 200) {
            JsonNode json = mapper.readTree(res.body());
            log("🔑 Token OAuth2 obtido com sucesso!");
            return json.get("access_token").asText();
        } else {
            logError("[ERRO] Falha ao gerar token OAuth2: " + res.statusCode());
            logError(res.body());
            throw new IllegalStateException("Falha ao gerar token OAuth2");
        }
    }

    private static int getPersonIdByCpf(String cpf, String authToken) throws Exception {
        String url = BASE_URL + "/persons/cpf?cpf=" + cpf;
        log("📡 Consultando colaborador no endpoint: " + url);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + authToken)
                .build();

        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        log("🔁 Resposta da API (status=" + res.statusCode() + "): " + res.body());

        if (res.statusCode() == 200) {
            JsonNode json = mapper.readTree(res.body());

            if (json.isArray()) {
                List<Integer> validIds = new ArrayList<>();
                for (JsonNode node : json) {
                    if (node.has("personSituationId") && node.get("personSituationId").asInt() == 1) {
                        if (node.has("id")) validIds.add(node.get("id").asInt());
                    }
                }

                if (validIds.isEmpty()) {
                    logError("⚠ Nenhum colaborador ativo para CPF " + cpf);
                    return -1;
                } else if (validIds.size() > 1) {
                    throw new IllegalStateException("Mais de um colaborador ativo para CPF " + cpf);
                } else {
                    return validIds.get(0);
                }
            }
        } else {
            logError("❌ Erro ao consultar CPF " + cpf + ": " + res.statusCode());
        }

        return -1;
    }

    private static int getLatestWorkplace(int personId, String authToken) throws Exception {
        String url = BASE_URL + "/workplacetransfers/person/" + personId
                + "/start/01011940000000/finish/01012050000000";
        log("📡 Buscando workplaces em: " + url);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + authToken)
                .build();

        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        log("🔁 Resposta workplace (status=" + res.statusCode() + "): " + res.body());

        if (res.statusCode() == 200) {
            JsonNode root = mapper.readTree(res.body());
            JsonNode content = root.get("content");
            if (content != null && content.isArray() && content.size() > 0) {
                JsonNode last = content.get(content.size() - 1);
                log("   ➜ Último workplace encontrado: " + last.toPrettyString());
                return last.get("workplaceId").asInt();
            }
        }

        return -1;
    }

    private static String getTimezone(int workplaceId, String authToken) throws Exception {
        String url = BASE_URL + "/workplaces/" + workplaceId;
        log("📡 Buscando timezone em: " + url);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + authToken)
                .build();

        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        log("🔁 Resposta timezone (status=" + res.statusCode() + "): " + res.body());

        if (res.statusCode() == 200) {
            JsonNode root = mapper.readTree(res.body());
            JsonNode valueNode = root.get("value");
            if (valueNode != null && valueNode.has("timezone") && !valueNode.get("timezone").isNull()) {
                return valueNode.get("timezone").asText();
            }
        }

        return "America/Sao_Paulo";
    }

    private static void postClocking(ObjectNode body, String authToken) throws Exception {
        String bodyStr = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(body);

        log("📤 Enviando marcação: " + bodyStr);

        String url = BASE_URL + "/clockings/rules";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + authToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();

        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        log("✅ RESPOSTA DO POST: Status=" + res.statusCode() + " | Body: " + res.body());
        log("------------------------------\n");
    }
}

