# Filum Slicer: OrcaSlicer CLI behind a small HTTP API (see README.md).
FROM ubuntu:24.04

ARG ORCA_VERSION=2.4.2
COPY app/install_orca.sh /tmp/install_orca.sh
RUN bash /tmp/install_orca.sh deps \
 && bash /tmp/install_orca.sh orca /opt/orca "${ORCA_VERSION}" \
 && rm /tmp/install_orca.sh

COPY app /app

ENV ORCA_DIR=/opt/orca \
    SLICER_DATA=/data \
    SLICER_PORT=8090 \
    PYTHONUNBUFFERED=1
VOLUME /data
EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
  CMD python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/health', timeout=4)"

CMD ["python3", "/app/server.py"]
