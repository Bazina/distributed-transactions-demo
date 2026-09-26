#!/usr/bin/env bash
# Build if needed, then start all four services in the background.
set -e
cd "$(dirname "$0")"; source lib.sh
cd "$ROOT"; mkdir -p .run
for m in discovery-server payment-service accounting-service booking-service; do
  [ -f "$m/target/$m-0.0.1-SNAPSHOT.jar" ] || (cd "$m" && ./mvnw -q -B -DskipTests package)
done
for m in discovery-server payment-service accounting-service booking-service; do
  nohup "$JAVA_HOME/bin/java" -jar "$m/target/$m-0.0.1-SNAPSHOT.jar" > ".run/$m.log" 2>&1 &
  echo "started $m"
done
echo "waiting for startup"; sleep 40
echo "logs are in .run/. Stop everything with demo/99-stop.sh"
