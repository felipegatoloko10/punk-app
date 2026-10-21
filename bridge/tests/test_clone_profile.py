import tempfile
import unittest
from pathlib import Path

import yaml

from clone_profile import synchronize


class ProfileSyncTests(unittest.TestCase):
    def test_identity_model_skills_and_telegram_toolset_stay_in_parity(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source, target = root / 'default', root / 'punk-mobile'
            (source / 'skills' / 'shared').mkdir(parents=True)
            (target / 'skills' / 'obsolete').mkdir(parents=True)
            (source / 'SOUL.md').write_text('same soul', encoding='utf-8')
            (source / 'instructions.md').write_text('same instructions', encoding='utf-8')
            (source / 'skills' / 'shared' / 'SKILL.md').write_text('shared skill', encoding='utf-8')
            (target / 'skills' / 'obsolete' / 'SKILL.md').write_text('old', encoding='utf-8')
            source_config = {
                'model': {'default': 'cx/current', 'provider': 'router'},
                'agent': {'yolo': True},
                'plugins': {'enabled': ['shared'], 'disabled': []},
                'platform_toolsets': {'telegram': ['*']},
                'memory': {'provider': 'durable'},
            }
            target.mkdir(exist_ok=True)
            (source / 'config.yaml').write_text(yaml.safe_dump(source_config), encoding='utf-8')
            (target / 'config.yaml').write_text(yaml.safe_dump({'hooks': {'keep': True}}), encoding='utf-8')

            status = synchronize(source, target)
            config = yaml.safe_load((target / 'config.yaml').read_text(encoding='utf-8'))

            self.assertTrue(status['ok'])
            self.assertTrue(status['toolsetParity'])
            self.assertEqual(config['model'], source_config['model'])
            self.assertEqual(config['platform_toolsets']['api_server'], ['*'])
            self.assertIn('punk_control', config['plugins']['enabled'])
            self.assertFalse(config['agent']['yolo'])
            self.assertEqual(config['approvals']['mode'], 'off')
            self.assertEqual(config['hooks'], {'keep': True})
            self.assertEqual((target / 'instructions.md').read_text(encoding='utf-8'), 'same instructions')
            self.assertTrue((target / 'skills' / 'shared' / 'SKILL.md').is_file())
            self.assertFalse((target / 'skills' / 'obsolete' / 'SKILL.md').exists())


if __name__ == '__main__':
    unittest.main()
