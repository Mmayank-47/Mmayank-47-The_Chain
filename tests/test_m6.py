import unittest

from m6_battery import drain_model as dm
from m6_battery.relay_select import Peer, can_dispatch, energy_needed, p_reach, pick_relays
from m6_battery.urgency import LastGasp, Mode, mode, send_location_now, urgency

S = dm.Sample


def curve(rate, n=10, dt=300.0, start=0.8):
    return [S(i * dt, start - rate * i * dt) for i in range(n)]


class TestDrain(unittest.TestCase):
    def test_clean_curve_ok_and_rate_recovered(self):
        c = curve(3e-5)
        self.assertEqual(dm.check(c), (True, []))
        self.assertAlmostEqual(dm.fit_rate(c), 3e-5, places=7)

    def test_rise_without_charging_flagged_with_charging_allowed(self):
        bad = [S(0, 0.3), S(100, 0.5)]
        self.assertFalse(dm.check(bad)[0])
        self.assertTrue(dm.check([S(0, 0.3, True), S(100, 0.5, True)])[0])

    def test_impossible_drain_and_flat_curve_flagged(self):
        self.assertIn("drained faster than hardware allows", dm.check([S(0, 0.9), S(60, 0.1)])[1])
        flat = [S(0, 0.6), S(1800, 0.6), S(4000, 0.6)]
        self.assertIn("flat curve over a long span", dm.check(flat)[1])

    def test_out_of_range_flagged(self):
        self.assertFalse(dm.check([S(0, 1.4), S(60, 1.39)])[0])

    def test_default_rate_with_few_samples_and_clamp(self):
        self.assertEqual(dm.fit_rate([S(0, 0.5)], default=4e-5), 4e-5)
        self.assertLessEqual(dm.fit_rate([S(0, 0.9), S(1, 0.1), S(2, 0.0)]), dm.MAX_RATE)

    def test_time_to_silence(self):
        self.assertAlmostEqual(dm.time_to_silence(0.12, 3e-5), 4000.0)
        self.assertEqual(dm.time_to_silence(0.0, 3e-5), 0.0)

    def test_llr(self):
        self.assertEqual(dm.battery_llr([S(0, 0.5)]), 0.0)
        self.assertGreater(dm.battery_llr(curve(3e-5)), 0)
        self.assertLess(dm.battery_llr([S(0, 0.3), S(100, 0.9)]), 0)


class TestUrgency(unittest.TestCase):
    def test_modes(self):
        self.assertEqual([mode(x) for x in (0.9, 0.3, 0.2, 0.15, 0.05)],
                         [Mode.NORMAL, Mode.SAVER, Mode.SAVER, Mode.BEACON, Mode.BEACON])

    def test_urgency_decreases_with_time_left(self):
        self.assertGreater(urgency(600), urgency(7200))
        self.assertEqual(urgency(0), 1.0)

    def test_send_location_early_at_12_percent(self):
        tts = dm.time_to_silence(0.12, 3e-5)              # ~67 min: not yet in the 1 h window
        self.assertTrue(send_location_now(0.12, tts))     # but battery is below beacon level
        self.assertFalse(send_location_now(0.8, 40000))

    def test_last_gasp_fires_once(self):
        g = LastGasp()
        self.assertEqual([g.should_send(x) for x in (0.2, 0.06, 0.05, 0.04, 0.01)],
                         [False, False, True, False, False])


class TestRelayAndEnergy(unittest.TestCase):
    def test_relays_prefer_charged_connected_and_skip_beacon_mode(self):
        peers = [Peer("a", 0.9, 8), Peer("b", 0.9, 2), Peer("c", 0.10, 20), Peer("d", 0.5, 6)]
        self.assertEqual([p.id for p in pick_relays(peers, 3)], ["a", "d", "b"])

    def test_deterministic_ties(self):
        peers = [Peer("y", 0.5, 6), Peer("x", 0.5, 6)]
        self.assertEqual([p.id for p in pick_relays(peers, 2)], ["x", "y"])

    def test_energy_scales_with_route(self):
        self.assertLess(energy_needed(500), energy_needed(3000))

    def test_p_reach_falls_with_distance_and_low_charge(self):
        self.assertGreater(p_reach(0.9, 500), 0.99)
        self.assertLess(p_reach(0.9, 8000), 0.1)
        self.assertLess(p_reach(0.2, 500), p_reach(0.9, 500))

    def test_nobody_dispatched_who_dies_mid_route(self):
        self.assertTrue(can_dispatch(0.8, 1000))
        self.assertFalse(can_dispatch(0.25, 2500))


if __name__ == "__main__":
    unittest.main()
