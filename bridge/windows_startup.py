"""Opt-in, per-user Windows startup. No elevation, password or system service."""
import argparse
import ctypes
import json
import os
import subprocess
import sys
import time
from pathlib import Path

RUN_KEY = r'Software\Microsoft\Windows\CurrentVersion\Run'
VALUE = 'PunkCompanion'


def command(root):
    python = Path(sys.executable).with_name('pythonw.exe')
    return subprocess.list2cmdline([str(python), str(Path(root).resolve() / 'windows_startup.py'), '--run'])


def startup_enabled(root):
    if os.name != 'nt':
        return False
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
            return winreg.QueryValueEx(key, VALUE)[0] == command(root)
    except FileNotFoundError:
        return False


def set_startup(root, enabled):
    if os.name != 'nt':
        raise RuntimeError('A inicialização automática desta versão requer Windows.')
    root = Path(root).resolve()
    if not (root / 'runtime' / 'config.json').is_file() or not (root / 'INICIAR-PUNK.ps1').is_file():
        raise RuntimeError('Conclua a instalação do Punk antes de ativar a inicialização automática.')
    import winreg
    expected = command(root)
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
        try:
            previous = winreg.QueryValueEx(key, VALUE)[0]
        except FileNotFoundError:
            previous = None
        if previous and previous != expected:
            raise RuntimeError('Outra instalação do Punk usa a inicialização automática. Desative-a primeiro.')
        if enabled:
            winreg.SetValueEx(key, VALUE, 0, winreg.REG_SZ, expected)
        elif previous is not None:
            winreg.DeleteValue(key, VALUE)
    if enabled:
        subprocess.Popen([str(Path(sys.executable).with_name('pythonw.exe')), str(root / 'windows_startup.py'), '--run'],
                         cwd=root, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    return startup_enabled(root)


def watch(root):
    # One supervisor per checkout/user. It never kills another process or touches Hermes services.
    root = Path(root).resolve()
    key = __import__('hashlib').sha256(str(root).lower().encode()).hexdigest()[:20]
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.CreateMutexW.argtypes = [ctypes.c_void_p, ctypes.c_bool, ctypes.c_wchar_p]
    kernel.CreateMutexW.restype = ctypes.c_void_p
    handle = kernel.CreateMutexW(None, False, 'Local\\PunkStartup-' + key)
    if not handle or ctypes.get_last_error() == 183:
        if handle:
            kernel.CloseHandle.argtypes = [ctypes.c_void_p]
            kernel.CloseHandle(handle)
        return
    try:
        while startup_enabled(root):
            try:
                from desktop import Connection
                state = Connection(root).status()
                if state.get('configured') and not state.get('online'):
                    Connection(root).connect()
            except Exception:
                pass  # UI diagnostics remain available; no secret-bearing exception logs.
            time.sleep(15)
    finally:
        kernel.CloseHandle.argtypes = [ctypes.c_void_p]
        kernel.CloseHandle(handle)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--run', action='store_true')
    if parser.parse_args().run and os.name == 'nt':
        watch(Path(__file__).resolve().parent)
