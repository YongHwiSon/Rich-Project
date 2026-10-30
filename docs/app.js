// data.json 을 읽어 카드(모바일)와 표(데스크톱)를 그린다. 프레임워크 없음.
// 제목/링크는 외부에서 온 값이므로 innerHTML 은 쓰지 않고 textContent 로만 넣는다.
(function () {
  'use strict';

  var UNIT_LABEL = { per_item: '개당', per_100g: '100g당', per_100ml: '100ml당' };
  var UNIT_ORDER = { per_100g: 0, per_100ml: 1, per_item: 2 };
  var SORTS = [
    { id: 'vs', label: '평소가 대비 싼 순' },
    { id: 'discount', label: '할인율 높은 순' },
    { id: 'unit', label: '단가 낮은 순' },
    { id: 'recent', label: '최신순' }
  ];
  // 제휴(수익) 링크가 생기면 'sponsored noopener noreferrer' 로 바꾼다.
  var REL_COMMUNITY = 'noopener noreferrer';

  var state = { data: null, tab: 'all', sort: 'vs' };

  function el(tag, props) {
    var node = document.createElement(tag);
    if (props) {
      Object.keys(props).forEach(function (k) {
        if (k === 'class') node.className = props[k];
        else if (k === 'text') node.textContent = props[k];
        else node.setAttribute(k, props[k]);
      });
    }
    for (var i = 2; i < arguments.length; i++) {
      var child = arguments[i];
      if (child == null || child === false) continue;
      node.appendChild(typeof child === 'string' ? document.createTextNode(child) : child);
    }
    return node;
  }

  function won(n) { return n.toLocaleString('ko-KR') + '원'; }

  function unitText(v) {
    // 소수는 한 자리까지, 정수면 정수로
    var r = Math.round(v * 10) / 10;
    return r.toLocaleString('ko-KR', { maximumFractionDigits: 1 }) + '원';
  }

  function isHttp(url) { return typeof url === 'string' && /^https?:\/\//i.test(url); }

  function fmtTime(iso) {
    var d = new Date(iso);
    if (isNaN(d)) return iso;
    return (d.getMonth() + 1) + '/' + d.getDate() + ' ' +
      String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  }

  // ---- 데이터 ----
  function allItems() {
    var out = [];
    state.data.sources.forEach(function (s) {
      s.items.forEach(function (it) { out.push({ item: it, sourceName: s.name, sourceId: s.id }); });
    });
    return out;
  }

  function visibleItems() {
    var list = allItems().filter(function (x) { return state.tab === 'all' || x.sourceId === state.tab; });
    var nullLast = function (a, b, dir) {
      if (a == null && b == null) return 0;
      if (a == null) return 1;
      if (b == null) return -1;
      return (a - b) * dir;
    };
    var cmp = {
      vs: function (a, b) { return nullLast(a.item.vsUsualPct, b.item.vsUsualPct, 1); },
      discount: function (a, b) { return nullLast(a.item.discountRate, b.item.discountRate, -1); },
      unit: function (a, b) {
        var ua = a.item.unit, ub = b.item.unit;
        if (!ua.parsed && !ub.parsed) return 0;
        if (!ua.parsed) return 1;
        if (!ub.parsed) return -1;
        if (ua.type !== ub.type) return UNIT_ORDER[ua.type] - UNIT_ORDER[ub.type];
        return ua.value - ub.value;
      },
      recent: function (a, b) { return String(b.item.collectedAt).localeCompare(String(a.item.collectedAt)); }
    }[state.sort];
    return list.sort(cmp);
  }

  // ---- 조각 ----
  function vsBadge(it) {
    if (it.vsUsualPct == null) return el('span', { class: 'badge', text: '평소가 이력 부족' });
    var p = it.vsUsualPct;
    var cls = p < 0 ? 'badge good' : p > 0 ? 'badge bad' : 'badge';
    var text = '평소가 대비 ' + (p > 0 ? '+' : '') + p + '%';
    return el('span', { class: cls, text: text, title: '최근 30일 평균 ' + won(it.usualPrice.avg30d) + ' (' + it.usualPrice.daysOfData + '일 데이터)' });
  }

  // compact=true(표)에서는 버튼 이름을 줄여 한 줄에 들어가게 한다.
  function linkButtons(it, compact) {
    var wrap = el('div', { class: 'links' });
    if (isHttp(it.url)) {
      wrap.appendChild(el('a', { class: 'btn primary', href: it.url, target: '_blank', rel: REL_COMMUNITY, text: compact ? '원문' : '원문 보기' }));
    } else {
      wrap.appendChild(el('span', { class: 'btn primary', 'aria-disabled': 'true', text: compact ? '원문' : '원문 없음(샘플)' }));
    }
    (it.compareLinks || []).forEach(function (l) {
      if (!isHttp(l.url)) return;
      wrap.appendChild(el('a', {
        class: 'btn', href: l.url, target: '_blank', rel: 'noopener noreferrer',
        title: l.mall + '에서 같은 상품 찾아보기',
        text: compact ? l.mall.replace('쇼핑', '') : l.mall + '에서 비교'
      }));
    });
    return wrap;
  }

  function card(x) {
    var it = x.item;
    var priceRow = el('div', { class: 'price-row' },
      el('span', { class: 'price', text: won(it.price) }),
      it.originalPrice ? el('span', { class: 'orig', text: won(it.originalPrice) }) : null,
      it.discountRate != null ? el('span', { class: 'pill', text: it.discountRate + '%' }) : null);
    var unitLine = el('div', { class: 'unit' });
    if (it.unit.parsed) {
      unitLine.appendChild(document.createTextNode(UNIT_LABEL[it.unit.type] + ' '));
      unitLine.appendChild(el('strong', { text: unitText(it.unit.value) }));
    } else {
      unitLine.appendChild(el('span', { class: 'na', text: '단가 계산 불가 (제목에서 수량/용량을 읽지 못함)' }));
    }
    var rating = it.rating != null
      ? el('div', { class: 'rating', text: '★ ' + it.rating + (it.reviewCount != null ? ' (' + it.reviewCount.toLocaleString('ko-KR') + ')' : '') })
      : null;

    return el('li', { class: 'card' },
      el('div', { class: 'meta' }, el('span', { class: 'src-tag', text: x.sourceName }), el('span', { text: fmtTime(it.collectedAt) + ' 수집' })),
      el('h2', { class: 'title', text: it.title }),
      priceRow, unitLine, rating,
      el('div', { class: 'vs-row' }, vsBadge(it)),
      linkButtons(it, false));
  }

  function row(x) {
    var it = x.item;
    var unitCell = el('td', { class: 'num' });
    if (it.unit.parsed) {
      unitCell.appendChild(el('strong', { text: unitText(it.unit.value) }));
      unitCell.appendChild(el('span', { class: 'un-label', text: UNIT_LABEL[it.unit.type] }));
    } else {
      unitCell.appendChild(el('span', { class: 'na', text: '계산 불가' }));
    }
    return el('tr', null,
      el('td', { class: 't-title' },
        el('span', { class: 'src-tag t-src', text: x.sourceName }),
        it.title,
        it.rating != null ? el('div', { class: 'rating', text: '★ ' + it.rating + (it.reviewCount != null ? ' (' + it.reviewCount.toLocaleString('ko-KR') + ')' : '') }) : null),
      el('td', { class: 'num' }, el('strong', { text: won(it.price) }), it.originalPrice ? el('span', { class: 'orig', text: won(it.originalPrice) }) : null),
      el('td', { class: 'num', text: it.discountRate != null ? it.discountRate + '%' : '-' }),
      unitCell,
      el('td', null, vsBadge(it)),
      el('td', null, linkButtons(it, true)));
  }

  // ---- 그리기 ----
  function renderControls() {
    var tabs = document.getElementById('tabs');
    tabs.replaceChildren();
    var defs = [{ id: 'all', name: '전체' }].concat(state.data.sources.map(function (s) { return { id: s.id, name: s.name }; }));
    defs.forEach(function (d) {
      var b = el('button', { class: 'chip', type: 'button', 'aria-pressed': String(state.tab === d.id), text: d.name });
      b.addEventListener('click', function () { state.tab = d.id; render(); });
      tabs.appendChild(b);
    });

    var sorts = document.getElementById('sorts');
    sorts.replaceChildren();
    SORTS.forEach(function (s) {
      var b = el('button', { class: 'chip', type: 'button', 'aria-pressed': String(state.sort === s.id), text: s.label });
      b.addEventListener('click', function () { state.sort = s.id; render(); });
      sorts.appendChild(b);
    });

    var hint = document.getElementById('sortHint');
    hint.hidden = state.sort !== 'unit';
    hint.textContent = '단가는 단위(100g당 → 100ml당 → 개당)별로 묶어서 정렬합니다. 단위가 다른 상품끼리는 비교할 수 없어요.';
  }

  function render() {
    renderControls();
    var list = visibleItems();
    var cards = document.getElementById('cards');
    var tbody = document.querySelector('#table tbody');
    cards.replaceChildren();
    tbody.replaceChildren();
    list.forEach(function (x) {
      cards.appendChild(card(x));
      tbody.appendChild(row(x));
    });
    document.getElementById('status').textContent = list.length ? list.length + '개 상품' : '표시할 상품이 없습니다.';
  }

  function init(data) {
    state.data = data;
    document.getElementById('collected').textContent = fmtTime(data.generatedAt) + ' 기준 · 가격은 바뀔 수 있어요';
    document.getElementById('sampleBanner').hidden = !data.isSample;
    render();
  }

  fetch('data.json', { cache: 'no-store' })
    .then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    })
    .then(init)
    .catch(function (e) {
      document.getElementById('collected').textContent = '';
      document.getElementById('status').textContent =
        'data.json 을 불러오지 못했습니다 (' + e.message + '). 파일을 그냥 열지 말고 VS Code Live Server 같은 로컬 서버로 여세요.';
    });
})();
