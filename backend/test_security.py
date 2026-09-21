import unittest
from worker import public_address, safe_error

class AddressPolicy(unittest.TestCase):
    def test_blocks_private_destinations(self):
        for address in ('127.0.0.1','10.1.2.3','169.254.169.254','192.168.1.1','172.16.0.1','0.0.0.0','::1','fc00::1','fe80::1','::ffff:127.0.0.1'):
            with self.subTest(address=address):
                self.assertFalse(public_address(address))
    def test_allows_public_destinations(self):
        self.assertTrue(public_address('8.8.8.8'))
        self.assertTrue(public_address('2606:4700:4700::1111'))

    def test_extractor_errors_are_safe_and_actionable(self):
        self.assertEqual('VIDEO REQUIRES LOGIN OR IS PRIVATE', safe_error(Exception('Login required; provide cookies')))
        self.assertEqual('VIDEO EXTRACTION TIMED OUT', safe_error(Exception('socket timeout')))
        self.assertEqual('DOWNLOADED VIDEO HAS NO AUDIO', safe_error(Exception('DOWNLOADED VIDEO HAS NO AUDIO')))
        self.assertEqual('VIDEO UNAVAILABLE, RESTRICTED OR NOT SUPPORTED', safe_error(Exception('secret signed URL details')))

if __name__ == '__main__':
    unittest.main()
