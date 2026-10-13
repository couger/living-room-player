"""Exercise the local HTTP boundary without contacting a receiver or loading credentials."""
import http.client
import importlib.util
import json
from pathlib import Path
import threading
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('media_toolkit', Path(__file__).with_name('web-debug-toolkit.py'))
toolkit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(toolkit)


class MediaProxyTests(unittest.TestCase):
    def test_request_confirmation_and_local_configuration_boundary(self):
        toolkit.PROFILES['fixture'] = {'id': 'fixture'}
        server = toolkit.ThreadingHTTPServer(('127.0.0.1', 0), toolkit.Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        origin = 'http://127.0.0.1:' + str(server.server_port)
        def post(route, body):
            connection = http.client.HTTPConnection('127.0.0.1', server.server_port)
            connection.request('POST', route + '?device=fixture', body, {
                'Origin': origin, 'X-Toolkit-Token': toolkit.CSRF,
                'Content-Type': 'application/x-www-form-urlencoded'})
            response = connection.getresponse()
            result = response.status, response.getheader('Content-Type'), response.read()
            connection.close()
            return result
        try:
            with patch.object(toolkit, 'native', return_value=(b'{"mediaRequestId":42}', {})) as native:
                code, mime, data = post('/cgi-bin/action.sh', 'action=media_toggle')
                self.assertEqual(code, 200)
                self.assertEqual(mime, 'application/json')
                self.assertEqual(json.loads(data), {'mediaRequestId': 42})
                native.assert_called_once_with({'id': 'fixture'}, '/action', {'action': 'media_toggle'})
                native.reset_mock()
                self.assertEqual(post('/cgi-bin/action.sh', 'action=media_toggle&desiredState=paused&audioOnly=1')[0], 200)
                native.assert_called_once_with({'id': 'fixture'}, '/action', {'action': 'media_toggle', 'desiredState': 'paused', 'audioOnly': True})
                native.reset_mock()
                self.assertEqual(post('/cgi-bin/action.sh', 'action=media_toggle&desiredState=bad&audioOnly=1')[0], 400)
                self.assertEqual(post('/cgi-bin/action.sh', 'action=media_toggle&audioOnly=yes')[0], 400)
                native.assert_not_called()
                self.assertEqual(post('/api/config', 'playerPackage=other')[0], 403)
                self.assertEqual(post('/cgi-bin/action.sh', 'action=inspect_keys_start')[0], 403)
                native.assert_not_called()
        finally:
            server.shutdown()
            server.server_close()
            worker.join()
            toolkit.PROFILES.clear()


if __name__ == '__main__':
    unittest.main()
