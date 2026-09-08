#!/usr/bin/env python3
"""Opt-in cumulative Nema regression against the pinned disposable Prosody proof.

Run inside this checkout with --python pointing to a Slixmpp 1.12.0 interpreter.
Requires the existing nema-proof.slice resource budget, Nix, Docker and cached image.
No APK, device, production server, credentials or source changes are performed.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[2]
SERVER = 'fd6679181c090c32a246f2899fae959232007c2f'
MODULE = 'a678f50803759ac63f75214830f4bbc77b4d9076165919ea050e488834daa77a'
TEST = 'org.thanosapollo.nema.service.SharedThreadNetworkProofTest'
REPORT = REPO / f'app/build/test-results/testDebugUnitTest/TEST-{TEST}.xml'
CACHE = Path.home() / '.hermes/cache'


def git(*args):
    env = {k: v for k, v in os.environ.items() if k not in ('GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE')}
    return subprocess.check_output(['git', *args], cwd=REPO, env=env)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sources():
    return {path for path in git('ls-files', '-c', '-o', '--exclude-standard').decode().splitlines()
            if (REPO / path).is_file()}


def verify_source(evidence):
    manifest = json.loads((evidence / 'manifest.json').read_text())
    if sources() != set(manifest['client']):
        raise RuntimeError('Candidate source path set drifted during proof')
    for path, expected in manifest['client'].items():
        if digest(REPO / path) != expected or digest(evidence / 'client' / path) != expected:
            raise RuntimeError('Candidate/archive source bytes drifted during proof')


def gradle(evidence, fixture=None):
    verify_source(evidence)
    env = os.environ.copy()
    env.pop('NEMA_THREAD_PROOF_CLIENT_CONFIG', None)
    if fixture:
        env['NEMA_THREAD_PROOF_CLIENT_CONFIG'] = str(fixture)
    label = 'live' if fixture else 'precompile'
    unit = 'nema-shared-network-' + uuid.uuid4().hex[:12]
    command = ['systemd-run', '--user', '--scope', '--slice=nema-proof.slice',
               '--unit=' + unit, 'nice', '-n10', 'ionice', '-c3',
               'nix', 'develop', '--command', './gradlew', '--no-daemon', '--max-workers=2',
               '-Pkotlin.compiler.execution.strategy=in-process', 'testDebugUnitTest', '--rerun', '--tests', TEST]
    (evidence / (label + '-command.json')).write_text(json.dumps(command) + '\n')
    started = time.time_ns()
    with (evidence / (label + '.log')).open('w') as output:
        process = subprocess.Popen(command, cwd=REPO, env=env, stdout=output,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        try:
            status = process.wait(timeout=105 if fixture else 900)
        except BaseException:
            # The scope is uniquely owned; include any single-use daemon escaping the process group.
            subprocess.run(['systemctl', '--user', 'kill', '--signal=KILL', unit + '.scope'],
                           stdout=output, stderr=subprocess.STDOUT, timeout=10)
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()
            raise
    if REPORT.exists() and REPORT.stat().st_mtime_ns >= started:
        shutil.copyfile(REPORT, evidence / (label + '.xml'))
    if status:
        raise RuntimeError(f'{label} Gradle exit {status}; inspect archived logs/XML')
    suite = ET.parse(evidence / (label + '.xml')).getroot()
    expected = {'tests': '1', 'failures': '0', 'errors': '0', 'skipped': '0' if fixture else '1'}
    if any(suite.get(k) != v for k, v in expected.items()):
        raise RuntimeError(f'{label} expected exact JUnit counts {expected}')
    if fixture:
        markers = [line for line in (suite.findtext('system-out') or '').splitlines()
                   if line.startswith('NEMA_NETWORK PASS ')]
        if len(markers) != 5:
            raise RuntimeError('Missing cumulative network journey markers')
        print('\n'.join(markers), flush=True)


def run(interpreter):
    CACHE.mkdir(parents=True, exist_ok=True)
    evidence = Path(tempfile.mkdtemp(prefix='nema-shared-network-', dir=CACHE))
    print(f'EVIDENCE={evidence}', flush=True)
    manifest = {'base': git('rev-parse', 'HEAD').decode().strip(), 'server_commit': SERVER,
                'server': {}, 'client': {}, 'evidence': str(evidence)}
    # Include dirty/untracked source and tests: this is deliberately a cumulative candidate,
    # not an assertion that HEAD alone describes the tested source.
    quota = subprocess.check_output(['systemctl', '--user', 'show', 'nema-proof.slice',
                                     '-p', 'CPUQuotaPerSecUSec', '--value'], text=True).strip()
    if not quota or quota == 'infinity':
        raise RuntimeError('Existing aggregate nema-proof.slice CPU budget required')
    manifest['aggregate_cpu_quota'] = quota
    for path in sorted(sources()):
        source = REPO / path
        if not source.is_file():
            continue
        target = evidence / 'client' / path
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        manifest['client'][path] = digest(target)
        if digest(source) != manifest['client'][path]:
            raise RuntimeError('Source changed while capturing exact snapshot')
    server = evidence / 'server'
    server.mkdir()
    for path in git('ls-tree', '-r', '--name-only', SERVER, '--', 'experiments/thread-directory').decode().splitlines():
        data = git('show', SERVER + ':' + path)
        target = server / Path(path).name
        target.write_bytes(data)
        manifest['server'][path] = digest(target)
    if digest(server / 'mod_thread_directory.lua') != MODULE:
        raise RuntimeError('Pinned server module mismatch')
    (evidence / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    status = 'failed'
    try:
        # The hook has a hard 120-second server lease: never compile new sources inside it.
        gradle(evidence)
        env = os.environ.copy()
        env.pop('PYTHONPATH', None)
        env.pop('NEMA_THREAD_PROOF_CLIENT_CONFIG', None)
        env['PYTHONDONTWRITEBYTECODE'] = '1'
        env['NEMA_THREAD_PROOF_HOOK'] = str(Path(__file__).resolve())
        env['NEMA_SHARED_NETWORK_EVIDENCE'] = str(evidence)
        with (CACHE / 'nema-thread-proof.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            with (evidence / 'server.log').open('w') as output:
                result = subprocess.run([str(interpreter), str(server / 'proof.py')], env=env,
                                        stdout=output, stderr=subprocess.STDOUT)
            if result.returncode:
                raise RuntimeError(f'Disposable server proof exit {result.returncode}')
        lines = (evidence / 'server.log').read_text().splitlines()
        if sum(line.startswith('PASS ') for line in lines) != 56:
            raise RuntimeError('Expected 56 canonical fixture/hook/cleanup checks')
        if sum(line.startswith('NEMA_NETWORK PASS ') for line in lines) != 5:
            raise RuntimeError('Expected all five cumulative network groups')
        verify_source(evidence)
        status = 'passed'
    finally:
        artifacts = {p.name: digest(p) for p in evidence.iterdir() if p.is_file()}
        summary = {'status': status, 'evidence': str(evidence), 'artifacts': artifacts}
        (evidence / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
        print(json.dumps(summary), flush=True)


if __name__ == '__main__':
    os.umask(0o077)
    if len(sys.argv) == 2 and 'NEMA_SHARED_NETWORK_EVIDENCE' in os.environ:
        fixture = Path(sys.argv[1])
        if not fixture.is_absolute() or not fixture.is_file() or fixture.stat().st_mode & 0o077:
            raise RuntimeError('Expected a private disposable fixture file')
        gradle(Path(os.environ['NEMA_SHARED_NETWORK_EVIDENCE']), fixture)
    else:
        parser = argparse.ArgumentParser(description=__doc__)
        parser.add_argument('--python', required=True, type=Path, help='Existing interpreter with Slixmpp 1.12.0')
        interpreter = parser.parse_args().python.absolute()
        if not interpreter.is_file() or not os.access(interpreter, os.X_OK):
            raise RuntimeError('Expected an executable Slixmpp interpreter')
        # Do not resolve the venv executable symlink: that discards its package environment.
        run(interpreter)
