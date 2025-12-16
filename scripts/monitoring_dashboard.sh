#!/bin/bash

# Monitoring Dashboard
# Real-time monitoring of the trade capture system

echo "═══════════════════════════════════════════════"
echo "TRADE CAPTURE MONITORING DASHBOARD"
echo "═══════════════════════════════════════════════"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

print_status() {
    local status=$1
    local message=$2
    case $status in
        "PASS")
            echo -e "${GREEN}✅ PASS${NC}: $message"
            ;;
        "FAIL")
            echo -e "${RED}❌ FAIL${NC}: $message"
            ;;
        "WARN")
            echo -e "${YELLOW}⚠️  WARN${NC}: $message"
            ;;
        "INFO")
            echo -e "${BLUE}ℹ️  INFO${NC}: $message"
            ;;
        "TITLE")
            echo -e "${CYAN}📊 $message${NC}"
            ;;
    esac
}

# Function to get container status
get_container_status() {
    local container=$1
    if docker ps --format "table {{.Names}}" | grep -q "^${container}$"; then
        echo "RUNNING"
    else
        echo "STOPPED"
    fi
}

# Function to get container health
get_container_health() {
    local container=$1
    local health=$(docker inspect --format='{{.State.Health.Status}}' $container 2>/dev/null)
    if [ "$health" = "healthy" ]; then
        echo "HEALTHY"
    elif [ "$health" = "unhealthy" ]; then
        echo "UNHEALTHY"
    else
        echo "UNKNOWN"
    fi
}

# Function to get trade counts
get_trade_counts() {
    local safe_store_count=$(docker exec -i postgres psql -U pms -d pmsdb -t -c "SELECT COUNT(*) FROM safe_store_trade;" 2>/dev/null | tr -d ' ')
    local outbox_count=$(docker exec -i postgres psql -U pms -d pmsdb -t -c "SELECT COUNT(*) FROM outbox_event;" 2>/dev/null | tr -d ' ')
    local sent_count=$(docker exec -i postgres psql -U pms -d pmsdb -t -c "SELECT COUNT(*) FROM outbox_event WHERE status = 'SENT';" 2>/dev/null | tr -d ' ')
    local pending_count=$(docker exec -i postgres psql -U pms -d pmsdb -t -c "SELECT COUNT(*) FROM outbox_event WHERE status = 'PENDING';" 2>/dev/null | tr -d ' ')
    local dlq_count=$(docker exec -i postgres psql -U pms -d pmsdb -t -c "SELECT COUNT(*) FROM dlq_entry;" 2>/dev/null | tr -d ' ')
    
    echo "$safe_store_count|$outbox_count|$sent_count|$pending_count|$dlq_count"
}

# Function to get Kafka metrics
get_kafka_metrics() {
    local topic_info=$(docker exec kafka kafka-topics --list --bootstrap-server localhost:9092 2>/dev/null | grep -c "raw-trades-proto" || echo "0")
    local message_count=$(docker exec kafka kafka-run-class kafka.tools.GetOffsetShell --broker-list localhost:9092 --topic raw-trades-proto --time -1 2>/dev/null | awk -F: '{sum += $2} END {print sum}' || echo "0")
    
    echo "$topic_info|$message_count"
}

# Function to get RabbitMQ metrics
get_rabbitmq_metrics() {
    local stream_exists=$(docker exec rabbitmq rabbitmq-streams list_streams 2>/dev/null | grep -c "trade-stream" || echo "0")
    local connections=$(docker exec rabbitmq rabbitmqctl list_connections 2>/dev/null | wc -l || echo "0")
    
    echo "$stream_exists|$connections"
}

# Main monitoring loop
while true; do
    clear
    echo "═══════════════════════════════════════════════"
    echo "TRADE CAPTURE MONITORING DASHBOARD"
    echo "═══════════════════════════════════════════════"
    echo "Last updated: $(date)"
    echo ""

    # Infrastructure Status
    print_status "TITLE" "INFRASTRUCTURE STATUS"
    
    services=("postgres" "rabbitmq" "kafka" "schema-registry" "trade-capture-app" "pms-simulation")
    for service in "${services[@]}"; do
        status=$(get_container_status $service)
        health=$(get_container_health $service)
        
        if [ "$status" = "RUNNING" ]; then
            if [ "$health" = "HEALTHY" ]; then
                print_status "PASS" "$service: $status ($health)"
            else
                print_status "WARN" "$service: $status ($health)"
            fi
        else
            print_status "FAIL" "$service: $status"
        fi
    done
    
    echo ""
    print_status "TITLE" "TRADE PROCESSING METRICS"
    
    # Get trade counts
    IFS='|' read -r safe_store_count outbox_count sent_count pending_count dlq_count <<< "$(get_trade_counts)"
    
    echo "Safe Store Trades: $safe_store_count"
    echo "Outbox Events: $outbox_count"
    echo "Sent to Kafka: $sent_count"
    echo "Pending: $pending_count"
    echo "DLQ Entries: $dlq_count"
    
    if [ "$dlq_count" -gt 0 ]; then
        print_status "WARN" "DLQ has $dlq_count entries - check for parsing errors"
    else
        print_status "PASS" "No DLQ entries (good)"
    fi
    
    echo ""
    print_status "TITLE" "MESSAGE FLOW STATUS"
    
    # Kafka metrics
    IFS='|' read -r topic_exists kafka_messages <<< "$(get_kafka_metrics)"
    if [ "$topic_exists" -gt 0 ]; then
        print_status "PASS" "Kafka topic 'raw-trades-proto' exists"
        echo "Messages in topic: $kafka_messages"
    else
        print_status "FAIL" "Kafka topic 'raw-trades-proto' not found"
    fi
    
    # RabbitMQ metrics
    IFS='|' read -r stream_exists rabbit_connections <<< "$(get_rabbitmq_metrics)"
    if [ "$stream_exists" -gt 0 ]; then
        print_status "PASS" "RabbitMQ stream 'trade-stream' exists"
        echo "Active connections: $((rabbit_connections - 1))"  # Subtract header line
    else
        print_status "FAIL" "RabbitMQ stream 'trade-stream' not found"
    fi
    
    echo ""
    print_status "TITLE" "RECENT ACTIVITY"
    
    # Recent trades
    echo "Recent trades (last 5):"
    docker exec -i postgres psql -U pms -d pmsdb -c "
    SELECT 
        id, 
        LEFT(trade_id::text, 8) as trade_id_short,
        symbol, 
        side, 
        quantity, 
        received_at 
    FROM safe_store_trade 
    ORDER BY received_at DESC 
    LIMIT 5;" 2>/dev/null | head -10
    
    echo ""
    echo "Press Ctrl+C to exit. Refreshing in 10 seconds..."
    sleep 10
done
