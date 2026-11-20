"""Real HTTP/PostgreSQL/Mailpit acceptance, synthetic accounts only. No browser or remote requests.
Run with the consolidated local server on 8080 and Angular Development on 4200.
Secrets stay in memory; the report contains only statuses and boolean assertions.
"""
from pathlib import Path
import concurrent.futures, html, json, os, re, secrets, subprocess as sp, time
import urllib.request, urllib.error, urllib.parse

root = Path(__file__).resolve().parent.parent
private = root/'.local-dev'
properties = dict(line.split('=', 1) for line in (private/'application.properties').read_text().splitlines() if '=' in line)
assert properties['spring.datasource.url'] == 'jdbc:postgresql://127.0.0.1:55439/cmc_development'
client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
metrics = {'backend': 'http://localhost:8080', 'frontend': 'http://localhost:4200', 'mailpit': 'http://localhost:8025',
           'database': 'cmc_development|127.0.0.1|55439|cmc_dev', 'productionRequests': 0, 'browserAutomated': False}

def sql(query):
    env = os.environ | {'PGPASSWORD': properties['spring.datasource.password']}
    result = sp.run([r'C:/Program Files/PostgreSQL/18/bin/psql.exe', '-X', '-At', '-w', '-v', 'ON_ERROR_STOP=1',
                     '-h', '127.0.0.1', '-p', '55439', '-U', 'cmc_dev', '-d', 'cmc_development', '-c', query],
                    env=env, capture_output=True, text=True)
    assert result.returncode == 0, 'Local identity query failed'
    return result.stdout.strip()

assert sql('select current_database(),inet_server_addr(),inet_server_port(),current_user') == metrics['database']
assert sql("select shobj_description(oid,'pg_database') from pg_database where datname=current_database()") == 'CMC isolated local development; no commercial data'

def request(port, path, body=None, token=None, method=None):
    assert port in (8080, 4200, 8025) and path.startswith('/') and not path.startswith('//')
    headers = {'Origin': 'http://localhost:4200'} if port == 8080 else {}
    if body is not None: headers['Content-Type'] = 'application/json'
    if token: headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(f'http://localhost:{port}' + path, headers=headers,
        data=json.dumps(body).encode() if body is not None else None, method=method or ('POST' if body is not None else 'GET'))
    try:
        with client.open(req, timeout=25) as r: status, data, hd = r.status, r.read(), r.headers
    except urllib.error.HTTPError as r: status, data, hd = r.code, r.read(), r.headers
    return status, json.loads(data) if data.lstrip().startswith((b'{', b'[')) else data.decode('utf8', 'replace'), hd

def inbox(address):
    return request(8025, '/api/v1/search?' + urllib.parse.urlencode({'query': 'to:' + address}))[1].get('messages', [])

def message(address, kind, exclude=()):
    for _ in range(100):
        for item in inbox(address):
            if item['ID'] in exclude or not kind(item['Subject']): continue
            return request(8025, '/api/v1/message/' + item['ID'])[1], item['ID']
        time.sleep(.2)
    raise AssertionError('Expected synthetic Mailpit message was not received')

def create(link=False):
    suffix = secrets.token_hex(6)
    account = {'email': 'cmcconsolidation' + suffix + '@gmail.com', 'password': 'Aa9!' + secrets.token_urlsafe(18)}
    payload = dict(account, username='cmc' + suffix, firstName='Cuenta', lastName='Sintetica')
    legal = request(8080, '/api/public/legal/registration')[1]
    if legal.get('required'):
        payload['legalAcceptance'] = dict(termsAccepted=True, privacyRead=True, termsVersion=legal['termsVersion'], privacyVersion=legal['privacyVersion'])
    result = request(8080, '/api/auth/register', payload)
    assert result[0] == 200 and 'accessToken' not in json.dumps(result[1]), 'Synthetic registration failed'
    mail, _ = message(account['email'], lambda s: 'Verificación de cuenta' in s)
    text = html.unescape(mail.get('Text', '') + '\n' + mail.get('HTML', ''))
    if link:
        token = re.search(r'verify-account\?token=([A-Za-z0-9_-]+)', text)
        assert token and request(8080, '/api/auth/verify-email', {'token': token[1]})[0] == 200
        metrics['activationByLink'] = True
    else:
        code = re.search(r'Código de activación:\s*(\d{6})', text)
        assert code and request(8080, '/api/auth/verify-email-code', {'email': account['email'], 'code': code[1]})[0] == 200
        metrics['activationByCode'] = True
    return account

def login(account):
    result = request(8080, '/api/auth/login', account)
    assert result[0] == 200, 'Synthetic login failed'
    return result[1]['data']

