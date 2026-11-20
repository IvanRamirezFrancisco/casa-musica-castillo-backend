"""Prepare/start only this checkout's private PostgreSQL 18 clusters. No external DB or email.

python scripts/prepare-local.py          # durable development DB, Mailpit preflight
python scripts/prepare-local.py --tests  # also prepare the existing suite's databases
python scripts/prepare-local.py --stop  # stop only clusters whose ownership was verified
"""
from pathlib import Path
import argparse, json, os, secrets, socket, subprocess as sp

root = Path(__file__).resolve().parent.parent
local = root / '.local-dev'
pg = Path(os.environ.get('CMC_PG_BIN', r'C:/Program Files/PostgreSQL/18/bin'))
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--tests', action='store_true')
parser.add_argument('--reset-tests', action='store_true', help='Recreate only named disposable test databases in owned clusters; preserves cmc_development')
parser.add_argument('--stop', action='store_true')
args = parser.parse_args()
assert (root / 'pom.xml').is_file() and (pg / 'pg_ctl.exe').is_file(), 'PostgreSQL 18 binaries required'
local.mkdir(exist_ok=True)
if os.name == 'nt':
    identity = sp.check_output(['whoami'], text=True).strip()
    sp.run(['icacls', str(local), '/inheritance:r', '/grant:r', identity + ':(OI)(CI)F', 'SYSTEM:(OI)(CI)F'],
           check=True, stdout=sp.DEVNULL)
env = {k: v for k, v in os.environ.items() if k.upper() in
       ('SYSTEMROOT', 'WINDIR', 'TEMP', 'TMP', 'PATH', 'COMSPEC', 'PATHEXT', 'USERPROFILE')}
env['PGPASSWORD'] = 'synthetic-test-only'

