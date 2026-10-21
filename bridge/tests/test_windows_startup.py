import json
import os
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

import windows_startup
from desktop import Connection


@unittest.skipUnless(os.name == 'nt', 'Windows registry required')
class StartupTests(unittest.TestCase):
    def test_opt_in_only_current_user_and_preserves_other_installation(self):
        import winreg
        key = 'Software\\PunkCompanionTests\\' + str(uuid.uuid4())
        with tempfile.TemporaryDirectory(prefix='punk startup ') as directory:
            root=Path(directory);(root/'runtime').mkdir()
            (root/'runtime'/'config.json').write_text('{}',encoding='utf-8')
            (root/'INICIAR-PUNK.ps1').touch()
            with patch.object(windows_startup,'RUN_KEY',key), patch.object(windows_startup.subprocess,'Popen') as launch:
                try:
                    self.assertFalse(windows_startup.startup_enabled(root))
                    self.assertTrue(windows_startup.set_startup(root,True))
                    launch.assert_called_once()
                    self.assertIn('--run',windows_startup.command(root))
                    self.assertIn('"',windows_startup.command(root))
                    self.assertFalse(windows_startup.set_startup(root,False))
                    with winreg.CreateKey(winreg.HKEY_CURRENT_USER,key) as registry:
                        winreg.SetValueEx(registry,windows_startup.VALUE,0,winreg.REG_SZ,'another installation')
                    with self.assertRaises(RuntimeError):windows_startup.set_startup(root,True)
                    with winreg.OpenKey(winreg.HKEY_CURRENT_USER,key) as registry:
                        self.assertEqual(winreg.QueryValueEx(registry,windows_startup.VALUE)[0],'another installation')
                finally:
                    winreg.DeleteKey(winreg.HKEY_CURRENT_USER,key)

    def test_mcp_export_has_no_credentials_and_uses_console_python(self):
        with tempfile.TemporaryDirectory() as directory:
            connection=Connection(directory)
            config=connection.mcp_config(str(uuid.uuid4()))
            text=json.dumps(config)
            self.assertNotIn('token',text.lower())
            server=next(iter(config['mcpServers'].values()))
            self.assertTrue(server['command'].endswith('python.exe'))
            self.assertIn('--client',server['args'])
            self.assertIn('--runtime',server['args'])
            with self.assertRaises(RuntimeError):connection.mcp_config('../escape')
