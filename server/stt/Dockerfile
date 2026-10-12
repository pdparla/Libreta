# Dictation for Libreta (docs/design/ai.md): faster-whisper on the CPU behind a small HTTPS endpoint.
FROM python:3.12-slim

# faster-whisper 1.1.1 imports requests without declaring it. The app decodes WAV itself, but faster-whisper
# still imports PyAV; 14+ dropped an argument 1.1.1 passes, hence av 12.
RUN pip install --no-cache-dir faster-whisper==1.1.1 requests==2.32.3 av==12.3.0

COPY app.py /app/app.py
# No USER: with rootless Docker the container's root is the server user that runs it, not the host's root, and
# that is the user who owns the key and the token (mode 600).
ENV HF_HOME=/cache/huggingface
EXPOSE 8443
CMD ["python", "/app/app.py"]