def run(command):
    # A background postgres can inherit capture pipes on Windows. pg_ctl writes its
    # own log; do not wait for a pipe held by the long-lived server.
    control = Path(str(command[0])).stem == 'pg_ctl'
    result = sp.run([str(x) for x in command], env=env,
                    stdout=sp.DEVNULL if control else sp.PIPE,
                    stderr=sp.DEVNULL if control else sp.PIPE, text=True,
                    creationflags=sp.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    if result.returncode:
        # Never echo SQL or connection arguments, which may contain local credentials.
        raise RuntimeError('Local PostgreSQL command failed: ' + Path(str(command[0])).stem)
    return (result.stdout or '').strip()

def sql(port, database, query):
    return run([pg/'psql.exe', '-X', '-At', '-v', 'ON_ERROR_STOP=1', '-h', '127.0.0.1',
                '-p', port, '-U', 'auth_test', '-d', database, '-c', query])

def occupied(port):
    with socket.socket() as sock:
        return sock.connect_ex(('127.0.0.1', port)) == 0

def cluster(name, port):
    folder = local/name
    marker = folder/'owner.json'
    data = folder/'data'
    if folder.exists():
        assert marker.is_file() and json.loads(marker.read_text()) == {'checkout': str(root), 'port': port}, 'Unknown cluster; refusing'
    elif args.stop:
        return False
    else:
        folder.mkdir()
        marker.write_text(json.dumps({'checkout': str(root), 'port': port}))
    if args.stop:
        if not occupied(port): return False
        assert Path(sql(port, 'postgres', 'show data_directory')).resolve() == data.resolve(), 'Unowned server; refusing stop'
        run([pg/'pg_ctl.exe', '-D', data, '-m', 'fast', '-w', 'stop'])
        print('Stopped owned local PostgreSQL on', port)
        return False
    if not (data/'PG_VERSION').exists():
        assert not occupied(port), 'Local port belongs to another process; refusing'
        password_file = folder/'init-password'
        password_file.write_text(env['PGPASSWORD'] + '\n', encoding='ascii')
        try:
            run([pg/'initdb.exe', '-D', data, '-E', 'UTF8', '--locale=C', '-U', 'auth_test',
                 '--auth=scram-sha-256', '--pwfile', password_file])
        finally:
            password_file.unlink(missing_ok=True)
    if not occupied(port):
        run([pg/'pg_ctl.exe', '-D', data, '-l', folder/'postgres.log', '-o', f'-h 127.0.0.1 -p {port}', '-w', 'start'])
    assert Path(sql(port, 'postgres', 'show data_directory')).resolve() == data.resolve(), 'Unknown PostgreSQL target; refusing writes'
    assert sql(port, 'postgres', 'select current_database(),inet_server_addr(),inet_server_port(),current_user') == f'postgres|127.0.0.1|{port}|auth_test'
    assert sql(port, 'postgres', 'show server_version_num').startswith('18'), 'PostgreSQL 18 required'
    print('Verified owned isolated PostgreSQL on', port)
    return True

if cluster('pg-main', 55439):
    private = local/'application.properties'
    if not private.exists():
        private.write_text('\n'.join([
            'spring.datasource.url=jdbc:postgresql://127.0.0.1:55439/cmc_development',
            'spring.datasource.username=cmc_dev', 'spring.datasource.password=' + secrets.token_hex(24),
            'app.security.jwt.secret=' + secrets.token_hex(64),
            'pickup.code.encryption.key=' + secrets.token_hex(16), ''
        ]), encoding='ascii')
    props = dict(line.split('=', 1) for line in private.read_text().splitlines() if '=' in line)
    password = props['spring.datasource.password']
    assert len(password) == 48 and all(c in '0123456789abcdef' for c in password), 'Unrecognized local credential file'
    roles = sql(55439, 'postgres', 'select rolname from pg_roles').splitlines()
    if 'spring_app' not in roles: sql(55439, 'postgres', 'CREATE ROLE spring_app NOLOGIN')
    if 'cmc_dev' not in roles:
        # Local-only migration owner. Never reused outside the owned disposable cluster.
        sql(55439, 'postgres', "CREATE ROLE cmc_dev LOGIN SUPERUSER PASSWORD '" + password + "'")
    databases = sql(55439, 'postgres', 'select datname from pg_database').splitlines()
    if 'cmc_development' not in databases:
        sql(55439, 'postgres', 'CREATE DATABASE cmc_development OWNER cmc_dev')
        sql(55439, 'postgres', "COMMENT ON DATABASE cmc_development IS 'CMC isolated local development; no commercial data'")
    assert sql(55439, 'cmc_development', "select shobj_description(oid,'pg_database') from pg_database where datname=current_database()") == 'CMC isolated local development; no commercial data'
    if args.tests or args.reset_tests:
        test_databases = ('backend_hardening_test', 'auth_challenges_test', 'schema50_upgrade_test', 'activation_upgrade_test',
                          'activation_e2e_test', 'password_upgrade_test', 'password_e2e_test')
        for database in test_databases:
            if args.reset_tests and database in databases:
                sql(55439, 'postgres', "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='" + database + "' AND pid<>pg_backend_pid()")
                sql(55439, 'postgres', 'DROP DATABASE ' + database)
                databases.remove(database)
            if database not in databases: sql(55439, 'postgres', 'CREATE DATABASE ' + database)
    assert occupied(1025) and occupied(8025), 'Start local Mailpit on 1025/8025 before Spring Boot'
    print('Ready: cmc_development, Mailpit 1025/8025; private keys retained locally')
if args.tests or args.reset_tests or args.stop:
    if cluster('pg-catalog', 55448):
        roles = sql(55448, 'postgres', 'select rolname from pg_roles').splitlines()
        if 'spring_app' not in roles: sql(55448, 'postgres', 'CREATE ROLE spring_app NOLOGIN')
        if 'catalog_search_test' not in roles:
            sql(55448, 'postgres', "CREATE ROLE catalog_search_test LOGIN PASSWORD 'synthetic-test-only'")
        catalog_exists = 'catalog_search_test' in sql(55448, 'postgres', 'select datname from pg_database').splitlines()
        if args.reset_tests and catalog_exists:
            sql(55448, 'postgres', "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='catalog_search_test' AND pid<>pg_backend_pid()")
            sql(55448, 'postgres', 'DROP DATABASE catalog_search_test')
            catalog_exists = False
        if not catalog_exists:
            sql(55448, 'postgres', 'CREATE DATABASE catalog_search_test OWNER catalog_search_test')
