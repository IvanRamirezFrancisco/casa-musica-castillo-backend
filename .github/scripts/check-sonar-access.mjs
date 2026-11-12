const token = process.env.SONAR_TOKEN?.trim();
const key = process.env.SONAR_PROJECT_KEY;
const organization = process.env.SONAR_ORGANIZATION;

async function get(path) {
  return fetch(new URL(path, 'https://sonarcloud.io'), {
    headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
    redirect: 'error',
    signal: AbortSignal.timeout(15_000),
  });
}

try {
  if (!token) throw new Error('SONAR_TOKEN was not provided to this workflow run.');
  if (!key || !organization) throw new Error('Sonar project key and organization are required.');
  const validation = await get('/api/authentication/validate');
  if (!validation.ok) throw new Error(`Sonar token validation returned HTTP ${validation.status}.`);
  if ((await validation.json()).valid !== true) {
    throw new Error('SonarCloud rejected SONAR_TOKEN. Replace it with a valid token from the project organization.');
  }
  const project = await get(`/api/components/show?${new URLSearchParams({ component: key })}`);
  if (!project.ok) throw new Error(`Sonar project lookup returned HTTP ${project.status}. Verify project scope and access.`);
  const { component } = await project.json();
  if (component?.key !== key || component.organization !== organization) {
    throw new Error('SonarCloud returned an unexpected project or organization.');
  }
  console.log('Sonar token and project verified. The scanner still requires Execute Analysis permission.');
} catch (error) {
  const detail = error instanceof SyntaxError || error instanceof TypeError
    ? 'SonarCloud access check could not retrieve a valid response.'
    : error.message;
  console.error(detail);
  process.exitCode = 1;
}
