// Cloudflare Worker with a Cron Trigger: starts the GitHub Actions workflow on schedule.
// No public endpoint (no fetch handler), so there is nothing to attack; the only secret is
// GITHUB_TOKEN, a fine-grained token limited to this one repo with "Actions: read and write".

export default {
  async scheduled(event, env, ctx) {
    ctx.waitUntil(dispatch(env));
  },
};

async function dispatch(env) {
  const url = `https://api.github.com/repos/${env.REPO}/actions/workflows/${env.WORKFLOW}/dispatches`;
  const res = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.GITHUB_TOKEN}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "ai-tv-scheduler",
    },
    body: JSON.stringify({ ref: env.BRANCH || "main" }),
  });
  // GitHub answers 204 No Content when the run was queued. Log status only, never the token.
  console.log(`dispatch ${env.WORKFLOW}: HTTP ${res.status}`);
  if (res.status !== 204) console.log((await res.text()).slice(0, 300));
}
