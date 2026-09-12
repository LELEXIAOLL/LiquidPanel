/*
  LiquidPanel 管理面板 · 概览页

  数据来源两条通道：
    - GET /api/status  ：首次加载，带完整历史采样，曲线一进来就是满的
    - WebSocket /ws    ：之后每 2 秒推一个新采样点，前端自己接上

  曲线图为手写内联 SVG（CSP 不允许外链图表库），要点：
    - 每个系列绑定一个实体（本进程 / 整机），开关只改可见性，不重新分配颜色
    - 每张图单一 Y 轴，绝不做双 Y 轴
    - Y 轴上限按观测峰值动态取整（百分比 / 人数各一套档位）
    - 折线步进与刻度缩放都走 0.3 秒 easeOutCubic 动画
    - 宽度用 ResizeObserver 跟随，容器一变就重算 viewBox
    - 所有动态文本用 textContent / createTextNode，不拼 innerHTML
*/
(function () {
  'use strict';

  const SESSION_ENDPOINT = '/api/auth/session';
  const STATUS_ENDPOINT = '/api/status';
  const LOGOUT_ENDPOINT = '/api/auth/logout';
  const LOG_ENDPOINT = '/api/logs';
  const COMMAND_ENDPOINT = '/api/console';
  const CREDENTIALS_ENDPOINT = '/api/auth/credentials';
  const LOGIN_PAGE = '/login/login.html';

  const PING_INTERVAL_MS = 25000;
  const RECONNECT_MIN_MS = 1000;
  const RECONNECT_MAX_MS = 15000;

  /** 与后端 MetricsHistory 保持一致：120 个点 × 2 秒 = 4 分钟 */
  const CAPACITY = 120;
  const WINDOW_MS = 4 * 60 * 1000;

  const CHART_HEIGHT = 132;
  const PAD = { left: 30, right: 8, top: 10, bottom: 18 };
  const MIN_CHART_WIDTH = 240;

  /** 曲线步进与刻度缩放的时长 */
  const REDUCED_MOTION = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const RENDER_MS = REDUCED_MOTION ? 0 : 300;

  /** 百分比轴档位：只从这几档里挑，刻度稳定、标签好看 */
  const PERCENT_STEPS = [5, 10, 20, 25, 50, 100];
  /** 人数轴档位 */
  const COUNT_STEPS = [1, 2, 5, 10, 15, 20, 25, 50, 75, 100, 150, 200, 500, 1000];
  /** TPS 轴档位：TPS 不会超过 20 */
  const TPS_STEPS = [5, 10, 15, 20];

  const RING_CIRCUMFERENCE = 2 * Math.PI * 52;
  const WARN_PERCENT = 75;
  const DANGER_PERCENT = 90;

  const FOLDERS = ['world', 'world_nether', 'world_the_end'];
  const SVG_NS = 'http://www.w3.org/2000/svg';

  /** 前端最多保留多少行日志 */
  const LOG_LIMIT = 400;
  /** 距底部多少像素内算「贴着底」，用于自动滚动 */
  const LOG_NEAR_BOTTOM_PX = 24;

  /** 原版 16 色，与游戏内控制台的取色一致 */
  const MC_COLORS = {
    '0': '#000000', '1': '#0000aa', '2': '#00aa00', '3': '#00aaaa',
    '4': '#aa0000', '5': '#aa00aa', '6': '#ffaa00', '7': '#aaaaaa',
    '8': '#555555', '9': '#5555ff', 'a': '#55ff55', 'b': '#55ffff',
    'c': '#ff5555', 'd': '#ff55ff', 'e': '#ffff55', 'f': '#ffffff'
  };

  /**
   * ANSI 前八色 -> Minecraft 色码。
   *
   * ANSI 的顺序是 黑红绿黄蓝紫青白，Minecraft 的顺序是
   * 黑深蓝深绿深青深红深紫金灰，两者对不上，
   * 必须查表而不能拿序号直接偏移（否则红蓝、黄青会互换）。
   */
  const ANSI_BASE_COLORS = ['0', '4', '2', '6', '1', '5', '3', '7'];

  /** ANSI 亮色（90-97）-> Minecraft 色码 */
  const ANSI_BRIGHT_COLORS = ['8', 'c', 'a', 'e', '9', 'd', 'b', 'f'];

  /** Minecraft 的颜色代码引导字符 */
  const SECTION = '\u00A7';

  const state = {
    username: null,
    socket: null,
    reconnectDelay: RECONNECT_MIN_MS,
    reconnectTimer: null,
    pingTimer: null,
    playerKey: '',
    samples: [],
    /** 内存 tooltip 要把百分比换算成容量，这两个总量取最近一次状态里的值 */
    machineTotalBytes: 0,
    heapMaxBytes: 0
  };

  function $(id) {
    return document.getElementById(id);
  }

  function toLogin() {
    window.location.replace(LOGIN_PAGE);
  }

  function easeOutCubic(t) {
    return 1 - Math.pow(1 - t, 3);
  }

  // ------------------------------------------------------------------
  // HTTP
  // ------------------------------------------------------------------

  async function request(url, options) {
    const config = Object.assign({ credentials: 'same-origin' }, options || {});
    if (config.body) {
      config.headers = Object.assign({ 'Content-Type': 'application/json' }, config.headers || {});
    }

    const response = await fetch(url, config);

    let payload = null;
    try {
      payload = await response.json();
    } catch (error) {
      payload = null;
    }

    if (response.status === 401) {
      toLogin();
      throw new Error('未登录');
    }
    if (!response.ok) {
      throw new Error((payload && payload.message) || ('请求失败（' + response.status + '）'));
    }
    return payload;
  }

  // ------------------------------------------------------------------
  // 通用格式化
  // ------------------------------------------------------------------

  function formatBytes(bytes) {
    const value = Number(bytes);
    if (!isFinite(value) || value <= 0) {
      return '0 B';
    }
    const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
    let index = 0;
    let size = value;
    while (size >= 1024 && index < units.length - 1) {
      size /= 1024;
      index++;
    }
    const digits = index === 0 ? 0 : (size >= 100 ? 0 : (size >= 10 ? 1 : 2));
    return size.toFixed(digits) + ' ' + units[index];
  }

  function percentText(value) {
    return (typeof value === 'number' && value >= 0) ? value.toFixed(1) : '--';
  }

  function clockText(timestamp) {
    const date = new Date(timestamp);
    return String(date.getHours()).padStart(2, '0') + ':'
      + String(date.getMinutes()).padStart(2, '0') + ':'
      + String(date.getSeconds()).padStart(2, '0');
  }

  function setText(id, text) {
    const el = $(id);
    if (el && el.textContent !== text) {
      el.textContent = text;
    }
  }

  function setNumber(id, value, options) {
    const el = $(id);
    if (!el || typeof value !== 'number' || !isFinite(value)) {
      return;
    }
    const opts = options || {};
    const format = opts.format || (v => String(Math.round(v)));
    const target = format(value);

    if (el.textContent === target) {
      return;
    }

    const previous = el.dataset.value === undefined ? null : Number(el.dataset.value);
    el.dataset.value = String(value);

    if (previous === null || previous === value) {
      el.textContent = target;
    } else {
      countUp(el, previous, value, format);
    }

    if (opts.pop) {
      el.classList.remove('pop');
      void el.offsetWidth;
      el.classList.add('pop');
    }
  }

  function countUp(el, from, to, format) {
    const duration = 620;
    const start = performance.now();

    function step(now) {
      const t = Math.min(1, (now - start) / duration);
      const eased = easeOutCubic(t);
      el.textContent = format(from + (to - from) * eased);
      if (t < 1) {
        requestAnimationFrame(step);
      } else {
        el.textContent = format(to);
      }
    }
    requestAnimationFrame(step);
  }

  function severityClass(percent) {
    if (percent >= DANGER_PERCENT) {
      return 'is-danger';
    }
    return percent >= WARN_PERCENT ? 'is-warn' : '';
  }

  function setBar(id, percent) {
    const el = $(id);
    if (!el) {
      return;
    }
    const clamped = Math.max(0, Math.min(100, percent));
    el.style.width = clamped + '%';
    el.classList.remove('is-warn', 'is-danger');
    const severity = severityClass(clamped);
    if (severity) {
      el.classList.add(severity);
    }
  }

  function setRing(id, percent) {
    const el = $(id);
    if (!el) {
      return;
    }
    const clamped = Math.max(0, Math.min(100, percent));
    el.style.strokeDashoffset = String(RING_CIRCUMFERENCE * (1 - clamped / 100));
    el.classList.remove('is-warn', 'is-danger');
    const severity = severityClass(clamped);
    if (severity) {
      el.classList.add(severity);
    }
  }

  function pickCeil(value, steps) {
    for (let i = 0; i < steps.length; i++) {
      if (value <= steps[i]) {
        return steps[i];
      }
    }
    return steps[steps.length - 1];
  }

  // ==================================================================
  // 曲线图
  // ==================================================================

  /**
   * @param rootName 对应 HTML 里的 [data-chart="..."]
   * @param config   primary / secondary 取采样里的哪个字段，
   *                 unit 数值后缀，ceil 轴上限取整函数，rows tooltip 行定义
   */
  /**
   * @param rootName 对应 HTML 里的 [data-chart="..."]
   * @param config   series 系列列表（optional 的默认关，各带一个开关），
   *                 ceil 轴上限取整函数，unit 数值后缀，decimals 小数位
   */
  function createChart(rootName, config) {
    const root = document.querySelector('[data-chart="' + rootName + '"]');
    const card = root.closest('.chart-card');

    const series = config.series.map(function (def) {
      return {
        key: def.key,
        label: def.label,
        swatch: def.swatch,
        optional: !!def.optional,
        on: !def.optional,
        line: root.querySelector('[data-line="' + def.key + '"]'),
        dot: root.querySelector('[data-dot="' + def.key + '"]'),
        input: card.querySelector('input[data-series="' + def.key + '"]')
      };
    });

    return {
      name: rootName,
      root: root,
      card: card,
      svg: root.querySelector('.chart__svg'),
      grid: root.querySelector('[data-grid]'),
      axis: root.querySelector('[data-axis]'),
      tooltip: root.querySelector('[data-tooltip]'),
      hover: root.querySelector('[data-hover]'),
      hair: root.querySelector('[data-hair]'),
      legend: card.querySelector('[data-legend]'),

      series: series,
      ceil: config.ceil,
      unit: config.unit || '',
      decimals: config.decimals === undefined ? 1 : config.decimals,

      // 布局：宽度从 0 起步，保证第一次 layoutChart 一定执行
      width: 0,
      height: CHART_HEIGHT,

      // 轴上限：yMax 是目标值（网格用它），animYMax 是当前绘制值
      yMax: config.initialYMax || 1,
      animYMax: undefined,

      // 右端时间：animEdge 是当前绘制值
      animEdge: undefined,
      animFromEdge: 0,
      animToEdge: 0,
      animFromYMax: 0,
      animToYMax: 0,
      animStart: 0,
      animating: false,

      // 悬停状态：动画期间要用它把 tooltip 跟着重定位
      hovering: false,
      lastPointerX: 0
    };
  }

  const charts = [
    createChart('cpu', {
      ceil: value => pickCeil(value, PERCENT_STEPS), initialYMax: 100, unit: '%', decimals: 1,
      series: [
        { key: 'cpu', label: '本进程', swatch: 'swatch--process' },
        { key: 'cpuAll', label: '整机', swatch: 'swatch--machine', optional: true }
      ]
    }),
    createChart('mem', {
      ceil: value => pickCeil(value, PERCENT_STEPS), initialYMax: 100, unit: '%', decimals: 1,
      series: [
        { key: 'mem', label: '本进程', swatch: 'swatch--process' },
        { key: 'memAll', label: '整机', swatch: 'swatch--machine', optional: true }
      ]
    }),
    createChart('players', {
      ceil: value => pickCeil(value, COUNT_STEPS), initialYMax: 20, unit: ' 人', decimals: 0,
      series: [
        { key: 'players', label: '在线', swatch: 'swatch--process' }
      ]
    }),
    createChart('tps', {
      ceil: value => pickCeil(value, TPS_STEPS), initialYMax: 20, unit: '', decimals: 1,
      series: [
        { key: 'tps1', label: '1 分钟', swatch: 'swatch--process' },
        { key: 'tps5', label: '5 分钟', swatch: 'swatch--machine', optional: true },
        { key: 'tps15', label: '15 分钟', swatch: 'swatch--third', optional: true }
      ]
    })
  ];

  /** 当前打开的系列 */
  function activeSeries(chart) {
    return chart.series.filter(function (item) {
      return item.on;
    });
  }
  function xFor(timestamp, edge, chart) {
    const span = chart.width - PAD.left - PAD.right;
    const ratio = (timestamp - (edge - WINDOW_MS)) / WINDOW_MS;
    return PAD.left + Math.max(0, Math.min(1, ratio)) * span;
  }

  function yForRatio(ratio, chart) {
    const bottom = chart.height - PAD.bottom;
    const span = chart.height - PAD.top - PAD.bottom;
    return bottom - Math.max(0, Math.min(1, ratio)) * span;
  }

  function yFor(value, chart, yMax) {
    return yForRatio(yMax > 0 ? value / yMax : 0, chart);
  }

  /** 当前正在绘制的右端时间 */
  function currentEdge(chart) {
    return chart.animEdge === undefined ? latestEdge() : chart.animEdge;
  }

  /** 当前正在绘制的轴上限 */
  function currentYMax(chart) {
    return chart.animYMax === undefined ? chart.yMax : chart.animYMax;
  }

  function latestEdge() {
    return state.samples.length
      ? state.samples[state.samples.length - 1].t
      : Date.now();
  }

  function svgEl(name, attrs) {
    const el = document.createElementNS(SVG_NS, name);
    for (const key in attrs) {
      if (Object.prototype.hasOwnProperty.call(attrs, key)) {
        el.setAttribute(key, attrs[key]);
      }
    }
    return el;
  }

  // ------------------------------------------------------------------
  // 布局
  // ------------------------------------------------------------------

  /**
   * 按元素实际像素宽度设置 viewBox，避免非等比缩放把圆点压成椭圆。
   *
   * <p>卡片隐藏 / 网格重排都会改变宽度，所以这个方法由 ResizeObserver 反复调用，
   * 宽度没变就直接返回，不做多余工作。
   *
   * @return 宽度是否发生变化
   */
  function layoutChart(chart) {
    // 切到别的选项卡时面板是隐藏的，clientWidth 为 0。
    // 此时保持原样不动，等切回来由 ResizeObserver 重新触发，
    // 否则会被压成最小值、切回来时闪一下。
    const measured = Math.round(chart.svg.clientWidth);
    if (measured <= 0) {
      return false;
    }

    if (chart.width === measured) {
      return false;
    }
    chart.width = measured;
    chart.height = CHART_HEIGHT;
    chart.svg.setAttribute('viewBox', '0 0 ' + chart.width + ' ' + chart.height);
    buildGrid(chart);
    return true;
  }

  function axisText(value, chart) {
    const rounded = Math.round(value * 10) / 10;
    const text = Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1);
    return chart.unit === '%' ? text + '%' : text;
  }

  function buildGrid(chart) {
    chart.grid.textContent = '';
    chart.axis.textContent = '';

    const left = PAD.left;
    const right = chart.width - PAD.right;
    const bottom = chart.height - PAD.bottom;

    chart.hair.setAttribute('y1', String(PAD.top));
    chart.hair.setAttribute('y2', String(bottom));

    // 水平细网格线；只标 0 / 中间 / 上限三个刻度，保持克制
    [0, 0.25, 0.5, 0.75, 1].forEach(function (ratio) {
      const y = yForRatio(ratio, chart);
      chart.grid.appendChild(svgEl('line', { x1: left, x2: right, y1: y, y2: y }));

      if (ratio === 0 || ratio === 0.5 || ratio === 1) {
        const label = svgEl('text', { x: left - 6, y: y + 3, 'text-anchor': 'end' });
        label.appendChild(document.createTextNode(axisText(chart.yMax * ratio, chart)));
        chart.axis.appendChild(label);
      }
    });

    // 时间轴：-4 分 / -2 分 / 现在
    const ticks = [
      { x: left, anchor: 'start', text: '-' + Math.round(WINDOW_MS / 60000) + ' 分' },
      { x: (left + right) / 2, anchor: 'middle', text: '-' + Math.round(WINDOW_MS / 120000) + ' 分' },
      { x: right, anchor: 'end', text: '现在' }
    ];
    ticks.forEach(function (tick) {
      const label = svgEl('text', { x: tick.x, y: bottom + 13, 'text-anchor': tick.anchor });
      label.appendChild(document.createTextNode(tick.text));
      chart.axis.appendChild(label);
    });
  }

  // ------------------------------------------------------------------
  // 绘制
  // ------------------------------------------------------------------

  /**
   * 把采样序列拼成 path。取不到值的点（-1）断开画笔，不连线，
   * 免得用 0 画出一条并不存在的谷底。
   */
  function buildPath(chart, key, edge, yMax) {
    let d = '';
    let penDown = false;

    state.samples.forEach(function (sample) {
      const value = sample[key];
      if (typeof value !== 'number' || value < 0) {
        penDown = false;
        return;
      }
      d += (penDown ? ' L' : ' M') + xFor(sample.t, edge, chart).toFixed(1)
        + ' ' + yFor(value, chart, yMax).toFixed(1);
      penDown = true;
    });

    return d.trim();
  }

  /** 按给定的右端时间与轴上限画一帧 */
  function paintChart(chart, edge, yMax) {
    if (chart.width <= 0) {
      return;
    }
    chart.series.forEach(function (item) {
      if (!item.on) {
        item.line.setAttribute('hidden', '');
        return;
      }
      item.line.removeAttribute('hidden');
      item.line.setAttribute('d', buildPath(chart, item.key, edge, yMax));
    });
    renderLegend(chart);
  }

  /**
   * 图例只在同时显示两个以上系列时出现；
   * 单系列时标题已经说明了画的是什么，再加图例是多余的。
   */
  function renderLegend(chart) {
    if (!chart.legend) {
      return;
    }
    const active = activeSeries(chart);
    if (active.length < 2) {
      chart.legend.hidden = true;
      return;
    }

    chart.legend.textContent = '';
    active.forEach(function (item) {
      const wrap = document.createElement('span');
      wrap.className = 'legend__item';

      const swatch = document.createElement('i');
      swatch.className = 'legend__swatch ' + item.swatch;
      wrap.appendChild(swatch);

      wrap.appendChild(document.createTextNode(item.label));
      chart.legend.appendChild(wrap);
    });
    chart.legend.hidden = false;
  }

  /**
   * 按当前可见系列算出轴上限，再从档位里取整。
   * 只统计可见的线，所以打开「整机」开关时上限会跟着变。
   */
  function computeYMax(chart) {
    const active = activeSeries(chart);
    let peak = 0;
    state.samples.forEach(function (sample) {
      active.forEach(function (item) {
        const value = sample[item.key];
        if (typeof value === 'number' && value > peak) {
          peak = value;
        }
      });
    });
    return chart.ceil(peak);
  }

  function animationFrame(chart, now) {
    if (!chart.animStart) {
      chart.animStart = now;
    }
    const t = RENDER_MS <= 0 ? 1 : Math.min(1, (now - chart.animStart) / RENDER_MS);
    const eased = easeOutCubic(t);

    const edge = chart.animFromEdge + (chart.animToEdge - chart.animFromEdge) * eased;
    const yMax = chart.animFromYMax + (chart.animToYMax - chart.animFromYMax) * eased;

    chart.animEdge = edge;
    chart.animYMax = yMax;
    paintChart(chart, edge, yMax);

    // 折线在动，悬停层也要跟着动，否则每 2 秒就会被复位一次
    if (chart.hovering) {
      showHoverAt(chart, chart.lastPointerX);
    }

    if (t < 1) {
      requestAnimationFrame(function (next) {
        animationFrame(chart, next);
      });
      return;
    }

    // 收尾时对齐到精确值，避免累积误差
    chart.animating = false;
    chart.animStart = 0;
    chart.animEdge = chart.animToEdge;
    chart.animYMax = chart.animToYMax;
    if (chart.hovering) {
      showHoverAt(chart, chart.lastPointerX);
    }
  }

  /**
   * 重画一张图。
   *
   * <p>轴上限若变化，网格立刻切到新刻度（标签必须立刻正确，
   * 否则中间态会出现对不上的数字），折线再用 0.3 秒从这里平滑滑到新比例。
   */
  function renderChart(chart) {
    const nextYMax = computeYMax(chart);
    if (nextYMax !== chart.yMax) {
      chart.yMax = nextYMax;
      buildGrid(chart);
    }

    const edge = latestEdge();
    chart.animFromEdge = currentEdge(chart);
    chart.animToEdge = edge;
    chart.animFromYMax = currentYMax(chart);
    chart.animToYMax = chart.yMax;
    chart.animStart = 0;

    if (!chart.animating) {
      chart.animating = true;
      requestAnimationFrame(function (now) {
        animationFrame(chart, now);
      });
    }
  }

  function renderCharts() {
    charts.forEach(renderChart);
  }

  // ------------------------------------------------------------------
  // 悬停：十字准星 + tooltip
  // ------------------------------------------------------------------

  function hideHover(chart) {
    chart.hover.setAttribute('hidden', '');
    chart.tooltip.setAttribute('hidden', '');
  }

  function formatValue(chart, value) {
    if (typeof value !== 'number' || value < 0) {
      return '--';
    }
    const text = chart.decimals > 0 ? value.toFixed(chart.decimals) : String(Math.round(value));
    return text + chart.unit;
  }

  function showHoverAt(chart, clientX) {
    if (!state.samples.length || !chart.width) {
      return;
    }

    const rect = chart.svg.getBoundingClientRect();
    if (rect.width <= 0) {
      return;
    }
    // 屏幕坐标换算回 viewBox 坐标；用实际渲染宽度而不是缓存的宽度，避免布局变化后错位
    const pointerX = (clientX - rect.left) / rect.width * chart.width;

    const edge = currentEdge(chart);
    const yMax = currentYMax(chart);

    let nearest = null;
    let nearestDistance = Infinity;
    state.samples.forEach(function (sample) {
      const distance = Math.abs(xFor(sample.t, edge, chart) - pointerX);
      if (distance < nearestDistance) {
        nearestDistance = distance;
        nearest = sample;
      }
    });
    if (!nearest) {
      return;
    }

    const x = xFor(nearest.t, edge, chart);
    chart.hover.removeAttribute('hidden');
    chart.hair.setAttribute('x1', x.toFixed(1));
    chart.hair.setAttribute('x2', x.toFixed(1));

    // 只给当前打开的系列画点，其余一律隐藏
    chart.series.forEach(function (item) {
      if (!item.on) {
        item.dot.setAttribute('hidden', '');
        return;
      }
      const value = nearest[item.key];
      if (typeof value !== 'number' || value < 0) {
        item.dot.setAttribute('hidden', '');
        return;
      }
      item.dot.removeAttribute('hidden');
      item.dot.setAttribute('cx', x.toFixed(1));
      item.dot.setAttribute('cy', yFor(value, chart, yMax).toFixed(1));
    });

    chart.tooltip.textContent = '';
    const time = document.createElement('span');
    time.className = 'tip-time';
    time.textContent = clockText(nearest.t);
    chart.tooltip.appendChild(time);

    activeSeries(chart).forEach(function (item) {
      chart.tooltip.appendChild(buildTipRow(chart, item, nearest));
    });

    // tooltip 按 viewBox 比例定位，再换算成容器百分比
    const left = Math.max(56, Math.min(chart.width - 56, x));
    chart.tooltip.style.left = (left / chart.width * 100) + '%';
    chart.tooltip.removeAttribute('hidden');
  }

  function buildTipRow(chart, item, sample) {
    const line = document.createElement('span');
    line.className = 'tip-row';

    const swatch = document.createElement('i');
    swatch.className = 'legend__swatch ' + item.swatch;
    line.appendChild(swatch);

    const label = document.createElement('span');
    label.textContent = item.label + ' ';
    line.appendChild(label);

    const value = document.createElement('b');
    value.textContent = formatValue(chart, sample[item.key]);
    line.appendChild(value);

    // 内存这类指标再补上具体容量
    const detail = memoryDetail(chart, item, sample);
    if (detail) {
      const node = document.createElement('em');
      node.className = 'tip-detail';
      node.textContent = detail;
      line.appendChild(node);
    }
    return line;
  }

  /** 内存曲线额外显示容量，其它曲线返回 null */
  function memoryDetail(chart, item, sample) {
    if (chart.name !== 'mem') {
      return null;
    }
    return item.key === 'mem'
      ? formatBytes(sample.memUsed) + ' / ' + formatBytes(state.heapMaxBytes)
      : formatBytes(sample.memAllUsed) + ' / ' + formatBytes(state.machineTotalBytes);
  }

  function bindChartEvents(chart) {
    chart.svg.addEventListener('pointermove', function (event) {
      chart.hovering = true;
      chart.lastPointerX = event.clientX;
      showHoverAt(chart, event.clientX);
    });
    chart.svg.addEventListener('pointerleave', function () {
      chart.hovering = false;
      hideHover(chart);
    });
    // 键盘可达：聚焦时定位到最新一个点
    chart.svg.addEventListener('focus', function () {
      const rect = chart.svg.getBoundingClientRect();
      chart.hovering = true;
      chart.lastPointerX = rect.left + rect.width;
      showHoverAt(chart, chart.lastPointerX);
    });
    chart.svg.addEventListener('blur', function () {
      chart.hovering = false;
      hideHover(chart);
    });

    // 每个可选系列一个开关，勾上/取消只影响它自己
    chart.series.forEach(function (item) {
      if (!item.input) {
        return;
      }
      item.input.checked = item.on;
      item.input.addEventListener('change', function () {
        item.on = item.input.checked;
        renderChart(chart);
      });
    });
  }

  /**
   * 卡片隐藏、网格重排、窗口缩放都会改宽度，
   * 用 ResizeObserver 盯着，一变就重算 viewBox 并重画。
   */
  /** 宽度变了就重算 viewBox 并按当前比例重画一帧 */
  function refreshChartLayout(chart) {
    if (!layoutChart(chart)) {
      return;
    }
    if (chart.animEdge === undefined) {
      chart.animEdge = latestEdge();
      chart.animYMax = chart.yMax;
    }
    paintChart(chart, currentEdge(chart), currentYMax(chart));
    if (chart.hovering) {
      showHoverAt(chart, chart.lastPointerX);
    }
  }

  function observeChartResize() {
    if (typeof ResizeObserver !== 'function') {
      window.addEventListener('resize', relayout);
      return;
    }
    const observer = new ResizeObserver(function (entries) {
      entries.forEach(function (entry) {
        const chart = charts.find(c => c.svg === entry.target);
        if (chart) {
          refreshChartLayout(chart);
        }
      });
    });
    charts.forEach(function (chart) {
      observer.observe(chart.svg);
    });
  }

  // ==================================================================
  // 控制台日志
  // ==================================================================

  /**
   * 把 ANSI 转义序列统一翻译成 Minecraft 的 § 代码。
   *
   * 服务端控制台可能是 ANSI 上色（终端），也可能是 § 代码（游戏内发出的消息），
   * 先都归一到 § 再渲染，两条来源就都能正确还原颜色。
   */
  function ansiToSection(text) {
    return text
      .replace(/\u001B\[([0-9;]*)m/g, function (match, codes) {
        let out = '';
        codes.split(';').forEach(function (code) {
          const n = Number(code || '0');
          if (n === 0) {
            out += SECTION + 'r';
          } else if (n === 1) {
            out += SECTION + 'l';
          } else if (n === 3) {
            out += SECTION + 'o';
          } else if (n === 4) {
            out += SECTION + 'n';
          } else if (n === 9) {
            out += SECTION + 'm';
          } else if (n >= 30 && n <= 37) {
            out += SECTION + ANSI_BASE_COLORS[n - 30];
          } else if (n >= 90 && n <= 97) {
            out += SECTION + ANSI_BRIGHT_COLORS[n - 90];
          }
        });
        return out;
      })
      // 其余 ANSI 序列（光标移动、清屏等）直接丢掉
      .replace(/\u001B\[[0-9;]*[A-Za-z]/g, '');
  }

  function applyLogStyle(span, style) {
    if (style.color) {
      span.style.color = style.color;
    }
    if (style.bold) {
      span.style.fontWeight = '700';
    }
    if (style.italic) {
      span.style.fontStyle = 'italic';
    }
    const decoration = [];
    if (style.underline) {
      decoration.push('underline');
    }
    if (style.strike) {
      decoration.push('line-through');
    }
    if (decoration.length) {
      span.style.textDecoration = decoration.join(' ');
    }
  }

  /**
   * 按颜色代码把一行日志拆成带样式的 span。
   * 全程 textContent 写入，日志内容不会被当成 HTML 执行。
   */
  function renderLogText(container, raw) {
    const text = ansiToSection(raw == null ? '' : String(raw));
    const style = { color: null, bold: false, italic: false, underline: false, strike: false };
    let buffer = '';

    function flush() {
      if (!buffer) {
        return;
      }
      const span = document.createElement('span');
      span.textContent = buffer;
      applyLogStyle(span, style);
      container.appendChild(span);
      buffer = '';
    }

    for (let i = 0; i < text.length; i++) {
      const ch = text.charAt(i);
      if (ch !== SECTION || i + 1 >= text.length) {
        buffer += ch;
        continue;
      }

      const code = text.charAt(i + 1).toLowerCase();
      i++;

      if (code === 'r') {
        flush();
        style.color = null;
        style.bold = false;
        style.italic = false;
        style.underline = false;
        style.strike = false;
        continue;
      }
      if (code === 'l' || code === 'o' || code === 'n' || code === 'm') {
        flush();
        if (code === 'l') { style.bold = true; }
        if (code === 'o') { style.italic = true; }
        if (code === 'n') { style.underline = true; }
        if (code === 'm') { style.strike = true; }
        continue;
      }
      // 混淆字符在控制台里没有意义，标记本身丢掉
      if (code === 'k') {
        continue;
      }
      if (MC_COLORS[code]) {
        flush();
        style.color = MC_COLORS[code];
        continue;
      }
      // 不认识的代码原样保留，不做静默丢弃
      buffer += SECTION + code;
    }
    flush();
  }

  function isNearBottom(el) {
    return el.scrollHeight - el.scrollTop - el.clientHeight <= LOG_NEAR_BOTTOM_PX;
  }

  function updateLogMeta(count) {
    setText('log-meta', 'latest.log · ' + count + ' 行');
  }

  /**
   * 追加日志行。新行从底部渐显弹出（动画在 CSS 里，0.2 秒先快后慢）。
   * 一次来很多行时按序号错开一点，避免整块糊在一起。
   */
  function appendLogLines(lines) {
    const view = $('log-view');
    if (!view || !lines.length) {
      return;
    }

    // 追加前先记下是不是贴着底，决定要不要跟着滚
    const pinned = isNearBottom(view);
    const autoScroll = $('log-autoscroll');

    lines.forEach(function (entry, index) {
      const line = document.createElement('div');
      line.className = 'log__line';
      line.style.animationDelay = (Math.min(index, 12) * 0.02).toFixed(2) + 's';
      renderLogText(line, entry && entry.text);
      view.appendChild(line);
    });

    while (view.childElementCount > LOG_LIMIT) {
      view.removeChild(view.firstElementChild);
    }

    if (pinned && (!autoScroll || autoScroll.checked)) {
      view.scrollTop = view.scrollHeight;
    }
    updateLogMeta(view.childElementCount);
  }

  // ==================================================================
  // 初始密码未修改时的强制弹窗
  // ==================================================================

  /** 用户名规则要与服务端一致 */
  const USERNAME_PATTERN = /^[A-Za-z0-9_.-]{3,32}$/;
  const MIN_PASSWORD_LENGTH = 8;

  function setSetupError(text) {
    const box = $('setup-error');
    if (!box) {
      return;
    }
    box.textContent = text || '';
    box.hidden = !text;
  }

  /**
   * 弹出强制修改窗口。刻意不做关闭入口、不响应 ESC、点遮罩也不关：
   * 这是「还在用初始密码」的强制要求，不是提示。
   */
  function showSetupModal(currentUsername) {
    const modal = $('setup-modal');
    if (!modal) {
      return;
    }
    $('setup-username').value = currentUsername || '';
    $('setup-password').value = '';
    $('setup-confirm').value = '';
    setSetupError('');
    modal.hidden = false;
    $('setup-password').focus();
  }

  async function submitSetup(event) {
    event.preventDefault();

    const button = $('setup-submit');
    if (button.disabled) {
      return;
    }

    const username = $('setup-username').value.trim();
    const password = $('setup-password').value;
    const confirm = $('setup-confirm').value;

    if (!USERNAME_PATTERN.test(username)) {
      setSetupError('用户名需为 3-32 位字母、数字、下划线、点或短横线');
      return;
    }
    if (password.length < MIN_PASSWORD_LENGTH) {
      setSetupError('密码长度至少为 ' + MIN_PASSWORD_LENGTH + ' 位');
      return;
    }
    if (password !== confirm) {
      setSetupError('两次输入的密码不一致');
      return;
    }

    button.disabled = true;
    setSetupError('');

    try {
      await request(CREDENTIALS_ENDPOINT, {
        method: 'POST',
        body: JSON.stringify({ username: username, newPassword: password })
      });
      // 服务端已清空全部会话，这里直接回登录页，不再恢复按钮状态
      toLogin();
    } catch (error) {
      setSetupError(error.message);
      button.disabled = false;
    }
  }

  // ==================================================================
  // 选项卡切换
  // ==================================================================

  const TAB_NAMES = ['overview', 'console'];

  function scrollLogToBottom() {
    const view = $('log-view');
    if (view) {
      view.scrollTop = view.scrollHeight;
    }
  }

  function switchTab(name) {
    TAB_NAMES.forEach(function (key) {
      const pane = $('panel-' + key);
      const button = document.querySelector('[data-tab="' + key + '"]');
      const active = key === name;

      if (pane) {
        pane.hidden = !active;
      }
      if (button) {
        button.classList.toggle('is-active', active);
        button.setAttribute('aria-selected', String(active));
      }
    });

    // 刚切完布局还没重算，等下一帧再处理
    requestAnimationFrame(function () {
      if (name === 'console') {
        // 面板隐藏期间没法自动滚动（scrollHeight 是 0），切过来补一次
        scrollLogToBottom();
        const input = $('command-input');
        if (input) {
          input.focus();
        }
      } else {
        // 概览里的曲线重新可见，需要按新宽度重画
        relayout();
      }
    });
  }

  // ==================================================================
  // 控制台命令
  // ==================================================================

  /** 最多记多少条历史 */
  const COMMAND_HISTORY_LIMIT = 50;

  const commandHistory = [];
  let historyIndex = -1;

  function setCommandHint(text, kind) {
    const hint = $('command-hint');
    if (!hint) {
      return;
    }
    hint.textContent = text;
    hint.classList.toggle('is-ok', kind === 'ok');
    hint.classList.toggle('is-error', kind === 'error');
  }

  function rememberCommand(command) {
    if (commandHistory[0] === command) {
      return;
    }
    commandHistory.unshift(command);
    while (commandHistory.length > COMMAND_HISTORY_LIMIT) {
      commandHistory.pop();
    }
  }

  async function runCommand() {
    const input = $('command-input');
    const button = $('command-run');
    if (!input || !button || button.disabled) {
      return;
    }

    const command = input.value.trim();
    if (!command) {
      return;
    }

    button.disabled = true;
    setCommandHint('正在执行...', null);

    try {
      const payload = await request(COMMAND_ENDPOINT, {
        method: 'POST',
        body: JSON.stringify({ command: command })
      });
      const data = (payload && payload.data) || {};

      rememberCommand(command);
      input.value = '';
      historyIndex = -1;

      if (data.dispatched === false) {
        // 服务端回了 false，说明没有这条命令
        setCommandHint('服务端无法识别这条命令：' + command, 'error');
      } else {
        setCommandHint('已执行：' + command, 'ok');
      }
    } catch (error) {
      setCommandHint(error.message, 'error');
    } finally {
      button.disabled = false;
      input.focus();
    }
  }

  function handleCommandKey(event) {
    if (event.key === 'Enter') {
      event.preventDefault();
      runCommand();
      return;
    }
    if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') {
      return;
    }
    if (!commandHistory.length) {
      return;
    }

    event.preventDefault();
    const input = $('command-input');
    if (event.key === 'ArrowUp') {
      historyIndex = Math.min(commandHistory.length - 1, historyIndex + 1);
    } else {
      historyIndex = Math.max(-1, historyIndex - 1);
    }

    input.value = historyIndex < 0 ? '' : commandHistory[historyIndex];
    // 光标挪到末尾，接着补字更顺手
    input.setSelectionRange(input.value.length, input.value.length);
  }

  // ==================================================================
  // 各卡片渲染
  // ==================================================================

  function renderSpecs(cpu, memory, processMemory) {
    state.machineTotalBytes = memory.totalBytes || 0;
    state.heapMaxBytes = processMemory.maxBytes || 0;

    setText('cpu-model', cpu.model || '未知');
    setNumber('cpu-cores', cpu.cores || 0, { format: v => String(Math.round(v)) });
    setNumber('mem-total', state.machineTotalBytes, { format: formatBytes });
    setNumber('mem-heap-max', state.heapMaxBytes, { format: formatBytes });
  }

  function renderTps(data) {
    const tps = data.tps && data.tps.oneMinute;
    if (typeof tps === 'number' && tps >= 0) {
      setNumber('tps-now', tps, { format: v => v.toFixed(1) });
    } else {
      setText('tps-now', '--');
    }
  }

  function renderPlayers(data) {
    setNumber('players-online', data.onlinePlayers || 0, { format: v => String(Math.round(v)), pop: true });
    setText('players-max', '/ ' + (data.maxPlayers || 0));

    const players = data.players || [];
    const key = players.map(player => player.name).join(',');
    if (key === state.playerKey) {
      return; // 名单没变就不重建，免得每 2 秒重播一次入场动画
    }
    state.playerKey = key;

    const box = $('player-chips');
    box.textContent = '';
    $('players-empty').hidden = players.length > 0;

    players.forEach(function (player, index) {
      const chip = document.createElement('span');
      chip.className = 'chip';
      chip.style.animationDelay = (index * 0.035).toFixed(3) + 's';
      chip.textContent = player.name;
      box.appendChild(chip);
    });
  }

  function renderDisk(disk) {
    const percent = typeof disk.usagePercent === 'number' ? disk.usagePercent : -1;
    if (percent < 0) {
      setText('disk-percent', '--');
      setText('disk-detail', '不可用');
      setRing('disk-ring', 0);
    } else {
      setNumber('disk-percent', percent, { format: percentText });
      setText('disk-detail', formatBytes(disk.usedBytes || 0) + ' / ' + formatBytes(disk.totalBytes || 0));
      setRing('disk-ring', percent);
    }
    setText('disk-path', (disk.root || '') + '  可用 ' + formatBytes(disk.freeBytes || 0));
  }

  /** 不悬停也能看到内存的具体容量，而不是只有一个百分比 */
  function renderMemoryCurrent(memory, processMemory) {
    setText('mem-current',
      '本进程 ' + formatBytes(processMemory.usedBytes || 0) + ' / ' + formatBytes(processMemory.maxBytes || 0)
      + '　·　整机 ' + formatBytes(memory.usedBytes || 0) + ' / ' + formatBytes(memory.totalBytes || 0));
  }

  function renderFolders(folders) {
    let total = 0;
    FOLDERS.forEach(function (name) {
      const folder = folders[name];
      if (folder && folder.exists) {
        total += folder.bytes || 0;
      }
    });

    FOLDERS.forEach(function (name) {
      const folder = folders[name] || {};
      const card = document.querySelector('[data-folder="' + name + '"]');

      // 扫不到的世界文件夹，整张卡片直接不显示
      if (card) {
        card.hidden = !folder.exists;
      }
      if (!folder.exists) {
        return;
      }

      setNumber('folder-' + name, folder.bytes || 0, { format: formatBytes });
      setBar('share-' + name, total > 0 ? ((folder.bytes || 0) / total) * 100 : 0);
    });
  }

  function applySystem(system) {
    renderSpecs(system.cpu || {}, system.memory || {}, system.processMemory || {});
    renderMemoryCurrent(system.memory || {}, system.processMemory || {});

    if (Array.isArray(system.history)) {
      // 首次加载：整体替换历史
      state.samples = system.history.slice(-CAPACITY);
    } else if (system.sample) {
      state.samples.push(system.sample);
      while (state.samples.length > CAPACITY) {
        state.samples.shift();
      }
    }
    renderCharts();
  }

  function renderStatus(data) {
    const system = data.system || {};
    applySystem(system);
    renderTps(data);
    renderPlayers(data);
    renderDisk(system.disk || {});
    renderFolders(system.folders || {});

    document.body.classList.remove('is-pending');
  }

  // ------------------------------------------------------------------
  // WebSocket
  // ------------------------------------------------------------------

  function setSocketState(connected) {
    $('ws-state').classList.toggle('is-on', connected);
    setText('ws-text', connected ? '实时' : '未连接');
  }

  function connectSocket() {
    stopSocket();

    const scheme = window.location.protocol === 'https:' ? 'wss://' : 'ws://';
    let socket;
    try {
      socket = new WebSocket(scheme + window.location.host + '/ws');
    } catch (error) {
      scheduleReconnect();
      return;
    }
    state.socket = socket;

    socket.addEventListener('open', function () {
      setSocketState(true);
      state.reconnectDelay = RECONNECT_MIN_MS;
      startPing();
    });

    socket.addEventListener('message', function (event) {
      let payload;
      try {
        payload = JSON.parse(event.data);
      } catch (error) {
        return;
      }
      if (payload.type === 'welcome') {
        setUsername(payload.username);
      } else if (payload.type === 'status' && payload.data) {
        renderStatus(payload.data);
      } else if (payload.type === 'log' && payload.lines) {
        appendLogLines(payload.lines);
      }
    });

    socket.addEventListener('close', function () {
      setSocketState(false);
      stopPing();
      state.socket = null;
      scheduleReconnect();
    });

    socket.addEventListener('error', function () {
      /* close 紧随其后 */
    });
  }

  function stopSocket() {
    stopPing();
    if (state.reconnectTimer) {
      clearTimeout(state.reconnectTimer);
      state.reconnectTimer = null;
    }
    if (state.socket) {
      state.socket.onclose = null;
      try {
        state.socket.close();
      } catch (error) {
        /* 忽略 */
      }
      state.socket = null;
    }
    setSocketState(false);
  }

  function scheduleReconnect() {
    if (!state.username || state.reconnectTimer) {
      return;
    }
    const delay = state.reconnectDelay;
    state.reconnectDelay = Math.min(RECONNECT_MAX_MS, state.reconnectDelay * 2);

    state.reconnectTimer = window.setTimeout(function () {
      state.reconnectTimer = null;
      if (!state.username) {
        return;
      }
      request(SESSION_ENDPOINT)
        .then(function (payload) {
          if (payload && payload.data && payload.data.authenticated) {
            connectSocket();
          } else {
            toLogin();
          }
        })
        .catch(function () {
          scheduleReconnect();
        });
    }, delay);
  }

  function startPing() {
    stopPing();
    state.pingTimer = window.setInterval(function () {
      if (state.socket && state.socket.readyState === WebSocket.OPEN) {
        state.socket.send(JSON.stringify({ type: 'ping' }));
      }
    }, PING_INTERVAL_MS);
  }

  function stopPing() {
    if (state.pingTimer) {
      clearInterval(state.pingTimer);
      state.pingTimer = null;
    }
  }

  function setUsername(username) {
    if (username) {
      state.username = username;
    }
    setText('current-user', state.username || '');
  }

  // ------------------------------------------------------------------
  // 登出
  // ------------------------------------------------------------------

  async function handleLogout() {
    const button = $('logout-button');
    button.disabled = true;
    button.textContent = '退出中...';

    stopSocket();
    try {
      await request(LOGOUT_ENDPOINT, { method: 'POST' });
    } catch (error) {
      /* 登出失败也照样回登录页 */
    }
    toLogin();
  }

  // ------------------------------------------------------------------
  // 启动
  // ------------------------------------------------------------------

  function relayout() {
    charts.forEach(refreshChartLayout);
  }

  async function bootstrap() {
    $('logout-button').addEventListener('click', handleLogout);
    charts.forEach(bindChartEvents);

    document.querySelectorAll('[data-tab]').forEach(function (button) {
      button.addEventListener('click', function () {
        switchTab(button.dataset.tab);
      });
    });

    $('command-run').addEventListener('click', runCommand);
    $('command-input').addEventListener('keydown', handleCommandKey);
    $('setup-form').addEventListener('submit', submitSetup);

    // 手动关掉自动滚动后，重新勾上时立刻回到底部
    $('log-autoscroll').addEventListener('change', function () {
      if (this.checked) {
        const view = $('log-view');
        view.scrollTop = view.scrollHeight;
      }
    });

    relayout();
    observeChartResize();

    let payload;
    try {
      payload = await request(SESSION_ENDPOINT);
    } catch (error) {
      return;
    }

    if (!payload || !payload.data || !payload.data.authenticated) {
      toLogin();
      return;
    }
    setUsername(payload.data.username);

    // 还在用初始密码就先弹窗，其它事情等改完再说
    if (payload.data.mustChangePassword) {
      showSetupModal(payload.data.username);
      return;
    }

    try {
      const status = await request(STATUS_ENDPOINT);
      if (status && status.data) {
        renderStatus(status.data);
      }
    } catch (error) {
      /* 拉取失败不影响长连接 */
    }

    // 首次补看最近的日志，之后走 WebSocket 增量推送
    try {
      const logs = await request(LOG_ENDPOINT);
      if (logs && logs.data && logs.data.lines) {
        appendLogLines(logs.data.lines);
      }
    } catch (error) {
      updateLogMeta(0);
    }

    connectSocket();
  }

  document.addEventListener('DOMContentLoaded', bootstrap);
}());
