# Flowmatic — Backend

Backend service for **[Flowmatic](https://flowmaticai.in)**, an AI-powered drag-and-drop workflow automation platform inspired by tools like Zapier and n8n.

This Spring Boot service handles the core backend functionality — storing workflows, running them, tracking executions, managing integrations, authentication, billing, and usage.

🌐 [flowmaticai.in](https://flowmaticai.in) · 🚀 [app.flowmaticai.in](https://app.flowmaticai.in)

[![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk\&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot\&logoColor=white)](https://spring.io/projects/spring-boot)
[![MySQL](https://img.shields.io/badge/MySQL-Aiven-4479A1?logo=mysql\&logoColor=white)](https://mysql.com/)
[![Stripe](https://img.shields.io/badge/Stripe-Billing-635BFF?logo=stripe\&logoColor=white)](https://stripe.com/)
[![Docker](https://img.shields.io/badge/Docker-ready-2496ED?logo=docker\&logoColor=white)](https://www.docker.com/)

## Features

* **Authentication** — JWT-based authentication with email OTP verification, refresh tokens, and BCrypt password hashing.
* **Workflow engine** — Executes workflows as DAGs using topological sorting, conditional branching, and `{{node.field}}` data templating.
* **AI nodes** — Uses Spring AI with Groq/Llama to process workflow data, return structured JSON, and generate AI-based workflow prompts.
* **Data sources** — Supports CSV uploads through Cloudinary as well as live Google Drive and Google Sheets data through OAuth2.
* **HTTP, Filter & Transform nodes** — Make API requests, filter data, and transform it as it moves through a workflow.
* **Workflow execution** — Uses a FIFO run queue and keeps detailed execution logs for each node.
* **Email** — Sends emails through Resend and supports manual approval before sending.
* **Billing** — Stripe subscriptions with webhook-based plan synchronization and usage limits designed to handle concurrent requests safely.
* **Analytics** — Tracks workflow runs, success rates, and execution status.

## What is Flowmatic?

Flowmatic is a visual workflow automation platform where you can connect different services and build workflows without having to write all the integration logic yourself.

The backend is responsible for taking those workflows and actually executing them.

https://github.com/user-attachments/assets/a0ab593a-5434-4a24-8b35-866469f6e787

## Architecture

![Architecture](.github/assets/architecture.png)

## Tech Stack

* Java 17
* Spring Boot
* Spring Security
* Spring Data JPA
* Spring AI + Groq
* MySQL
* Stripe
* Cloudinary
* Resend
* Google OAuth2
* Docker
* Maven

## Getting Started

Clone the repository and start the Spring Boot application:

```bash
git clone https://github.com/swayamterode/flowmatic-backend.git
cd flowmatic-backend
./mvnw spring-boot:run   # http://localhost:8080
```

You can also run the backend with Docker:

```bash
docker build -t flowmatic-backend .
docker run -p 8080:8080 --env-file .env flowmatic-backend
```

Before starting the application, you'll need to configure a MySQL database along with the required API keys for services such as Groq, Resend, Cloudinary, Stripe, and Google.

The available environment variables are listed in `application.properties`.

## Testing

Run the test suite with:

```bash
./mvnw test
```

## Author

**Swayam Terode** — [GitHub](https://github.com/swayamterode)
