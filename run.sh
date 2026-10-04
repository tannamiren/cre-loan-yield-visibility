#!/usr/bin/env bash
# Runs the Loan Early-Warning Engine: starts MySQL, seeds synthetic data on first run,
# and starts the Spring Boot app (which serves both the REST API and the Thymeleaf
# screens at http://localhost:8080/ -- there's no separate "Thymeleaf app" to run).
set -euo pipefail

cd "$(dirname "$0")"

# --- locate Maven + a JDK 21 if they're not already on PATH ---
if ! command -v mvn >/dev/null 2>&1; then
  for candidate in "$HOME/apache-maven-3.9.6/bin"; do
    [ -x "$candidate/mvn" ] && export PATH="$candidate:$PATH"
  done
fi
if ! command -v mvn >/dev/null 2>&1; then
  echo "mvn not found on PATH. Install Maven 3.9+ (or Maven Wrapper) and re-run." >&2
  exit 1
fi

if ! mvn -v 2>/dev/null | grep -q "Java version: 2[0-9]"; then
  for candidate in \
    "$HOME/.vscode/extensions/redhat.java-1.54.0-darwin-x64/jre/21.0.10-macosx-x86_64" \
    "/Applications/PyCharm.app/Contents/jbr/Contents/Home"; do
    if [ -x "$candidate/bin/java" ]; then
      export JAVA_HOME="$candidate"
      export PATH="$JAVA_HOME/bin:$PATH"
      break
    fi
  done
fi
if ! mvn -v 2>/dev/null | grep -q "Java version: 2[0-9]"; then
  echo "Java 21+ not found. Set JAVA_HOME to a JDK 21+ install and re-run." >&2
  exit 1
fi

echo "==> Starting MySQL (docker compose up -d)"
docker compose up -d

echo "==> Waiting for MySQL to accept connections"
until docker compose exec -T mysql mysqladmin ping -h localhost -uearlywarning -pearlywarning --silent >/dev/null 2>&1; do
  sleep 2
done
echo "    MySQL is ready."

RESEED=false
[ "${1:-}" = "--reseed" ] && RESEED=true

if [ "$RESEED" = true ] || [ ! -f done/report-2025-12.csv ]; then
  echo "==> First run (or --reseed): generating synthetic loan data and starting the app"
  echo "    (profile=generator seeds the data, then the SAME process keeps running --"
  echo "     its built-in poller ingests the generated files within ~10s, so this one"
  echo "     command is all you need; no separate second run required)"
  exec mvn spring-boot:run -Dspring-boot.run.profiles=generator
else
  echo "==> Data already seeded; starting the app"
  echo "    (pass --reseed to regenerate from scratch)"
  exec mvn spring-boot:run
fi
