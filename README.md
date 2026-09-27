# DEVPILOT

DevPilot is an AI-powered GitHub repository assistant and RAG (Retrieval-Augmented Generation) chat platform. It connects with your GitHub account, indexes your repositories into high-dimensional vector embeddings, and enables real-time conversational Q&A grounded directly in your codebase.

---

## Features

- **GitHub OAuth Integration**: Connect and synchronize personal and organization repositories.
- **Automated Repository Indexing**: Parse, chunk, and embed code files with intelligent rate-limit handling and backoff.
- **RAG-Powered Chat**: Ground answers with exact file paths and line citations.
- **Real-time Streaming**: Token-by-token SSE (Server-Sent Events) chat response streaming.
- **Background Job Management**: Real-time progress tracking with instant cancel capabilities.
- **Modern Tech Stack**: Spring Boot 4 + Spring AI + Google Gemini + NeonDB (PostgreSQL + pgvector) + Next.js 16 + Tailwind CSS.

---

## Architecture

- **Backend**: Java 21, Spring Boot 4.1.0, Spring AI, Spring Security OAuth2
- **Vector Database**: Neon Serverless PostgreSQL with `pgvector`
- **AI Models**: 
  - Chat: Google Gemini (`gemini-3.5-flash-lite`)
  - Embeddings: Google Gemini (`gemini-embedding-001`, 768 dimensions)
- **Frontend**: Next.js 16 (App Router), TypeScript, Tailwind CSS, TanStack React Query, Base UI

---

## Getting Started

### Prerequisites

- Java 21+
- Node.js 20+
- A Google Gemini API key
- A GitHub OAuth Application ([Create one here](https://github.com/settings/developers))
- A PostgreSQL database with `pgvector` enabled (e.g. [Neon](https://neon.tech))

---

### Backend Setup

1. Navigate to `backend`:
   ```bash
   cd backend
   ```
2. Copy the sample configuration:
   ```bash
   cp src/main/resources/application.properties.example src/main/resources/application.properties
   ```
3. Fill in your credentials in `application.properties`:
   - `spring.datasource.url`: Neon / Postgres JDBC URL
   - `spring.datasource.username` & `password`
   - `spring.ai.google.genai.api-key`: Your Gemini API key
   - `spring.security.oauth2.client.registration.github.client-id` & `client-secret`
4. Run the backend:
   ```bash
   ./mvnw spring-boot:run
   ```
   Backend will start on `http://localhost:8081`.

---

### Frontend Setup

1. Navigate to `client`:
   ```bash
   cd client
   ```
2. Install dependencies:
   ```bash
   npm install
   ```
3. Configure `.env.local`:
   ```bash
   NEXT_PUBLIC_API_BASE_URL=http://localhost:8081
   ```
4. Run the development server:
   ```bash
   npm run dev
   ```
   Open [http://localhost:3000](http://localhost:3000) in your browser.

---

## Deployment

Refer to `docker-compose.prod.yml` and `.env.production.example` for containerized and cloud deployments (Vercel, Render, Railway, AWS, Docker).

---

## License

MIT License.
