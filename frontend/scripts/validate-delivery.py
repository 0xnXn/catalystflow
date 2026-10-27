"""Validate pipeline/buildspec/artifact contracts before deployment. Maintainer: Nixon."""
from pathlib import Path
import json
import yaml

class CloudFormationLoader(yaml.SafeLoader):
    pass
CloudFormationLoader.add_multi_constructor('!', lambda loader, tag, node: loader.construct_scalar(node) if isinstance(node, yaml.ScalarNode) else loader.construct_sequence(node) if isinstance(node, yaml.SequenceNode) else loader.construct_mapping(node))
repo = Path(__file__).resolve().parents[1]
pipeline = json.loads((repo / 'infra/pipeline.yaml').read_text())
expected = ['Source', 'UnitTests', 'IntegrationTests', 'Build', 'SecurityScan', 'Deploy', 'E2E']
stages = pipeline['Resources']['Pipeline']['Properties']['Stages']
assert [s['Name'] for s in stages] == expected
used = set()
for name, resource in pipeline['Resources'].items():
    if resource['Type'] != 'AWS::CodeBuild::Project': continue
    filename = resource['Properties']['Source']['BuildSpec']
    assert (repo / filename).exists(), filename
    used.add(filename)
    spec = yaml.safe_load((repo / filename).read_text())
    for phase in spec.get('phases', {}).values():
        assert all(isinstance(c, str) for c in phase.get('commands', [])), filename
    print(name, '->', filename)
assert {p.name for p in repo.glob('buildspec*.yml')} == used, 'Unused or missing buildspec'
assert stages[4]['Actions'][0]['InputArtifacts'] == [{'Name': 'Source'}, {'Name': 'BuildOutput'}]
assert stages[5]['Actions'][0]['InputArtifacts'] == [{'Name': 'BuildOutput'}]
if (repo / 'template.yaml').exists():
    sam = yaml.load((repo / 'template.yaml').read_text(), Loader=CloudFormationLoader)
    paths = [v['Properties']['CodeUri'] for v in sam['Resources'].values() if v['Type'] == 'AWS::Serverless::Function']
    assert len(paths) == 6 and all(p.endswith('/target/function.zip') for p in paths)
    assert sam['Parameters']['LambdaRuntime']['Default'] in ['provided.al2023', 'java21']
    assert 'LambdaRuntime=java21' in (repo / 'buildspec-deploy.yml').read_text()
print('Pipeline stages, buildspec use, artifact flow and SAM package contracts validated.')
