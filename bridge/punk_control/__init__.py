"""Supported Hermes execution middleware. No core patch and no global YOLO changes."""
import json
import threading
import urllib.error
import urllib.request
from pathlib import Path


def _post(settings, path, body, timeout=135):
    req = urllib.request.Request(settings['url'] + path, data=json.dumps(body).encode(),
                                 headers={'Authorization': 'Bearer ' + settings['token'], 'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return json.load(response)


def _phone_call(tool, args):
    if tool == 'jarvis_telemetry':
        return 'device_status', {}
    if tool == 'jarvis_speak':
        return 'tts_speak', {'text': args.get('text', '')}
    if tool == 'jarvis_capture_view':
        return ('camera_capture', {'facing': 'back'}) if args.get('source') == 'camera' else ('screen_capture_b64', {})
    if tool == 'jarvis_screen_act':
        action = args.get('action')
        mapping = {'tap': ('input_tap', {'x': args.get('x', 0), 'y': args.get('y', 0)}),
                   'click': ('click_node', {'query': args.get('target', ''), 'byId': False}),
                   'click_id': ('click_node', {'query': args.get('target', ''), 'byId': True}),
                   'type': ('input_text', {'text': args.get('text', ''), 'clear': False}),
                   'scroll_down': ('scroll', {'direction': 'down'}), 'scroll_up': ('scroll', {'direction': 'up'}),
                   'dump': ('screen_dump', {}), 'open_app': ('open_app', {'package': args.get('target', '')})}
        if action in ('home', 'back', 'recents'):
            return 'global_action', {'action': action}
        return mapping.get(action)
    return None


def make_middleware(settings):
    def policy(tool_name='', args=None, session_id='', tool_call_id='', next_call=None, **kwargs):
        # Do not let an exception escape this callback: Hermes deliberately skips
        # failing middleware. Policy/network failures must short-circuit instead.
        try:
            effective = args if isinstance(args, dict) else {}
            verdict = _post(settings, '/api/policy/check', {'tool': tool_name, 'args': effective,
                            'sessionId': session_id, 'callId': tool_call_id})
            if verdict.get('allow') is not True:
                return json.dumps({'error': 'Punk: execução não autorizada ou cancelada.'})
            if tool_name == 'punk_send_file':
                result = _post(settings, '/api/files/publish', {'path': effective.get('path', ''),
                               'deviceId': verdict.get('deviceId'), 'conversationId': verdict.get('conversationId')})
                return json.dumps(result, ensure_ascii=False)
            phone = _phone_call(tool_name, effective)
            if phone:
                result = _post(settings, '/api/tool', {'tool': phone[0], 'params': phone[1],
                               'callId': tool_call_id or None, 'deviceId': verdict.get('deviceId'),
                               'conversationId': verdict.get('conversationId'), 'timeout': 120})
                if result.get('ok') is not True:
                    return json.dumps({'error': 'A ferramenta do celular falhou.', 'data': result.get('data')})
                data = result.get('data', {})
                return json.dumps(data.get('result') or data, ensure_ascii=False)
        except Exception:
            return json.dumps({'error': 'Punk: política indisponível; ação bloqueada.'})
        # Exactly one call after approval, with the same argument object that was
        # checked. Downstream failures remain genuine tool failures, not retries.
        return next_call(effective)
    return policy


def register(ctx):
    settings_path = Path(__file__).parent / 'settings.json'
    settings = json.loads(settings_path.read_text(encoding='utf-8'))
    from hermes_constants import get_hermes_home
    if Path(get_hermes_home()).name != 'punk-mobile':
        raise RuntimeError('punk_control is restricted to punk-mobile')
    ctx.register_middleware('tool_execution', make_middleware(settings))
    ctx.register_tool(name='punk_send_file', toolset='punk_control',
                      schema={'name': 'punk_send_file', 'description': 'Envia um arquivo existente no PC para a conversa do Punk Mobile, preservando os bytes originais.',
                              'parameters': {'type': 'object', 'properties': {'path': {'type': 'string'}}, 'required': ['path'], 'additionalProperties': False}},
                      handler=lambda path: {'error': 'Punk policy middleware is required for file delivery.'},
                      description='Entrega arquivo do PC ao celular na conversa ativa.', emoji='📎')
    stopped = threading.Event()

    def heartbeat():
        while not stopped.is_set():
            try:
                _post(settings, '/api/policy/ready', {'profile': 'punk-mobile', 'version': 3}, timeout=3)
            except Exception:
                pass
            stopped.wait(10)
    thread = threading.Thread(target=heartbeat, name='punk-policy-health', daemon=True)
    thread.start()
    if hasattr(ctx, 'on_unload'):
        ctx.on_unload(stopped.set)
