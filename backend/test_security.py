import unittest
from worker import public_address

class AddressPolicy(unittest.TestCase):
    def test_blocks_private_destinations(self):
        for address in ('127.0.0.1','10.1.2.3','169.254.169.254','192.168.1.1','172.16.0.1','0.0.0.0','::1','fc00::1','fe80::1','::ffff:127.0.0.1'):
            with self.subTest(address=address):
                self.assertFalse(public_address(address))
    def test_allows_public_destinations(self):
        self.assertTrue(public_address('8.8.8.8'))
        self.assertTrue(public_address('2606:4700:4700::1111'))

if __name__ == '__main__':
    unittest.main()
