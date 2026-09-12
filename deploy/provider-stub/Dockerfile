# IntelliDesk E2E-only deterministic provider stub.
# Serves OpenAI-compatible /v1/chat/completions (stream + non-stream + Agent
# tool-call fixture) and /v1/embeddings on 0.0.0.0:18080 so the containerized
# backend can reach it over the Docker network. Only used by docker-compose.e2e.yml
# as E2E infrastructure — never part of the production image or compose.
FROM node:22-alpine
WORKDIR /srv
COPY stub.js /srv/stub.js
EXPOSE 18080
CMD ["node", "/srv/stub.js"]