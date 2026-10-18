// Small helpers used by the guest page and the staff page.

// Builds an element. Text is set with textContent, so anything a guest types
// (like a note on an order) is shown as plain text and can never run as HTML.
function el(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined && text !== null) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function money(value) {
  return Number(value).toFixed(2) + ' QAR';
}

function orderTotal(order) {
  return order.items.reduce((sum, item) => sum + Number(item.unitPrice) * item.quantity, 0);
}

function timeText(value) {
  const date = new Date(value);
  return isNaN(date) ? '' : date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

// A page header banner: a small illustration with a short quote beside it. The
// "modifier" class picks the image (see style.css's .banner-* rules).
function pageBanner(modifier, quote) {
  const banner = el('div', null, 'page-banner ' + modifier);
  banner.append(el('div', null, 'page-banner-image'));
  const quoteBox = el('div', null, 'page-banner-quote');
  quoteBox.append(el('span', null, 'fa-solid fa-quote-left'), el('span', quote));
  banner.append(quoteBox);
  return banner;
}

// Mirrors CustomerService's discount ladder, so the guest/staff pages can preview the
// real discount before checkout. The server always recalculates and enforces the
// actual amount — this is display only.
const LOYALTY_DISCOUNT_TIERS = [
  { points: 2000, rate: 0.40 },
  { points: 1000, rate: 0.25 },
  { points: 500, rate: 0.15 }
];

function loyaltyDiscountForPoints(points) {
  return LOYALTY_DISCOUNT_TIERS.find(t => points >= t.points) || null;
}

function minutesSince(value) {
  const date = new Date(value);
  return isNaN(date) ? 0 : Math.max(0, Math.round((Date.now() - date.getTime()) / 60000));
}

// A richer "nothing here yet" message: an icon above a line of muted text,
// used wherever a list or panel has nothing to show.
function emptyState(icon, message) {
  const box = el('div', null, 'empty-state');
  const i = el('span', null, 'fa-solid ' + icon);
  box.append(i, el('p', message, 'muted'));
  return box;
}

// A small spinner + message, shown while a panel's data is being fetched.
function loadingState(message) {
  const box = el('div', null, 'empty-state loading-state');
  const i = el('span', null, 'fa-solid fa-spinner fa-spin');
  box.append(i, el('p', message || 'Loading...', 'muted'));
  return box;
}

// Points an <img> at /images/<dish name>.<ext>, trying each likely extension in
// turn (the images folder has mixed .jpg/.jpeg/.webp). Falls back to a plain
// placeholder box if no matching file exists, so a dish with no photo still works.
const DISH_IMAGE_EXTENSIONS = ['jpg', 'jpeg', 'png', 'webp'];

function dishImage(name) {
  const wrap = el('div', null, 'dish-image');
  const img = el('img');
  img.alt = name;
  wrap.append(img);
  let i = 0;
  const tryNext = () => {
    if (i >= DISH_IMAGE_EXTENSIONS.length) {
      wrap.classList.add('no-image');
      img.remove();
      return;
    }
    img.src = '/images/' + encodeURIComponent(name) + '.' + DISH_IMAGE_EXTENSIONS[i++];
  };
  img.onerror = tryNext;
  tryNext();
  return wrap;
}

// Builds a row of small stat tiles (e.g. "12 Orders served today"). Purely visual —
// takes values the caller already has, makes no calls of its own.
function statsRow(tiles) {
  const row = el('div', null, 'stats-row');
  for (const tile of tiles) {
    const box = el('div', null, 'stat-tile' + (tile.accent ? ' accent' : ''));
    const icon = el('span', null, 'stat-icon');
    if (tile.icon) icon.innerHTML = '<i class="fa-solid ' + tile.icon + '"></i>';
    const text = el('div');
    text.append(el('div', String(tile.value), 'stat-value'), el('div', tile.label, 'stat-label'));
    box.append(icon, text);
    row.append(box);
  }
  return row;
}

// Turns "username:password" into the text the Authorization header needs.
function basicToken(username, password) {
  const bytes = new TextEncoder().encode(username + ':' + password);
  let binary = '';
  bytes.forEach(b => { binary += String.fromCharCode(b); });
  return btoa(binary);
}

// Calls the API and returns the JSON answer. If the server says no, it throws an
// Error with the server's message and the HTTP status. "auth" is the Basic token
// for staff and admin calls; guests leave it out.
async function api(path, method = 'GET', body = null, auth = null) {
  const headers = {};
  if (body !== null) headers['Content-Type'] = 'application/json';
  if (auth) headers['Authorization'] = 'Basic ' + auth;

  const response = await fetch(path, {
    method: method,
    headers: headers,
    body: body !== null ? JSON.stringify(body) : undefined
  });

  const text = await response.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }

  if (!response.ok) {
    const error = new Error((data && data.message) || 'Something went wrong (' + response.status + ')');
    error.status = response.status;
    throw error;
  }
  return data;
}