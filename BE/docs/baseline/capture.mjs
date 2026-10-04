#!/usr/bin/env node
// Chụp response của backend để so sánh giữa stack microservice và monolith.
// Dùng: node capture.mjs --base http://localhost:8080 --out <thư mục>
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const args = process.argv.slice(2);
const opt = (name, def) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? args[i + 1] : def;
};
const BASE = opt('base', 'http://localhost:8080').replace(/\/$/, '');
const OUT = opt('out');
if (!OUT) {
  console.error('Thiếu --out <thư mục>');
  process.exit(1);
}

const KEEP_HEADERS = ['content-type', 'cache-control', 'x-frame-options', 'location'];
const DROP_BODY_KEYS = new Set(['timestamp', 'responseTime']);

// Cookie jar tối thiểu, giữ trong bộ nhớ (bỏ qua Secure để chạy được qua http).
const jar = new Map();
const cookieHeader = () => [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
const storeCookies = (res) => {
  for (const line of res.headers.getSetCookie()) {
    const [pair] = line.split(';');
    const eq = pair.indexOf('=');
    const name = pair.slice(0, eq).trim();
    const value = pair.slice(eq + 1).trim();
    if (!value || /max-age=0/i.test(line)) jar.delete(name);
    else jar.set(name, value);
  }
};

// Chỉ giữ tên cookie và thuộc tính, bỏ giá trị token.
const sanitizeSetCookie = (res) =>
  res.headers.getSetCookie().map((line) => {
    const [pair, ...attrs] = line.split(';').map((s) => s.trim());
    const name = pair.split('=')[0];
    const kept = attrs.filter((a) => /^(path|max-age|httponly|samesite|secure)/i.test(a));
    return [name, ...kept].join('; ');
  });

const clean = (v) => {
  if (Array.isArray(v)) return v.slice(0, 2).map(clean);
  if (v && typeof v === 'object') {
    const o = {};
    for (const [k, val] of Object.entries(v)) if (!DROP_BODY_KEYS.has(k)) o[k] = clean(val);
    return o;
  }
  return v;
};

const results = [];
let seq = 0;

async function call(name, method, path, { body, headers = {}, auth = false, sseMs = 0 } = {}) {
  const h = { ...headers };
  if (auth && jar.size) h.Cookie = cookieHeader();
  if (body !== undefined) h['Content-Type'] = 'application/json';
  const rec = { method, path };
  const ctrl = new AbortController();
  let timer;
  try {
    const res = await fetch(BASE + path, {
      method,
      headers: h,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      redirect: 'manual',
      signal: ctrl.signal,
    });
    if (auth) storeCookies(res);
    rec.status = res.status;
    for (const k of KEEP_HEADERS) rec[k] = res.headers.get(k);
    rec['set-cookie'] = sanitizeSetCookie(res);

    if (sseMs) {
      // Chỉ đọc vài giây đầu của luồng SSE.
      timer = setTimeout(() => ctrl.abort(), sseMs);
      let text = '';
      try {
        const reader = res.body.getReader();
        const dec = new TextDecoder();
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          text += dec.decode(value, { stream: true });
        }
      } catch {
        /* abort chủ ý */
      }
      rec.body = text.split('\n').filter(Boolean).slice(0, 6);
    } else if (method === 'HEAD') {
      rec.body = null;
    } else {
      const ct = res.headers.get('content-type') || '';
      if (ct.includes('json')) {
        const text = await res.text();
        try {
          rec.body = clean(JSON.parse(text));
        } catch {
          rec.body = text.slice(0, 500);
        }
      } else {
        const buf = await res.arrayBuffer();
        rec.body = `<${buf.byteLength} bytes, ${ct}>`;
      }
    }
  } catch (e) {
    rec.error = String(e.message || e);
  } finally {
    clearTimeout(timer);
  }
  const file = `${String(++seq).padStart(2, '0')}-${name}.json`;
  results.push({ file, rec });
  console.log(`${file.padEnd(48)} ${rec.status ?? 'ERR ' + rec.error}`);
  return rec;
}

