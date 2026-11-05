FROM python:3.13-slim
WORKDIR /app
COPY scripts/camel-acceptance.py scripts/camel-hawtio-proxy.py ./
COPY docker/hawtio/proxy.py ./container-proxy.py
USER 65534:65534
CMD ["python3", "-u", "container-proxy.py"]
