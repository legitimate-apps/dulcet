"""Host-pressure receipts and finite boot settling for Discover's native proofs."""
import os
import re
import shutil
import subprocess
import time


class SimulatorHealth:
    def __init__(self):
        self.streaks = dict(swapoutsPerSecond=0, load1=0)
        self.peaks = {}
        self.previous_children = 0
        self.last_growth = None
        self.quiet_since = None
        self.settled = False

    def observe(self, sample):
        now = sample['time']
        for field in ['children', 'rssKB', 'swapoutsPerSecond', 'load1']:
            value = sample[field]
            if value is not None:
                self.peaks[field] = max(self.peaks.get(field, value), value)
        for field in ['memoryFreePercent', 'bootDiskFreeGiB']:
            self.peaks[field] = min(self.peaks.get(field, sample[field]), sample[field])
        for field, bad in [('swapoutsPerSecond', sample['swapoutsPerSecond'] is not None
                           and sample['swapoutsPerSecond'] >= 200),
                           ('load1', sample['load1'] > 120)]:
            self.streaks[field] = self.streaks[field] + 1 if bad else 0
            if self.streaks[field] >= 3:
                return field + ': three consecutive samples'
        for field, bad in [('children', sample['children'] > 200),
                           ('rssKB', sample['rssKB'] > 12 * 1024 * 1024),
                           ('bootDiskFreeGiB', sample['bootDiskFreeGiB'] < 6),
                           ('memoryFreePercent', sample['memoryFreePercent'] < 10)]:
            if bad:
                return field + ': immediate limit'
        if sample['children'] > self.previous_children:
            self.last_growth = now
        self.previous_children = sample['children']
        quiet = (sample['children'] > 0 and sample['load1'] < 40
                 and sample['swapoutsPerSecond'] is not None
                 and sample['swapoutsPerSecond'] < 200
                 and sample['memoryFreePercent'] >= 20)
        if not quiet:
            self.quiet_since = None
        elif self.quiet_since is None:
            self.quiet_since = now
        self.settled = (self.last_growth is not None and self.quiet_since is not None
                        and now - max(self.last_growth, self.quiet_since) >= 60)
        return None


class HostSampler:
    def __init__(self, udid):
        self.udid = udid
        self.previous_swap = None

    @staticmethod
    def read(command):
        return subprocess.check_output(command, text=True, timeout=10)

    def sample(self):
        rows = [line.strip().split(None, 3) for line in
                self.read(['ps', '-Ao', 'pid=,ppid=,rss=,command=']).splitlines()]
        parent = next((p for p, pp, rss, cmd in rows
                       if 'launchd_sim' in cmd and self.udid in cmd), None)
        children = [row for row in rows if parent and row[1] == parent]
        swap = int(re.search(r'Swapouts:\s+(\d+)', self.read(['vm_stat']))[1])
        now = time.monotonic()
        rate = None if self.previous_swap is None else max(
            0, (swap - self.previous_swap[1]) / (now - self.previous_swap[0]))
        self.previous_swap = (now, swap)
        free = float(re.search(r'free percentage:\s+(\d+)',
                               self.read(['memory_pressure']))[1])
        receipt = dict(time=now, utc=time.time(), launchd=parent,
                       children=len(children), rssKB=sum(int(r[2]) for r in children),
                       swapouts=swap, swapoutsPerSecond=rate, load1=os.getloadavg()[0],
                       memoryFreePercent=free,
                       bootDiskFreeGiB=shutil.disk_usage('/').free / (1024 ** 3))
        return receipt, children
