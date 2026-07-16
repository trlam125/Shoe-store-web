# LSHOE Store

LSHOE Store is a shoe e-commerce application built with Spring Boot, Thymeleaf, PostgreSQL, and a separate Python AI service. It includes the standard shopping workflow, an NVIDIA-powered chatbot, customer segmentation, sales forecasting, and image-based product search.

## Features

- Product and category management
- Shopping cart and order management
- User authentication and authorization with Spring Security
- Website chatbot powered by the NVIDIA API
- Customer segmentation using RFM analysis and K-Means clustering
- Product sales forecasting using machine learning
- Similar-product image search using a pretrained ResNet18 model

## Technology Stack

### Web Application

- Java 21
- Spring Boot 3.5.3
- Spring MVC, Spring Data JPA, and Spring Security
- Thymeleaf
- PostgreSQL 16
- Maven

### AI Service

- Python 3.11
- FastAPI and Uvicorn
- pandas, scikit-learn, and psycopg
- PyTorch and torchvision
- ResNet18 pretrained on ImageNet

## Project Structure

```text
Lshoe-store/
|-- ai-service/                   Python ML/DL service
|   |-- app/main.py               FastAPI endpoints and AI logic
|   |-- requirements.txt          Python dependencies
|   |-- run.py                    Uvicorn entry point
|   |-- run.bat                   Windows setup and launcher
|   `-- run.sh                    Linux/macOS setup and launcher
|-- Chatbot/                      Local chatbot environment file
|-- src/main/java/                Spring Boot source code
|-- src/main/resources/           Templates, static files, and configuration
|-- docker-compose.yml            PostgreSQL development container
`-- pom.xml                       Maven configuration
```

## Prerequisites

Install the following tools before running the project:

- Java 21
- Maven 3.9 or the Maven integration included with IntelliJ IDEA
- PostgreSQL 16, or Docker Desktop
- Python 3.11 for `ai-service`
- An NVIDIA API key for the chatbot
- Internet access the first time image search runs, so torchvision can download the pretrained ResNet18 weights

## 1. Start PostgreSQL

### Option A: Docker Compose

From the project root, run:

```bash
docker compose up -d postgres
```

The Docker database uses these credentials:

```text
Host: localhost
Port: 5432
Database: lshoe_store
Username: postgres
Password: postgres
```

### Option B: Local PostgreSQL

Create the database manually:

```sql
CREATE DATABASE lshoe_store WITH ENCODING 'UTF8';
```

Use your own PostgreSQL username and password in the configuration steps below.

## 2. Configure Spring Boot and the Chatbot

Create `Chatbot/.env` in the project root. For the Docker Compose database, use:

```env
DATABASE_URL=jdbc:postgresql://localhost:5432/lshoe_store
DB_USERNAME=postgres
DB_PASSWORD=postgres

NVIDIA_API_KEY=nvapi-your-key-here
```

Optional chatbot settings:

```env
CHATBOT_API_URL=https://integrate.api.nvidia.com/v1/chat/completions
CHATBOT_MODEL=openai/gpt-oss-120b
CHATBOT_TIMEOUT_MS=30000
```

Important notes:

- Spring Boot database URLs must start with `jdbc:postgresql://`.
- If `DB_PASSWORD` is not provided, Spring Boot currently defaults to `120505`.
- The chatbot page can load without an API key, but chatbot requests will fail until `NVIDIA_API_KEY` is configured.
- Do not commit `Chatbot/.env` or expose the API key in frontend code.

## 3. Configure the AI Service

Copy `ai-service/.env.example` to `ai-service/.env`, then update its database URL. For the Docker Compose database, use:

```env
AI_DATABASE_URL=postgresql://postgres:postgres@localhost:5432/lshoe_store
AI_PORT=8001
AI_HOST=0.0.0.0
AI_RELOAD=true
AI_ALLOWED_ORIGINS=http://localhost:8081,http://127.0.0.1:8081
AI_MAX_UPLOAD_BYTES=10485760
```

The AI service URL does not use the JDBC prefix. Spring Boot and `ai-service` must connect to the same PostgreSQL database.

## 4. Run the Project

Spring Boot and `ai-service` are separate processes. Both must be running for all AI features to work.

### Start the AI Service on Windows

```bat
cd ai-service
run.bat
```

