#!/usr/bin/env python3
# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the Mozilla Public License, v. 2.0.
# https://mozilla.org/MPL/2.0/
"""Run ANPA tests on the Maven-resolved native Fineract runtime and fresh local PostgreSQL DBs."""
import base64
import getpass
import os
from pathlib import Path
import signal
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[4]
RUN = ROOT / "target" / ("anpa-native-" + uuid.uuid4().hex[:10])
RUN.mkdir(parents=True)
DB_USER = os.environ.get("PGUSER", getpass.getuser())
DB_PORT = os.environ.get("PGPORT", "5432")
DB_HOST = "127.0.0.1"
PREFIX = "web1205_it_" + uuid.uuid4().hex[:10]
DATABASES = [PREFIX + "_tenants", PREFIX + "_default", PREFIX + "_secondary"]


def maven(arguments, log_name):
    with (RUN / log_name).open("w") as log:
        result = subprocess.run([str(ROOT / "mvnw"), *arguments], cwd=ROOT,
                                stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f"Maven failed: {RUN / log_name}")


def main():
    # The existing project test setup also resolves the pinned runtime before integration-test.
    maven(["-DskipTests", "-Djacoco.skip=true", "package", "dependency:build-classpath", "-DincludeScope=runtime",
           "-Dmdep.outputFile=" + str(RUN / "classpath.txt")], "build.log")
    for database in DATABASES:
        subprocess.run(["createdb", "-h", DB_HOST, "-p", DB_PORT, "-U", DB_USER, database], check=True)
    print("Created disposable databases:", ", ".join(DATABASES), flush=True)
    with socket.socket() as listener:
        listener.bind((DB_HOST, 0))
        port = listener.getsockname()[1]
    env = os.environ.copy()
    env.update({
        "FINERACT_HIKARI_JDBC_URL": f"jdbc:postgresql://{DB_HOST}:{DB_PORT}/{DATABASES[0]}",
        "FINERACT_HIKARI_DRIVER_SOURCE_CLASS_NAME": "org.postgresql.Driver",
        "FINERACT_HIKARI_USERNAME": DB_USER,
        "FINERACT_HIKARI_PASSWORD": os.environ.get("PGPASSWORD", ""),
        "FINERACT_DEFAULT_TENANTDB_HOSTNAME": DB_HOST,
        "FINERACT_DEFAULT_TENANTDB_PORT": DB_PORT,
        "FINERACT_DEFAULT_TENANTDB_UID": DB_USER,
        "FINERACT_DEFAULT_TENANTDB_PWD": os.environ.get("PGPASSWORD", ""),
        "FINERACT_DEFAULT_TENANTDB_NAME": DATABASES[1],
        "FINERACT_DEFAULT_TENANTDB_CONN_PARAMS": "",
        "FINERACT_DEFAULT_TENANTDB_TIMEZONE": "UTC",
        "FINERACT_MODULE_SAVINGS_ENABLED": "true",
        "FINERACT_MODE_BATCH_WORKER_ENABLED": "false",
        "FINERACT_MODE_BATCH_MANAGER_ENABLED": "false",
        "FINERACT_SERVER_PORT": str(port),
        "FINERACT_SERVER_SSL_ENABLED": "false",
        "FINERACT_NODE_ID": "55",
        "SERVER_ADDRESS": DB_HOST,
        "TZ": "UTC",
    })
    classpath = str(ROOT / "target/classes") + os.pathsep + (RUN / "classpath.txt").read_text().strip()
    base = f"http://{DB_HOST}:{port}/fineract-provider"
    def launch():
      with (RUN / "runtime.log").open("a") as log:
        process = subprocess.Popen(["java", "-Xmx2g", "-Duser.timezone=UTC", "-cp", classpath,
                                    "org.apache.fineract.ServerApplication", "--logging.level.root=INFO"],
                                   cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True)
      return process

    def stop(process):
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()

    def wait_ready(process):
        deadline = time.monotonic() + 180
        ready = False
        while time.monotonic() < deadline and process.poll() is None:
            request = urllib.request.Request(base + "/api/v1/loans/anpa-writeoff/template", headers={
                "Fineract-Platform-TenantId": "default",
                "Authorization": "Basic " + base64.b64encode(b"mifos:password").decode(),
            })
            try:
                with urllib.request.urlopen(request, timeout=3) as response:
                    ready = response.status == 200
            except (urllib.error.URLError, TimeoutError):
                pass
            if ready:
                break
            time.sleep(1)
        if not ready:
            raise RuntimeError(f"Native startup failed: {RUN / 'runtime.log'}")

    process=launch()
    try:
        wait_ready(process)
        stop(process)
        # Provision tenant metadata only. Native Liquibase owns both tenant schemas on restart.
        psql=["psql","-h",DB_HOST,"-p",DB_PORT,"-U",DB_USER,"-d",DATABASES[0],"-v","ON_ERROR_STOP=1"]
        columns=subprocess.check_output(psql+["-Atc","SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='tenant_server_connections' AND column_name NOT IN ('id','schema_name') ORDER BY ordinal_position"],text=True).splitlines()
        column_list=",".join(columns)
        sql=("WITH added AS (INSERT INTO tenant_server_connections("+column_list+",schema_name) SELECT "+column_list+",'"+DATABASES[2]+"' FROM tenant_server_connections WHERE id=(SELECT oltp_id FROM tenants WHERE identifier='default') RETURNING id) INSERT INTO tenants(identifier,name,timezone_id,oltp_id,report_id) SELECT 'anpa-secondary','ANPA secondary','UTC',id,id FROM added;")
        subprocess.run(psql,input=sql,text=True,check=True,stdout=subprocess.DEVNULL)
        process=launch()
        wait_ready(process)
        # A targeted run does not attempt the bundle-wide coverage gate. Run ./mvnw test separately.
        maven(["-Dtest=AnpaAccountingTest,AnpaValidationTest,AnpaAccessTest", "-Djacoco.skip=true",
               "-Dit.test=AnpaNativeIntegrationTest", "-Danpa.it.baseUrl=" + base,
               f"-Danpa.it.jdbcUrl=jdbc:postgresql://{DB_HOST}:{DB_PORT}/{DATABASES[1]}",
               f"-Danpa.it.secondaryJdbcUrl=jdbc:postgresql://{DB_HOST}:{DB_PORT}/{DATABASES[2]}",
               "-Danpa.it.dbUser=" + DB_USER, "verify"], "tests.log")
        print("ANPA native integration passed. Evidence and logs:", RUN, flush=True)
    finally:
        stop(process)
        # Keep only these newly created fixture DBs for inspection; existing databases are untouched.
        print("Preserved disposable fixture databases:", ", ".join(DATABASES), flush=True)


if __name__ == "__main__":
    main()
