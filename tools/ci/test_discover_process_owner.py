#!/usr/bin/env python3
"""Check TERM and loss of the invoking parent, including a child's separate group."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest

HERE = Path(__file__).resolve().parent
PROGRAM = '''
import json, os, sys, time
sys.path.insert(0, sys.argv[1])
from discover_process_owner import ProcessOwner
owner=ProcessOwner().install()
try:
    child=owner.spawn([sys.executable,'-c','import time;time.sleep(180)'])
    open(sys.argv[2],'w').write(json.dumps([os.getpid(),child.pid]))
    time.sleep(180)
finally:
    owner.close()
    open(sys.argv[2]+'.cleaned','w').write('done')
'''


# Cleanup controls fake every native executable, so their host sampler is fake too. Real host
# pressure must not kill a lease-control child before the test's deliberate TERM. Health policy
# and real sampling are exercised separately by the health controls and native proof receipts.
CONTROL_LAUNCHER = r'''
import os, pathlib, runpy, sys, time
sys.path.insert(0, sys.argv[1])
import discover_simulator_health
class ControlSampler:
    def __init__(self, udid): pass
    def sample(self):
        pathlib.Path(os.environ['CONTROL_ROOT'], 'sampler-used').write_text('controlled')
        return dict(time=time.monotonic(), launchd='control', children=1, rssKB=0,
                    swapoutsPerSecond=0, load1=0, memoryFreePercent=80,
                    bootDiskFreeGiB=100), []
discover_simulator_health.HostSampler=ControlSampler
sys.argv=[sys.argv[2], 'iphone']
runpy.run_path(sys.argv[0], run_name='__main__')
'''


class OwnerTests(unittest.TestCase):
    def exercise(self, parent_death):
        with tempfile.TemporaryDirectory() as directory:
            receipt=Path(directory)/'pids.json'
            command=[sys.executable,'-c',PROGRAM,str(HERE),str(receipt)]
            if parent_death:
                command=[sys.executable,'-c','import subprocess,sys,time;subprocess.Popen(sys.argv[1:]);time.sleep(180)',*command]
            sentinel=subprocess.Popen([sys.executable,'-c','import time;time.sleep(180)'])
            process=subprocess.Popen(command)
            try:
                deadline=time.monotonic()+10
                while not receipt.exists() and time.monotonic()<deadline:time.sleep(.05)
                pids=json.loads(receipt.read_text())
                process.send_signal(signal.SIGTERM)
                process.wait(timeout=10)
                deadline=time.monotonic()+15
                while time.monotonic()<deadline:
                    # The orphan's zombie may wait for launchd to reap it; neither PID may run.
                    states=[subprocess.run(['ps','-o','stat=','-p',str(pid)],capture_output=True,text=True).stdout.strip() for pid in pids]
                    if all(not state or state.startswith('Z') for state in states):break
                    time.sleep(.1)
                self.assertTrue(all(not state or state.startswith('Z') for state in states),states)
                self.assertTrue(Path(str(receipt)+'.cleaned').exists(),'owner cleanup did not finish')
                if not parent_death:self.assertEqual(143,process.returncode)
                self.assertIsNone(sentinel.poll())
            finally:
                if process.poll() is None:process.terminate();process.wait()
                sentinel.terminate();sentinel.wait()
    def runner_term(self, hosted=False):
        # These executable controls record effects; no simulator or fixture is started.
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            worker=root/'worker'
            worker.write_text('#!'+sys.executable+'\n'+r"""
import json,os,pathlib,sys,time
root=pathlib.Path(os.environ['CONTROL_ROOT']);name=pathlib.Path(sys.argv[0]).name
with (root/'commands.jsonl').open('a') as log:log.write(json.dumps([name,*sys.argv[1:]])+'\n')
if name=='leg-lease':
    if sys.argv[1]=='create':(root/'build-lease').write_text('held');print('lease-control')
    else:(root/'build-lease').unlink()
