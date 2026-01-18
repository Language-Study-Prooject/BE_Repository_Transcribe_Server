package com.transcribe.proxy.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.transcribe.proxy.config.AwsClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.transcribe.model.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TranscribeProxyHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Logger logger = LoggerFactory.getLogger(TranscribeProxyHandler.class);
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final String TEMP_BUCKET = System.getenv("TEMP_BUCKET");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent request,
            Context context) {


        try {
            JsonObject body = JsonParser.parseString(request.getBody()).getAsJsonObject();
            String audioBase64 = body.get("audio_data").getAsString();
            String sessionId = body.get("session_id").getAsString();
            String languageCode = body.has("language_code")
                    ? body.get("language_code").getAsString()
                    : "en-US";

            logger.info("Processing transcription for session: {}", sessionId);
            logger.debug("Audio data length: {} chars", audioBase64.length());

            // 녹음 파일 고유값 생성
            String jobName = String.format("opic-%s-%s",
                    sessionId,
                    UUID.randomUUID().toString().substring(0, 8));
            String s3Key = "temp/" + jobName + ".webm";

            // S3에 음성 파일 저장
            byte[] audioBytes = Base64.getDecoder().decode(audioBase64);
            logger.debug("Decoded audio size: {} bytes", audioBytes.length);

            AwsClients.s3().putObject(
                    PutObjectRequest.builder()
                            .bucket(TEMP_BUCKET)
                            .key(s3Key)
                            .contentType("audio/webm")
                            .build(),
                    RequestBody.fromBytes(audioBytes)
            );

            logger.info("녹음 파일이 업로드 되었습니다 : {}", s3Key);

            // Transcribe 작업 시작
            String mediaUri = String.format("s3://%s/%s", TEMP_BUCKET, s3Key);

            AwsClients.transcribe().startTranscriptionJob(
                    StartTranscriptionJobRequest.builder()
                            .transcriptionJobName(jobName)
                            .media(Media.builder().mediaFileUri(mediaUri).build())
                            .mediaFormat(MediaFormat.WEBM)
                            .languageCode(languageCode)
                            .build()
            );


            // 완료 대기
            TranscriptionResult result = waitForTranscription(jobName);

            // 임시 파일 삭제 (실패해도 lifecycle이 처리)
            cleanupTempFile(s3Key);

            // 응답 반환
            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("transcript", result.transcript());
            responseBody.put("job_name", jobName);
            responseBody.put("confidence", result.confidence());

            logger.debug("Transcript: {}", result.transcript());

            return createResponse(200, gson.toJson(responseBody));

        } catch (Exception e) {
            logger.error("Transcription 에러: {}", e.getMessage(), e);

            Map<String, String> errorBody = new HashMap<>();
            errorBody.put("error", e.getMessage());

            return createResponse(500, gson.toJson(errorBody));
        }

    }

    /**
     * Transcribe 작업 완료 대기 (폴링)
     */
    private TranscriptionResult waitForTranscription(String jobName) throws Exception {
        int maxWaitSeconds = 90;
        int waited = 0;
        int pollInterval = 3;

        while (waited < maxWaitSeconds) {
            GetTranscriptionJobResponse response = AwsClients.transcribe().getTranscriptionJob(
                    GetTranscriptionJobRequest.builder()
                            .transcriptionJobName(jobName)
                            .build()
            );

            TranscriptionJob job = response.transcriptionJob();
            TranscriptionJobStatus status = job.transcriptionJobStatus();

            logger.debug("Job {} status: {} (waited {}s)", jobName, status, waited);

            if (status == TranscriptionJobStatus.COMPLETED) {
                String resultUri = job.transcript().transcriptFileUri();
                return fetchTranscriptResult(resultUri);
            } else if (status == TranscriptionJobStatus.FAILED) {
                throw new RuntimeException("Transcription failed: " + job.failureReason());
            }

            Thread.sleep(pollInterval * 1000L);
            waited += pollInterval;
        }

        throw new RuntimeException("Transcription timeout after " + maxWaitSeconds + " seconds");
    }

    /**
     * Transcribe 결과 파일에서 텍스트 추출
     */
    private TranscriptionResult fetchTranscriptResult(String resultUri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resultUri))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        // Gson으로 결과 파싱
        JsonObject resultJson = JsonParser.parseString(response.body()).getAsJsonObject();

        // transcript 추출
        String transcript = resultJson
                .getAsJsonObject("results")
                .getAsJsonArray("transcripts")
                .get(0)
                .getAsJsonObject()
                .get("transcript")
                .getAsString();

        // confidence 평균 계산
        double confidence = calculateAverageConfidence(resultJson);

        return new TranscriptionResult(transcript, confidence);
    }

    /**
     * 단어별 신뢰도 평균 계산
     */
    private double calculateAverageConfidence(JsonObject resultJson) {
        try {
            var items = resultJson
                    .getAsJsonObject("results")
                    .getAsJsonArray("items");

            if (items == null || items.isEmpty()) {
                return 0.0;
            }

            double sum = 0;
            int count = 0;

            for (var item : items) {
                JsonObject itemObj = item.getAsJsonObject();
                if ("pronunciation".equals(itemObj.get("type").getAsString())) {
                    var alternatives = itemObj.getAsJsonArray("alternatives");
                    if (alternatives != null && !alternatives.isEmpty()) {
                        double conf = alternatives.get(0)
                                .getAsJsonObject()
                                .get("confidence")
                                .getAsDouble();
                        sum += conf;
                        count++;
                    }
                }
            }

            return count > 0 ? sum / count : 0.0;
        } catch (Exception e) {
            logger.warn("Failed to calculate confidence: {}", e.getMessage());
            return 0.0;
        }
    }

    /**
     * 임시 파일 정리
     */
    private void cleanupTempFile(String s3Key) {
        try {
            AwsClients.s3().deleteObject(
                    DeleteObjectRequest.builder()
                            .bucket(TEMP_BUCKET)
                            .key(s3Key)
                            .build()
            );
        } catch (Exception e) {
            logger.warn("Failed to delete temp file (lifecycle will handle): {}", e.getMessage());
        }
    }

    /**
     * API Gateway 응답 생성
     */
    private APIGatewayProxyResponseEvent createResponse(int statusCode, String body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "POST, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type, X-Api-Key");

        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withHeaders(headers)
                .withBody(body);
    }

    /**
     * Transcription 결과 레코드
     */
    private record TranscriptionResult(String transcript, double confidence) {}
}
