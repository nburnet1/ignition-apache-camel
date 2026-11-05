"""Keep scoped-key configuration probes on the installed Camel resource type."""
import ast
from pathlib import Path
import unittest


class ScopedKeyFixtureTest(unittest.TestCase):
    def test_configuration_denial_uses_the_registered_route_type(self):
        source = Path(__file__).with_name("camel-http-api-key-acceptance.py").read_text()
        tree = ast.parse(source)
        assignments = [node for node in ast.walk(tree) if isinstance(node, ast.Assign)
                       and any(isinstance(target, ast.Name) and target.id == "configuration_type"
                               for target in node.targets)]
        self.assertEqual(1, len(assignments))
        self.assertEqual("gateway.TYPE", ast.unparse(assignments[0].value))

    def test_component_configuration_fixture_explicitly_grants_http_invocation(self):
        source = Path(__file__).with_name("camel-configuration-acceptance.py").read_text()
        main = next(node for node in ast.parse(source).body
                    if isinstance(node, ast.FunctionDef) and node.name == "main")
        configs = [node.value for node in ast.walk(main) if isinstance(node, ast.Assign)
                   and any(isinstance(target, ast.Name) and target.id == "config"
                           for target in node.targets) and isinstance(node.value, ast.Dict)]
        self.assertEqual(1, len(configs))
        fields = {key.value: value for key, value in zip(configs[0].keys, configs[0].values)
                  if isinstance(key, ast.Constant)}
        self.assertEqual(["Authenticated/Roles/Administrator"],
                         ast.literal_eval(fields["httpSecurityLevels"]))


if __name__ == "__main__":
    unittest.main()
