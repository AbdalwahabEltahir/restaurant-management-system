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
  return Number(value).toFixed(2);
}

function orderTotal(order) {
  return order.items.reduce((sum, item) => sum + Number(item.unitPrice) * item.quantity, 0);
}

function timeText(value) {
  const date = new Date(value);
  return isNaN(date) ? '' : date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
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