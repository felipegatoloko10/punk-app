"""Provision the native profile without copying bot channels or invoking the agent.

Run with --apply after reviewing README. Restart/cutover is a separate explicit step.
"""
import argparse
import getpass
import json
import os
import re
import secrets
import shutil
import subprocess
from pathlib import Path

from server import load_config


def set_env(path, key, value):
    text = path.read_text(encoding='utf-8') if path.exists() else ''
    pattern = re.compile(r'^' + re.escape(key) + r'=.*$', re.M)
    line = key + '=' + value
    text = pattern.sub(lambda _: line, text) if pattern.search(text) else text.rstrip() + '\n' + line + '\n'
    path.write_text(text, encoding='utf-8'); os.chmod(path, 0o600)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--hermes-home', default=str(Path(os.environ.get('LOCALAPPDATA', Path.home())) / 'hermes'))
    parser.add_argument('--hermes-root')
    parser.add_argument('--runtime', default=str(Path(__file__).parent / 'runtime'))
    parser.add_argument('--internal-port', type=int, default=18766)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    home = Path(args.hermes_home).resolve()
    source = Path(args.hermes_root or home / 'hermes-agent').resolve()
    python = source / 'venv' / 'Scripts' / 'python.exe'
    if not python.exists():
        raise SystemExit('Use --hermes-root pointing to the installed native Hermes checkout.')
    if not args.apply:
        print('Dry run: clone default identity/config/plugins to punk-mobile; no bot channels/history/cron; install policy; configure loopback API. No changes made.')
        return
    env = dict(os.environ); env['HERMES_HOME'] = str(home)
    env['PYTHONPATH'] = str(source)
    # Resolve the Windows junction before the native profile API creates its
    # staging directory. Keep its platform-default root identical to the real
    # Hermes home inside this provisioning subprocess only.
    if os.name == 'nt' and home.name.startswith('hermes'):
        env['LOCALAPPDATA'] = str(home.parent)
        env['HERMES_DATA_DIR_SUFFIX'] = home.name[len('hermes'):]

    # Native APIs own profile path resolution and clone filtering. No shell strings.
    script = str(Path(__file__).parent / "clone_profile.py")
    runtime = Path(args.runtime).resolve()
    result = subprocess.run(
        [str(python), script, '--status-file', str(runtime / 'identity-sync.json')],
        cwd=source, env=env, capture_output=True, text=True, timeout=600,
    )
    if result.returncode:
        raise SystemExit('Native profile clone failed; source profile was not changed. Inspect Hermes diagnostics locally.')
    profile = Path(result.stdout.strip().splitlines()[-1]).resolve()
    if profile.name != 'punk-mobile' or not (profile / 'config.yaml').exists():
        raise SystemExit('Native profile returned an unexpected path; refusing to write.')
    config = load_config(runtime)
    if os.name == 'nt':
        account = os.environ.get('USERDOMAIN', '') + '\\' + getpass.getuser()
        protected = subprocess.run(['icacls', str(runtime), '/inheritance:r', '/grant:r', account + ':(OI)(CI)F', 'SYSTEM:(OI)(CI)F'], capture_output=True)
        if protected.returncode:
            raise SystemExit('Could not protect bridge runtime ACL; refusing to store additional secrets.')
    config['internalPort'] = args.internal_port
    if not config.get('hermesToken'):
        config['hermesToken'] = secrets.token_urlsafe(48)
    config['hermesUrl'] = 'http://127.0.0.1:8642/p/punk-mobile'
    config.pop('_runtimePath', None)
    config_path = runtime / 'config.json'
    config_path.write_text(json.dumps(config, indent=2), encoding='utf-8'); os.chmod(config_path, 0o600)
    plugin = profile / 'plugins' / 'punk_control'; plugin.mkdir(parents=True, exist_ok=True)
    for name in ('__init__.py', 'plugin.yaml'):
        shutil.copy2(Path(__file__).parent / 'punk_control' / name, plugin / name)
    settings_path = plugin / 'settings.json'
    settings_path.write_text(json.dumps({'url': f'http://127.0.0.1:{args.internal_port}', 'token': config['internalToken']}), encoding='utf-8'); os.chmod(settings_path, 0o600)
    set_env(profile / '.env', 'API_SERVER_KEY', config['hermesToken'])
    set_env(profile / '.env', 'API_SERVER_ENABLED', 'true')
    # The shared listener needs a default-profile credential, distinct from the
    # named profile key. Existing root API key is preserved. Existing channel
    # configuration is untouched; no service restart is performed here.
    existing = (home / '.env').read_text(encoding='utf-8')
    backup = runtime / 'hermes-env.pre-punk.backup'
    if not backup.exists():
        shutil.copy2(home / '.env', backup)
    if not re.search(r'^API_SERVER_KEY=.+$', existing, re.M):
        set_env(home / '.env', 'API_SERVER_KEY', secrets.token_urlsafe(48))
    set_env(home / '.env', 'API_SERVER_ENABLED', 'true')
    set_env(home / '.env', 'API_SERVER_HOST', '127.0.0.1')
    set_env(home / '.env', 'API_SERVER_PORT', '8642')
    print('Punk Mobile profile and policy installed. Secrets saved privately. Gateway restart/rescan and bridge cutover are still required; no agent request was sent.')


if __name__ == '__main__':
    main()