The script creates `.venv` when needed, installs the Python dependencies, and starts FastAPI on port `8001`.

### Start the AI Service on Linux or macOS

```bash
cd ai-service
chmod +x run.sh
./run.sh
```

### Start the AI Service Manually

```bash
cd ai-service
python -m venv .venv
```

Activate the virtual environment on Windows:

```bat
.venv\Scripts\activate
```

Activate it on Linux or macOS:

```bash
source .venv/bin/activate
```

Then install dependencies and start the service:

```bash
pip install -r requirements.txt
python run.py
```

### Start Spring Boot from the Terminal

From the project root, run:

```bash
mvn spring-boot:run
```

The web application is available at:

```text
http://localhost:8081
```

## Running with IntelliJ IDEA

1. Open the project root in IntelliJ IDEA.
2. Select Java 21 as the Project SDK.
3. Allow IntelliJ to import the Maven dependencies from `pom.xml`.
4. Start PostgreSQL.
5. Run `ai-service/run.bat` in a separate terminal.
6. Run the `LshoeStoreApplication` class from IntelliJ.
7. Open `http://localhost:8081` in a browser.

The Spring Boot application can run without `ai-service`, but customer segmentation, sales forecasting, and image search will be unavailable. The chatbot only requires Spring Boot, internet access, and a valid NVIDIA API key.

## Application URLs

| Component | URL |
| --- | --- |
| Store website | `http://localhost:8081` |
| Admin dashboard | `http://localhost:8081/admin` |
| Image search | `http://localhost:8081/ai/image-search` |
| AI service health check | `http://localhost:8001/health` |
| AI service Swagger UI | `http://localhost:8001/docs` |

## AI Service API

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/health` | Checks the AI service and database connection |
| `GET` | `/ml/customer-segments` | Returns RFM customer segments generated with K-Means |
| `GET` | `/ml/sales-forecast?days=7` | Forecasts product sales for the requested number of days |
| `POST` | `/dl/image-search?limit=6` | Finds products with images similar to an uploaded image |

The browser sends image-search requests through Spring Boot at `/ai/image-search/analyze`; Spring Boot then proxies them to the Python service.

## AI and Data Notes

- Customer segmentation uses completed orders and works best when the database contains enough customers and purchase history.
- Sales forecasting uses completed-order history and fills missing dates with zero sales before training.
- When there is not enough sales history, the service returns a fallback estimate instead of failing.
- Image search uses a pretrained ResNet18 neural network to extract image embeddings and compare visual similarity.
- The first image-search request may take longer while the pretrained model weights are downloaded and loaded.
- Uploaded images must be JPEG, PNG, or WebP and no larger than 10 MB.

## Troubleshooting

### Spring Boot cannot connect to PostgreSQL

- Confirm PostgreSQL is running on port `5432`.
- Check `DATABASE_URL`, `DB_USERNAME`, and `DB_PASSWORD` in `Chatbot/.env`.
- If Docker Compose is used, set the password to `postgres` unless `docker-compose.yml` was changed.

### The chatbot does not respond

- Confirm `NVIDIA_API_KEY` exists in `Chatbot/.env`.
- Restart Spring Boot after changing the environment file.
- Confirm the machine can access `https://integrate.api.nvidia.com`.
- Check the Spring Boot console for an API authentication or timeout error.

### AI features are unavailable

- Confirm `ai-service` is running at `http://localhost:8001`.
- Open `http://localhost:8001/health` and verify the database status.
- Confirm `AI_DATABASE_URL` points to the same database used by Spring Boot.
- Review the `ai-service` terminal for Python dependency or database errors.

### Image search fails

- Use a JPEG, PNG, or WebP image smaller than 10 MB.
- Allow extra time on the first request while ResNet18 is initialized.
- Confirm internet access is available if the model weights have not been downloaded before.

### Python is not found on Windows

- Install Python 3.11 and enable the **Add Python to PATH** option.
- Restart IntelliJ IDEA and its terminal after installing Python.
- Run `py -3.11 --version` or `python --version` to confirm the installation.

## Security

- Keep `.env` files, database passwords, and API keys out of source control.
- Use environment-specific secrets in production.
- Do not expose the Python AI service directly to the public internet; route browser requests through Spring Boot.
- Replace development passwords before deploying the application.