def factor(account, challenge):
    assert challenge.get('twoFactorRequired') and 'jwtResponse' not in challenge, 'MFA requirement missing'
    previous = [item['ID'] for item in inbox(account['email']) if '2FA' in item['Subject']]
    assert request(8080, '/api/2fa/send-login-code', {'preAuthChallenge': challenge['preAuthChallenge'], 'method': 'EMAIL'})[0] == 200
    mail, _ = message(account['email'], lambda s: '2FA' in s, previous)
    code = re.search(r'class="code">\s*(\d{6})', mail.get('HTML', ''))
    assert code, 'Synthetic MFA code was not present'
    result = request(8080, '/api/2fa/verify', {'preAuthChallenge': challenge['preAuthChallenge'], 'method': 'EMAIL', 'code': code[1]})
    assert result[0] == 200 and result[1]['data'].get('accessToken'), 'MFA completion failed'
    return result[1]['data']['accessToken']

assert request(8080, '/actuator/health')[1].get('status') == 'UP'
assert request(4200, '/login')[0] == 200 and request(4200, '/reset-password')[0] == 200
metrics['webRouteFallbacks'] = True
assert sql("select max(version::int) from public.flyway_schema_history where success and version ~ '^[0-9]+$'") == '59'
metrics['flywayVersion'] = 59
account = create()
session_a = login(account)['jwtResponse']['accessToken']
session_b = login(account)['jwtResponse']['accessToken']
capability = request(8080, '/api/users/security-settings', token=session_a)
assert capability[0] == 200 and capability[1]['data'].get('passwordChangeRevokesSessions') is True
assert capability[2].get('Access-Control-Allow-Origin') == 'http://localhost:4200'
metrics['securitySettings'] = {'status': 200, 'success': capability[1]['success'], 'data': {'passwordChangeRevokesSessions': True}}
assert request(8080, '/api/users/security-settings')[0] == 401
assert request(8080, '/api/users/profile', token=session_a)[0] == 200
assert request(8080, '/api/users/change-password', {'currentPassword': 'WrongSynthetic@9', 'newPassword': 'ValidSynthetic@9x'}, session_a)[0] == 400
assert request(8080, '/api/auth/me', token=session_b)[0] == 200
assert request(8080, '/api/2fa/email/enable', {}, session_a)[0] == 200
new_password = 'Aa9!' + secrets.token_urlsafe(18)
with concurrent.futures.ThreadPoolExecutor(2) as pool:
    changes = list(pool.map(lambda _: request(8080, '/api/users/change-password',
        {'currentPassword': account['password'], 'newPassword': new_password}, session_a)[0], range(2)))
assert changes.count(200) == 1 and all(s in (200, 400, 401) for s in changes), 'Concurrent change was inconsistent'
metrics['concurrentChangeStatuses'] = sorted(changes)
statuses = [request(8080, '/api/auth/me', token=t)[0] for t in (session_a, session_b)]
assert statuses == [401, 401], 'Old JWT still accepted'
metrics['oldJwtStatuses'] = statuses
notice, _ = message(account['email'], lambda s: 'contraseña' in s.lower() and 'recuperación' not in s.lower())
assert 'max-width:600px' in notice['HTML'] and notice.get('Text') and 'token=' not in notice['HTML']
(private/'password-notification.html').write_text(notice['HTML'], encoding='utf8')
(private/'password-notification.txt').write_text(notice['Text'], encoding='utf8')
time.sleep(1.2)
assert len([m for m in inbox(account['email']) if 'contraseña' in m['Subject'].lower() and 'recuperación' not in m['Subject'].lower()]) == 1
metrics['singlePasswordNotificationHtmlAndText'] = True
account['password'] = new_password
after_change = factor(account, login(account))
metrics['loginWithNewPasswordAndMfa'] = True
assert request(8080, '/api/users/profile', token=after_change)[0] == 200
with concurrent.futures.ThreadPoolExecutor(2) as pool:
    requests = list(pool.map(lambda _: request(8080, '/api/auth/password-reset/request', {'email': account['email']}), range(2)))
assert requests[0][:2] == requests[1][:2] and requests[0][0] == 200
reset, _ = message(account['email'], lambda s: 'Recuperación' in s)
url = re.search(r'http://localhost:4200/reset-password\?token=([A-Za-z0-9_-]+)', reset['HTML'])
assert url, 'Recovery link does not target the standard frontend'
time.sleep(1.2)
assert len([m for m in inbox(account['email']) if 'Recuperación' in m['Subject']]) == 1
metrics['singleRecoveryMailForConcurrentRequests'] = True
missing = request(8080, '/api/auth/password-reset/request', {'email': 'missing' + secrets.token_hex(6) + '@gmail.com'})
assert missing[:2] == requests[0][:2]
metrics['neutralPublicRecoveryResponse'] = True
recovery_password = 'Aa9!' + secrets.token_urlsafe(18)
body = {'token': url[1], 'newPassword': recovery_password}
assert request(8080, '/api/auth/password-reset/confirm', body)[0] == 200
assert request(8080, '/api/auth/password-reset/confirm', body)[0] == 400
assert request(8080, '/api/auth/me', token=after_change)[0] == 401
account['password'] = recovery_password
factor(account, login(account))
metrics['recoverySingleUseRevokesJwtAndPreservesMfa'] = True
create(link=True)
(private/'consolidation-api-evidence.json').write_text(json.dumps(metrics, indent=2), encoding='utf8')
print(json.dumps(metrics, ensure_ascii=True), flush=True)
