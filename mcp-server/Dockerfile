# ---------- 构建/运行 ----------
FROM python:3.12-slim
WORKDIR /mcp
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
ENV MCP_SERVER_HOST=0.0.0.0
EXPOSE 8090
CMD ["python", "server.py"]