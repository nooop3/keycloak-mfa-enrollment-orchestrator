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

2. Start Keycloak with watch mode:

   ```bash
   docker compose watch
   ```

   This will start Keycloak and automatically restart it when you rebuild the project.

3. Access Keycloak at <http://localhost:8080>
   - Admin Console: <http://localhost:8080/admin>
   - Username: `admin`
   - Password: `admin`

## Configuration

The `docker-compose.yaml` uses Docker Compose's built-in `watch` feature for automatic reloading:

- `../target` is bind-mounted into `/opt/keycloak/providers`, so the freshly built JAR is immediately visible to Keycloak.
- A lightweight marker file at `.dev/keycloak-restart` is touched during the Maven `package` phase (and by `scripts/watch-mvn-package.sh`). The `develop.watch` entry monitors the `.dev` directory and, when the marker changes, syncs it into the container and restarts Keycloak.
- Always use `docker compose watch` (or `docker compose up --watch`) while iterating so the restart automation stays active. Without watch mode, rebuilds still land in `/opt/keycloak/providers`, but you must restart Keycloak manually.
