"""Exercise the authenticated sync listener through Docker port mapping on Linux.
Two encrypted containers use distinct identities and Etch keys. A host client
exchanges records both ways, modelling a trusted tunnel/proxy transport.
"""
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

image = sys.argv[1]
def docker(*args, check=True):
    return subprocess.run(["docker", *args], capture_output=True, text=True, check=check)
def request(port, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token: headers["Authorization"] = "Bearer " + token
    data = None if body is None else json.dumps(body).encode()
    with urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{port}" + path, data=data, headers=headers), timeout=30) as response:
        raw = response.read()
        return json.loads(raw) if raw else None

def ready(node):
    for _ in range(90):
        if docker("inspect", "-f", "{{.State.Running}}", node["name"]).stdout.strip() != "true":
            raise RuntimeError("Sync test container stopped")
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{node['http']}/capture", timeout=2) as response:
                if response.status == 200: return
        except Exception: pass
        time.sleep(1)
    raise RuntimeError("Sync test container startup timed out")

with tempfile.TemporaryDirectory() as tmp:
    folder = Path(tmp); os.chmod(folder, 0o755)
    token = secrets.token_hex(32)
    tokenfile = folder / "token"; tokenfile.write_text(token); os.chmod(tokenfile, 0o444)
    nodes = []
    try:
        for i in range(2):
            suffix = uuid.uuid4().hex[:10]
            node = {"name": "sync-test-"+suffix, "volume": "sync-data-"+suffix,
                    "http": 8484+i*100, "sync": 8485+i*100}
            nodes.append(node)
            key = folder / (suffix+".key"); key.write_text(secrets.token_hex(32)); os.chmod(key, 0o444)
            config = folder / (suffix+".toml"); node["config"] = config
            text = f'''[node]
bind = "0.0.0.0"
port = {node["http"]}
data_dir = "/data"
[encryption]
enabled = true
key_file = "/run/secrets/etch-key"
[search]
enabled = false
'''
            node["base_config"] = text
            config.write_text(text); os.chmod(config, 0o444)
            docker("run", "-d", "--name", node["name"], "--read-only", "--cap-drop", "ALL",
                   "--security-opt", "no-new-privileges:true", "--tmpfs", "/tmp:rw,exec,nosuid,size=1g,mode=1777",
                   "-p", f"127.0.0.1:{node['http']}:{node['http']}", "-p", f"127.0.0.1:{node['sync']}:{node['sync']}",
                   "-v", node["volume"]+":/data",
                   "--mount", f"type=bind,src={key},dst=/run/secrets/etch-key,readonly",
                   "--mount", f"type=bind,src={tokenfile},dst=/run/secrets/sync-token,readonly",
                   "--mount", f"type=bind,src={config},dst=/run/sync.toml,readonly",
                   image, "start", "--config", "/run/sync.toml", "--allow-container-network")
            ready(node)
            logs = docker("logs", node["name"])
            node["signer"] = re.search(r"Sync signer public key: ([0-9a-f]{64})", logs.stdout+logs.stderr).group(1)
        assert nodes[0]["signer"] != nodes[1]["signer"]
        signers = ",".join(n["signer"] for n in nodes)
        for node in nodes:
            docker("stop", "-t", "60", node["name"])
            os.chmod(node["config"], 0o644)
            node["config"].write_text(node["base_config"]+f'''[sync]
enabled = true
bind = "0.0.0.0"
port = {node["sync"]}
token_file = "/run/secrets/sync-token"
sessions = "shared-memory"
trusted_signers = "{signers}"
peers = ""
''')
            os.chmod(node["config"], 0o444)
            docker("start", node["name"]); ready(node)
            try:
                request(node["sync"], "/v1/ids")
                raise AssertionError("Unauthenticated sync was allowed")
            except urllib.error.HTTPError as error: assert error.code == 401
            preview = request(node["http"], "/api/v1/capture/preview", {"content": "container-memory-"+node["name"], "sessionId": "shared-memory"})
            request(node["http"], "/api/v1/capture/commit", {"token": preview["token"]})
        # Exchange original signed packets through the same GET/POST endpoints used by the worker.
        for source, destination in [(nodes[0], nodes[1]), (nodes[1], nodes[0])]:
            for record in request(source["sync"], "/v1/ids", token=token):
                packet = request(source["sync"], "/v1/record/"+record, token=token)
                request(destination["sync"], "/v1/record/"+record, packet, token)
        expected = request(nodes[0]["sync"], "/v1/ids", token=token)
        assert len(expected) == 2
        for node in nodes:
            assert request(node["sync"], "/v1/ids", token=token) == expected
            docker("restart", "-t", "60", node["name"]); ready(node)
            assert request(node["sync"], "/v1/ids", token=token) == expected
            docker("exec", node["name"], "sh", "-c", "test ! -d /data/search-index")
        print("PASS: two encrypted containers; authenticated bidirectional record exchange and restart persistence", flush=True)
    except Exception:
        for node in nodes:
            log = docker("logs", node["name"], check=False)
            print(log.stdout[-2500:], log.stderr[-2500:])
        raise
    finally:
        for node in nodes:
            docker("rm", "-f", node["name"], check=False)
            docker("volume", "rm", node["volume"], check=False)
