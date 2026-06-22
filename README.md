# CommunityVoicesExample
Sample code project for Transcendent Endeavors

## Requirements

- Git
- Java 21 JDK
- Node.js 22
- npm 10
- Docker
- Docker Compose v2 (`docker compose`)

## Configuration Requirements

- Access to an Ollama endpoint for the Spring AI configuration in
  `backend/communityVoices/src/main/resources/application.properties`.  The Ollama instance will need two models.  Check the application.properties files for the required models.  They can be changed to something else as well.
- Redis is required by the backend, but it is provided automatically by
  `backend/communityVoices/docker-compose.yml` when running with Docker

## Ollama ( Optional )
- If Ollama access is not available elsewhere I included a Docker comopose file for that as well.  docker-compose-ollama.yml.  This will create a container with a running Ollama instance and pull the two models needed.

## Run With Docker

To run the application with Docker, start the compose file in `backend/communityVoices`:

```bash
cd backend/communityVoices
docker compose up --build
```

## Build The API

The API is a Spring Boot application built with Maven Wrapper.

```bash
cd backend/communityVoices
./mvnw clean package
```

Build output:

- JAR file: `backend/communityVoices/target/communityVoices-0.0.1-SNAPSHOT.jar`

## Build The Angular App

Install dependencies and create a production build:

```bash
cd webapp
npm install
npm run build
```

Build output:

- Static files: `webapp/dist/webapp`