// Lấy phần data thật (rec.body đã bị cắt mảng còn 2 phần tử, đủ để lấy id).
const dataOf = (rec) => rec?.body?.data;

async function main() {
  await mkdir(OUT, { recursive: true });

  // 1. Ẩn danh
  await call('categories', 'GET', '/api/categories');
  const exams = await call('exams', 'GET', '/api/exams');
  await call('exams-popular', 'GET', '/api/exams/popular');
  const examId = opt('exam', null) ?? dataOf(exams)?.[0]?.id;
  if (examId) {
    await call('exam-detail', 'GET', `/api/exams/${examId}`);
    await call('exam-questions', 'GET', `/api/exams/${examId}/questions`);
    await call('exam-rating-anon', 'GET', `/api/exams/${examId}/rating`);
  }
  const mats = await call('materials', 'GET', '/api/materials?page=0&size=5');
  const matId = dataOf(mats)?.content?.[0]?.id;
  if (matId != null) {
    await call('material-detail', 'GET', `/api/materials/${matId}`);
    await call('material-file-head', 'HEAD', `/api/materials/${matId}/file`);
  }
  await call('user-not-found', 'GET', '/api/users/999999');

  // 2. Lỗi xác thực
  await call('users-me-noauth', 'GET', '/api/users/me');
  await call('materials-post-noauth', 'POST', '/api/materials', { body: {} });
  if (examId) {
    await call('spoof-x-user-id', 'GET', `/api/exams/${examId}/rating`, { headers: { 'X-User-Id': '1' } });
  }

  // 3. Có đăng nhập
  const email = `baseline-${Date.now()}@example.com`;
  const password = 'Baseline123!';
  await call('register', 'POST', '/api/auth/register', {
    body: { name: 'bl' + String(Date.now()).slice(-8), email, phoneNumber: '0900000000', status: 'student', password },
  });
  const login = await call('login', 'POST', '/api/auth/login', { body: { email, password }, auth: true });
  const userId = dataOf(login)?.userId;
  await call('users-me', 'GET', '/api/users/me', { auth: true });

  if (examId) {
    // Lấy id câu hỏi từ response gốc (không bị cắt mảng).
    const qRes = await fetch(`${BASE}/api/exams/${examId}/questions`);
    const qJson = await qRes.json();
    const qs = (qJson.data ?? qJson).slice(0, 3);
    const answers = Object.fromEntries(qs.map((q, i) => [q.id, i % 2]));
    const submit = await call('exam-submit', 'POST', `/api/exams/${examId}/submit`, {
      auth: true,
      body: { answers, timeSpent: 60 },
    });
    await call('attempts-summary', 'GET', '/api/attempts/me/summary', { auth: true });
    const attemptId = dataOf(submit)?.id ?? dataOf(submit)?.attemptId;
    if (attemptId != null) await call('attempt-detail', 'GET', `/api/attempts/${attemptId}`, { auth: true });
    await call('exam-rating-post', 'POST', `/api/exams/${examId}/rating`, { auth: true, body: { rating: 4 } });
  }
  await call('auth-refresh', 'POST', '/api/auth/refresh', { auth: true });

  // 4. AI
  if (userId != null) {
    const analysis = await call('coach-analysis', 'GET', `/api/coach/${userId}/analysis`, { auth: true });
    const topicId = dataOf(analysis)?.nodes?.[0]?.id;
    if (topicId != null) {
      await call('coach-explain-stream', 'GET', `/api/coach/node/${topicId}/explain/stream?userId=${userId}`, {
        auth: true,
        sseMs: 2000,
      });
    }
  }

  for (const { file, rec } of results) {
    await writeFile(join(OUT, file), JSON.stringify(rec, null, 2) + '\n', 'utf8');
  }
  console.log(`\nĐã ghi ${results.length} file vào ${OUT}`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
