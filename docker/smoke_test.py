"""Linux Docker acceptance test, using disposable volumes and synthetic records."""
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid

image = sys.argv[1]
base = "http://127.0.0.1:8484"
session = None
request_id = 0

def docker(*args, check=True):
    return subprocess.run(["docker", *args], check=check, capture_output=True, text=True)

def post(path, body):
    headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
    if session:
        headers["Mcp-Session-Id"] = session
        headers["MCP-Protocol-Version"] = "2025-03-26"
    req = urllib.request.Request(base + path, data=json.dumps(body).encode(), headers=headers)
    with urllib.request.urlopen(req, timeout=120) as response:
        data = response.read().decode()
        if not data.strip(): return {}, response.headers
        if data.lstrip().startswith("{"): return json.loads(data), response.headers
        for line in data.splitlines():
            if line.startswith("data:"):
                return json.loads(line[5:].strip()), response.headers
        raise RuntimeError("Unrecognized response")

def initialize():
    global session
    session = None
    result, headers = post("/mcp", {"jsonrpc":"2.0", "id":1, "method":"initialize",
        "params":{"protocolVersion":"2025-03-26", "capabilities":{},
                  "clientInfo":{"name":"container-test", "version":"1"}}})
    assert "error" not in result, result
    session = headers.get("Mcp-Session-Id")
    post("/mcp", {"jsonrpc":"2.0", "method":"notifications/initialized"})

def tool(name, arguments):
    global request_id
    request_id += 1
    result, _ = post("/mcp", {"jsonrpc":"2.0", "id":request_id + 10,
        "method":"tools/call", "params":{"name":name, "arguments":arguments}})
    assert "error" not in result, result
    value = result["result"]
    assert not value.get("isError"), value
    if "structuredContent" in value: return value["structuredContent"]
    return json.loads(next(c["text"] for c in value["content"] if c["type"] == "text"))

def ready(name):
    for _ in range(90):
        state = docker("inspect", "-f", "{{.State.Running}}", name).stdout.strip()
        if state != "true": raise RuntimeError("Container exited: " + docker("logs", name).stdout[-4000:])
        try:
            with urllib.request.urlopen(base + "/capture", timeout=2) as response:
                if response.status == 200: return
        except Exception: pass
        time.sleep(2)
    raise RuntimeError("Container startup timeout")

for encrypted in [False, True]:
    suffix = uuid.uuid4().hex[:10]
    name, volume = "surstor-test-" + suffix, "surstor-test-data-" + suffix
    with tempfile.TemporaryDirectory() as tmp:
        key = Path(tmp) / "etch.key"
        key.write_text(secrets.token_hex(32)); os.chmod(key, 0o444)
        # Synthetic key, mounted read-only in this disposable test only.
        args = ["run", "-d", "--name", name, "--read-only", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges:true", "--tmpfs", "/tmp:rw,exec,nosuid,size=1g,mode=1777",
                "-p", "127.0.0.1:8484:8484", "-v", volume + ":/data"]
        if encrypted: args += ["--mount", "type=bind,src=" + str(key) + ",dst=/run/secrets/etch-key,readonly"]
        args += [image]
        if encrypted: args += ["start", "--config", "/app/config/encrypted.toml", "--allow-container-network"]
        try:
            docker(*args); ready(name); initialize()
            text = "Orchard uses SQLite for storage and UTC timestamps."
            saved = tool("surstor_store", {"content":text, "sessionId":"docker-test"})
            record_id = saved["id"]
            assert tool("surstor_verify", {"id":record_id, "deep":True})["valid"]
            docker("restart", "-t", "60", name); ready(name); initialize()
            assert tool("surstor_retrieve", {"id":record_id})["content"] == text
            assert tool("surstor_verify", {"id":record_id, "deep":True})["valid"]
            if not encrypted:
                found = tool("surstor_search", {"query":"Orchard database timezone", "sessionId":"docker-test", "k":3})
                assert any(r["id"] == record_id for r in found["results"])
            else:
                # The configured encrypted profile must never create a plaintext search index.
                docker("exec", name, "sh", "-c", "test ! -d /data/search-index")
            print("PASS encrypted=" + str(encrypted), flush=True)
        except Exception:
            logs = docker("logs", name, check=False)
            print(logs.stdout[-6000:], logs.stderr[-6000:])
            raise
        finally:
            docker("rm", "-f", name, check=False)
            docker("volume", "rm", volume, check=False)
