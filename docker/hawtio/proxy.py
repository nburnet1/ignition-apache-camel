"""Container-only adapter: fixed disposable Compose Gateway, no host listener."""
import importlib.util
import os
import time

spec = importlib.util.spec_from_file_location("proxy", "/app/camel-hawtio-proxy.py")
proxy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proxy)
password = os.environ["CAMEL_HAWTIO_PROXY_PASSWORD"]
if not password:
    raise SystemExit("A development proxy password is required")
client = proxy.acceptance_client()
# Not configurable: never turn this into an arbitrary Gateway/JMX proxy.
client.BASE = "http://gateway:8088"
for attempt in range(60):
    try:
        if client.bootstrap() and client.api("GET", client.MODULE + "/camel-jolokia/version")["status"] == 200:
            break
    except Exception:
        pass  # Do not log authentication responses or session material.
    if attempt == 59:
        raise SystemExit("Disposable Gateway management endpoint did not become ready")
    time.sleep(2)
server = proxy.make_server(proxy.RenewableSession(client), password, bind_host="0.0.0.0",
                          console_origin=os.environ["CAMEL_HAWTIO_CONSOLE_ORIGIN"])
try:
    print("Development Hawtio adapter ready (Compose network only)", flush=True)
    server.serve_forever()
finally:
    server.server_close()
