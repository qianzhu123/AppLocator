// AppLocator same-wifi update server.
// Run: node server.js   (default port 8777, binds 0.0.0.0)
// Serves:
//   /api/version -> { available, versionCode, versionName, apkSize, apkUrl }
//   /apk         -> the APK file (download)
// The APK + meta.json live in tool/dist-update/ (written by the release flow).
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import url from "node:url";

const ROOT = path.dirname(url.fileURLToPath(import.meta.url));
const DIST = path.join(ROOT, "dist-update");
const PORT = Number(process.env.APPLOCATOR_PORT || 8778);
const HOST = process.env.APPLOCATOR_HOST || "0.0.0.0";

function readUpdateMeta() {
  try {
    const f = path.join(DIST, "meta.json");
    if (!fs.existsSync(f)) return null;
    const meta = JSON.parse(fs.readFileSync(f, "utf8"));
    const apk = path.join(DIST, "applocator-latest.apk");
    if (!fs.existsSync(apk)) return null;
    return { ...meta, apkSize: fs.statSync(apk).size, apkUrl: "/apk" };
  } catch { return null; }
}

function json(res, code, obj) {
  res.writeHead(code, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
  res.end(JSON.stringify(obj));
}

http.createServer((req, res) => {
  const p = new URL(req.url, "http://x").pathname;

  if (p === "/api/version") {
    const meta = readUpdateMeta();
    if (!meta) return json(res, 200, { available: false });
    return json(res, 200, { available: true, ...meta });
  }

  if (p === "/apk") {
    const f = path.join(DIST, "applocator-latest.apk");
    if (!fs.existsSync(f)) return json(res, 404, { error: "no apk" });
    res.writeHead(200, {
      "Content-Type": "application/vnd.android.package-archive",
      "Content-Length": fs.statSync(f).size,
      "Content-Disposition": 'attachment; filename="applocator-latest.apk"',
    });
    return res.end(fs.readFileSync(f));
  }

  json(res, 404, { error: "not found" });
}).listen(PORT, HOST, () => {
  console.log(`AppLocator update server on http://${HOST}:${PORT} (dist-update/)`);
});
