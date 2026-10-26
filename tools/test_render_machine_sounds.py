"""Small DSP regression checks; run with the renderer's numpy-capable Python."""
import unittest

import numpy as np

from render_machine_sounds import RATE, pitched, render


class RenderSoundsTest(unittest.TestCase):
    def test_transient_is_finite_bounded_and_quiet_at_edges(self):
        source = np.zeros(RATE)
        source[18000:24000] = np.random.default_rng(7).normal(0, .1, 6000)
        first = render(source, .12)
        np.testing.assert_array_equal(first, render(source, .12))
        self.assertEqual(len(first), round(.12 * RATE))
        self.assertEqual((first[0], first[-1]), (0, 0))
        self.assertLess(np.max(np.abs(first)), 1)
        self.assertGreater(np.sqrt(np.mean(first ** 2)), .02)

    def test_fan_loop_has_no_zero_gap_or_abnormal_seam(self):
        t = np.arange(5 * RATE) / RATE
        source = np.sin(2 * np.pi * 123 * t) + .2 * np.sin(2 * np.pi * 257 * t)
        loop = render(source, 3, fan=True, peak_db=-6)
        self.assertEqual(len(loop), 3 * RATE)
        self.assertLess(abs(loop[0] - loop[-1]), np.max(np.abs(np.diff(loop))) * 1.1)
        self.assertGreater(np.sqrt(np.mean(loop[:480] ** 2)), .05)
        self.assertGreater(np.sqrt(np.mean(loop[-480:] ** 2)), .05)

    def test_game_pitch_changes_duration_and_silent_sources_fail(self):
        self.assertAlmostEqual(len(pitched(np.ones(RATE), 1.5)), RATE / 1.5, delta=1)
        with self.assertRaises(ValueError):
            render(np.zeros(RATE), .1)
        with self.assertRaises(ValueError):
            render(np.ones(RATE), 2, fan=True)


if __name__ == "__main__":
    unittest.main()
