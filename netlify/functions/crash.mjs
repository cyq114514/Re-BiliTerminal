// 崩溃上报反代：把 app 的 POST 原样转发到 Supabase（crash_reports 表）。
//
// 为什么存在：*.supabase.co 在部分网络环境下会被重置/拒连（CrashUploader 里
// 记录过「上报失败是常态」，实测 WiFi 下截断、连续多天 TLS 握手失败），
// 而本站 netlify 域名的可达性好得多。app 端先走这个反代，失败再回落直连。
//
// 只做哑管道：请求体（随机安装标识、堆栈等）内存转发后即丢弃，不落盘、不打日志。
const UPSTREAM =
  'https://yiplvonquoxpfrcayozg.supabase.co/rest/v1/crash_reports';
const API_KEY = 'sb_publishable_c4ZISmj36Nn0stegNoAszA_SlWp-3Xr';

export default async (req) => {
  if (req.method !== 'POST') {
    return new Response(JSON.stringify({ error: 'method not allowed' }), {
      status: 405,
      headers: { 'content-type': 'application/json; charset=utf-8' },
    });
  }
  try {
    const body = await req.text();
    const r = await fetch(UPSTREAM, {
      method: 'POST',
      headers: {
        apikey: API_KEY,
        Authorization: 'Bearer ' + API_KEY,
        Prefer: 'return=minimal',
        'content-type': 'application/json; charset=utf-8',
      },
      body,
    });
    //上游的 2xx/4xx 原样透传：4xx 是表约束拒绝，app 端按「重试无意义」处理
    return new Response(null, { status: r.status });
  } catch {
    return new Response(JSON.stringify({ error: 'upstream unreachable' }), {
      status: 502,
      headers: { 'content-type': 'application/json; charset=utf-8' },
    });
  }
};
