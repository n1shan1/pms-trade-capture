# PMS Trade Capture System

High-performance trade capture system using RabbitMQ Streams, PostgreSQL, and Kafka.

## 🚀 Quick Start

### Option 1: Build and Run (Recommended)
```bash
build-and-run.bat
```

### Option 2: Manual Steps
```bash
# Build the application
docker build -t pms-trade-capture:latest .

# Start all services
docker-compose up -d
```

## ✅ Verify Everything Works

```bash
# Check all services are healthy
docker-compose ps

# View logs
docker-compose logs -f trade-capture

# Check database has data
# Connect to: db-instance-pms.cvk4yqey0ex7.us-east-2.rds.amazonaws.com
# Database: pms_trade_db
SELECT COUNT(*) FROM safe_store_trade;
```

## 🌐 Access Points

- **RabbitMQ UI**: http://localhost:15672 (guest/guest)
- **Trade Capture Health**: http://localhost:8082/actuator/health
- **PMS Simulator Health**: http://localhost:4000/actuator/health
- **Schema Registry**: http://localhost:8081/subjects

## 📊 Architecture

```
Simulator → RabbitMQ Stream → Trade Capture → [PostgreSQL + Kafka]
```

## 🛑 Stop Services

```bash
docker-compose down
```

## 📖 Detailed Documentation

See [SETUP.md](SETUP.md) for complete documentation.

## 🔧 Configuration

All configuration is in `docker-compose.yaml`. No manual setup required!

## ⚡ Features

- ✅ Automatic database schema creation (Liquibase)
- ✅ Transactional outbox pattern for Kafka
- ✅ Batch processing with configurable size and flush interval
- ✅ Dead letter queue for failed messages
- ✅ Health checks for all services
- ✅ Protobuf serialization with Schema Registry
- ✅ High-throughput RabbitMQ Streams

## 📝 Database Tables (Auto-Created)

1. `safe_store_trade` - All captured trades
2. `outbox_event` - Transactional outbox for Kafka
3. `dlq_entry` - Dead letter queue
4. `databasechangelog` - Migration tracking

## 🎯 Next Steps

After running `build-and-run.bat`:
1. Wait 1-2 minutes for services to be healthy
2. Check database - trades will be flowing in automatically
3. Monitor logs to see the pipeline in action

**No manual configuration needed!** 🎉
