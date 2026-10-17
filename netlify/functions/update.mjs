// 服务端代理 GitHub Release 的 update.json。
//
// 为什么存在：此前 _redirects 直接 302 到 release-assets.githubusercontent.com，
// 最终响应没有 CORS 头，浏览器 fetch() 必然失败，页面只能走内置快照
// （快照的文件名/SHA 滞后时下载链接全部 404）。
// 改为函数在服务端取回后以同源身份返回，页面拿到的就是普通 JSON，无跨域问题。
const UPSTREAM =
  'https://github.com/cyq114514/Re-BiliTerminal/releases/latest/download/update.json';

export default async () => {
  try {
    const r = await fetch(UPSTREAM, { redirect: 'follow' });
    if (!r.ok) {
      return new Response(JSON.stringify({ error: 'upstream ' + r.status }), {
        status: 502,
        headers: { 'content-type': 'application/json; charset=utf-8' },
      });
    }
    return new Response(await r.text(), {
      status: 200,
      headers: {
        'content-type': 'application/json; charset=utf-8',
        'cache-control': 'no-store',
      },
    });
  } catch {
    return new Response(JSON.stringify({ error: 'upstream unreachable' }), {
      status: 502,
      headers: { 'content-type': 'application/json; charset=utf-8' },
    });
  }
};
