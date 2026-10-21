"""Synchronize Hermes' default identity and capabilities into punk-mobile.

Conversation history, channels, cron state and secrets remain profile-local. The
mobile API profile receives the same model, identity, skills and toolset as the
default/Telegram profile, plus the Punk device-control plug-in.
"""
import argparse
import copy
import hashlib
import json
import re
import shutil
from datetime import datetime, timezone
from pathlib import Path

import yaml
from hermes_cli import profiles


IGNORED = shutil.ignore_patterns('__pycache__', '.git', '.venv', 'node_modules')
IGNORED_NAMES = {'__pycache__', '.git', '.venv', 'node_modules'}
IDENTITY_FILES = ('SOUL.md', 'instructions.md', 'prefill.json')
WINDOWS_RESERVED = {'CON', 'PRN', 'AUX', 'NUL', *(f'COM{i}' for i in range(1, 10)), *(f'LPT{i}' for i in range(1, 10))}


def portable(relative: Path):
    return not any(part.split('.')[0].upper() in WINDOWS_RESERVED for part in relative.parts)


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
            shutil.copytree(plugin, target / name, dirs_exist_ok=True, ignore=IGNORED)
    metadata = source_plugins / '.install-metadata.json'
    if metadata.is_file():
        shutil.copy2(metadata, target / metadata.name)


def sync_tree(source: Path, destination: Path):
    """Mirror a non-secret content tree while preserving its directory itself."""
    if not source.is_dir():
        return
    destination.mkdir(parents=True, exist_ok=True)
    source_files = {
        item.relative_to(source)
        for item in source.rglob('*')
        if item.is_file()
        and not any(part in IGNORED_NAMES for part in item.relative_to(source).parts)
        and portable(item.relative_to(source))
    }
    for item in destination.rglob('*'):
        relative = item.relative_to(destination)
        if any(part in IGNORED_NAMES for part in relative.parts):
            continue
        if item.is_file() and relative not in source_files:
            item.unlink()
    directories = [item for item in destination.rglob('*') if item.is_dir()]
    for item in sorted(directories, key=lambda path: len(path.parts), reverse=True):
        relative = item.relative_to(destination)
        if not any(part in IGNORED_NAMES for part in relative.parts) and not any(item.iterdir()):
            item.rmdir()
    for relative in sorted(source_files, key=lambda path: str(path).lower()):
        output = destination / relative
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source / relative, output)


def file_digest(paths):
    digest = hashlib.sha256()
    found = False
    for path in sorted(paths, key=lambda value: str(value).lower()):
        if not path.is_file():
            continue
        found = True
        digest.update(path.name.encode('utf-8'))
        digest.update(path.read_bytes())
    return digest.hexdigest() if found else None


def tree_digest(root: Path):
    return file_digest(
        path for path in root.rglob('*')
        if path.is_file()
        and not any(part in IGNORED_NAMES for part in path.relative_to(root).parts)
        and portable(path.relative_to(root))
    ) if root.is_dir() else None


def synchronize(source: Path, target: Path):
    for name in IDENTITY_FILES:
        origin = source / name
        destination = target / name
        if origin.is_file():
            shutil.copy2(origin, destination)
        elif destination.exists():
            destination.unlink()
    sync_tree(source / 'skills', target / 'skills')
    copy_enabled_plugins(source, target)

    source_config = yaml.safe_load((source / 'config.yaml').read_text(encoding='utf-8')) or {}
    current = yaml.safe_load((target / 'config.yaml').read_text(encoding='utf-8')) or {}
    merged = copy.deepcopy(source_config)
    merged.setdefault('agent', {})['yolo'] = False
    merged.setdefault('approvals', {})['mode'] = 'off'
    plugins = merged.setdefault('plugins', {})
    plugins['enabled'] = list(dict.fromkeys(list(plugins.get('enabled') or []) + ['punk_control']))
    plugins['disabled'] = [name for name in plugins.get('disabled', []) if name != 'punk_control']
    telegram_tools = (source_config.get('platform_toolsets') or {}).get('telegram') or ['*']
    merged.setdefault('platform_toolsets', {})['api_server'] = copy.deepcopy(telegram_tools)
    if current.get('hooks') and not merged.get('hooks'):
        merged['hooks'] = current['hooks']

    config_path = target / 'config.yaml'
    temporary = config_path.with_suffix('.yaml.tmp')
    temporary.write_text(yaml.safe_dump(merged, allow_unicode=True, sort_keys=False), encoding='utf-8')
    temporary.replace(config_path)

    model = merged.get('model') or {}
    return {
        'ok': True,
        'sourceProfile': 'default',
        'profile': 'punk-mobile',
        'provider': model.get('provider'),
        'model': model.get('default'),
        'toolsetParity': merged.get('platform_toolsets', {}).get('api_server') == telegram_tools,
        'identitySha256': file_digest(target / name for name in IDENTITY_FILES),
        'skillsSha256': tree_digest(target / 'skills'),
        'sharedMemory': merged.get('memory') == source_config.get('memory'),
        'syncedAt': datetime.now(timezone.utc).isoformat(),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--status-file')
    args = parser.parse_args()
    source = profiles.get_profile_dir('default')
    target = profiles.get_profile_dir('punk-mobile')
    if not target.exists():
        profiles._clone_plugins = copy_enabled_plugins
        target = profiles.create_profile('punk-mobile', clone_from='default', clone_config=True, no_alias=True)
    status = synchronize(source, target)
    if args.status_file:
        status_path = Path(args.status_file).resolve()
        status_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = status_path.with_suffix(status_path.suffix + '.tmp')
        temporary.write_text(json.dumps(status, ensure_ascii=False, indent=2), encoding='utf-8')
        temporary.replace(status_path)
    print(json.dumps(status, ensure_ascii=False, sort_keys=True))
    print(str(target))


if __name__ == '__main__':
    main()
