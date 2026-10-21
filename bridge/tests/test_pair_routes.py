import unittest
from pair import pairing_payload
from account import PcAccount
from test_account import MemorySecret

class PairRoutes(unittest.TestCase):
    def test_live_external_url_in_qr_with_pc_identity(self):
        data=pairing_payload({'port':18765},'192.168.15.148','ab'*32,'temporary',
            {'remote':True,'remoteEndpoint':'https://test.trycloudflare.com','computerId':'pc'})
        self.assertEqual(data['remoteEndpoint'],'https://test.trycloudflare.com')
        self.assertEqual(data['computerId'],'pc')
        self.assertEqual(data['host'],'192.168.15.148')
    def test_unavailable_or_invalid_tunnel_never_enters_qr(self):
        for account in ({'remote':False,'remoteEndpoint':'https://test.trycloudflare.com'},
                        {'remote':True,'remoteEndpoint':'https://test.trycloudflare.com.attacker.example'},{}):
            self.assertNotIn('remoteEndpoint',pairing_payload({'port':18765},'192.168.15.148','ab'*32,'temporary',account))
    def test_restore_remote_default_without_overriding_explicit_off(self):
        for previous,expected in (({},True),({'remote_enabled':True},True),({'remote_enabled':False},False)):
            secret=MemorySecret();secret.save({'cookies':'session',**previous})
            restored=PcAccount('.',public_config={},secret=secret)
            self.assertEqual(restored.status()['remoteEnabled'],expected)
            self.assertEqual(secret.load()['remote_enabled'],expected)
