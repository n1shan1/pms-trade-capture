# Lifecycle Events Implementation

## Overview
Lifecycle events are published to Kafka topic `lifecycle.event` to track the ingestion status of trades. These events are consumed by the `pms-crosscutting` service for monitoring and observability.

## Architecture

```
Trade Processing → BatchingIngestService → CrossCuttingService → Kafka (lifecycle.event)
                                                                          ↓
                                                                   pms-crosscutting
```

## Files Created

### 1. CrossCuttingService.java
**Location**: `src/main/java/com/pms/pms_trade_capture/service/CrossCuttingService.java`

Interface defining lifecycle event methods:
- `recordIngestionSuccess()` - Called after successful DB write
- `recordIngestionFailure()` - Called when processing fails (DLQ)

### 2. CrossCuttingServiceImpl.java
**Location**: `src/main/java/com/pms/pms_trade_capture/service/CrossCuttingServiceImpl.java`

Implementation that publishes JSON events to Kafka:
- Topic: `lifecycle.event`
- Format: JSON string
- Key: tradeId (for ordering)
- Service Name: `PMS_TRADE_CAPTURE`
- Stage: `INGESTION`

## Files Modified

### 1. KafkaConfig.java
**Added**: Separate KafkaTemplate for lifecycle events
```java
@Bean
public KafkaTemplate<String, String> lifecycleKafkaTemplate()
```
- Uses StringSerializer for both key and value
- Separate from existing Protobuf template
- No impact on existing trade event publishing

### 2. BatchingIngestService.java
**Added**: CrossCuttingService integration
- Injected via constructor
- Calls `recordIngestionSuccess()` after successful DB save
- Calls `recordIngestionFailure()` when saving to DLQ
- Non-blocking: failures logged but don't affect trade processing

### 3. TradeEventDto.java
**Added**: Getter methods
- `getPortfolioId()` - Returns UUID
- `getTradeId()` - Returns String

### 4. application.yaml
**Added**: Lifecycle topic configuration
```yaml
app:
  kafka:
    lifecycle-topic: lifecycle.event
```

### 5. docker-compose.yaml
**Added**: Environment variable
```yaml
LIFECYCLE_TOPIC_NAME: lifecycle.event
```

## Event Format

### Success Event
```json
{
  "traceId": "uuid",
  "portfolioId": "uuid",
  "stage": "INGESTION",
  "status": "SUCCESS",
  "ts": "2025-12-07T08:00:00Z",
  "details": {
    "sourceService": "PMS_TRADE_CAPTURE",
    "eventType": "INGESTION_PERSISTED",
    "safeStoreId": 123,
    "outboxId": 456,
    "receivedAt": "2025-12-07T08:00:00Z",
    "createdAt": "2025-12-07T08:00:00Z"
  }
}
```

### Failure Event
```json
{
  "traceId": "uuid",
  "portfolioId": "uuid",
  "stage": "INGESTION",
  "status": "FAILURE",
  "ts": "2025-12-07T08:00:00Z",
  "details": {
    "sourceService": "PMS_TRADE_CAPTURE",
    "eventType": "INGESTION_FAILED",
    "dlqId": 789,
    "failedAt": "2025-12-07T08:00:00Z",
    "errorMessage": "DB write failed after 3 retries",
    "exceptionType": "java.lang.RuntimeException"
  }
}
```

## Integration with pms-crosscutting

The `pms-crosscutting` service consumes these events with:
- Consumer Group: `pms-crossref-group`
- Topic: `lifecycle.event`
- Auto Offset Reset: `earliest`
- Batch Processing: 200 messages, 5 seconds timeout

## Key Features

### 1. Non-Disruptive
- Existing Kafka Protobuf producer unchanged
- Existing outbox pattern unchanged
- Lifecycle events run in parallel
- Failures don't affect trade processing

### 2. Idempotent
- Uses tradeId as Kafka key for ordering
- Duplicate events handled by consumer

### 3. Resilient
- Lifecycle publishing failures are logged
- Trade processing continues even if lifecycle event fails
- Uses `.get()` for synchronous send with error handling

### 4. Observable
- Logs all lifecycle event sends
- Includes success and failure details
- Tracks source service and event type

## Testing

### 1. Verify Lifecycle Events are Published
```bash
# Check trade-capture logs
docker-compose logs trade-capture | grep "lifecycle event"

# Should see:
# "Sending lifecycle event to topic 'lifecycle.event'"
# "Successfully sent lifecycle event"
```

### 2. Verify Events in Kafka
```bash
# Connect to Kafka container
docker exec -it kafka bash

# Consume lifecycle events
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic lifecycle.event \
  --from-beginning \
  --property print.key=true
```

### 3. Verify pms-crosscutting Consumes Events
Check pms-crosscutting service logs for consumption messages.

## Configuration

### Environment Variables
- `LIFECYCLE_TOPIC_NAME` - Topic name (default: lifecycle.event)
- `SPRING_KAFKA_BOOTSTRAP_SERVERS` - Kafka brokers (default: kafka:19092)

### Application Properties
```yaml
app:
  kafka:
    lifecycle-topic: ${LIFECYCLE_TOPIC_NAME:lifecycle.event}
```

## Troubleshooting

### Lifecycle Events Not Published
1. Check Kafka connection: `docker-compose logs trade-capture | grep "Kafka"`
2. Verify topic exists: `kafka-topics --list --bootstrap-server localhost:9092`
3. Check for errors: `docker-compose logs trade-capture | grep "lifecycle"`

### Events Not Consumed by pms-crosscutting
1. Verify consumer group: Check pms-crosscutting configuration
2. Check topic name matches: `lifecycle.event`
3. Verify Kafka connectivity from pms-crosscutting

## Deployment

### Build and Deploy
```bash
# Rebuild with lifecycle events
docker build -t pms-trade-capture:latest .

# Restart services
docker-compose down
docker-compose up -d
```

### Verify Deployment
```bash
# Check all services healthy
docker-compose ps

# Verify lifecycle events flowing
docker-compose logs -f trade-capture | grep "lifecycle"
```

## Summary

✅ Lifecycle events implemented without disrupting existing functionality  
✅ Publishes to `lifecycle.event` topic in JSON format  
✅ Tracks both SUCCESS and FAILURE ingestion events  
✅ Integrated with pms-crosscutting service  
✅ Non-blocking and resilient  
✅ Ready for production use  
