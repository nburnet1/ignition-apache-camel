"""Opt-in native database reference check on the dedicated 9288 Gateway.

Uses an existing native database (CamelQuartz by default), SELECT 1, and unique
temporary project/route resources. Does not create a database or registry alias.
"""
import json
from pathlib import Path
import runpy
import sys
import urllib.error
import uuid


def main() -> None:
    m = runpy.run_path(str(Path(__file__).with_name("camel-acceptance.py")))
    m["wait"]("9288 login", m["bootstrap"])
    database = sys.argv[1] if len(sys.argv) > 1 else "CamelQuartz"
    token = uuid.uuid4().hex[:12]
    project = "native_db_proof_" + token
    name = "native-db-proof-" + token
    path = "/" + name
    source = """
from javax.sql import DataSource

def query(exchange):
    source = exchange.getContext().getRegistry().lookupByNameAndType(%s, DataSource)
    connection = source.getConnection()
    try:
        exchange.getMessage().setBody(connection.runScalarQuery("SELECT 1"))
    finally:
        connection.close()

def build(builder):
    builder.from_(%s).routeId("native-database-proof").process(query)
""" % (json.dumps("ignition.database/" + database),
       json.dumps("ignition-gateway-http:" + path + "?httpMethodRestrict=POST"))
    registry_path = "/data/api/v1/resources/names/com.noahburnette.ignition.apachecamel/camel-registry"
    before = m["api"]("GET", registry_path)
    try:
        m["import_project"](project, source)
        m["create"](name, "routes.build", language="JYTHON", project=project)
        m["wait"]("native database proof route", lambda: m["api"]("GET", m["MODULE"] + "/camel-routes").get(name) == "RUNNING")
        assert m["api"]("POST", "/system/integrations" + path, {}) == 1
        assert m["api"]("GET", registry_path) == before, "The reference must not create a registry alias"
        print("Verified direct Ignition database reference, SELECT 1, and no added database binding.")
    finally:
        try:
            m["delete"](name)
        finally:
            try:
                m["api"]("DELETE", "/data/api/v1/projects/" + project)
            except urllib.error.HTTPError as error:
                if error.code != 404:
                    raise


if __name__ == "__main__":
    main()
