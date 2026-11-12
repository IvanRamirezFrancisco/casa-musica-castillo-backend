import assert from 'node:assert/strict';
import test from 'node:test';
import { checkSonarAccess } from '../../../.github/scripts/check-sonar-access.mjs';

const env = {
  SONAR_TOKEN: 'synthetic-token',
  SONAR_PROJECT_KEY: 'example-project',
  SONAR_ORGANIZATION: 'example-org',
};
const project = { component: { key: env.SONAR_PROJECT_KEY, organization: env.SONAR_ORGANIZATION } };
const validateUrl = 'https://sonarcloud.io/api/authentication/validate';
const projectUrl = 'https://sonarcloud.io/api/components/show?component=example-project';
const json = (body, status = 200) => Response.json(body, { status });

const cases = [
  { name: 'accepts a valid token for the expected project', replies: [() => json({ valid: true }), () => json(project)] },
  { name: 'stops before calling Sonar when the token is absent', override: { SONAR_TOKEN: '' }, error: /was not provided/ },
  { name: 'requires the project organization', override: { SONAR_ORGANIZATION: '' }, error: /key and organization are required/ },
  { name: 'reports the token endpoint error', replies: [() => json({}, 403)], error: /token validation returned HTTP 403/ },
  { name: 'rejects an invalid token without looking up the project', replies: [() => json({ valid: false })], error: /rejected SONAR_TOKEN/ },
  { name: 'reports a project lookup failure', replies: [() => json({ valid: true }), () => json({}, 404)], error: /project lookup returned HTTP 404/ },
  { name: 'rejects a project belonging to another organization', replies: [() => json({ valid: true }), () => json({ component: { ...project.component, organization: 'other-org' } })], error: /unexpected project or organization/ },
  { name: 'redacts network failure details', replies: [() => { throw new TypeError('private transport diagnostic'); }], error: /could not retrieve a valid response/ },
  { name: 'handles invalid JSON without printing the response', replies: [() => new Response('invalid JSON')], error: /could not retrieve a valid response/ },
];

for (const { name, override, replies = [], error } of cases) {
  test(name, async (t) => {
    const original = Object.fromEntries(Object.keys(env).map(key => [key, process.env[key]]));
    const originalExitCode = process.exitCode;
    t.after(() => {
      for (const [key, value] of Object.entries(original)) {
        if (value === undefined) delete process.env[key];
        else process.env[key] = value;
      }
      process.exitCode = originalExitCode;
    });
    Object.assign(process.env, env, override);
    process.exitCode = 0;

    const requests = [];
    const logs = t.mock.method(console, 'log', () => {});
    const errors = t.mock.method(console, 'error', () => {});
    t.mock.method(globalThis, 'fetch', async (url, options) => {
      requests.push({ url: url.href, authorization: options.headers.Authorization, redirect: options.redirect });
      const reply = replies[requests.length - 1];
      if (!reply) throw new Error('unexpected fetch');
      return reply();
    });

    await checkSonarAccess();

    assert.deepEqual(requests, [validateUrl, projectUrl].slice(0, replies.length).map(url => ({
      url, authorization: 'Bearer synthetic-token', redirect: 'error',
    })));
    assert.equal(process.exitCode, error ? 1 : 0);
    if (error) {
      assert.equal(logs.mock.callCount(), 0);
      assert.equal(errors.mock.callCount(), 1);
      assert.match(errors.mock.calls[0].arguments[0], error);
      assert.doesNotMatch(errors.mock.calls[0].arguments[0], /synthetic-token|private transport diagnostic/);
    } else {
      assert.equal(errors.mock.callCount(), 0);
      assert.equal(logs.mock.callCount(), 1);
      assert.match(logs.mock.calls[0].arguments[0], /token and project verified/);
    }
  });
}
