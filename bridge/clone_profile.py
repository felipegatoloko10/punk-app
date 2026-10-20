"""Native config clone, copying the installed plugins selected by that config.

Unselected plugin downloads may contain very large runtimes; they are not part
of the mobile profile's enabled behavior. No installed Hermes code is changed.
"""
import json
import re
import shutil
from pathlib import Path

import yaml
from hermes_cli import profiles


def copy_enabled_plugins(source, destination):
    config = yaml.safe_load((source / 'config.yaml').read_text(encoding='utf-8')) or {}
    selected = set((config.get('plugins') or {}).get('enabled') or [])
    provider = (config.get('memory') or {}).get('provider')
    if isinstance(provider, str):
        selected.add(provider)
    source_plugins = source / 'plugins'
    target = destination / 'plugins'
    target.mkdir(parents=True, exist_ok=True)
    for name in selected:
        if not isinstance(name, str) or not re.fullmatch(r'[a-zA-Z0-9_.-]+', name):
            continue
        plugin = source_plugins / name
        if plugin.is_dir():
            shutil.copytree(plugin, target / name, dirs_exist_ok=True,
                            ignore=shutil.ignore_patterns('__pycache__', '.git', '.venv', 'node_modules'))
    metadata = source_plugins / '.install-metadata.json'
    if metadata.is_file():
        shutil.copy2(metadata, target / metadata.name)


def main():
    path = profiles.get_profile_dir('punk-mobile')
    if not path.exists():
        # Copy policy is scoped to this one provisioning process; native clone
        # still owns config/identity/skills, channel stripping and atomic publish.
        profiles._clone_plugins = copy_enabled_plugins
        path = profiles.create_profile('punk-mobile', clone_from='default', clone_config=True, no_alias=True)
    print(str(path))


if __name__ == '__main__':
    main()
