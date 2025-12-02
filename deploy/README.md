# Test Environment

This directory contains a Docker Compose setup to test the MFA Enrollment Orchestrator.

## Prerequisites

- Docker and Docker Compose
- Maven (to build the project)

## Usage

1. Build the project:
   ```bash
   mvn clean package
   ```

2. Start Keycloak:
   ```bash
   docker-compose up
   ```

3. Access Keycloak at http://localhost:8080
   - Admin Console: http://localhost:8080/admin
   - Username: `admin`
   - Password: `admin`

## Configuration

The `docker-compose.yaml` mounts the `target` directory to `/opt/keycloak/providers`, so the built JAR file will be automatically detected by Keycloak.
