"""Fixture authoring uses direct consumer URIs, never a runtime mapping layer."""
import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location("camel_acceptance", Path(__file__).with_name("camel-acceptance.py"))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class DirectHttpFixtureTest(unittest.TestCase):
    def test_fixture_namespacing_writes_an_explicit_consumer_uri(self):
        source = 'uri: ignition-gateway-http:/submit?httpMethodRestrict=POST\n'
        result = fixture.fixture_http_yaml("proof", source)
        self.assertEqual('uri: ignition-gateway-http:/proof/submit?httpMethodRestrict=POST\n', result)
        self.assertEqual(result, fixture.fixture_http_yaml("renamed", result))

    def test_explicit_public_paths_are_not_rewritten(self):
        source = 'uri: ignition-gateway-http:/v1/orders/submit?httpMethodRestrict=POST\n'
        self.assertEqual(source, fixture.fixture_http_yaml("anything", source))

    def test_outgoing_endpoints_are_not_rewritten(self):
        source = 'uri: https://example.com/orders\n'
        self.assertEqual(source, fixture.fixture_http_yaml("proof", source))


if __name__ == "__main__":
    unittest.main()
