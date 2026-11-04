// Evaluate an expression in CleanFrame's page on the stick via WebView DevTools.
// Usage: node tools/devtools-eval.mjs "<js expression>"   (needs `adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>`)
// Needs Node 22+ (the global WebSocket). Prints the value as JSON and exits 0; anything else (no page,
// a refused or failed socket, an error reply, undefined, no answer in 10 s) exits 1 with the reason on stderr.
const port = process.env.DEVTOOLS_PORT || 9333;
setTimeout(() => { console.error('devtools: no answer in 10s'); process.exit(1); }, 10000).unref();
if (typeof WebSocket !== 'function') { console.error(`devtools: no global WebSocket in Node ${process.versions.node}; needs Node 22+`); process.exit(1); }
const pages = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
const page = pages.find(p => p.url.startsWith('https://cleanframe.local/'));
if (!page) { console.error('no cleanframe page', pages.map(p => p.url)); process.exit(1); }
const ws = new WebSocket(page.webSocketDebuggerUrl);
ws.onopen = () => ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression: process.argv[2], returnByValue: true, awaitPromise: true } }));
// A refused or dropped socket fails at once, not after the 10 s wait (the error event carries no reason).
ws.onerror = () => { console.error(`devtools: WebSocket error on ${page.webSocketDebuggerUrl} (refused or dropped)`); process.exit(1); };
// A result of undefined is no answer: printed as {"result":{"type":"undefined"}} it read as a value to
// tools/stick-test.sh's dt, so expect_set/expect_not passed on something that does not exist. It exits
// 1 instead, as for no answer (dt prints DT_ERROR). A thrown exception still prints its
// exceptionDetails (dt refuses those). An error reply ({id:1,error:{code,message}}: e.g. the page went
// away) has no result: it exits 1, naming the error, instead of a TypeError.
ws.onmessage = m => {
  const r = JSON.parse(m.data);
  if (r.id !== 1) return;
  if (r.error) { console.error(`devtools: Runtime.evaluate failed: ${JSON.stringify(r.error)}`); process.exit(1); }
  const v = r.result && r.result.result;
  if (!v) { console.error(`devtools: a reply without a result: ${JSON.stringify(r).slice(0, 300)}`); process.exit(1); }
  if (v.type === 'undefined' && !r.result.exceptionDetails) { console.error('devtools: the expression gave undefined'); process.exit(1); }
  console.log(JSON.stringify(v.value ?? r.result)); ws.close();
};
