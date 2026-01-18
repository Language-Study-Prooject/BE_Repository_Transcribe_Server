package com.transcribe.proxy.config;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.transcribe.TranscribeClient;

public final class AwsClients {

    // DynamoDB
    private static final DynamoDbClient DYNAMO_DB_CLIENT = DynamoDbClient.builder().build();
    private static final DynamoDbEnhancedClient DYNAMO_DB_ENHANCED_CLIENT = DynamoDbEnhancedClient.builder()
            .dynamoDbClient(DYNAMO_DB_CLIENT)
            .build();
    // S3
    private static final S3Client S3_CLIENT = S3Client.builder().build();
    private static final S3Presigner S3_PRESIGNER = S3Presigner.builder().build();

    // Transcribe
    private static final TranscribeClient TRANSCRIBE_CLIENT = TranscribeClient.builder().build();


    private AwsClients() {
        // 인스턴스화 방지
    }

    public static DynamoDbClient dynamoDb() {
        return DYNAMO_DB_CLIENT;
    }

    public static DynamoDbEnhancedClient dynamoDbEnhanced() {
        return DYNAMO_DB_ENHANCED_CLIENT;
    }

    public static S3Client s3() {
        return S3_CLIENT;
    }

    public static S3Presigner s3Presigner() { return S3_PRESIGNER; }

    public static TranscribeClient transcribe() { return TRANSCRIBE_CLIENT; }

}
