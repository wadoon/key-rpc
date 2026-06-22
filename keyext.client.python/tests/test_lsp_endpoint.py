# This file is part of KeY - https://key-project.org
# KeY is licensed under the GNU General Public License Version 2
# SPDX-License-Identifier: GPL-2.0-only
"""Stability tests for keyapi.rpc.LspEndpoint (timeout semantics, disconnect).

Run with::

    python3 -m unittest discover -s tests -t .

from the ``keyext.client.python`` directory.
"""
import threading
import time
import unittest

from keyapi.rpc import LspEndpoint


class _SilentEndpoint:
    """Never responds and never closes — used to exercise the timeout path."""

    def send_request(self, message):
        pass

    def recv_response(self):
        time.sleep(3600)
        return None


class _DisconnectingEndpoint:
    """Simulates the server closing the connection shortly after a request."""

    def send_request(self, message):
        pass

    def recv_response(self):
        time.sleep(0.1)
        return None  # connection closed


class LspEndpointTimeoutTest(unittest.TestCase):
    def test_default_timeout_is_none(self):
        # #13: the default must be an unambiguous "wait indefinitely", not the
        # old 2000 that looked like milliseconds but meant ~33 minutes (seconds).
        self.assertIsNone(LspEndpoint(_SilentEndpoint())._timeout)

    def test_finite_timeout_is_seconds_and_raises(self):
        endpoint = LspEndpoint(_SilentEndpoint(), timeout=0.2)
        endpoint.daemon = True
        endpoint.start()
        start = time.monotonic()
        with self.assertRaises(TimeoutError):
            endpoint.call_method("ping", [])
        # 0.2 is interpreted as seconds, so the call returns promptly.
        self.assertLess(time.monotonic() - start, 5)

    def test_server_disconnect_wakes_pending_call(self):
        # With no timeout a disconnect must still wake the caller (ConnectionError)
        # instead of hanging forever.
        endpoint = LspEndpoint(_DisconnectingEndpoint())
        endpoint.daemon = True
        endpoint.start()

        outcome = {}

        def call():
            try:
                endpoint.call_method("ping", [])
            except Exception as exc:  # noqa: BLE001 - recorded for assertion
                outcome["exc"] = exc

        t = threading.Thread(target=call)
        t.daemon = True  # so an unfixed (hanging) endpoint can't block the suite
        t.start()
        t.join(timeout=5)
        self.assertFalse(t.is_alive(), "call_method hung after server disconnect")
        self.assertIsInstance(outcome.get("exc"), ConnectionError)


if __name__ == "__main__":
    unittest.main()
