# PMS Trade Capture - Setup Guide

## Prerequisites
- Docker Desktop installed and running
- Access to AWS RDS database (already configured)

## Quick Start (One Command)

```bash
docker-compose up -d
```

That's it! Everything will start automatically.

## What Happens Automatically

1. **RabbitMQ** starts with Stream plugin enabled
2. **Kafka** starts in KRaft mode (no Zookeeper needed)
3. **Schema Registry** connects to Kafka
4. **PMS Simulator** waits for RabbitMQ to be healthy, then starts sending trades
5. **Trade Capture** waits for all services, then:
   - Connects to AWS RDS PostgreSQL
   - Creates database tables automatically (Liquibase)
   - Consumes trades from RabbitMQ Stream
   - Saves trades to database
   - Publishes to Kafka

## Verify Everything is Working

### 1. Check Service Status
```bash
docker-compose ps
```
All services should show "healthy" status.

### 2. Check Logs
```bash
# All services
docker-compose logs -f

# Specific service
docker-compose logs -f trade-capture
docker-compose logs -f pms-simulator-temp
```

### 3. Access UIs
- **RabbitMQ Management**: http://localhost:15672 (guest/guest)
- **PMS Simulator Health**: http://localhost:4000/actuator/health
- **Trade Capture Health**: http://localhost:8082/actuator/health
- **Schema Registry**: http://localhost:8081/subjects

### 4. Verify Database
Connect to AWS RDS and run:
```sql
-- Host: db-instance-pms.cvk4yqey0ex7.us-east-2.rds.amazonaws.com
-- Database: pms_trade_db
-- User: postgres
-- Password: PMS.2025

SELECT COUNT(*) FROM safe_store_trade;
SELECT * FROM safe_store_trade ORDER BY created_at DESC LIMIT 10;

SELECT COUNT(*) FROM outbox_event;
```

## Trigger Manual Trade Simulation

```bash
curl -X POST "http://localhost:4000/api/simulate/trades?count=100"
```

## Stop Everything

```bash
docker-compose down
```

## Clean Restart (Remove All Data)

```bash
docker-compose down -v
docker-compose up -d
```

## Architecture

```
┌─────────────────┐
│ PMS Simulator   │ (Port 4000)
└────────┬────────┘
         │ Publishes trades
         ▼
┌─────────────────┐
│ RabbitMQ Stream │ (Ports 5552, 5672, 15672)
└────────┬────────┘
         │ Stream consumption
         ▼
┌─────────────────┐
│ Trade Capture   │ (Port 8082)
└────┬────────┬───┘
     │        │
     │        └──────────────┐
     ▼                       ▼
┌──────────┐         ┌──────────────┐
│ AWS RDS  │         │ Kafka        │ (Port 9092)
│ Postgres │         │ + Schema Reg │ (Port 8081)
└──────────┘         └──────────────┘
```

## Database Tables (Auto-Created)

1. **safe_store_trade** - All captured trades
2. **outbox_event** - Transactional outbox for Kafka publishing
3. **dlq_entry** - Dead letter queue for failed messages
4. **databasechangelog** - Liquibase migration tracking

## Troubleshooting

### Services not starting
```bash
# Check Docker is running
docker ps

# Restart everything
docker-compose restart
```

### Simulator unhealthy
```bash
# Restart just the simulator
docker-compose restart pms-simulator-temp
```

### No data in database
```bash
# Check if trades are being sent
docker-compose logs pms-simulator-temp | grep "Message sent"

# Check if trade-capture is receiving
docker-compose logs trade-capture | grep "Outbox payload"
```

### Port conflicts
If ports are already in use, stop the conflicting services or modify ports in docker-compose.yaml.

## Configuration

All configuration is in `docker-compose.yaml`. Key environment variables:

### Trade Capture Service
- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASS` - Database connection
- `RABBIT_HOST`, `RABBIT_STREAM_PORT` - RabbitMQ Stream connection
- `SPRING_KAFKA_BOOTSTRAP_SERVERS` - Kafka connection
- `SPRING_KAFKA_PROPERTIES_SCHEMA_REGISTRY_URL` - Schema Registry URL
- `BATCH_MAX_SIZE_PER_PORTFOLIO` - Batch size (default: 100)
- `BATCH_FLUSH_INTERVAL_MS` - Flush interval (default: 200ms)

### PMS Simulator
- `RABBIT_HOST`, `RABBIT_STREAM_PORT` - RabbitMQ Stream connection
- Automatically sends trades continuously

## Next Steps

After running `docker-compose up -d`:
1. Wait 1-2 minutes for all services to be healthy
2. Check database - trades should be flowing in
3. Monitor logs to see the data pipeline in action
4. Access RabbitMQ UI to see stream activity

**No manual configuration needed!**
