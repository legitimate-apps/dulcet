"""Finite child process groups, with cleanup when the invoking parent disappears."""
import os
import signal
import subprocess
import threading


class ProcessOwner:
    def __init__(self):
        self.children = []
        self.parent = os.getppid()
        self.done = threading.Event()
        self.previous = {}
        self.stopping = False

    def install(self):
        def interrupted(number, frame):
            if self.stopping:
                return
            self.stopping = True
            raise SystemExit(128 + number)
        for number in (signal.SIGTERM, signal.SIGINT):
            self.previous[number] = signal.signal(number, interrupted)
        def watch():
            while not self.done.wait(.5):
                if os.getppid() != self.parent:
                    os.kill(os.getpid(), signal.SIGTERM)
                    return
        threading.Thread(target=watch, daemon=True).start()
        return self

    def spawn(self, argv, **kwargs):
        # Defer Python handlers until registration; children retain normal signal masks.
        previous = {}
        pending = []
        for number in (signal.SIGTERM, signal.SIGINT):
            previous[number] = signal.signal(number, lambda n, f: pending.append(n))
        try:
            child = subprocess.Popen(argv, start_new_session=True, **kwargs)
            self.children.append(child)
        finally:
            for number, handler in previous.items():
                signal.signal(number, handler)
        if pending:
            os.kill(os.getpid(), pending[0])
        return child

    def run(self, argv, check=False, capture_output=False, timeout=None, **kwargs):
        if capture_output:
            kwargs.update(stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        child = self.spawn(argv, **kwargs)
        try:
            out, err = child.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            self.stop(child)
            raise
        self.children.remove(child)
        result = subprocess.CompletedProcess(argv, child.returncode, out, err)
        if check:
            result.check_returncode()
        return result

    @staticmethod
    def signal_group(child, number):
        try:
            os.killpg(child.pid, number)
        except ProcessLookupError:
            pass
        except PermissionError as error:
            # Darwin reports EPERM for a group containing only an unreaped zombie.
            # Reap that known child, then retry so live grandchildren still receive it.
            try:child.wait(timeout=1)
            except subprocess.TimeoutExpired:raise error
            try:os.killpg(child.pid, number)
            except ProcessLookupError:pass

    @staticmethod
    def stop(child, grace=20):
        # A group can still contain grandchildren after its leader exits.
        ProcessOwner.signal_group(child, signal.SIGTERM)
        try:
            child.wait(timeout=grace)
        except subprocess.TimeoutExpired:
            pass
        ProcessOwner.signal_group(child, signal.SIGKILL)
        child.wait(timeout=10)

    def terminate_children(self):
        # Broadcast before waiting: one slow child cannot keep the other groups running.
        for child in reversed(self.children):
            self.signal_group(child, signal.SIGTERM)

    def stop_children(self):
        self.stopping = True
        self.terminate_children()
        for child in reversed(self.children):
            self.stop(child)
        self.children.clear()

    def close(self):
        self.done.set()
        self.stop_children()
        for number, handler in self.previous.items():
            signal.signal(number, handler)
