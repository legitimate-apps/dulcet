#!/usr/bin/env python3
"""Audit downloaded device-run output and emit a receipt to private local storage.

No receipt is written into the repository. A detected privacy violation deletes
the public run, including its logs and artifacts; diagnostics expose no values.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import stat
import subprocess
import tempfile
import urllib.parse
import zipfile

from verify_signed_ipad_privacy import TEST, clean, junit, registry

REPO = 'legitimate-apps/dulcet'
SCRATCH = 'legitimate-apps/dulcet-signed-ipad-privacy-check'
WORKFLOW = 'signed-ipad-account-connect'
JOB = 'signed-ipad-account-connect'
NEUTRAL = 'dulcet-signed-ipad-host'
SOURCES = (
    'tools/ci/run-signed-device-proof',
    'apple/DulcetAppleShared/DulcetSignedDeviceAccountProbe.swift',
    'apple/DulcetSignedDeviceUITestSupport/SignedAccountConnectProof.swift',
    'apple/DulcetSignedIPadUITests/DulcetSignedIPadAccountConnectUITests.swift',
    'apple/DulcetAppleShared/DulcetSignedIPadAccountProbe.swift',
    'apple/DulcetiOS/DulcetiOSApp.swift',
    'apple/project.yml',
    'tools/ci/run-signed-ipad-proof',
    'tools/verify_signed_ipad_privacy.py',
    'tools/audit_signed_ipad_receipt.py',
    'tools/ci/signed-ipad-job',
    '.github/workflows/signed-ipad-account-connect.yml',
    'apple/DulcetAppleShared/DulcetAppleProduction.swift',
    'apple/DulcetKit/Sources/DulcetKit/AccountConnectionPresentation.swift',
    'apple/DulcetKit/Sources/DulcetKit/DulcetCredentialStore.swift',
)


class PrivacyIncident(Exception):
    def __init__(self, deleted):
        self.deleted = deleted


class GitHub:
    def api(self, path):
        return subprocess.check_output(['ghx', '--as', 'legit', 'api', path], stderr=subprocess.DEVNULL)

    def delete(self, repo, run_id):
        subprocess.run(['ghx', '-R', repo, 'run', 'delete', str(run_id)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def extract_zip(raw, destination):
    destination.mkdir(mode=0o700)
    files, names = [], []
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        for info in archive.infolist():
            relative = Path(info.filename)
            if (relative.is_absolute() or '..' in relative.parts or '\\' in info.filename
                    or stat.S_ISLNK(info.external_attr >> 16)):
                raise ValueError('unsafe archive')
            target = destination / relative
            if info.filename in names:
                raise ValueError('duplicate archive member')
            names.append(info.filename)
            if info.is_dir():
                continue
            target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            target.write_bytes(archive.read(info))
            target.chmod(0o600)
            files.append(target)
    return files, names


def literal_scan(paths, tokens):
    for path in paths:
        for token in tokens:
            result = subprocess.run(['/usr/bin/grep', '-Fiq', '-f', '-', str(path)],
                                    input=(token+'\n').encode(), stdout=subprocess.DEVNULL,
                                    stderr=subprocess.DEVNULL)
            if result.returncode != 1:
                return False
    return True


def audit(repo, run_id, tokens, output, github, literal=literal_scan, *, lane=None):
    # The iPhone lane supplies its exact identity while sharing the full archive,
    # metadata, privacy and source-hash audit with the receipt-pinned iPad lane.
    context = lane or globals()
    REPO, SCRATCH, WORKFLOW, JOB, NEUTRAL, SOURCES, TEST, junit = (
        context[key] for key in ('REPO', 'SCRATCH', 'WORKFLOW', 'JOB', 'NEUTRAL', 'SOURCES', 'TEST', 'junit'))
    audit_tool = context.get('AUDIT_TOOL', 'tools/audit_signed_ipad_receipt.py')
    artifact_prefix = context.get('ARTIFACT_PREFIX', 'dulcet-signed-ipad-junit')
    if repo not in (REPO, SCRATCH) or not re.fullmatch(r'[1-9]\d*', str(run_id)):
        raise ValueError('invalid run')
    # An output directory inside any checkout would make a local-only receipt
    # easier to commit accidentally. Require the caller's fresh directory outside it.
    checkout = Path(__file__).resolve().parents[1]
    output = output.resolve()
    if output == checkout or checkout in output.parents or output.exists():
        raise ValueError('output must be fresh local storage')
    output.mkdir(mode=0o700, parents=True)
    base = 'repos/'+repo+'/actions/runs/'+str(run_id)
    run = json.loads(github.api(base))
    raw = github.api(base+'/logs')
    (output/'logs.zip').write_bytes(raw)
    files, names = extract_zip(raw, output/'logs')
    artifacts = json.loads(github.api(base+'/artifacts'))['artifacts']
    # Scan metadata and archive member names as well as file contents. No device
    # identifying value can escape by being used as an artifact or log filename.
    jobs = json.loads(github.api(base+'/jobs?per_page=100'))
    metadata = output/'download-metadata.json'
    metadata.write_text(json.dumps({'run': run, 'jobs': jobs, 'artifacts': artifacts,
                                    'log_members': names}))
    files.append(metadata)
    for index, artifact in enumerate(artifacts):
        raw = github.api('repos/'+repo+'/actions/artifacts/'+str(artifact['id'])+'/zip')
        (output/('artifact-'+str(index)+'.zip')).write_bytes(raw)
        extracted, members = extract_zip(raw, output/('artifact-'+str(index)))
        files.extend(extracted)
        index_file = output/('artifact-'+str(index)+'-members.json')
        index_file.write_text(json.dumps(members))
        files.append(index_file)
    for path in output.rglob('*'):
        if path.is_file():
            path.chmod(0o600)
    try:
        private_clean = clean(files, tokens) and literal(files, tokens)
    except Exception:
        private_clean = False
    if not private_clean:
        deleted = False
        if repo == REPO:
            try:
                github.delete(repo, run_id)
                deleted = True
            except Exception:
                pass
        raise PrivacyIncident(deleted)
    if (run['conclusion'] != 'success' or run['status'] != 'completed'
            or run['head_branch'] != 'main' or run['event'] != 'workflow_dispatch'
            or run['name'] != WORKFLOW or str(run['id']) != str(run_id)
            or not re.fullmatch(r'[0-9a-f]{40}', run['head_sha'])
            or run['html_url'] != 'https://github.com/'+repo+'/actions/runs/'+str(run_id)):
        raise ValueError('invalid run identity')
    passed_jobs = [job for job in jobs['jobs'] if job['name'] == JOB]
    if (len(passed_jobs) != 1 or passed_jobs[0]['conclusion'] != 'success'
            or passed_jobs[0]['runner_name'] != NEUTRAL
            or len(artifacts) != 1 or artifacts[0]['expired']
            or artifacts[0]['name'] != artifact_prefix+'-'+str(run_id)+'-'+str(run['run_attempt'])):
        raise ValueError('invalid evidence job')
    setup = '\n'.join(path.read_text() for path in files if 'Set up job' in path.name)
    if ("Machine name: '"+NEUTRAL+"'" not in setup
            or "Runner name: '"+NEUTRAL+"'" not in setup):
        raise ValueError('missing neutral setup evidence')
    artifact_files = [path for path in files if output/'artifact-0' in path.parents]
    if len(artifact_files) != 1 or artifact_files[0].relative_to(output/'artifact-0').as_posix() != 'proof.xml':
        raise ValueError('unexpected artifact contents')
    xml = artifact_files[0]
    junit(xml)
    source_hashes = {}
    for path in SOURCES:
        source = json.loads(github.api('repos/'+repo+'/contents/'+urllib.parse.quote(path, safe='/')
                                      +'?ref='+run['head_sha']))
        if (source['type'] != 'file' or source['path'] != path
                or not re.fullmatch(r'[0-9a-f]{40}', source['sha'])):
            raise ValueError('unresolved test source')
        source_hashes[path] = source['sha']
    receipt = {
        'workflow': WORKFLOW, 'job': JOB, 'run_id': str(run_id), 'run_url': run['html_url'],
        'head_sha': run['head_sha'], 'conclusion': 'success',
        'tests': [{'name': TEST, 'result': 'passed'}],
        'junit_sha256': hashlib.sha256(xml.read_bytes()).hexdigest(),
        'test_sources': source_hashes,
        'audit': {'tool': audit_tool, 'result': 'pass',
                  'files_scanned': len(files), 'tokens_checked': len(tokens)},
    }
    receipt_path = output/'receipt.json'
    receipt_path.write_text(json.dumps(receipt, indent=2)+'\n')
    receipt_path.chmod(0o600)
    if not clean([receipt_path], tokens):
        receipt_path.unlink()
        raise ValueError('receipt withheld')
    return receipt


def self_test(lane=None):
    import verify_signed_ipad_privacy as default_privacy
    context = lane or globals()
    REPO, WORKFLOW, JOB, NEUTRAL, SOURCES, TEST = (
        context[key] for key in ('REPO', 'WORKFLOW', 'JOB', 'NEUTRAL', 'SOURCES', 'TEST'))
    privacy = context.get('privacy', default_privacy)
    artifact_prefix = context.get('ARTIFACT_PREFIX', 'dulcet-signed-ipad-junit')

    def zipped(entries):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as archive:
            for name, content in entries.items():
                archive.writestr(name, content)
        return stream.getvalue()

    class Fixture:
        def __init__(self):
            self.deleted = False
            self.run = {'id': 123, 'conclusion': 'success', 'status': 'completed',
                        'head_branch': 'main', 'event': 'workflow_dispatch', 'name': WORKFLOW,
                        'head_sha': 'a'*40, 'run_attempt': 1,
                        'html_url': 'https://github.com/'+REPO+'/actions/runs/123'}
            self.xml = '<testsuite tests="1"><testcase classname="'+privacy.CLASS+'" name="'+privacy.CASE+'"/></testsuite>'
            self.logs = {'signed-ipad/1_Set up job.txt': "Runner name: '"+NEUTRAL+"'\nMachine name: '"+NEUTRAL+"'"}
            self.artifact = {'proof.xml': self.xml}
            self.blob = 'b'*40
            self.artifact_name = artifact_prefix+'-123-1'

        def api(self, path):
            if '/contents/' in path:
                source = path.split('/contents/')[1].split('?')[0]
                return json.dumps({'path': source, 'type': 'file', 'sha': self.blob}).encode()
            if path.endswith('/zip'):
                return zipped(self.artifact)
            if path.endswith('/logs'):
                return zipped(self.logs)
            if path.endswith('/artifacts'):
                return json.dumps({'artifacts': [{'id': 456, 'expired': False,
                                  'name': self.artifact_name}]}).encode()
            if '/jobs?' in path:
                return json.dumps({'jobs': [{'name': JOB, 'conclusion': 'success',
                                            'runner_name': NEUTRAL}]}).encode()
            return json.dumps(self.run).encode()

        def delete(self, repo, run_id):
            self.deleted = True

    count = 0
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        fixture = Fixture()
        result = audit(REPO, 123, ['private-fixture-value'], root/'positive', fixture, lane=lane)
        assert result['run_id'] == '123'
        assert result['audit']['result'] == 'pass'
        assert result['tests'] == [{'name': TEST, 'result': 'passed'}]
        assert set(result) == {'workflow', 'job', 'run_id', 'run_url', 'head_sha', 'conclusion',
                               'tests', 'junit_sha256', 'test_sources', 'audit'}
        assert result['test_sources'] == {path: 'b'*40 for path in SOURCES}
        assert result['junit_sha256'] == hashlib.sha256(fixture.xml.encode()).hexdigest()
        for label in ('log-token', 'member-token', 'xml-token', 'failed', 'branch', 'trigger',
                      'neutral', 'wrong-lane-artifact', 'extra-artifact', 'wrong-test', 'missing-source', 'unsafe-archive'):
            fixture = Fixture()
            if label == 'log-token': fixture.logs['signed-ipad/2_Proof.txt'] = 'PRIVATE-FIXTURE-VALUE'
            if label == 'member-token': fixture.logs['private-fixture-value.txt'] = 'neutral'
            if label == 'xml-token': fixture.artifact['proof.xml'] = fixture.xml.replace('/>', ' time="private-fixture-value"/>')
            if label == 'failed': fixture.run['conclusion'] = 'failure'
            if label == 'branch': fixture.run['head_branch'] = 'topic'
            if label == 'trigger': fixture.run['event'] = 'push'
            if label == 'neutral': fixture.logs = {'1_Set up job.txt': 'neutral'}
            if label == 'wrong-lane-artifact': fixture.artifact_name = 'dulcet-other-device-junit-123-1'
            if label == 'extra-artifact': fixture.artifact['unexpected.txt'] = 'neutral'
            if label == 'wrong-test': fixture.artifact['proof.xml'] = fixture.xml.replace(privacy.CASE, 'wrongTest')
            if label == 'missing-source': fixture.blob = ''
            if label == 'unsafe-archive': fixture.artifact['../outside.txt'] = 'neutral'
            output = root/label
            try:
                audit(REPO, 123, ['private-fixture-value'], output, fixture, lane=lane)
            except PrivacyIncident as incident:
                assert label in ('log-token', 'member-token', 'xml-token')
                assert fixture.deleted and incident.deleted
                count += 1
            except ValueError:
                assert label not in ('log-token', 'member-token', 'xml-token')
                count += 1
            else:
                raise AssertionError('invalid run accepted')
            assert not (output/'receipt.json').exists()
    print(context.get('TITLE', 'iPad')+' receipt controls PASS count='+str(count))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run-id')
    parser.add_argument('--tokens', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--repo', choices=(REPO, SCRATCH), default=REPO)
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    try:
        if args.self_test:
            self_test()
        if args.run_id:
            receipt = audit(args.repo, args.run_id, registry(args.tokens), args.output, GitHub())
            print('iPad receipt audit PASS files='+str(receipt['audit']['files_scanned'])
                  +' tokens='+str(receipt['audit']['tokens_checked'])+' tests=1')
        elif not args.self_test:
            raise ValueError('missing run')
    except PrivacyIncident as incident:
        print('iPad privacy audit FAIL; public run deleted' if incident.deleted
              else 'iPad privacy audit FAIL; receipt withheld; run removal requires attention')
        return 1
    except Exception:
        print('iPad receipt audit FAIL; receipt withheld')
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
