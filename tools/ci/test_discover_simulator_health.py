#!/usr/bin/env python3
"""Exercise the operator's host-pressure limits without booting a simulator."""
import unittest

from discover_simulator_health import SimulatorHealth


class HealthTests(unittest.TestCase):
    def sample(self, **changes):
        return dict(time=100, children=140, rssKB=8 * 1024 * 1024,
                    swapoutsPerSecond=0, load1=10, memoryFreePercent=40,
                    bootDiskFreeGiB=12, **changes)

    def observe(self, guard, **changes):
        sample = self.sample()
        sample.update(changes)
        return guard.observe(sample)

    def test_ordinary_shared_page_sums_do_not_stop_a_healthy_ipad(self):
        guard = SimulatorHealth()
        self.assertIsNone(self.observe(guard))
        self.assertIsNone(self.observe(guard, time=160, children=130))
        self.assertTrue(guard.settled)

    def test_swap_and_load_need_three_consecutive_bad_samples(self):
        for field, value in [('swapoutsPerSecond', 200), ('load1', 120.1)]:
            guard = SimulatorHealth()
            self.assertIsNone(self.observe(guard, **{field: value}))
            self.assertIsNone(self.observe(guard, **{field: value}))
            self.assertIsNone(self.observe(guard))  # resets the streak
            self.assertIsNone(self.observe(guard, **{field: value}))
            self.assertIsNone(self.observe(guard, **{field: value}))
            self.assertIn(field, self.observe(guard, **{field: value}))

    def test_device_disk_and_memory_limits_stop_immediately(self):
        for field, value in [('children', 201), ('rssKB', 12 * 1024 * 1024 + 1),
                             ('bootDiskFreeGiB', 5.99), ('memoryFreePercent', 9.99)]:
            self.assertIn(field, self.observe(SimulatorHealth(), **{field: value}))
        self.assertIsNone(self.observe(SimulatorHealth(), children=200,
                                      rssKB=12 * 1024 * 1024, memoryFreePercent=10,
                                      bootDiskFreeGiB=6, load1=120))

    def test_growth_or_host_pressure_restarts_the_quiet_minute(self):
        guard = SimulatorHealth()
        self.observe(guard, time=0)
        self.observe(guard, time=45, children=145)
        self.observe(guard, time=90, children=145)
        self.assertFalse(guard.settled)
        self.observe(guard, time=105, children=145, swapoutsPerSecond=200)
        self.observe(guard, time=120, children=145)
        self.observe(guard, time=165, children=145)
        self.assertFalse(guard.settled)
        self.observe(guard, time=180, children=145)
        self.assertTrue(guard.settled)
        self.assertEqual(200, guard.peaks['swapoutsPerSecond'])


if __name__ == '__main__':
    unittest.main()
