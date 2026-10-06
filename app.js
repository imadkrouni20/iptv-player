document.addEventListener('DOMContentLoaded', () => {
const WORKER = 'https://iptv.imadkrouni20.workers.dev/?url=';
const APP = 'xtreamtv_';
const DEV_PASSWORD = 'unlock2024';
const DEV_KEY = APP + 'dev_unlock';
let db = null, subs = [], activeId = null, playlists = {};
let favs = JSON.parse(localStorage.getItem(APP + 'favs') || '[]');
let showFavsOnly = { live: false, movie: false, series: false };
let hls = null, mpegtsPlayer = null, currentChannelUrl = '';
let seriesCache = {};
const $ = id => document.getElementById(id);
const proxy = u => WORKER + encodeURIComponent(u);

function openDB() {
  return new Promise((res, rej) => {
    const req = indexedDB.open(APP + 'db', 2);
    req.onupgradeneeded = e => {
      const d = e.target.result;
      if (!d.objectStoreNames.contains('subs')) d.createObjectStore('subs');
      if (!d.objectStoreNames.contains('playlists')) d.createObjectStore('playlists');
      if (!d.objectStoreNames.contains('meta')) d.createObjectStore('meta');
    };
    req.onsuccess = e => { db = e.target.result; res(db); };
    req.onerror = e => rej(e.target.error);
  });
}
function idbSet(s, k, v) {
  return new Promise((res, rej) => {
    const t = db.transaction(s, 'readwrite');
    t.objectStore(s).put(v, k);
    t.oncomplete = () => res();
    t.onerror = e => rej(e.target.error);
  });
}
function idbGet(s, k) {
  return new Promise((res, rej) => {
    const t = db.transaction(s, 'readonly');
    const r = t.objectStore(s).get(k);
    r.onsuccess = () => res(r.result);
    r.onerror = e => rej(e.target.error);
  });
}
function idbDel(s, k) {
  return new Promise((res, rej) => {
    const t = db.transaction(s, 'readwrite');
    t.objectStore(s).delete(k);
    t.oncomplete = () => res();
    t.onerror = e => rej(e.target.error);
  });
}
function showView(name) {
  document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
  const el = $('view-' + name);
  if (el) el.classList.add('active');
  if (name === 'settings') renderSubsList();
}
document.querySelectorAll('[data-goto]').forEach(btn => {
  btn.addEventListener('click', () => showView(btn.dataset.goto));
});
document.querySelectorAll('[data-back]').forEach(btn => {
  btn.addEventListener('click', () => {
    showView('home');
    document.querySelectorAll('.modal').forEach(m => m.classList.add('hidden'));
  });
});

async function loadSubs() {
  subs = (await idbGet('subs', 'list')) || [];
  activeId = (await idbGet('meta', 'activeId')) || null;
  if (subs.length && !activeId) activeId = subs[0].id;
  if (activeId && !subs.find(s => s.id === activeId)) activeId = subs[0]?.id || null;
}
async function saveSubs() { await idbSet('subs', 'list', subs); }
function getActive() { return subs.find(s => s.id === activeId) || null; }
function getData() { return playlists[activeId] || { live: [], movie: [], series: [] }; }
async function savePlaylist(id, d) { playlists[id] = d; await idbSet('playlists', id, d); }
async function loadCurrentPlaylist() {
  if (!activeId) return;
  const c = await idbGet('playlists', activeId);
  playlists[activeId] = c || { live: [], movie: [], series: [] };
}

function calcDays(s) {
  if (!s || !s.userInfo || !s.userInfo.exp_date) return null;
  return Math.ceil((parseInt(s.userInfo.exp_date) * 1000 - Date.now()) / 86400000);
}
function updateSubDisplay() {
  const s = getActive();
  const ne = $('home-sub-name'), de = $('home-sub-days');
  if (!s) { ne.textContent = 'لا يوجد اشتراك'; de.textContent = ''; return; }
  ne.textContent = s.name;
  const d = calcDays(s);
  if (d === null) { de.textContent = '✓ مُفعّل'; de.className = 'home-sub-days'; }
  else if (d > 0) { de.textContent = '⏳ ' + d + ' يوم متبقي'; de.className = 'home-sub-days' + (d <= 7 ? ' warning' : ''); }
  else { de.textContent = '⛔ منتهي'; de.className = 'home-sub-days expired'; }
  $('um-name').textContent = s.name;
  $('um-days').textContent = de.textContent;
}
function populateCat(sel, view) {
  const d = getData();
  const g = new Set();
  (d[view] || []).forEach(c => c[2] && g.add(c[2]));
  sel.innerHTML = '<option value="">كل التصنيفات</option>';
  [...g].sort().forEach(x => { const o = document.createElement('option'); o.value = x; o.textContent = x; sel.appendChild(o); });
}

function toggleFav(n) {
  if (favs.includes(n)) favs = favs.filter(f => f !== n);
  else favs.push(n);
  localStorage.setItem(APP + 'favs', JSON.stringify(favs));
}

function renderLive() {
  const d = getData();
  const s = $('live-search').value.toLowerCase().trim();
  const c = $('live-category').value;
  const items = (d.live || []).filter(x => {
    if (showFavsOnly.live && !favs.includes(x[0])) return false;
    if (c && x[2] !== c) return false;
    if (s && !x[0].toLowerCase().includes(s)) return false;
    return true;
  });
  const el = $('live-list');
  el.innerHTML = '';
  if (!items.length) { el.innerHTML = '<li class="empty-msg">لا توجد قنوات</li>'; return; }
  items.forEach(ch => {
    const li = document.createElement('li');
    if (ch[1] === currentChannelUrl) li.classList.add('active');
    const st = document.createElement('span');
    st.className = 'fav-star' + (favs.includes(ch[0]) ? ' on' : '');
    st.textContent = favs.includes(ch[0]) ? '★' : '☆';
    st.addEventListener('click', e => { e.stopPropagation(); toggleFav(ch[0]); renderLive(); });
    const nm = document.createElement('span');
    nm.className = 'name'; nm.textContent = ch[0];
    li.appendChild(st); li.appendChild(nm);
    li.addEventListener('click', () => playItem(ch, 'live'));
    el.appendChild(li);
  });
}

function renderMovies() {
  const d = getData();
  const s = $('movie-search').value.toLowerCase().trim();
  const c = $('movie-category').value;
  const items = (d.movie || []).filter(x => {
    if (showFavsOnly.movie && !favs.includes(x[0])) return false;
    if (c && x[2] !== c) return false;
    if (s && !x[0].toLowerCase().includes(s)) return false;
    return true;
  });
  renderPosters($('movie-grid'), items, 'movie');
}
function renderSeries() {
  const d = getData();
  const s = $('series-search').value.toLowerCase().trim();
  const c = $('series-category').value;
  const items = (d.series || []).filter(x => {
    if (showFavsOnly.series && !favs.includes(x[0])) return false;
    if (c && x[2] !== c) return false;
    if (s && !x[0].toLowerCase().includes(s)) return false;
    return true;
  });
  renderPosters($('series-grid'), items, 'series');
}
const PAGE_SIZE = 60;
let moviePage = 0, seriesPage = 0;
let movieItems = [], seriesItems = [];

function makePosterCard(it, type) {
  const [name, , group, poster] = it;
  const card = document.createElement('div');
  card.className = 'poster-card';
  card.style.contentVisibility = 'auto';
  card.style.containIntrinsicSize = '250px';

  const ph = document.createElement('div');
  ph.className = 'poster-placeholder';
  ph.textContent = type === 'movie' ? '🎬' : '🎞️';
  card.appendChild(ph);

  if (poster) {
    const img = document.createElement('img');
    img.className = 'poster-img';
    img.loading = 'lazy';
    img.decoding = 'async';
    img.style.display = 'none';
    img.onload = () => { img.style.display = 'block'; ph.remove(); };
    img.onerror = () => { img.remove(); };
    img.src = poster;
    card.insertBefore(img, ph);
  }

  const fav = document.createElement('div');
  fav.className = 'poster-fav' + (favs.includes(name) ? ' on' : '');
  fav.textContent = favs.includes(name) ? '★' : '☆';
  fav.addEventListener('click', e => {
    e.stopPropagation();
    toggleFav(name);
    fav.classList.toggle('on', favs.includes(name));
    fav.textContent = favs.includes(name) ? '★' : '☆';
  });
  card.appendChild(fav);

  const info = document.createElement('div');
  info.className = 'poster-info';
  const t = document.createElement('div');
  t.className = 'poster-title'; t.textContent = name;
  const g = document.createElement('div');
  g.className = 'poster-group'; g.textContent = group || '';
  info.appendChild(t); info.appendChild(g);
  card.appendChild(info);

  card.addEventListener('click', () => {
    if (type === 'series') openSeriesDetail(it);
    else playItem(it, 'movie');
  });
  return card;
}

function renderPostersBatch(grid, items, type, from, to) {
  const frag = document.createDocumentFragment();
  const slice = items.slice(from, to);
  slice.forEach(it => frag.appendChild(makePosterCard(it, type)));
  grid.appendChild(frag);
}

function renderPosters(grid, items, type) {
  grid.innerHTML = '';
  if (!items.length) {
    grid.innerHTML = '<div style="grid-column:1/-1;text-align:center;padding:60px;color:#666">لا توجد عناصر</div>';
    return;
  }
  // إعادة تعيين الصفحة
  if (type === 'movie') { moviePage = 0; movieItems = items; }
  else { seriesPage = 0; seriesItems = items; }
  renderPostersBatch(grid, items, type, 0, PAGE_SIZE);
  if (type === 'movie') moviePage = 1;
  else seriesPage = 1;
}

function loadMorePosters(grid, type) {
  const items = type === 'movie' ? movieItems : seriesItems;
  const page = type === 'movie' ? moviePage : seriesPage;
  const from = page * PAGE_SIZE;
  if (from >= items.length) return;
  renderPostersBatch(grid, items, type, from, from + PAGE_SIZE);
  if (type === 'movie') moviePage++;
  else seriesPage++;
}

function setupInfiniteScroll() {
  const movieGrid = $('movie-grid');
  const seriesGrid = $('series-grid');
  const scrollHandler = (grid, type) => {
    return () => {
      if (grid.scrollTop + grid.clientHeight >= grid.scrollHeight - 400) {
        loadMorePosters(grid, type);
      }
    };
  };
  movieGrid.addEventListener('scroll', scrollHandler(movieGrid, 'movie'));
  seriesGrid.addEventListener('scroll', scrollHandler(seriesGrid, 'series'));
}
setTimeout(setupInfiniteScroll, 500);
async function openSeriesDetail(it) {
  const [name, , , poster, seriesId] = it;
  $('sd-title').textContent = name;
  $('sd-poster').src = poster || '';
  $('sd-plot').textContent = 'جاري التحميل...';
  $('seasons-tabs').innerHTML = '';
  $('sd-episodes').innerHTML = '<div style="text-align:center;padding:20px;color:#888">جاري التحميل...</div>';
  $('modal-series').classList.remove('hidden');
  const s = getActive();
  if (!s || s.type !== 'xtream' || !seriesId) {
    $('sd-plot').textContent = 'التفاصيل متاحة فقط لـ Xtream';
    return;
  }
  try {
    let info = seriesCache[seriesId];
    if (!info) {
      const url = s.cred.host + '/player_api.php?username=' + s.cred.user + '&password=' + s.cred.pass + '&action=get_series_info&series_id=' + seriesId;
      info = await fetch(proxy(url)).then(r => r.json());
      seriesCache[seriesId] = info;
    }
    $('sd-plot').textContent = (info.info && info.info.plot) || 'لا يوجد وصف';
    renderSeasons(info);
  } catch(e) {
    $('sd-plot').textContent = 'فشل التحميل: ' + e.message;
  }
}

function renderSeasons(info) {
  const eps = info.episodes || {};
  const seasons = Object.keys(eps).sort((a, b) => a - b);
  const tabsEl = $('seasons-tabs');
  tabsEl.innerHTML = '';
  if (!seasons.length) {
    $('sd-episodes').innerHTML = '<div style="text-align:center;padding:20px;color:#888">لا توجد حلقات</div>';
    return;
  }
  seasons.forEach((sn, i) => {
    const btn = document.createElement('button');
    btn.className = 'season-tab' + (i === 0 ? ' active' : '');
    btn.textContent = 'الموسم ' + sn;
    btn.addEventListener('click', () => {
      tabsEl.querySelectorAll('.season-tab').forEach(x => x.classList.remove('active'));
      btn.classList.add('active');
      renderEpisodes(eps[sn]);
    });
    tabsEl.appendChild(btn);
  });
  renderEpisodes(eps[seasons[0]]);
}

function renderEpisodes(list) {
  const el = $('sd-episodes');
  el.innerHTML = '';
  if (!list || !list.length) { el.innerHTML = '<div style="text-align:center;padding:20px;color:#888">لا توجد حلقات</div>'; return; }
  const s = getActive();
  list.forEach(ep => {
    const item = document.createElement('div');
    item.className = 'episode-item';
    const num = document.createElement('div');
    num.className = 'episode-num'; num.textContent = ep.episode_num || '?';
    const info = document.createElement('div');
    info.className = 'episode-info';
    const t = document.createElement('div');
    t.className = 'episode-title'; t.textContent = ep.title || ('حلقة ' + ep.episode_num);
    const d = document.createElement('div');
    d.className = 'episode-duration'; d.textContent = (ep.info && ep.info.duration) || '';
    info.appendChild(t); info.appendChild(d);
    item.appendChild(num); item.appendChild(info);
    item.addEventListener('click', () => {
      const ext = ep.container_extension || 'mp4';
      const u = s.cred.host + '/series/' + s.cred.user + '/' + s.cred.pass + '/' + ep.id + '.' + ext;
      $('modal-series').classList.add('hidden');
      playItem([ep.title || 'حلقة', u, '', ''], 'series');
    });
    el.appendChild(item);
  });
}
function showError(m) {
  $('info-bar').textContent = m;
  $('info-bar').classList.add('show');
  setTimeout(() => $('info-bar').classList.remove('show'), 4000);
}

function playItem(ch, fromView) {
  const url = ch[1], name = ch[0];
  if (!url) return showError('لا يوجد رابط');
  if (fromView !== 'live') showView('live');
  currentChannelUrl = url;
  $('overlay').style.display = 'none';
  $('info-bar').textContent = name;
  $('info-bar').classList.add('show');
  const isAndroid = /android/i.test(navigator.userAgent);
  const isWebOS = /web0s|webos/i.test(navigator.userAgent);
  if (isAndroid && !isWebOS) {
    try {
      let scheme, cleanUrl;
      if (url.startsWith('https://')) { scheme = 'https'; cleanUrl = url.substring(8); }
      else { scheme = 'http'; cleanUrl = url.substring(7); }
      window.location.href = 'intent://' + cleanUrl + '#Intent;scheme=' + scheme + ';package=org.videolan.vlc;type=video/*;S.title=' + encodeURIComponent(name) + ';end';
      setTimeout(() => $('info-bar').classList.remove('show'), 2500);
    } catch(e) { showError('فشل VLC: ' + e.message); }
    return;
  }
  if (hls) { hls.destroy(); hls = null; }
  if (mpegtsPlayer) { mpegtsPlayer.destroy(); mpegtsPlayer = null; }
  const video = $('player');
  video.removeAttribute('src'); video.load();
  const purl = proxy(url);
  if (url.includes('.m3u8') && typeof Hls !== 'undefined' && Hls.isSupported()) {
    hls = new Hls({ enableWorker: false, maxBufferLength: 30 });
    hls.loadSource(purl); hls.attachMedia(video);
    hls.on(Hls.Events.MANIFEST_PARSED, () => video.play().catch(() => {}));
    hls.on(Hls.Events.ERROR, (e, d) => { if (d.fatal) showError('خطأ HLS'); });
  } else if (typeof mpegts !== 'undefined' && mpegts.isSupported()) {
    mpegtsPlayer = mpegts.createPlayer({ type: 'mpegts', isLive: true, url: purl }, { enableWorker: false });
    mpegtsPlayer.attachMediaElement(video);
    mpegtsPlayer.load();
    mpegtsPlayer.play().catch(() => {});
  } else {
    video.src = purl;
    video.play().catch(e => showError('فشل: ' + e.message));
  }
  setTimeout(() => $('info-bar').classList.remove('show'), 3000);
}
$('live-search').addEventListener('input', renderLive);
$('live-category').addEventListener('change', renderLive);
$('live-fav-btn').addEventListener('click', () => {
  showFavsOnly.live = !showFavsOnly.live;
  $('live-fav-btn').classList.toggle('active', showFavsOnly.live);
  renderLive();
});
$('movie-search').addEventListener('input', debounce(renderMovies, 300));
$('movie-category').addEventListener('change', renderMovies);
$('movie-fav-btn').addEventListener('click', () => {
  showFavsOnly.movie = !showFavsOnly.movie;
  $('movie-fav-btn').classList.toggle('active', showFavsOnly.movie);
  renderMovies();
});
$('series-search').addEventListener('input', debounce(renderSeries, 300));
$('series-category').addEventListener('change', renderSeries);
$('series-fav-btn').addEventListener('click', () => {
  showFavsOnly.series = !showFavsOnly.series;
  $('series-fav-btn').classList.toggle('active', showFavsOnly.series);
  renderSeries();
});

function openModal() { $('modal').classList.remove('hidden'); $('modal-msg').textContent = ''; }
function setMsg(t, c) { $('modal-msg').textContent = t; $('modal-msg').style.color = c || '#fff'; }
document.querySelectorAll('[data-close]').forEach(b => {
  b.addEventListener('click', () => $(b.dataset.close).classList.add('hidden'));
});
document.querySelectorAll('.sub-tab').forEach(t => {
  t.addEventListener('click', () => {
    document.querySelectorAll('.sub-tab').forEach(x => x.classList.remove('active'));
    document.querySelectorAll('.tab-panel').forEach(x => x.classList.remove('active'));
    t.classList.add('active');
    $(t.dataset.target).classList.add('active');
  });
});
$('toggle-pass').addEventListener('click', () => {
  const p = $('xt-pass');
  p.type = p.type === 'password' ? 'text' : 'password';
});
function newSubId() { return 'sub_' + Date.now() + '_' + Math.random().toString(36).slice(2, 7); }
function getSubName(fb) { return $('sub-name-input').value.trim() || fb; }
function clearModalForm() {
  ['sub-name-input','m3u-url','xt-host','xt-user','xt-pass','pt-url','pt-mac'].forEach(id => $(id).value = '');
}
async function addSub(type, cred, fb) {
  const id = newSubId();
  const name = getSubName(fb);
  subs.push({ id, name, type, cred, userInfo: null, createdAt: Date.now() });
  await saveSubs();
  activeId = id;
  await idbSet('meta', 'activeId', id);
  return id;
}
function parseM3U(text) {
  const lines = text.split(/\r?\n/);
  const live = [];
  let cur = {};
  for (const raw of lines) {
    const line = raw.trim();
    if (line.startsWith('#EXTINF:')) {
      const name = (line.match(/,(.+)$/) || [null, 'قناة'])[1].trim();
      const group = (line.match(/group-title="([^"]*)"/) || [null, 'عام'])[1] || 'عام';
      const logo = (line.match(/tvg-logo="([^"]*)"/) || [null, ''])[1];
      cur = { name, group, logo };
    } else if (line.startsWith('http') && cur.name) {
      live.push([cur.name, line, cur.group, cur.logo]);
      cur = {};
    }
  }
  return { live, movie: [], series: [] };
}

$('btn-m3u-url').addEventListener('click', async () => {
  const u = $('m3u-url').value.trim();
  if (!u) return setMsg('أدخل الرابط', '#ff6b6b');
  setMsg('جاري التحميل...', '#fbbf24');
  try {
    const r = await fetch(proxy(u));
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const data = parseM3U(await r.text());
    const id = await addSub('m3u-url', { url: u }, 'M3U');
    await savePlaylist(id, data);
    setMsg('تم ✓', '#22c55e');
    setTimeout(() => { $('modal').classList.add('hidden'); clearModalForm(); }, 800);
    await refreshAll();
  } catch(e) { setMsg('فشل: ' + e.message, '#ff6b6b'); }
});
$('btn-m3u-file').addEventListener('click', async () => {
  const f = $('m3u-file').files[0];
  if (!f) return setMsg('اختر ملفاً', '#ff6b6b');
  const reader = new FileReader();
  reader.onload = async e => {
    try {
      const data = parseM3U(e.target.result);
      const id = await addSub('m3u-file', {}, 'ملف: ' + f.name);
      await savePlaylist(id, data);
      setMsg('تم ✓', '#22c55e');
      setTimeout(() => { $('modal').classList.add('hidden'); clearModalForm(); }, 800);
      await refreshAll();
    } catch(err) { setMsg('فشل: ' + err.message, '#ff6b6b'); }
  };
  reader.readAsText(f);
});
$('btn-xtream').addEventListener('click', async () => {
  let host = $('xt-host').value.trim().replace(/\/+$/, '');
  if (!/^https?:\/\//i.test(host)) host = 'http://' + host;
  const user = $('xt-user').value.trim();
  const pass = $('xt-pass').value.trim();
  if (!host || !user || !pass) return setMsg('املأ الحقول', '#ff6b6b');

  setMsg('🔍 جاري التحقق من البيانات...', '#fbbf24');
  try {
    const auth = await fetch(proxy(host + '/player_api.php?username=' + user + '&password=' + pass)).then(r => r.json());
    if (!auth.user_info || auth.user_info.auth === 0) throw new Error('بيانات غير صحيحة');

    const base = host + '/player_api.php?username=' + user + '&password=' + pass + '&action=';
    const id = await addSub('xtream', { host, user, pass }, user + '@' + new URL(host).hostname);
    const sub = subs.find(s => s.id === id);
    sub.userInfo = auth.user_info;
    await saveSubs();

    const data = { live: [], movie: [], series: [] };
    await savePlaylist(id, data);

    // ===== 1. القنوات المباشرة (الأولوية) =====
    setMsg('📡 جاري تحميل القنوات...', '#fbbf24');
    try {
      const [live, lc] = await Promise.all([
        fetch(proxy(base + 'get_live_streams')).then(r => r.json()).catch(() => []),
        fetch(proxy(base + 'get_live_categories')).then(r => r.json()).catch(() => [])
      ]);
      const lm = {};
      (lc || []).forEach(c => lm[c.category_id] = c.category_name);
      data.live = (live || []).map(s => [
        s.name || 'قناة',
        host + '/live/' + user + '/' + pass + '/' + s.stream_id + '.ts',
        lm[s.category_id] || 'عام',
        s.stream_icon || ''
      ]);
      await savePlaylist(id, data);
      await refreshAll();
      setMsg('📡 القنوات جاهزة (' + data.live.length + ') — جاري تحميل الأفلام...', '#22c55e');
    } catch(e) { console.warn('Live load:', e); }

    // ===== 2. الأفلام =====
    try {
      const [vod, vc] = await Promise.all([
        fetch(proxy(base + 'get_vod_streams')).then(r => r.json()).catch(() => []),
        fetch(proxy(base + 'get_vod_categories')).then(r => r.json()).catch(() => [])
      ]);
      const vm = {};
      (vc || []).forEach(c => vm[c.category_id] = c.category_name);
      data.movie = (vod || []).map(s => [
        s.name || 'فيلم',
        host + '/movie/' + user + '/' + pass + '/' + s.stream_id + '.' + (s.container_extension || 'mp4'),
        vm[s.category_id] || 'عام',
        s.stream_icon || s.movie_image || ''
      ]);
      await savePlaylist(id, data);
      await refreshAll();
      setMsg('🎬 الأفلام جاهزة (' + data.movie.length + ') — جاري تحميل المسلسلات...', '#22c55e');
    } catch(e) { console.warn('VOD load:', e); }

    // ===== 3. المسلسلات =====
    try {
      const [ser, sc] = await Promise.all([
        fetch(proxy(base + 'get_series')).then(r => r.json()).catch(() => []),
        fetch(proxy(base + 'get_series_categories')).then(r => r.json()).catch(() => [])
      ]);
      const sm = {};
      (sc || []).forEach(c => sm[c.category_id] = c.category_name);
      data.series = (ser || []).map(s => [
        s.name || 'مسلسل',
        '',
        sm[s.category_id] || 'عام',
        s.cover || '',
        s.series_id
      ]);
      await savePlaylist(id, data);
      await refreshAll();
    } catch(e) { console.warn('Series load:', e); }

    setMsg('✅ اكتمل التحميل: ' + data.live.length + ' قناة، ' + data.movie.length + ' فيلم، ' + data.series.length + ' مسلسل', '#22c55e');
    setTimeout(() => { $('modal').classList.add('hidden'); clearModalForm(); }, 1500);
    await refreshAll();
  } catch(e) { setMsg('فشل: ' + e.message, '#ff6b6b'); }
});

$('btn-portal').addEventListener('click', async () => {
  const u = $('pt-url').value.trim(), mac = $('pt-mac').value.trim();
  if (!u || !mac) return setMsg('أدخل الرابط و MAC', '#ff6b6b');
  setMsg('Portal يحتاج معالجة خاصة', '#f59e0b');
  const id = await addSub('portal', { url: u, mac }, 'Portal');
  await savePlaylist(id, { live: [], movie: [], series: [] });
  await refreshAll();
  setTimeout(() => { $('modal').classList.add('hidden'); clearModalForm(); }, 800);
});

async function refreshAll() {
  await loadSubs();
  await loadCurrentPlaylist();
  updateSubDisplay();
  populateCat($('live-category'), 'live');
  populateCat($('movie-category'), 'movie');
  populateCat($('series-category'), 'series');
  renderLive(); renderMovies(); renderSeries();
  renderSubsList();
}
$('btn-user').addEventListener('click', e => {
  e.stopPropagation();
  $('user-menu').classList.toggle('hidden');
  updateSubDisplay();
});
document.addEventListener('click', e => {
  const um = $('user-menu');
  if (!um.contains(e.target) && e.target !== $('btn-user')) um.classList.add('hidden');
});
$('um-add').addEventListener('click', () => { $('user-menu').classList.add('hidden'); openModal(); });
$('um-switch').addEventListener('click', () => { $('user-menu').classList.add('hidden'); showSwitch(); });
$('um-edit').addEventListener('click', async () => {
  $('user-menu').classList.add('hidden');
  if (!getActive()) return alert('لا يوجد اشتراك');
  if (!confirm('سيتم حذف الاشتراك الحالي لإعادة إضافته. متابعة؟')) return;
  await removeActive();
  setTimeout(openModal, 300);
});
$('um-delete').addEventListener('click', async () => {
  $('user-menu').classList.add('hidden');
  if (!getActive()) return alert('لا يوجد اشتراك');
  if (!confirm('حذف الاشتراك الحالي؟')) return;
  await removeActive();
});

async function removeActive() {
  if (!activeId) return;
  subs = subs.filter(s => s.id !== activeId);
  await saveSubs();
  await idbDel('playlists', activeId);
  delete playlists[activeId];
  activeId = subs[0]?.id || null;
  await idbSet('meta', 'activeId', activeId);
  await refreshAll();
  updateSubDisplay();
}

function showSwitch() {
  const list = $('switch-list');
  list.innerHTML = '';
  if (!subs.length) list.innerHTML = '<li style="cursor:default">لا توجد اشتراكات</li>';
  else subs.forEach(s => {
    const li = document.createElement('li');
    if (s.id === activeId) li.classList.add('active');
    const n = document.createElement('span');
    n.className = 'sl-name'; n.textContent = s.name;
    const d = document.createElement('span');
    d.className = 'sl-days';
    const dd = calcDays(s);
    d.textContent = dd === null ? '✓' : (dd > 0 ? dd + ' يوم' : 'منتهي');
    li.appendChild(n); li.appendChild(d);
    li.addEventListener('click', async () => {
      activeId = s.id;
      await idbSet('meta', 'activeId', activeId);
      await loadCurrentPlaylist();
      await refreshAll();
      updateSubDisplay();
      $('modal-switch').classList.add('hidden');
    });
    list.appendChild(li);
  });
  $('modal-switch').classList.remove('hidden');
}

function renderSubsList() {
  const ul = $('subs-list');
  if (!ul) return;
  ul.innerHTML = '';
  if (!subs.length) {
    ul.innerHTML = '<li style="cursor:default;justify-content:center;color:#666">لا توجد اشتراكات</li>';
    return;
  }
  subs.forEach(s => {
    const li = document.createElement('li');
    if (s.id === activeId) li.classList.add('active-sub');
    const l = document.createElement('div');
    l.className = 'sub-li-name'; l.textContent = s.name;
    const r = document.createElement('div');
    const dd = calcDays(s);
    r.textContent = dd === null ? '' : (dd > 0 ? dd + ' يوم' : 'منتهي');
    r.style.fontSize = '12px';
    r.style.color = dd !== null && dd > 0 ? '#a970ff' : '#888';
    li.appendChild(l); li.appendChild(r);
    if (s.id !== activeId) {
      const ub = document.createElement('button');
      ub.className = 'btn-use'; ub.textContent = 'استخدام';
      ub.addEventListener('click', async e => {
        e.stopPropagation();
        activeId = s.id;
        await idbSet('meta', 'activeId', activeId);
        await loadCurrentPlaylist();
        await refreshAll();
        updateSubDisplay();
      });
      li.appendChild(ub);
    } else {
      const b = document.createElement('span');
      b.textContent = '✓ نشط'; b.style.color = '#22c55e';
      b.style.fontSize = '12px'; b.style.fontWeight = '700';
      li.appendChild(b);
    }
    const rm = document.createElement('button');
    rm.className = 'btn-remove-sub'; rm.textContent = '🗑️';
    rm.addEventListener('click', async e => {
      e.stopPropagation();
      if (!confirm('حذف "' + s.name + '"؟')) return;
      subs = subs.filter(x => x.id !== s.id);
      await saveSubs();
      await idbDel('playlists', s.id);
      delete playlists[s.id];
      if (activeId === s.id) {
        activeId = subs[0]?.id || null;
        await idbSet('meta', 'activeId', activeId);
      }
      await refreshAll(); updateSubDisplay();
    });
    li.appendChild(rm);
    ul.appendChild(li);
  });
}
$('btn-add-new').addEventListener('click', openModal);
const LOCK = $('lock-screen');
let devUnlocked = localStorage.getItem(DEV_KEY) === 'yes';
function lockApp(msg, sub, wa, expDate) {
  if (devUnlocked) { LOCK.classList.add('hidden'); return; }
  LOCK.classList.remove('hidden');
  if (msg) $('lock-msg').textContent = msg;
  if (sub) $('lock-sub').textContent = sub;
  const waBtn = $('lock-wa');
  if (wa) waBtn.href = 'https://wa.me/' + wa + '?text=' + encodeURIComponent('مرحباً، أريد تجديد اشتراك تطبيقي');
  else waBtn.style.display = 'none';
  if (expDate) $('lock-date').textContent = '📅 ' + expDate;
}
async function checkLicense() {
  const URL = 'https://raw.githubusercontent.com/imadkrouni20/iptv-player/main/license.json';
  try {
    const lic = await fetch(URL + '?t=' + Date.now()).then(r => r.json());
    localStorage.setItem(APP + 'license_cache', JSON.stringify(lic));
    if (!lic.enabled) return;
    if (lic.expires) {
      const exp = new Date(lic.expires + 'T23:59:59').getTime();
      if (Date.now() > exp) return lockApp(lic.message, lic.sub_message, lic.whatsapp, lic.expires);
    }
    if (lic.check_local && lic.trial_days) {
      let f = localStorage.getItem(APP + 'first_launch');
      if (!f) { f = Date.now().toString(); localStorage.setItem(APP + 'first_launch', f); }
      if (Math.floor((Date.now() - parseInt(f)) / 86400000) >= lic.trial_days) {
        return lockApp(lic.message, lic.sub_message, lic.whatsapp, lic.expires);
      }
    }
  } catch(e) {
    const c = localStorage.getItem(APP + 'license_cache');
    if (c) {
      const lic = JSON.parse(c);
      if (lic.expires) {
        const exp = new Date(lic.expires + 'T23:59:59').getTime();
        if (Date.now() > exp) return lockApp(lic.message, lic.sub_message, lic.whatsapp, lic.expires);
      }
    }
  }
}
const lockIcon = LOCK.querySelector('.lock-icon');
if (lockIcon) {
  let timer = null;
  const start = () => { timer = setTimeout(() => {
    const pwd = prompt('🔐 كلمة سر المطور:');
    if (pwd === DEV_PASSWORD) {
      devUnlocked = true;
      localStorage.setItem(DEV_KEY, 'yes');
      LOCK.classList.add('hidden');
      alert('✓ تم الفتح');
    } else if (pwd !== null) alert('✗ كلمة خاطئة');
  }, 1500); };
  const cancel = () => { if (timer) clearTimeout(timer); };
  ['mousedown','touchstart'].forEach(e => lockIcon.addEventListener(e, start));
  ['mouseup','touchend','mouseleave','touchcancel'].forEach(e => lockIcon.addEventListener(e, cancel));
}


function debounce(fn, wait) {
  let t;
  return function(...args) {
    clearTimeout(t);
    t = setTimeout(() => fn.apply(this, args), wait);
  };
}

async function init() {
  await openDB();
  await loadSubs();
  await loadCurrentPlaylist();
  updateSubDisplay();
  populateCat($('live-category'), 'live');
  populateCat($('movie-category'), 'movie');
  populateCat($('series-category'), 'series');
  renderLive(); renderMovies(); renderSeries();
  renderSubsList();
  checkLicense();
  for (const s of subs) {
    if (s.type === 'xtream' && s.cred.host) {
      try {
        const auth = await fetch(proxy(s.cred.host + '/player_api.php?username=' + s.cred.user + '&password=' + s.cred.pass)).then(r => r.json());
        if (auth.user_info) s.userInfo = auth.user_info;
      } catch(e) {}
    }
  }
  await saveSubs();
  updateSubDisplay();
  renderSubsList();
}
init();
});
