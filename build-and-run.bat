@echo off
echo ========================================
echo Building PMS Trade Capture Application
echo ========================================

echo.
echo Step 1: Building Docker image...
docker build -t pms-trade-capture:latest .

if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Docker build failed!
    exit /b 1
)

echo.
echo Step 2: Stopping existing containers...
docker-compose down

echo.
echo Step 3: Starting all services...
docker-compose up -d

echo.
echo ========================================
echo Build and deployment complete!
echo ========================================
echo.
echo Services starting... Please wait 1-2 minutes for all services to be healthy.
echo.
echo Check status with: docker-compose ps
echo View logs with: docker-compose logs -f
echo.
echo Access points:
echo - RabbitMQ UI: http://localhost:15672 (guest/guest)
echo - Trade Capture Health: http://localhost:8082/actuator/health
echo - PMS Simulator Health: http://localhost:4000/actuator/health
echo - Schema Registry: http://localhost:8081/subjects
echo.
