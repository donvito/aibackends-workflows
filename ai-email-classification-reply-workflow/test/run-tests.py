#!/usr/bin/env python3
"""Run the real Camel route against local APIs and a temporary SQLite database."""
import json
import os
from pathlib import Path
import subprocess
import sqlite3
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parent.parent
barriers = {}
lock = threading.Lock()
errors = []
requests = []
request_bodies = []
openai_path = '/openai'
openai_model = 'provider/draft-model'


class API(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        try:
            request_body = self.rfile.read(int(self.headers['Content-Length'])).decode('utf-8')
            payload = json.loads(request_body)
            jev = self.path == '/jev'
            assert self.path in ('/jev', openai_path), self.path
            assert self.headers['Authorization'] == ('Bearer jev-test-key' if jev else 'Bearer openai-test-key')
            assert self.headers['Content-Type'].startswith('application/json')
            email = json.loads(payload['state'] if jev else payload['messages'][1]['content'])
            subject = email['subject']
            if jev:
                assert payload['model'] == 'jev-latest'
                assert set(payload['questions']) == {'classification', 'intent', 'sentiment'}
                assert all(q['type'] == 'choice' for q in payload['questions'].values())
            else:
                assert payload['model'] == openai_model
                assert payload['messages'][0]['role'] == 'system'
                assert payload['max_completion_tokens'] == 1200
            with lock:
                requests.append((subject, 'jev' if jev else 'openai'))
                request_bodies.append((subject, 'jev' if jev else 'openai', request_body))
                barrier = barriers.setdefault(subject, threading.Barrier(2))
            # A sequential multicast cannot pass this: both requests must arrive before either responds.
            barrier.wait(timeout=10)
            if subject == 'both-error' or subject == ('jev-error' if jev else 'openai-error'):
                status, response = 500, {'error': 'upstream-secret'}
            elif not jev and subject == 'provider-error-body':
                status, response = 200, {'error': {'code': 503, 'message': 'upstream-secret'}}
            elif jev:
                answers = {name: {'type': 'choice', 'choice': choice, 'confidence': 0.8,
                                  'probabilities': {choice: 1.0}}
                           for name, choice in [('classification', 'technical_support'),
                                                ('intent', 'request_help'), ('sentiment', 'negative')]}
                if subject == 'invalid-jev':
                    del answers['intent']
                status, response = 200, {'model': 'jev-test', 'answers': answers,
                                         'usage': {'input_tokens': 40, 'output_tokens': 10}}
            else:
                status, response = 200, {'model': 'openai-test', 'choices': [{
                    'message': {'content': '' if subject == 'empty-draft' else 'Hello, we can help.'},
                    'finish_reason': 'length' if subject == 'truncated-draft' else 'stop'}],
                    'usage': {'prompt_tokens': 20, 'completion_tokens': 5, 'total_tokens': 25}}
        except Exception as failure:
            with lock:
                errors.append(repr(failure))
            status, response = 500, {'error': 'mock assertion failed'}
        data = json.dumps(response).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)


server = ThreadingHTTPServer(('127.0.0.1', 0), API)
threading.Thread(target=server.serve_forever, daemon=True).start()
try:
    with tempfile.TemporaryDirectory(prefix='jev-email-tests-') as folder:
        base = f'http://127.0.0.1:{server.server_port}'
        env = os.environ | {'TYPESAFE_API_KEY': 'jev-test-key', 'OPENAI_API_KEY': 'openai-test-key',
            'TYPESAFE_URL': base + '/jev', 'OPENAI_URL': base + '/openai',
            'TYPESAFE_MODEL': 'jev-latest', 'OPENAI_MODEL': openai_model,
            'EMAIL_FILES_ENABLED': 'false', 'EMAIL_IMAP_ENABLED': 'false',
            'EMAIL_INBOX_DIR': str(Path(folder) / 'inbox'),
            'EMAIL_DB_FILE': str(Path(folder) / 'nested' / 'emails.db')}
        configurations = [
            ('full endpoint takes precedence', '/openai', {
                'OPENAI_URL': base + '/openai',
                'OPENAI_BASE_URL': base + '/unused-base'}),
            ('base URL with trailing slash', '/compatible/v1/chat/completions', {
                'OPENAI_URL': '', 'OPENAI_BASE_URL': base + '/compatible/v1/'}),
        ]
        for index, (label, path, overrides) in enumerate(configurations):
            openai_path = path
            print(f'Testing {label}', flush=True)
            run_env = env | overrides | {
                'EMAIL_INBOX_DIR': str(Path(folder) / str(index) / 'inbox'),
                'EMAIL_DB_FILE': str(Path(folder) / str(index) / 'nested' / 'emails.db')}
            before = len(requests)
            database = Path(run_env['EMAIL_DB_FILE'])
            if index == 0:
                database.parent.mkdir(parents=True)
                with sqlite3.connect(database) as db:
                    db.execute((ROOT / 'test/legacy-schema.sql').read_text())
                    db.execute("""INSERT INTO email_results
                        (id, source, sender, recipient, subject, email_body, received_at,
                         status, jev_result, openai_result, processed_at)
                        VALUES ('legacy', 'legacy', '', '', 'Before migration', 'Original email',
                                '2026-09-30', 'succeeded', '{}', '{}', '2026-09-30')""")
            result = subprocess.run(['camel', 'run', 'ai-email-classification-reply-workflow.camel.yaml',
                'test/integration-tests.camel.yaml', 'application.properties', '--max-seconds=90'],
                cwd=ROOT, env=run_env, timeout=240)
            if errors:
                print('Mock API assertions failed:', errors)
            if result.returncode or errors:
                raise SystemExit(result.returncode or 1)
            assert len(requests) - before == 30, 'Expected exactly 30 provider calls per configuration'
            with sqlite3.connect(database) as db:
                columns = {row[1] for row in db.execute('PRAGMA table_info(email_results)')}
                assert {'jev_request', 'openai_request'} <= columns
                if index == 0:
                    assert db.execute("SELECT email_body, jev_request, openai_request FROM email_results WHERE id = 'legacy'").fetchone() == ('Original email', None, None)
                    print('PASS existing database migration preserves historical rows', flush=True)
                captured = set(request_bodies[before:])
                for subject, jev_request, openai_request in db.execute(
                        "SELECT subject, jev_request, openai_request FROM email_results WHERE id != 'legacy'"):
                    assert (subject, 'jev', jev_request) in captured, 'Stored Jev request differs from sent body'
                    assert (subject, 'openai', openai_request) in captured, 'Stored OpenAI request differs from sent body'
                    for payload in (jev_request, openai_request):
                        assert 'jev-test-key' not in payload and 'openai-test-key' not in payload
                        assert 'inbound-secret' not in payload
                print('PASS every stored request matches the exact API body without auth headers', flush=True)
                try:
                    db.execute("UPDATE email_results SET jev_request = 'invalid-json' WHERE subject = 'success'")
                except sqlite3.IntegrityError:
                    print('PASS request columns reject invalid JSON', flush=True)
                else:
                    raise AssertionError('Invalid JSON request was accepted')
        print('Both provider URL configurations passed', flush=True)
finally:
    server.shutdown()
    server.server_close()