elif name=='sim-lease':
    if sys.argv[1]=='create':(root/'device-lease').write_text('held');print('CONTROL-UDID')
    else:(root/'device-lease').unlink()
elif name=='git':print('control-head')
elif name=='xcodebuild':time.sleep(180)
elif sys.argv[1:4]==['simctl','list','devices']:
    print(json.dumps({'devices':{'com.apple.CoreSimulator.SimRuntime.iOS-control':[{'udid':'CONTROL-UDID','name':'iPhone control','deviceTypeIdentifier':'control-phone-type'}]}}))
elif sys.argv[1:3]==['simctl','create']:
    (root/'hosted-device').write_text('held');print('CONTROL-UDID')
elif sys.argv[1:3]==['simctl','delete']:
    (root/'hosted-device').unlink();(root/'deleted').write_text('CONTROL-UDID')
elif sys.argv[1:3]==['simctl','shutdown']:(root/'shutdown').write_text('CONTROL-UDID')
elif sys.argv[1:3]==['simctl','bootstatus']:
    (root/'boot-pid').write_text(str(os.getpid()));time.sleep(180)
elif sys.argv[1:3]==['simctl','spawn']:time.sleep(180)
""")
            worker.chmod(0o755)
            for name in ['leg-lease','sim-lease','xcrun','xcodebuild','git']:(root/name).symlink_to(worker)
            env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ['PATH'],CONTROL_ROOT=str(root),DULCET_DISCOVER_DISPOSABLE='true',DULCET_DISCOVER_PROOF_OUTPUT=str(root/'report'))
            env.pop('GITHUB_ACTIONS',None);env.pop('RUNNER_ENVIRONMENT',None)
            if hosted:env.update(GITHUB_ACTIONS='true',RUNNER_ENVIRONMENT='github-hosted')
            env.pop('DULCET_DISCOVER_HOST_GATE',None)
            env.pop('DULCET_DISCOVER_PROOF_UDID',None)
            with (root/'runner.log').open('w') as log:
                runner=subprocess.Popen([sys.executable,'-c',CONTROL_LAUNCHER,str(HERE),str(HERE/'run-discover-wishlist-proofs')],env=env,stdout=log,stderr=subprocess.STDOUT)
                try:
                    deadline=time.monotonic()+15
                    while (not (root/'boot-pid').exists() or (not hosted and not (root/'sampler-used').exists())) and runner.poll() is None and time.monotonic()<deadline:time.sleep(.05)
                    self.assertTrue((root/'boot-pid').exists(),
                        'runner_exit='+str(runner.poll())+' log='+(root/'runner.log').read_text()+
                        ' commands='+((root/'commands.jsonl').read_text() if (root/'commands.jsonl').exists() else 'none'))
                    if not hosted:self.assertTrue((root/'sampler-used').exists())
                    child=int((root/'boot-pid').read_text())
                    runner.terminate();runner.wait(timeout=30)
                    self.assertEqual(143,runner.returncode,(root/'runner.log').read_text())
                    self.assertEqual('CONTROL-UDID',(root/'shutdown').read_text())
                    self.assertFalse((root/'device-lease').exists())
                    self.assertFalse((root/'build-lease').exists())
                    if hosted:
                        self.assertFalse((root/'hosted-device').exists())
                        self.assertEqual('CONTROL-UDID',(root/'deleted').read_text())
                    with self.assertRaises(ProcessLookupError):os.kill(child,0)
                finally:
                    if runner.poll() is None:runner.terminate();runner.wait(timeout=30)

    def test_runner_term_shuts_device_and_releases_both_leases(self):self.runner_term()
    def test_hosted_runner_term_deletes_its_device_without_local_leases(self):self.runner_term(True)

    def test_term_reaps_separate_child_group(self):self.exercise(False)
    def test_parent_death_triggers_cleanup(self):self.exercise(True)

if __name__=='__main__':unittest.main()
