'use strict';

const canvas = document.getElementById('graph');
const ctx = canvas.getContext('2d');
const form = document.getElementById('point-form');
const inputs = document.getElementById('inputs');
const yInput = document.getElementById('y');
const rInput = document.getElementById('r');
const clearButton = document.getElementById('clear');
const status = document.getElementById('status');

const ALLOWED_X = [-5, -4, -3, -2, -1, 0, 1, 2, 3];
const ALLOWED_R = [1, 2, 3, 4, 5];
const DECIMAL = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/;

let points = [];
let graphPoints = [];

function validY(text) {
    if (text.length > 64 || !DECIMAL.test(text)) {
        return false;
    }

    const whole = text.replace(/^[+-]/, '').split('.')[0];
    const limit = text.startsWith('-') ? 3 : 5;
    return Number(whole) < limit;
}

function draw() {
    const r = Number(rInput.value);
    const center = canvas.width / 2;
    const scale = 40;

    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = '#fafafa';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.save();
    ctx.translate(center, center);

    ctx.fillStyle = '#4a90e2';
    ctx.fillRect(0, -r * scale / 2, r * scale, r * scale / 2);

    ctx.beginPath();
    ctx.moveTo(0, 0);
    ctx.arc(0, 0, r * scale, Math.PI, 1.5 * Math.PI);
    ctx.closePath();
    ctx.fill();

    ctx.beginPath();
    ctx.moveTo(0, 0);
    ctx.lineTo(-r * scale, 0);
    ctx.lineTo(0, r * scale / 2);
    ctx.closePath();
    ctx.fill();

    ctx.strokeStyle = '#222';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(-225, 0);
    ctx.lineTo(225, 0);
    ctx.moveTo(0, 225);
    ctx.lineTo(0, -225);
    ctx.moveTo(225, 0);
    ctx.lineTo(215, -5);
    ctx.moveTo(225, 0);
    ctx.lineTo(215, 5);
    ctx.moveTo(0, -225);
    ctx.lineTo(-5, -215);
    ctx.moveTo(0, -225);
    ctx.lineTo(5, -215);
    ctx.stroke();
    ctx.font = '14px Arial';
    ctx.fillStyle = '#222';
    ctx.fillText('X', 230, 18);
    ctx.fillText('Y', 8, -230);

    for (const value of [-r, -r / 2, r / 2, r]) {
        ctx.beginPath();
        ctx.moveTo(value * scale, -4);
        ctx.lineTo(value * scale, 4);
        ctx.moveTo(-4, -value * scale);
        ctx.lineTo(4, -value * scale);
        ctx.stroke();
        ctx.textAlign = 'center';
        ctx.fillText(String(value), value * scale, 18);
        ctx.textAlign = 'left';
        ctx.fillText(String(value), 8, -value * scale + 5);
    }

    for (let i = graphPoints.length - 1; i >= 0; i--) {
        const point = graphPoints[i];
        const hit = point.hits[r - 1];
        ctx.beginPath();
        ctx.arc(point.x * scale, -Number(point.y) * scale, 5, 0, Math.PI * 2);
        ctx.fillStyle = hit ? '#16752d' : '#c62828';
        ctx.fill();
        ctx.strokeStyle = 'white';
        ctx.stroke();
    }
    ctx.restore();
}

function showHistory() {
    const table = document.getElementById('results');
    table.replaceChildren();

    if (points.length === 0) {
        const cell = table.insertRow().insertCell();
        cell.colSpan = 6;
        cell.textContent = 'Проверок пока нет';
    }

    for (const point of points) {
        const row = table.insertRow();
        row.insertCell().textContent = point.x;
        row.insertCell().textContent = point.y;
        row.insertCell().textContent = point.r;
        const result = row.insertCell();
        result.textContent = point.hit ? 'Попадание' : 'Промах';
        result.className = point.hit ? 'hit' : 'miss';
        row.insertCell().textContent = new Date(point.time).toLocaleString();
        row.insertCell().textContent = Number(point.elapsedMs).toFixed(6);
    }
    draw();
}

async function sendRequest(url, method = 'GET', restoreRadius = false, count = 1) {
    if (inputs.disabled) {
        return;
    }

    inputs.disabled = true;
    clearButton.disabled = true;
    status.className = '';
    status.textContent = 'Ожидание ответа сервера…';

    try {
        const response = await fetch(url, { method: method, cache: 'no-store' });
        const data = await response.json();
        if (!response.ok) {
            throw new Error(data.error || 'Ошибка сервера');
        }
        points = data.results;
        graphPoints = points.slice(0, count);
        if (restoreRadius && points.length > 0 && ALLOWED_R.includes(points[0].r)) {
            rInput.value = String(points[0].r);
        }
        showHistory();
        status.textContent = 'Готово.';
    } catch (error) {
        status.className = 'request-error';
        status.textContent = 'Ошибка запроса: ' + error.message;
    } finally {
        inputs.disabled = false;
        clearButton.disabled = false;
    }
}

form.addEventListener('submit', event => {
    event.preventDefault();
    if (inputs.disabled) {
        return;
    }
    const xs = [];
    for (const checkbox of form.querySelectorAll('input[name="x"]:checked')) {
        xs.push(Number(checkbox.value));
    }
    const yText = yInput.value.trim().replace(',', '.');
    const r = Number(rInput.value);

    const xError = xs.length > 0 && xs.length <= 9 && new Set(xs).size === xs.length
        && xs.every(x => ALLOWED_X.includes(x))
        ? '' : 'Выберите разные значения X от −5 до 3.';
    const yError = validY(yText)
        ? '' : 'Введите число строго между −3 и 5 (до 64 символов).';
    const rError = ALLOWED_R.includes(r)
        ? '' : 'Выберите R от 1 до 5.';

    document.getElementById('x-error').textContent = xError;
    document.getElementById('y-error').textContent = yError;
    document.getElementById('r-error').textContent = rError;
    yInput.setAttribute('aria-invalid', String(Boolean(yError)));
    rInput.setAttribute('aria-invalid', String(Boolean(rError)));

    if (xError || yError || rError) {
        return;
    }

    const query = new URLSearchParams();
    for (const x of xs) {
        query.append('x', x);
    }
    query.set('y', yText);
    query.set('r', String(r));
    sendRequest('api/check?' + query, 'GET', false, xs.length);
});

form.addEventListener('input', event => {
    const error = document.getElementById(event.target.name + '-error');
    if (error) {
        error.textContent = '';
        event.target.removeAttribute('aria-invalid');
    }
});

rInput.addEventListener('change', draw);

clearButton.addEventListener('click', () => {
    sendRequest('api/history', 'DELETE');
});
draw();
sendRequest('api/history', 'GET', true);
