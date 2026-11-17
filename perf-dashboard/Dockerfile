# perf-dashboard: build the React app, then serve it + the API with gunicorn.
#   docker build -t labuk-perf-dashboard:1.0.0 perf-dashboard
FROM node:20-alpine AS frontend-build
WORKDIR /frontend
COPY frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ .
# vite writes to ../static/dist
RUN mkdir -p /static && npm run build

FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY *.py ./
COPY routes/ routes/
COPY --from=frontend-build /static/dist ./static/dist
USER 1000
EXPOSE 5000
# one process (threads for concurrency): live metrics remember each worker's last numbers in memory
CMD ["gunicorn", "--bind", "0.0.0.0:5000", "--workers", "1", "--threads", "8", "--timeout", "60", "app:create_app()"]
