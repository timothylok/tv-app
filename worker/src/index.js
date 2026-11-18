// Cloudflare Worker with Cron Triggers: starts GitHub Actions workflows on schedule.
//   - every episode cron (see wrangler.toml)  -> WORKFLOW (ai-news.yml)
//   - the daily housekeeping cron             -> HOUSEKEEPING_WORKFLOW (housekeeping.yml)
// No public endpoint (no fetch handler), so there is nothing to attack; the only secret is
// GITHUB_TOKEN, a fine-grained token limited to this one repo with "Actions: read and write".

export default {
  async scheduled(event, env, ctx) {
    const workflow = event.cron === env.HOUSEKEEPING_CRON ? env.HOUSEKEEPING_WORKFLOW : env.WORKFLOW;
    ctx.waitUntil(dispatch(env, workflow));
  },
};

async function dispatch(env, workflow) {
  const url = `https://api.github.com/repos/${env.REPO}/actions/workflows/${workflow}/dispatches`;
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
  console.log(`dispatch ${workflow}: HTTP ${res.status}`);
  if (res.status !== 204) console.log((await res.text()).slice(0, 300));
}
