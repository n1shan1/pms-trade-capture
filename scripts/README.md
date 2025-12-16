# Trade Capture System - Verification Scripts

This directory contains shell scripts for comprehensive verification and monitoring of the PMS Trade Capture system.

## Available Scripts

### Core Verification Scripts

1. **`verify_all.sh`** - Complete system verification suite
   - Runs all individual verification scripts
   - Provides comprehensive health check
   - Use: `./verify_all.sh`

2. **`verify_stream_consumption.sh`** - RabbitMQ Stream verification
   - Checks stream connectivity and consumer status
   - Validates trade message reception
   - Use: `./verify_stream_consumption.sh`

3. **`verify_batch_ingestion.sh`** - Database persistence verification
   - Validates trade storage in PostgreSQL
   - Checks batch processing and outbox events
   - Use: `./verify_batch_ingestion.sh`

4. **`verify_outbox_polling.sh`** - Outbox processing verification
   - Monitors outbox event processing
   - Validates Kafka publication status
   - Use: `./verify_outbox_polling.sh`

5. **`verify_kafka_publication.sh`** - Kafka publishing verification
   - Checks Kafka topic creation and message publishing
   - Validates schema registry integration
   - Use: `./verify_kafka_publication.sh`

6. **`verify_trade_order.sh`** - Trade ordering verification
   - Ensures trades maintain correct order throughout the system
   - Validates FIFO processing guarantees
   - Use: `./verify_trade_order.sh`

7. **`verify_dlq_handling.sh`** - DLQ handling verification
   - Tests dead letter queue functionality
   - Validates error handling for unparseable messages
   - Use: `./verify_dlq_handling.sh`

### Configuration Display Scripts

8. **`show_infrastructure_config.sh`** - Infrastructure configuration display
   - Shows all Docker service configurations
   - Displays connection details and ports
   - Use: `./show_infrastructure_config.sh`

9. **`show_application_config.sh`** - Application configuration display
   - Shows Spring Boot application settings
   - Displays environment variables and profiles
   - Use: `./show_application_config.sh`

10. **`show_database_config.sh`** - Database configuration display
    - Shows PostgreSQL schema and table structures
    - Displays indexes, constraints, and Liquibase changelog
    - Use: `./show_database_config.sh`

### Monitoring Scripts

11. **`monitoring_dashboard.sh`** - Real-time monitoring dashboard
    - Live system monitoring with auto-refresh
    - Shows infrastructure status and trade metrics
    - Use: `./monitoring_dashboard.sh`

## Prerequisites

- Docker and Docker Compose installed
- All services running (`docker-compose up -d`)
- System has processed some trades (run pms-simulation first)

## Quick Start

```bash
# Make all scripts executable
chmod +x *.sh

# Run complete verification
./verify_all.sh

# Start monitoring dashboard
./monitoring_dashboard.sh

# Check specific components
./verify_trade_order.sh
./show_infrastructure_config.sh
```

## Expected Results

### Healthy System Indicators
- ✅ All infrastructure services running and healthy
- ✅ Trade order maintained (0 out-of-order trades)
- ✅ DLQ table empty (valid Protobuf messages)
- ✅ Kafka topics created with messages
- ✅ Database tables populated with trades

### Common Issues
- ❌ Services not starting: Check Docker resources
- ❌ DLQ has entries: Invalid messages from simulation
- ❌ Out-of-order trades: Threading or batching issues
- ❌ Kafka connection failed: Network or configuration issues

## Testing DLQ Functionality

To test DLQ handling with invalid messages:

```bash
# Inject a poison message
docker exec rabbitmq rabbitmq-streams add_message trade-stream 'INVALID_GARBAGE_DATA'

# Check logs for DLQ activity
docker logs trade-capture-app 2>&1 | grep -E "(DLQ|Invalid Protobuf)"

# Verify DLQ table
./verify_dlq_handling.sh
```

## Troubleshooting

1. **Scripts not executable**: Run `chmod +x *.sh`
2. **Database connection failed**: Ensure PostgreSQL is healthy
3. **Kafka connection failed**: Check advertised listeners configuration
4. **Services not responding**: Check Docker container logs

## Architecture Overview

The system processes trades through:
1. **RabbitMQ Streams** → Trade reception
2. **Batch Ingestion** → PostgreSQL storage
3. **Outbox Pattern** → Event publishing
4. **Kafka + Schema Registry** → Message publishing
5. **DLQ** → Error handling

All scripts validate each component in this pipeline.
