// Serves the test page over http://localhost so the plugin's origin check accepts it.
// Run: bun test-page/serve.js
const root = new URL('.', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');

Bun.serve({
  port: 8787,
  hostname: '127.0.0.1',
  async fetch(req) {
    const path = new URL(req.url).pathname;
    const name = path === '/' ? 'index.html' : path.slice(1);
    const file = Bun.file(root + name);
    if (!(await file.exists())) return new Response('not found', { status: 404 });
    return new Response(file, { headers: { 'cache-control': 'no-store' } });
  },
});

console.log('Test page: http://localhost:8787');
