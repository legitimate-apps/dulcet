"""Apply apple-ci's shared startup admission to finite Discover proofs."""
import os
import subprocess
import sys


def hosted_simulator_template(inventory, device_name, runtime_version):
    """Use the selected SDK's runtime and requested device, regardless of image defaults."""
    runtime = (runtime_version if runtime_version.startswith('com.apple.CoreSimulator.SimRuntime.')
               else 'com.apple.CoreSimulator.SimRuntime.iOS-' + runtime_version.replace('.', '-'))
    candidates = [device for device in inventory.get('devices', {}).get(runtime, [])
                  if device.get('name') == device_name and device.get('isAvailable', True)
                  and device.get('deviceTypeIdentifier')]
    if not candidates:
        raise RuntimeError(f'Hosted Discover requires {device_name} on {runtime}; '
                           'select an installed runtime matching the pinned Xcode SDK')
    return runtime, sorted(candidates, key=lambda device: device['udid'])[0]


class HostedAppleAdmission:
    def __init__(self, owner, tools, output, mode):
        self.owner = owner
        self.tools = tools
        self.output = output
        self.mode = mode
        self.record = str(output / 'host-pressure.jsonl')
        self.sampler = None

    def start(self):
        self.sampler = self.owner.spawn([sys.executable, str(self.tools / 'host-pressure'),
            'sample', '--output', self.record, '--interval', '5'],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.owner.run([sys.executable, str(self.tools / 'host-pressure'),
            'mark', self.record, 'discover-' + self.mode + '-start'], check=True, timeout=90)

    def admit(self, udid, app):
        if not app.is_dir():
            raise FileNotFoundError('Hosted Discover warm-up requires --build-for-testing: ' + str(app))
        self.owner.run([sys.executable, str(self.tools / 'isolate-simulator'),
            '--phase', 'discover-' + self.mode + '-first-launch', '--keep', udid,
            '--require-isolation', '--settle', '--settle-pressure', '--warmup-app', str(app)],
            env={**os.environ, 'DULCET_HOST_PRESSURE_LOG': self.record},
            check=True, timeout=1800)

    def close(self):
        # The sampler belongs to ProcessOwner, including TERM and parent-loss cleanup.
        # Stop its detached, bounded stack workers before artifact upload as baseline CI does.
        if self.sampler is not None:
            self.owner.stop(self.sampler, grace=5)
        for action in ('stop', 'summarize'):
            try:
                subprocess.run([sys.executable, str(self.tools / 'host-pressure'), action, self.record],
                               check=False, timeout=60)
            except (OSError, subprocess.TimeoutExpired) as error:
                print('DISCOVER PRESSURE cleanup=' + action + ' error=' + type(error).__name__, flush=True)
