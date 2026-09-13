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

  /**
   * 把字节数换算到合适的单位，数值与单位分开返回。
   *
   * <p>分开是因为圆环中心要把数字和单位用不同字号渲染，
   * 拼成一整串就没法分别上样式了。
   */
  function scaleBytes(bytes) {
    const value = Number(bytes);
    if (!isFinite(value) || value <= 0) {
      return { value: 0, unit: 'B', digits: 0 };
    }

    const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
    let index = 0;
    let size = value;
    while (size >= 1024 && index < units.length - 1) {
      size /= 1024;
      index++;
    }
    const digits = index === 0 ? 0 : (size >= 100 ? 0 : (size >= 10 ? 1 : 2));
    return { value: size, unit: units[index], digits: digits };
  }

  function formatBytes(bytes) {
    const scaled = scaleBytes(bytes);
    return scaled.value.toFixed(scaled.digits) + ' ' + scaled.unit;
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

  // ------------------------------------------------------------------
  // 自定义下拉框
  // ------------------------------------------------------------------

  /**
   * 自己画一个下拉框。
   *
   * <p>不用原生 {@code <select>}：它的展开列表由操作系统绘制，
   * 样式完全改不动，跟面板其它控件摆在一起很突兀。
   *
   * <p>约定的 DOM 结构（都在 root 之内）：
   * <pre>
   *   .dropdown__button   触发按钮
   *   .dropdown__value    显示当前选中项
   *   .dropdown__menu     选项列表容器
   * </pre>
   *
   * @param rootId   根元素 id
   * @param onChange 选中项变化时的回调，参数是新值
   */
  function createDropdown(rootId, onChange) {
    const root = $(rootId);
    const button = root.querySelector('.dropdown__button');
    const valueNode = root.querySelector('.dropdown__value');
    const menu = root.querySelector('.dropdown__menu');

    // 把菜单搬到 body 下。
    //
    // 它原本是按钮的兄弟节点，会被卡片的 overflow: hidden 裁掉。
    // 而卡片还带 backdrop-filter，那会让卡片成为 fixed 定位的包含块 ——
    // 所以「改成 position: fixed 就能逃出裁剪」这条常规出路在这里也不成立。
    // 只有让它不再是卡片的后代，才彻底脱离这两层限制。
    document.body.appendChild(menu);

    let value = '';

    function close() {
      menu.hidden = true;
      button.classList.remove('is-open');
      button.setAttribute('aria-expanded', 'false');
    }

    function open() {
      // 必须先显示再量高度：hidden 的元素 offsetHeight 是 0，
      // 而下面要靠它判断该往下弹还是往上翻
      menu.hidden = false;
      place();
      button.classList.add('is-open');
      button.setAttribute('aria-expanded', 'true');
    }

    /**
     * 把菜单贴到按钮下方。
     *
     * <p>菜单已经不是按钮的后代，CSS 里没法再用 {@code top: 100%} 定位，
     * 只能按视口坐标自己算。
     */
    function place() {
      const rect = button.getBoundingClientRect();
      const height = menu.offsetHeight;
      const gap = 6;

      let top = rect.bottom + gap;
      // 底下放不下就翻到按钮上方
      if (top + height > window.innerHeight && rect.top - gap - height > 0) {
        top = rect.top - gap - height;
      }

      menu.style.left = rect.left + 'px';
      menu.style.width = rect.width + 'px';
      menu.style.top = top + 'px';
    }

    function select(next) {
      if (next === value) {
        close();
        return;
      }
      value = next;
      renderValue();
      close();
      if (onChange) {
        onChange(value);
      }
    }

    function renderValue() {
      // 显示文本取自选项本身而不是 value —— 有的场景值是英文枚举、显示要中文
      let label = value;
      menu.querySelectorAll('.dropdown__option').forEach(function (option) {
        const selected = option.dataset.value === value;
        option.setAttribute('aria-selected', selected ? 'true' : 'false');
        if (selected) {
          label = option.textContent;
        }
      });
      valueNode.textContent = label || '--';
    }

    button.addEventListener('click', function (event) {
      // 不冒泡到 document，否则刚打开就被那个「点外面关闭」的监听收回去
      event.stopPropagation();
      if (menu.hidden) {
        open();
      } else {
        close();
      }
    });

    document.addEventListener('click', function (event) {
      // 菜单已经不在 root 里了，两边都得判，否则点选项会被当成「点了外面」
      if (!menu.hidden && !root.contains(event.target) && !menu.contains(event.target)) {
        close();
      }
    });
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && !menu.hidden) {
        close();
      }
    });

    // 固定定位的菜单不会跟着按钮走，一滚就脱开了，收起来最省事。
    // capture 是为了连卡片内部那些自己的滚动容器也一起捕获到
    window.addEventListener('resize', close);
    window.addEventListener('scroll', close, true);

    return {
      /**
       * 重建选项。当前选中项若不在新列表里，退回第一项。
       *
       * <p>每项可以是字符串（值即显示文本），也可以是 {@code {value, label}} ——
       * 「值是英文枚举、显示要中文」的场景用得上。
       */
      setOptions: function (options) {
        const values = [];

        menu.textContent = '';
        options.forEach(function (item) {
          const itemValue = typeof item === 'string' ? item : item.value;
          values.push(itemValue);

          const option = document.createElement('li');
          option.className = 'dropdown__option';
          option.dataset.value = itemValue;
          option.setAttribute('role', 'option');
          option.textContent = typeof item === 'string' ? item : (item.label || item.value);
          option.addEventListener('click', function () {
            select(itemValue);
          });
          menu.appendChild(option);
        });

        if (values.indexOf(value) < 0) {
          value = values.length ? values[0] : '';
        }
        renderValue();
      },

      /**
       * 直接设值，<b>不触发 onChange</b>。
       *
       * <p>这条很关键：加载设置时要用服务端的值回填，
       * 触发回调就等于「打开页面就自动保存一次」。
       */
      setValue: function (next) {
        value = next || '';
        renderValue();
      },

      getValue: function () {
        return value;
      },

      close: close
    };
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
      const line = root.querySelector('[data-line="' + def.key + '"]');
      const dot = root.querySelector('[data-dot="' + def.key + '"]');

      // 颜色由 swatch 类决定，CSS 里只按这三档配色写规则，
      // 不需要为每个系列 key 各写一条 —— 那样漏一个就是一条隐形的线
      if (line) {
        line.classList.add(def.swatch);
      }
      if (dot) {
        dot.classList.add(def.swatch);
      }

      return {
        key: def.key,
        label: def.label,
        swatch: def.swatch,
        optional: !!def.optional,
        on: !def.optional,
        line: line,
        dot: dot,
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
    setSetupErrorFor('setup-error', text);
  }

  function setSetupErrorFor(id, text) {
    const box = $(id);
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

  /** 当前选项卡，用于判断是不是「离开设置页」 */
  let currentTab = 'overview';

  function scrollLogToBottom() {
    const view = $('log-view');
    if (view) {
      view.scrollTop = view.scrollHeight;
    }
  }

  function switchTab(name) {
    // 离开设置页之前把没保存的玩家管理设置落盘
    if (currentTab === 'settings' && name !== 'settings') {
      saveSettings();
    }
    currentTab = name;

    // 面板直接从 DOM 里取，而不是拿一个写死的列表去对。
    // 写死的话，一旦 HTML 里加了新面板而这份列表没跟上，
    // 切过去时所有已知面板都会因为 active=false 被藏掉，整个内容区变空白。
    document.querySelectorAll('.panes > .content').forEach(function (pane) {
      pane.hidden = pane.id !== 'panel-' + name;
    });

    document.querySelectorAll('[data-tab]').forEach(function (button) {
      const active = button.dataset.tab === name;
      button.classList.toggle('is-active', active);
      button.setAttribute('aria-selected', String(active));
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
      } else if (name === 'files') {
        // 第一次切过来才去拉目录，不做无谓请求
        if (!files.loaded) {
          loadFiles(files.path);
        }
      } else if (name === 'settings') {
        // 每次进来都清空密码框，避免上一次的输入残留
        resetSettingsForm();
        loadPanelSettings();
      } else if (name === 'players') {
        // 拉一次立刻有内容，之后由 WebSocket 推送持续刷新
        loadPlayers();
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
  // 文件管理
  // ==================================================================

  const FILES_ENDPOINT = '/api/files';
  const FILES_ACTION_ENDPOINT = '/api/files/action';
  const FILES_UPLOAD_ENDPOINT = '/api/files/upload';
  const FILES_DOWNLOAD_ENDPOINT = '/api/files/download';
  const FILES_TEXT_ENDPOINT = '/api/files/text';

  const files = {
    loaded: false,
    /** 当前目录，相对服务端根目录，正斜杠分隔，根目录是空串 */
    path: '',
    entries: [],
    selected: new Set(),
    /** 剪贴板：{ mode: 'copy' | 'cut', paths: [...] } */
    clipboard: null,
    busy: false
  };

  /**
   * 右下角短暂提示。文件管理与控制台都用它，比状态行显眼。
   */
  function notify(text, kind) {
    const box = $('toasts');
    if (!box) {
      return;
    }
    const toast = document.createElement('div');
    toast.className = 'toast' + (kind ? ' toast--' + kind : '');
    toast.textContent = text;
    box.appendChild(toast);

    window.setTimeout(function () {
      toast.classList.add('toast--out');
      window.setTimeout(function () {
        toast.remove();
      }, 260);
    }, 3200);
  }

  function setFilesStatus(text, kind) {
    const node = $('files-status');
    if (!node) {
      return;
    }
    node.textContent = text;
    node.classList.toggle('is-error', kind === 'error');
    node.classList.toggle('is-ok', kind === 'ok');
  }

  function formatTime(millis) {
    if (!millis) {
      return '--';
    }
    const date = new Date(millis);
    const pad = value => String(value).padStart(2, '0');
    return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate())
      + ' ' + pad(date.getHours()) + ':' + pad(date.getMinutes());
  }

  function joinPath(directory, name) {
    return directory ? directory + '/' + name : name;
  }

  function parentPath(path) {
    const index = path.lastIndexOf('/');
    return index < 0 ? '' : path.substring(0, index);
  }

  function baseName(path) {
    const index = path.lastIndexOf('/');
    return index < 0 ? path : path.substring(index + 1);
  }

  // ---------------------------------------------------------------- 加载

  async function loadFiles(path) {
    try {
      const payload = await request(FILES_ENDPOINT + '?path=' + encodeURIComponent(path || ''));
      const data = (payload && payload.data) || {};

      files.loaded = true;
      files.path = data.path || '';
      files.entries = data.entries || [];
      files.selected.clear();

      setText('files-root', data.root || '');
      renderCrumbs();
      renderFileList();
      setFilesStatus('共 ' + files.entries.length + ' 项');
    } catch (error) {
      setFilesStatus(error.message, 'error');
    }
  }

  // ---------------------------------------------------------------- 渲染

  function renderCrumbs() {
    const box = $('files-crumbs');
    box.textContent = '';

    const root = document.createElement('button');
    root.type = 'button';
    root.className = 'crumb' + (files.path ? '' : ' is-current');
    root.textContent = '服务端根目录';
    root.addEventListener('click', function () {
      loadFiles('');
    });
    box.appendChild(root);

    const parts = files.path ? files.path.split('/') : [];
    let accumulated = '';
    parts.forEach(function (part, index) {
      accumulated = accumulated ? accumulated + '/' + part : part;

      const sep = document.createElement('span');
      sep.className = 'crumb-sep';
      sep.textContent = '/';
      box.appendChild(sep);

      const target = accumulated;
      const button = document.createElement('button');
      button.type = 'button';
      const last = index === parts.length - 1;
      button.className = 'crumb' + (last ? ' is-current' : '');
      button.textContent = part;
      button.addEventListener('click', function () {
        loadFiles(target);
      });
      box.appendChild(button);
    });
  }

  function renderFileList() {
    const list = $('files-list');
    list.textContent = '';

    if (!files.entries.length) {
      setFilesStatus('这个目录是空的');
      updateFileButtons();
      return;
    }

    files.entries.forEach(function (entry, index) {
      const row = document.createElement('div');
      row.className = 'file-row';
      row.setAttribute('role', 'option');
      row.dataset.path = entry.path;
      row.style.animationDelay = (Math.min(index, 16) * 0.012).toFixed(3) + 's';

      // 选择框
      const checkCell = document.createElement('span');
      checkCell.className = 'files-col files-col--check';
      const check = document.createElement('input');
      check.type = 'checkbox';
      check.checked = files.selected.has(entry.path);
      check.setAttribute('aria-label', '选择 ' + entry.name);
      check.addEventListener('click', function (event) {
        event.stopPropagation();
      });
      check.addEventListener('change', function () {
        toggleFileSelection(entry.path);
        applySelection();
      });
      checkCell.appendChild(check);
      row.appendChild(checkCell);

      // 名称
      const nameCell = document.createElement('span');
      const nameButton = document.createElement('button');
      nameButton.type = 'button';
      nameButton.className = 'file-name' + (entry.directory ? ' is-dir' : '');

      const icon = document.createElementNS(SVG_NS, 'svg');
      icon.setAttribute('class', 'icon');
      icon.setAttribute('aria-hidden', 'true');
      const use = document.createElementNS(SVG_NS, 'use');
      use.setAttribute('href', entry.directory ? '#i-folder' : '#i-doc');
      icon.appendChild(use);
      nameButton.appendChild(icon);

      const label = document.createElement('span');
      label.textContent = entry.name;
      nameButton.appendChild(label);

      nameButton.title = entry.directory
        ? '打开文件夹'
        : (entry.editable ? '编辑文件' : '该类型不支持文本编辑');

      if (!entry.directory && !entry.editable) {
        nameButton.classList.add('is-locked');
      }

      nameButton.addEventListener('click', function (event) {
        event.stopPropagation();

        if (entry.directory) {
          loadFiles(entry.path);
          return;
        }
        if (entry.editable) {
          openEditor(entry.path);
          return;
        }

        // 不能编辑的说清楚原因，别让人以为是点坏了
        const dot = entry.name.lastIndexOf('.');
        const extension = dot > 0 ? entry.name.substring(dot + 1).toLowerCase() : '';
        const reason = extension ? '不支持编辑 .' + extension + ' 文件' : '该文件不能用文本编辑器打开';
        notify(reason, 'error');
        setFilesStatus(entry.name + '：' + reason, 'error');
      });
      nameCell.appendChild(nameButton);
      row.appendChild(nameCell);

      // 大小、时间
      row.appendChild(textCell('files-col--size', entry.directory ? '—' : formatBytes(entry.size)));
      row.appendChild(textCell('files-col--time', formatTime(entry.modified)));

      // 单文件下载
      const ops = document.createElement('span');
      ops.className = 'files-col files-col--ops';
      if (!entry.directory) {
        const download = document.createElement('button');
        download.type = 'button';
        download.className = 'file-op';
        download.textContent = '下载';
        download.addEventListener('click', function (event) {
          event.stopPropagation();
          downloadFile(entry.path);
        });
        ops.appendChild(download);
      }
      row.appendChild(ops);

      row.addEventListener('click', function () {
        toggleFileSelection(entry.path);
        applySelection();
      });

      if (files.selected.has(entry.path)) {
        row.classList.add('is-selected');
      }
      list.appendChild(row);
    });

    updateFileButtons();
  }

  function textCell(extraClass, text) {
    const cell = document.createElement('span');
    cell.className = 'files-col ' + extraClass;
    cell.textContent = text;
    return cell;
  }

  /**
   * 只刷新选择状态，不重建列表。
   *
   * <p>之前这里走的是 renderFileList()，会把整个列表的 DOM 重建一遍，
   * 于是每一行的入场动画都会跟着重播一次 —— 勾一个复选框闪一下全表。
   * 现在只改受影响行的 class 与勾选态，顺带也更省。
   */
  function applySelection() {
    const list = $('files-list');
    if (!list) {
      return;
    }

    list.querySelectorAll('.file-row').forEach(function (row) {
      const selected = files.selected.has(row.dataset.path);
      row.classList.toggle('is-selected', selected);

      const check = row.querySelector('input[type="checkbox"]');
      if (check) {
        check.checked = selected;
      }
    });

    updateFileButtons();
  }

  function toggleFileSelection(path) {
    if (files.selected.has(path)) {
      files.selected.delete(path);
    } else {
      files.selected.add(path);
    }
  }

  function selectedEntries() {
    return files.entries.filter(function (entry) {
      return files.selected.has(entry.path);
    });
  }

  /** 按选择状态与剪贴板状态决定哪些按钮可用 */
  function updateFileButtons() {
    const count = files.selected.size;
    setText('files-count', count ? '已选 ' + count + ' 项' : '未选择');

    const entries = selectedEntries();
    const single = entries.length === 1;
    const singleFile = single && !entries[0].directory;
    const anyArchive = single && isArchive(entries[0].name);

    const rules = {
      copy: count > 0,
      cut: count > 0,
      rename: single,
      edit: singleFile && entries[0].editable,
      compress: count > 0,
      extract: anyArchive,
      delete: count > 0,
      paste: files.clipboard !== null && !files.busy
    };

    document.querySelectorAll('[data-cmd]').forEach(function (button) {
      const rule = rules[button.dataset.cmd];
      if (rule !== undefined) {
        button.disabled = !rule || files.busy;
      }
    });

    const all = $('files-check-all');
    if (all) {
      all.checked = files.entries.length > 0 && count === files.entries.length;
    }
  }

  function isArchive(name) {
    const lower = (name || '').toLowerCase();
    return lower.endsWith('.zip') || lower.endsWith('.7z');
  }

  // ---------------------------------------------------------------- 操作

  async function fileAction(action, payload) {
    files.busy = true;
    updateFileButtons();
    try {
      const body = Object.assign({ action: action }, payload);
      const response = await request(FILES_ACTION_ENDPOINT, {
        method: 'POST',
        body: JSON.stringify(body)
      });
      const message = (response && response.message) || '完成';
      await loadFiles(files.path);
      setFilesStatus(message, 'ok');
    } catch (error) {
      setFilesStatus(error.message, 'error');
      updateFileButtons();
    } finally {
      files.busy = false;
      updateFileButtons();
    }
  }

  async function fileCommand(cmd) {
    const entries = selectedEntries();

    switch (cmd) {
      // 上传由隐藏的文件选择框触发，这里不处理
      case 'upload':
        return;

      case 'refresh':
        await loadFiles(files.path);
        return;

      case 'mkdir':
      case 'newfile': {
        const isDir = cmd === 'mkdir';
        const answer = await openFileDialog({
          title: isDir ? '新建文件夹' : '新建文件',
          desc: '将在当前目录创建，名称不能包含 / \\ : * ? " < > |',
          label: '名称',
          value: '',
          format: false
        });
        if (!answer) {
          return;
        }
        await fileAction(cmd, { path: joinPath(files.path, answer.value) });
        return;
      }

      case 'edit':
        // 按钮本身已经按 editable 禁用了，这里再兜一层
        if (entries[0].editable) {
          openEditor(entries[0].path);
        }
        return;

      case 'rename': {
        const entry = entries[0];
        const answer = await openFileDialog({
          title: '重命名',
          desc: '重命名 ' + entry.name,
          label: '新名称',
          value: entry.name,
          format: false
        });
        if (!answer) {
          return;
        }
        await fileAction('rename', { path: entry.path, name: answer.value });
        return;
      }

      case 'delete':
        // 按需求去掉二次确认，点一下直接删
        await fileAction('delete', { paths: Array.from(files.selected) });
        notify('已删除 ' + entries.length + ' 项', 'ok');
        return;

      case 'copy':
      case 'cut':
        files.clipboard = { mode: cmd, paths: Array.from(files.selected) };
        setFilesStatus('已' + (cmd === 'copy' ? '复制' : '剪切') + ' ' + entries.length + ' 项，进入目标目录后点「粘贴」', 'ok');
        updateFileButtons();
        return;

      case 'paste': {
        if (!files.clipboard) {
          return;
        }
        const action = files.clipboard.mode === 'copy' ? 'copy' : 'move';
        const paths = files.clipboard.paths;
        await fileAction(action, { paths: paths, target: files.path });
        if (files.clipboard.mode === 'cut') {
          files.clipboard = null;
        }
        return;
      }

      case 'compress': {
        const answer = await openFileDialog({
          title: '压缩',
          desc: '将选中的 ' + entries.length + ' 项压缩到当前目录。',
          label: '',
          value: '',
          format: true
        });
        if (!answer) {
          return;
        }
        await fileAction('compress', {
          paths: Array.from(files.selected),
          target: files.path,
          format: answer.format
        });
        return;
      }

      case 'extract': {
        const answer = await openFileDialog({
          title: '解压',
          desc: '将解压到当前目录下的同名文件夹。',
          label: '',
          value: '',
          format: true
        });
        if (!answer) {
          return;
        }
        const entry = entries[0];
        const folder = entry.name.replace(/\.(zip|7z)$/i, '');
        await fileAction('extract', {
          path: entry.path,
          target: joinPath(files.path, folder),
          format: answer.format
        });
        return;
      }

      default:
        setFilesStatus('未知操作: ' + cmd, 'error');
    }
  }

  /**
   * 下载前先用 HEAD 探一下。
   *
   * <p>直接让浏览器导航到下载地址的话，一旦服务端回的是错误 JSON，
   * 整个面板页面会被那个 JSON 顶掉。所以先确认能下，再真正触发。
   */
  async function downloadFile(path) {
    const url = FILES_DOWNLOAD_ENDPOINT + '?path=' + encodeURIComponent(path);
    const name = baseName(path);

    let probe;
    try {
      probe = await fetch(url, { method: 'HEAD', credentials: 'same-origin' });
    } catch (error) {
      setFilesStatus('下载失败：无法连接服务端', 'error');
      return;
    }

    if (!probe.ok) {
      const message = probe.status === 404 ? '文件不存在'
        : (probe.status === 403 ? '路径不被允许' : '服务端返回 ' + probe.status);
      setFilesStatus('下载失败：' + message, 'error');
      notify('下载失败：' + message, 'error');
      return;
    }

    const link = document.createElement('a');
    link.href = url;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);

    notify('已开始下载：' + name, 'ok');
    setFilesStatus('已开始下载 ' + name, 'ok');
  }

  // ---------------------------------------------------------------- 上传

  async function uploadFiles(fileList) {
    if (!fileList || !fileList.length) {
      return;
    }

    files.busy = true;
    updateFileButtons();

    const progress = $('files-progress');
    progress.hidden = false;

    let done = 0;
    try {
      for (let i = 0; i < fileList.length; i++) {
        const file = fileList[i];
        const label = '(' + (i + 1) + '/' + fileList.length + ') ' + file.name;

        setUploadProgress(0, label);
        await uploadOne(file, function (percent) {
          setUploadProgress(percent, label);
        });
        done++;
      }

      await loadFiles(files.path);
      setFilesStatus('已上传 ' + done + ' 个文件', 'ok');
      notify('已上传 ' + done + ' 个文件', 'ok');
    } catch (error) {
      setFilesStatus(error.message, 'error');
      notify(error.message, 'error');
    } finally {
      files.busy = false;
      progress.hidden = true;
      updateFileButtons();
    }
  }

  function setUploadProgress(percent, label) {
    const clamped = Math.max(0, Math.min(100, percent));
    $('files-progress-fill').style.width = clamped + '%';
    setText('files-progress-text', label + '  ' + clamped + '%');
  }

  /**
   * 用 XMLHttpRequest 而不是 fetch：fetch 拿不到上传进度。
   * 请求体就是文件的原始字节，服务端直接流式写盘。
   */
  function uploadOne(file, onProgress) {
    return new Promise(function (resolve, reject) {
      const url = FILES_UPLOAD_ENDPOINT
        + '?path=' + encodeURIComponent(files.path)
        + '&name=' + encodeURIComponent(file.name);

      const xhr = new XMLHttpRequest();
      xhr.open('POST', url, true);
      xhr.withCredentials = true;

      xhr.upload.addEventListener('progress', function (event) {
        if (event.lengthComputable) {
          onProgress(Math.round(event.loaded / event.total * 100));
        }
      });

      xhr.addEventListener('load', function () {
        if (xhr.status >= 200 && xhr.status < 300) {
          resolve();
          return;
        }
        let message = '上传失败（' + xhr.status + '）';
        try {
          const payload = JSON.parse(xhr.responseText);
          if (payload && payload.message) {
            message = payload.message;
          }
        } catch (error) {
          // 保留默认提示
        }
        if (xhr.status === 401) {
          toLogin();
        }
        reject(new Error(message));
      });

      xhr.addEventListener('error', function () {
        reject(new Error('上传中断'));
      });

      xhr.send(file);
    });
  }

  // ---------------------------------------------------------------- 文本编辑器

  /** 正在编辑的相对路径 */
  let editorPath = '';

  function updateEditorMeta() {
    const text = $('editor-content').value;
    const lines = text.split('\n').length;
    // 按 UTF-8 字节数算，和落盘大小一致
    const bytes = typeof TextEncoder === 'function'
      ? new TextEncoder().encode(text).length
      : text.length;
    setText('editor-meta', lines + ' 行 · ' + formatBytes(bytes));
  }

  async function openEditor(path) {
    try {
      const payload = await request(FILES_TEXT_ENDPOINT + '?path=' + encodeURIComponent(path));
      const data = (payload && payload.data) || {};

      editorPath = path;
      $('editor-content').value = data.content || '';
      setText('editor-path', data.path || path);
      setSetupErrorFor('editor-error', '');

      // 非 UTF-8 的文件打开是能打开的，但保存要提前警告
      const warning = $('editor-warning');
      if (data.utf8 === false) {
        warning.textContent = '这个文件不是 UTF-8 编码，直接保存会把非 ASCII 字符写坏。';
        warning.hidden = false;
      } else {
        warning.hidden = true;
      }

      updateEditorMeta();
      $('editor-modal').hidden = false;
      $('editor-content').focus();
    } catch (error) {
      setFilesStatus(error.message, 'error');
      notify(error.message, 'error');
    }
  }

  function closeEditor() {
    $('editor-modal').hidden = true;
    editorPath = '';
  }

  async function saveEditor() {
    const button = $('editor-save');
    if (button.disabled || !editorPath) {
      return;
    }

    button.disabled = true;
    setSetupErrorFor('editor-error', '');

    try {
      await request(FILES_TEXT_ENDPOINT, {
        method: 'POST',
        body: JSON.stringify({ path: editorPath, content: $('editor-content').value })
      });
      const saved = baseName(editorPath);
      closeEditor();
      await loadFiles(files.path);
      setFilesStatus('已保存 ' + saved, 'ok');
      notify('已保存：' + saved, 'ok');
    } catch (error) {
      setSetupErrorFor('editor-error', error.message);
    } finally {
      button.disabled = false;
    }
  }

  // ==================================================================
  // 玩家管理
  // ==================================================================

  const PLAYERS_ENDPOINT = '/api/players';
  const PLAYERS_ACTION_ENDPOINT = '/api/players/action';

  const players = {
    page: 1,
    pageSize: 20,
    totalPages: 1,
    /** 当前筛选条件，取值与后端 PlayerFilter 的 id 一致 */
    filter: 'Online',
    /** 最近一次渲染的名单，翻页时直接用它，不必再请求 */
    last: [],
    /** 名单指纹：只有名单变了才重建行 */
    key: '',
    /** 玩家名 -> { position, vitals }，原地刷新时用 */
    rows: new Map()
  };

  /** 筛选值到中文标签。值是后端给的英文枚举 */
  const PLAYER_FILTER_LABELS = {
    All: '全部玩家',
    Online: '在线玩家',
    Offline: '离线玩家',
    Banned: '已封禁'
  };

  /**
   * 玩家能做的操作：[动作, 文案, 是否要求在线, 是否需要经济系统]。
   *
   * <p>封禁不要求在线，因为它最后落到服务端的封禁名单上，只认名字不认人；
   * 经济操作同理，都是按 UUID 记账的。
   */
  const PLAYER_ACTIONS = [
    ['kick', '踢出', true, false],
    ['ban', '封禁', false, false],
    ['money', '余额', false, true],
    ['teleport', '传送', true, false],
    ['command', '执行命令', true, false]
  ];

  /**
   * 筛选下拉框。
   *
   * <p>「在线」以外的筛选不靠高频推送刷新（那要遍历整份名册），
   * 改由后端推一个版本号、变了才重新拉，见 {@link checkPlayersRevision}。
   */
  const playerFilterDropdown = createDropdown('player-filter-dropdown', function (value) {
    players.filter = value;
    players.page = 1;
    // 换了数据集，之前那套「只改坐标不重建行」的原地刷新就不适用了
    players.key = '';
    loadPlayers();
  });

  /** 后端玩家数据的版本号，-1 表示还没收到过 */
  let playersRevision = -1;

  /** 重拉列表的防抖定时器句柄，0 表示没有在等的 */
  let playersReloadTimer = 0;

  /**
   * 版本号变了就重新拉一次列表。
   *
   * <p>名册和封禁状态的变化（有人上下线、有人被解封、临时封禁到期）
   * 都不在坐标那条高频推送里，靠这个版本号通知。
   */
  function checkPlayersRevision(next) {
    if (typeof next !== 'number' || next === playersRevision) {
      return;
    }

    // 第一次只是记下来：切到该页时本来就会拉一次，不必再来一遍
    const first = playersRevision < 0;
    playersRevision = next;

    if (!first && currentTab === 'players') {
      schedulePlayersReload();
    }
  }

  /**
   * 攒一下再拉。
   *
   * <p>版本号的变化可能是连着来的 —— 几个人同时上线、批量解封 ——
   * 每次都拉一遍全量列表纯属浪费。
   */
  function schedulePlayersReload() {
    if (playersReloadTimer) {
      return;
    }
    playersReloadTimer = window.setTimeout(function () {
      playersReloadTimer = 0;
      loadPlayers();
    }, 800);
  }

  function formatCoord(value) {
    return (Math.round(value * 100) / 100).toFixed(2);
  }

  /**
   * 把时间戳说成「3 天前」。比一串日期好读，也不用管时区。
   */
  function relativeTime(timestamp) {
    if (!timestamp) {
      return '未知';
    }

    const diff = Date.now() - timestamp;
    if (diff < 0) {
      return '刚刚';
    }

    const minute = 60000;
    const hour = 60 * minute;
    const day = 24 * hour;

    if (diff < minute) {
      return '刚刚';
    }
    if (diff < hour) {
      return Math.floor(diff / minute) + ' 分钟前';
    }
    if (diff < day) {
      return Math.floor(diff / hour) + ' 小时前';
    }
    if (diff < 30 * day) {
      return Math.floor(diff / day) + ' 天前';
    }
    return new Date(timestamp).toLocaleDateString();
  }

  /**
   * 按名字取正版头像的服务。
   *
   * <p>它们直接吃玩家名、返回裁好的头部图，所以离线模式下也能用
   * （那种情况下服务端的 Profile 里没有贴图，只能靠名字去查）。
   * 两个是互相备份的关系，一个不通会自动试下一个。
   */
  const SKIN_PROVIDERS = [
    'https://mc-heads.net/avatar/{name}/64',
    'https://minotar.net/helm/{name}/64'
  ];

  /**
   * 建一个头像 img，按优先级依次尝试多个来源，全都不行才落到面板自带的 Steve。
   *
   * <p>注意来源分两类：
   * <ul>
   *     <li>Mojang 官方贴图是<b>整张 64×64 皮肤</b>，要靠 CSS 裁出头部；</li>
   *     <li>按名字取的那些<b>已经是头部图</b>，直接用。</li>
   * </ul>
   * 所以切来源时要同步切换裁剪用的 class。
   */
  function buildAvatar(entry) {
    const sources = [];
    if (entry.skin) {
      // 服务端解析出来的官方贴图，最权威，放第一位
      sources.push(entry.skin);
    }
    SKIN_PROVIDERS.forEach(function (template) {
      sources.push(template.replace('{name}', encodeURIComponent(entry.name)));
    });
    // 最后一档：面板自己画的 Steve，同时也是「整张皮肤」之外唯一的本地兜底
    sources.push('/api/players/skin?name=' + encodeURIComponent(entry.name));

    const avatar = document.createElement('img');
    avatar.alt = '';
    avatar.loading = 'lazy';

    let index = 0;
    function load() {
      const source = sources[index];
      avatar.classList.toggle('is-full-skin', source === entry.skin);
      avatar.src = source;
    }

    avatar.addEventListener('error', function () {
      index++;
      if (index < sources.length) {
        load();
      }
      // 全试完了就停在这个图标上，不再重试
    });

    load();
    return avatar;
  }

  function positionText(entry) {
    return entry.world + '  '
      + formatCoord(entry.x) + ' / ' + formatCoord(entry.y) + ' / ' + formatCoord(entry.z);
  }

  /**
   * 玩家行中间那一列：坐标。
   *
   * <p>在线的是实时位置，离线的是最后下线的位置，两者格式一样 ——
   * 是不是实时的，看第二行的「最后在线」就清楚了。
   */
  function playerStatusText(entry) {
    return entry.world ? positionText(entry) : '位置未知';
  }

  /**
   * 玩家行第二行。
   *
   * <p>在线的显示 UUID；离线的显示最后在线时间，这个对管理员更有用，
   * 也顺带说明了上面那串坐标不是实时的。
   */
  function playerSubText(entry) {
    if (entry.online) {
      // 服主手动封的、从没在本服上线过的玩家拿不到 UUID
      return entry.uuid || '无记录';
    }
    return entry.lastSeen > 0 ? '最后在线 ' + relativeTime(entry.lastSeen) : '无记录';
  }

  /** 玩家行右侧的操作按钮。长得都一样，只有文案、动作和配色不同 */
  function buildPlayerButton(label, action, name, extraClass) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn-mini' + (extraClass || '');
    button.textContent = label;
    button.addEventListener('click', function () {
      runPlayerAction(name, action);
    });
    return button;
  }

  // ---------------------------------------------------------------- 生存状态

  /** 生命值可能是 19.5 这种小数，整数就不带小数点 */
  function formatStat(value) {
    const number = Number(value) || 0;
    return Number.isInteger(number) ? String(number) : number.toFixed(1);
  }

  /** 生存状态那几项：[键, 标签]。余额不在这里，见 fillVitals */
  const VITALS = [
    ['health', '生命'],
    ['food', '饥饿'],
    ['armor', '护甲'],
    ['level', '经验']
  ];

  /**
   * 造一个状态格子：标签 + 数字 + 后缀。
   *
   * <p>数字和后缀先建成空的，内容统一由 {@link updateVitals} 填 ——
   * 建行时和之后每次刷新走同一段代码，不会出现两边写得不一样。
   */
  function buildVital(key, label) {
    const item = document.createElement('span');
    item.className = 'vital vital--' + key;

    const name = document.createElement('span');
    name.className = 'vital__label';
    name.textContent = label;
    item.appendChild(name);

    item.appendChild(document.createElement('b'));
    item.appendChild(document.createElement('i'));

    return item;
  }

  /** 按名字找格子。用类名而不是下标 —— 顺序会随条目增减而变 */
  function vitalOf(box, key) {
    return box.querySelector('.vital--' + key);
  }

  function fillVitals(box, entry) {
    // 生存状态只在人还在服务器里的时候才有意义
    if (entry.online) {
      VITALS.forEach(function (pair) {
        box.appendChild(buildVital(pair[0], pair[1]));
      });
    }

    // 余额格子总是建出来，没有经济系统时用 hidden 藏起来。
    // 这样「建行之后才接上经济」（Vault 比本插件晚加载）就不必重建整张表了
    box.appendChild(buildVital('balance', '余额'));

    updateVitals(box, entry);
  }

  /**
   * 只改数字，不动 DOM 结构。
   *
   * <p>血量是几秒就变的量，整行重建会让入场动画反复重播，所以拆开单独刷。
   * 每一项都先找得到才填 —— 离线玩家的行里没有生存状态那几个格子。
   */
  function updateVitals(box, entry) {
    const healthItem = vitalOf(box, 'health');
    if (healthItem) {
      const health = Number(entry.health) || 0;
      const maxHealth = Number(entry.maxHealth) || 0;
      const absorption = Number(entry.absorption) || 0;

      setVital(healthItem,
        formatStat(health) + '/' + formatStat(maxHealth),
        absorption > 0 ? '+' + formatStat(absorption) : '',
        '生命值 ' + formatStat(health) + ' / ' + formatStat(maxHealth)
          + (absorption > 0 ? '，另有 ' + formatStat(absorption) + ' 点伤害吸收' : ''),
        absorption > 0 ? 'is-absorption' : '');

      healthItem.classList.toggle('is-low', maxHealth > 0 && health / maxHealth <= 0.3);
    }

    const foodItem = vitalOf(box, 'food');
    if (foodItem) {
      const food = entry.food || 0;
      setVital(foodItem, String(food), '', '饥饿度 ' + food + ' / 20');
    }

    const armorItem = vitalOf(box, 'armor');
    if (armorItem) {
      const armor = entry.armor || 0;
      setVital(armorItem, String(armor), '', '护甲值 ' + armor);
    }

    const levelItem = vitalOf(box, 'level');
    if (levelItem) {
      const level = entry.level || 0;
      const progress = Math.round((Number(entry.expProgress) || 0) * 100);
      setVital(levelItem, String(level), '级 ' + progress + '%',
        '经验等级 ' + level
          + '，本级进度 ' + progress + '%'
          + '，累计 ' + (entry.totalExp || 0) + ' 点');
    }

    // 余额对离线玩家照样有效，所以不在上面那个 online 判断里
    const balanceItem = vitalOf(box, 'balance');
    if (balanceItem) {
      const text = entry.balanceText || '';
      balanceItem.hidden = !text;
      if (text) {
        setVital(balanceItem, text, '', '当前余额 ' + text);
      }
    }
  }

  function setVital(item, value, suffix, title, suffixClass) {
    const strong = item.querySelector('b');
    const small = item.querySelector('i');
    if (strong) {
      strong.textContent = value;
    }
    if (small) {
      small.textContent = suffix;
      small.className = suffixClass || '';
    }
    item.title = title;
  }

  /**
   * 原地刷新一行 —— 只改会变的那几处文本，不重建 DOM。
   *
   * <p>坐标和血量几秒就动一次，整表重建会让每一行的入场动画不停重播。
   */
  function updateRowLive(row, entry) {
    row.position.textContent = playerStatusText(entry);
    // 里面自己判断哪些格子该填 —— 离线玩家没有生存状态，但有余额
    updateVitals(row.vitals, entry);
  }

  /**
   * 收到一份在线玩家推送。
   *
   * <p>只有当前正在看「在线玩家」时才拿去渲染 —— 其它筛选的数据要遍历整份名册，
   * 不可能每 3 刻推一遍。切回去时 {@link loadPlayers} 会重新拉一次，
   * 所以也不会看到停在旧时刻的数据。
   */
  function onOnlinePush(entries) {
    const list = entries || [];

    if (players.filter === 'Online') {
      applyPlayerData(list);
      return;
    }

    // 其它筛选下，列表里该有谁是后端按条件算的，前端不该自己增删 ——
    // 「刚上线的人要不要出现在『全部』里」「被解封的人要不要从『已封禁』里消失」，
    // 这些只有后端说得清。增删交给版本号触发的重拉，这里只做原地更新。
    mergeOnlineIntoRows(list);
  }

  /**
   * 把最新的在线名单原地合并进当前列表：更新在线状态与坐标。
   *
   * <p>在线状态有变化就整表重建（按钮和副标题都要跟着变）；
   * 没变化就只改坐标文本 —— 坐标几秒动一次，重建会让入场动画一直重播。
   */
  function mergeOnlineIntoRows(entries) {
    const byName = new Map();
    entries.forEach(function (entry) {
      byName.set(entry.name.toLowerCase(), entry);
    });

    let statusChanged = false;

    players.last.forEach(function (entry) {
      const live = byName.get((entry.name || '').toLowerCase());

      if (!live) {
        // 推送里没有他，说明已经下线了
        if (entry.online) {
          entry.online = false;
          // 先按「刚刚」占个位，否则在重新拉取之前那一行会显示成「无记录」。
          // 几百毫秒后重拉会带回服务端的准确时间
          entry.lastSeen = Date.now();
          statusChanged = true;
        }
        return;
      }

      const wasOnline = entry.online;
      const wasBanned = entry.banned;

      // 整个覆盖过来，不逐个字段抄 —— 漏一个就是一处永远不刷新的数据。
      // 推送里带的字段比列表里多（坐标、生存状态都在里头）
      Object.assign(entry, live);

      // 只有这两个会改变行的结构（按钮、副标题），变了就得重建
      if (!wasOnline || wasBanned !== entry.banned) {
        statusChanged = true;
      }
    });

    if (statusChanged) {
      // 清掉指纹逼出一次重建
      players.key = '';
      applyPlayerData(players.last);
      return;
    }

    players.last.forEach(function (entry) {
      const row = players.rows.get(entry.name);
      if (row && entry.online) {
        updateRowLive(row, entry);
      }
    });
  }

  /**
   * 应用一份玩家数据。
   *
   * <p>两个来源：切筛选时的一次 HTTP 请求，以及之后持续推来的在线列表。
   *
   * <p>名单没变就<b>只改中间那列文本</b>，不重建表格 —— 坐标每几秒都在动，
   * 整表重建的话每一行的入场动画会跟着重播，界面会一直闪。
   */
  function applyPlayerData(entries) {
    const list = entries || [];
    players.last = list;
    setText('players-total', list.length);

    // 传送弹窗开着的话顺带把它显示的坐标也刷新掉 —— 数据是推过来的，不用另开定时器
    refreshDialogLive(list);

    // 指纹必须带上在线与封禁状态，不能只看名字。
    // 否则解封之后名字没变、指纹也没变，整行就不会重建，
    // 那个「解封」按钮会一直赖在那儿，看起来像是解封没生效
    const key = list.map(function (entry) {
      return entry.name + ':' + (entry.online ? '1' : '0') + (entry.banned ? '1' : '0');
    }).join('|');

    if (key === players.key) {
      list.forEach(function (entry) {
        const row = players.rows.get(entry.name);
        if (row) {
          updateRowLive(row, entry);
        }
      });
      return;
    }

    players.key = key;
    renderPlayerRows(list);
  }

  function renderPlayerRows(list) {
    const box = $('player-list');
    box.textContent = '';
    players.rows.clear();

    const totalPages = Math.max(1, Math.ceil(list.length / players.pageSize));
    players.totalPages = totalPages;
    players.page = Math.min(Math.max(1, players.page), totalPages);

    const from = (players.page - 1) * players.pageSize;
    const slice = list.slice(from, from + players.pageSize);

    setFilesStatus(list.length
      ? '第 ' + players.page + ' / ' + totalPages + ' 页，共 ' + list.length + ' 人'
      : '没有符合条件的玩家');

    slice.forEach(function (entry, index) {
      const row = document.createElement('div');
      row.className = 'player-row';
      row.style.animationDelay = (Math.min(index, 14) * 0.02).toFixed(2) + 's';

      const head = document.createElement('div');
      head.className = 'player-head';
      head.appendChild(buildAvatar(entry));
      row.appendChild(head);

      const main = document.createElement('div');
      main.className = 'player-main';

      const title = document.createElement('span');
      title.className = 'player-title';

      const nameNode = document.createElement('span');
      nameNode.className = 'player-name';
      nameNode.textContent = entry.name;
      title.appendChild(nameNode);

      if (entry.banned) {
        const flag = document.createElement('span');
        flag.className = 'player-flag';
        flag.textContent = '已封禁';
        title.appendChild(flag);
      }
      main.appendChild(title);

      const uuidNode = document.createElement('span');
      uuidNode.className = 'player-uuid';
      uuidNode.textContent = playerSubText(entry);
      main.appendChild(uuidNode);
      row.appendChild(main);

      const position = document.createElement('span');
      position.className = 'player-pos';
      position.textContent = playerStatusText(entry);
      row.appendChild(position);

      const vitals = document.createElement('span');
      vitals.className = 'player-vitals';
      fillVitals(vitals, entry);
      row.appendChild(vitals);

      players.rows.set(entry.name, { position: position, vitals: vitals });

      const ops = document.createElement('span');
      ops.className = 'player-ops';

      // 解封对离线玩家同样有效（服务端按名字放行，不需要人在线），
      // 所以它不受下面那个「必须在线」的限制
      if (entry.banned) {
        ops.appendChild(buildPlayerButton('解封', 'unban', entry.name, ' btn-mini--ok'));
      }

      // pair[2]：要求玩家在线（踢出、传送、执行命令都落不到离线的人身上）
      // pair[3]：要求接了经济系统（没接就别给一个点了必然报错的按钮）
      PLAYER_ACTIONS.forEach(function (pair) {
        if (pair[2] && !entry.online) {
          return;
        }
        if (pair[3] && !state.economyAvailable) {
          return;
        }
        ops.appendChild(buildPlayerButton(pair[1], pair[0], entry.name,
          pair[0] === 'ban' ? ' btn-mini--danger' : ''));
      });
      row.appendChild(ops);

      box.appendChild(row);
    });

    $('players-pager').hidden = totalPages <= 1;
    setText('players-page-info', players.page + ' / ' + totalPages);
  }

  async function loadPlayers() {
    try {
      const payload = await request(PLAYERS_ENDPOINT + '?filter=' + encodeURIComponent(players.filter));
      const data = (payload && payload.data) || {};

      // 筛选项由后端给，前端照着渲染 —— 后端加了新筛选这里自动跟上
      if (data.filters && data.filters.length) {
        playerFilterDropdown.setOptions(data.filters.map(function (value) {
          return { value: value, label: PLAYER_FILTER_LABELS[value] || value };
        }));
        // setValue 不触发 onChange，所以回填不会又转一圈去请求
        playerFilterDropdown.setValue(players.filter);
      }

      applyPlayerData(data.players || []);
    } catch (error) {
      setFilesStatus(error.message, 'error');
    }
  }

  /**
   * 玩家操作的输入提示。踢出/封禁的原因允许留空，传送与命令必须填。
   */
  const PLAYER_PROMPTS = {
    kick: {
      title: '踢出玩家',
      label: '原因（可留空）',
      required: false,
      desc: function (name) { return '将把 ' + name + ' 踢出服务器。'; }
    },
    ban: {
      title: '封禁玩家',
      label: '原因（可留空）',
      required: false,
      tempban: true,
      desc: function (name) {
        return '将把 ' + name + ' 封禁并踢出，重启后依然生效。'
          + '打开下面的开关可以改成临时封禁，到期由面板自动解除。';
      }
    },
    teleport: {
      title: '传送玩家',
      label: '目标：玩家名，或 x y z',
      required: true,
      live: true,
      desc: function (name) {
        return '把 ' + name + ' 传送到指定位置。坐标支持 ~ 相对写法（如 ~ ~10 ~），'
          + '由服务端按他此刻的位置实时计算。';
      }
    },
    command: {
      title: '以玩家身份执行命令',
      label: '命令（不用带 /）',
      required: true,
      desc: function (name) {
        return '以 ' + name + ' 作为执行者（@s、~ ~ ~ 都指向他），'
          + '但用最高权限执行。他本人不会因此获得任何权限。';
      }
    },
    money: {
      title: '调整余额',
      label: '金额',
      required: true,
      // 选中的那一项会当作真正的动作发出去
      choices: [['give', '存入'], ['take', '扣除'], ['setbalance', '设为']],
      desc: function (name) {
        const entry = findPlayerEntry(name);
        const current = entry && entry.balanceText
          ? name + ' 当前余额 ' + entry.balanceText + '。'
          : '';
        return current + '经济插件按 UUID 记账，他不在线也照样生效。';
      }
    }
  };

  /** 在当前列表里按名字找一行，找不到返回 null */
  function findPlayerEntry(name) {
    return players.last.find(function (entry) {
      return entry.name === name;
    }) || null;
  }

  /** 不需要填任何东西、点了直接执行的操作 */
  const PLAYER_SIMPLE_ACTIONS = new Set(['unban']);

  async function runPlayerAction(name, action) {
    const prompt = PLAYER_PROMPTS[action];

    // 解封这类没有输入框，点了就发
    if (!prompt && !PLAYER_SIMPLE_ACTIONS.has(action)) {
      return;
    }

    const body = { action: action, name: name, value: '' };

    if (prompt) {
      const answer = await openFileDialog({
        title: prompt.title,
        desc: prompt.desc(name),
        label: prompt.label,
        value: '',
        format: false,
        required: prompt.required,
        liveFor: prompt.live ? name : '',
        tempban: prompt.tempban,
        choices: prompt.choices
      });
      if (!answer) {
        return;
      }

      body.value = answer.value;

      // 带选项的弹窗（「存入 / 扣除 / 设为」）：选中的那一项才是真正的动作。
      // 按钮标识（money）只是用来找 prompt 的，不能当动作发出去 ——
      // 否则后端只回一句「未知操作」，看不出问题出在哪
      if (prompt.choices) {
        if (!answer.choice) {
          notify('没有选中要执行的操作', 'error');
          return;
        }
        body.action = answer.choice;
      }

      if (action === 'ban') {
        body.temporary = answer.temporary;
        // 七个时长字段平铺进请求体，没开的开关就是全 0，后端会忽略
        Object.assign(body, answer.duration);
      }
    }

    try {
      const response = await request(PLAYERS_ACTION_ENDPOINT, {
        method: 'POST',
        body: JSON.stringify(body)
      });
      notify((response && response.message) || '已执行', 'ok');
      await loadPlayers();
    } catch (error) {
      notify(error.message, 'error');
    }
  }

  function bindPlayerEvents() {
    document.querySelectorAll('[data-pcmd]').forEach(function (button) {
      button.addEventListener('click', function () {
        const command = button.dataset.pcmd;
        if (command === 'refresh') {
          loadPlayers();
        } else if (command === 'prev') {
          players.page = Math.max(1, players.page - 1);
          renderPlayerRows(players.last);
        } else if (command === 'next') {
          players.page = Math.min(players.totalPages, players.page + 1);
          renderPlayerRows(players.last);
        }
      });
    });
  }

  // ==================================================================
  // 设置：修改面板账号与密码
  // ==================================================================

  const SETTINGS_ENDPOINT = '/api/settings';

  /** 拉一次面板设置，填进表单 */
  async function loadPanelSettings() {
    try {
      const payload = await request(SETTINGS_ENDPOINT);
      const data = (payload && payload.data) || {};
      const input = $('settings-page-size');
      input.value = data.playersPerPage;
      input.min = data.minPlayersPerPage;
      input.max = data.maxPlayersPerPage;

      // 玩家列表的每页条数也用它，改完设置立刻生效
      players.pageSize = data.playersPerPage;
      setText('settings-page-hint', '修改后立即保存到 panelconfig.json');

      applyBanSettings(data);
      setText('settings-ban-hint', describeBanMethod(data.banMethod));
    } catch (error) {
      setText('settings-page-hint', error.message);
    }
  }

  /** 设置页是否有未保存的改动 */
  let settingsDirty = false;

  /** 控件被改动过。输入过程中只置这个标记，等 change 或失焦时才真去写盘 */
  function markSettingsDirty() {
    settingsDirty = true;
  }

  /** 自定义封禁方式的名字，与后端 BanMethod 的 id 一致 */
  const CUSTOM_BAN_METHOD = 'CustomCommand';

  /** 当前生效的封禁方式，封禁弹窗上会标出来 */
  let currentBanMethod = 'Vanilla';

  /**
   * 封禁方式下拉框。
   *
   * <p>选中即保存，顺带切换命令输入区的显隐 —— 不用再单独绑 change 事件，
   * 回调本身就是「值真的变了」的时机，比监听原生控件的 change 更准。
   */
  const banMethodDropdown = createDropdown('ban-method-dropdown', function () {
    syncBanCommandVisibility();
    markSettingsDirty();
    saveSettings();
  });

  /**
   * 用服务端的设置回填兼容性卡片。
   *
   * <p>下拉选项由后端给（{@code banMethods}），前端不写死 ——
   * 后端加了新的封禁方式，这里自动就多一项。
   */
  function applyBanSettings(data) {
    banMethodDropdown.setOptions(data.banMethods || []);
    // setValue 不触发 onChange，所以回填不会顺带触发一次保存
    banMethodDropdown.setValue(data.banMethod || '');
    currentBanMethod = banMethodDropdown.getValue() || currentBanMethod;

    $('settings-ban-command-temp').value = data.banCommandTemp || '';
    $('settings-ban-command-perm').value = data.banCommandPerm || '';
    $('settings-ban-command-unban').value = data.banCommandUnban || '';

    $('settings-vault').checked = Boolean(data.vaultEnabled);

    // 提示平时不占位，只有「开了却接不上」的时候才有话要说
    const vaultHint = describeVault(data);
    const vaultHintNode = $('settings-vault-hint');
    vaultHintNode.hidden = !vaultHint;
    vaultHintNode.textContent = vaultHint;

    syncBanCommandVisibility();
  }

  /**
   * Vault 开关下面的提示。
   *
   * <p>只回答「开了却接不上」这一种情况：开关开着本身在界面上看得见，
   * 真接上了余额会直接出现在玩家列表里，都不必再用一行字复述一遍。
   */
  function describeVault(data) {
    if (data.vaultEnabled && !data.vaultAvailable) {
      return '服务端上没有可用的 Vault 经济，详见控制台提示';
    }
    return '';
  }

  /** 只有自定义方式才需要填命令，其余两种藏起来免得干扰 */
  function syncBanCommandVisibility() {
    $('settings-ban-commands').hidden = banMethodDropdown.getValue() !== CUSTOM_BAN_METHOD;
  }

  /** 每种封禁方式的说明，直接显示在设置页上 */
  function describeBanMethod(method) {
    if (method === CUSTOM_BAN_METHOD) {
      return '执行的命令由下面填写，修改后立即保存';
    }
    if (method === 'Vanilla') {
      return '用服务端自带的封禁名单，临时封禁到期由面板自动解除';
    }
    return '临时封禁发 tempban、永久封禁发 ban、到期发 unban';
  }

  /** 把设置页上所有控件收成一个请求体 */
  function collectSettings() {
    return {
      playersPerPage: Math.round(Number($('settings-page-size').value)),
      banMethod: banMethodDropdown.getValue(),
      banCommandTemp: $('settings-ban-command-temp').value.trim(),
      banCommandPerm: $('settings-ban-command-perm').value.trim(),
      banCommandUnban: $('settings-ban-command-unban').value.trim(),
      vaultEnabled: $('settings-vault').checked
    };
  }

  /**
   * 保存设置页。
   *
   * <p>除了控件自己的 change，切走选项卡、以及网页失焦时也会调一次 ——
   * 免得填了没点走就关掉页面，设置丢了。
   * 用脏标记挡掉没改动时的重复写入。
   *
   * <p>一次把整页设置都发过去：接口是部分更新，但整页发过去更简单，
   * 也不会出现「改了 A 保存、再改 B 时 A 被旧值覆盖」这种事。
   */
  async function saveSettings() {
    if (!settingsDirty) {
      return;
    }

    const body = collectSettings();
    if (!isFinite(body.playersPerPage) || body.playersPerPage < 1) {
      setText('settings-page-hint', '请输入 1 以上的数字');
      return;
    }

    try {
      const payload = await request(SETTINGS_ENDPOINT, {
        method: 'POST',
        body: JSON.stringify(body)
      });
      const data = (payload && payload.data) || {};

      settingsDirty = false;

      // 用服务端返回的值回填：那里已经做过范围夹取与占位符校验
      $('settings-page-size').value = data.playersPerPage;
      applyBanSettings(data);
      setText('settings-page-hint', '已保存：每页 ' + data.playersPerPage + ' 条');
      setText('settings-ban-hint', '已保存：' + describeBanMethod(data.banMethod));
      notify('设置已保存', 'ok');

      // 页长立刻生效并重排
      players.pageSize = data.playersPerPage;
      players.page = 1;
      renderPlayerRows(players.last);
    } catch (error) {
      setText('settings-page-hint', error.message);
      setText('settings-ban-hint', error.message);
    }
  }

  function resetSettingsForm() {
    setText('settings-current-user', state.username || '--');
    $('settings-old').value = '';
    $('settings-username').value = state.username || '';
    $('settings-password').value = '';
    $('settings-confirm').value = '';
    setSetupErrorFor('settings-error', '');
    $('settings-submit').disabled = false;
  }

  async function submitSettings(event) {
    event.preventDefault();

    const button = $('settings-submit');
    if (button.disabled) {
      return;
    }

    const oldPassword = $('settings-old').value;
    const username = $('settings-username').value.trim();
    const password = $('settings-password').value;
    const confirm = $('settings-confirm').value;

    // 旧密码是必填：这是「本人正在操作」的凭据，不能只靠一个可能被劫持的会话
    if (!oldPassword) {
      setSetupErrorFor('settings-error', '请输入当前密码');
      return;
    }
    if (!USERNAME_PATTERN.test(username)) {
      setSetupErrorFor('settings-error', '用户名需为 3-32 位字母、数字、下划线、点或短横线');
      return;
    }
    if (password.length < MIN_PASSWORD_LENGTH) {
      setSetupErrorFor('settings-error', '新密码长度至少为 ' + MIN_PASSWORD_LENGTH + ' 位');
      return;
    }
    if (password !== confirm) {
      setSetupErrorFor('settings-error', '两次输入的新密码不一致');
      return;
    }

    button.disabled = true;
    setSetupErrorFor('settings-error', '');

    try {
      await request(CREDENTIALS_ENDPOINT, {
        method: 'POST',
        body: JSON.stringify({
          username: username,
          newPassword: password,
          oldPassword: oldPassword
        })
      });

      // 服务端已经清空全部会话，稍等一下让人看清提示再回登录页
      notify('账号已修改，请用新账号重新登录', 'ok');
      window.setTimeout(toLogin, 1200);
    } catch (error) {
      setSetupErrorFor('settings-error', error.message);
      button.disabled = false;
    }
  }

  // ---------------------------------------------------------------- 弹窗

  let fileDialogResolve = null;
  let fileDialogRequired = true;
  /** 弹窗里要实时显示坐标的玩家名，空串表示不显示 */
  let fileDialogLivePlayer = '';
  /** 本次弹窗是否带临时封禁那块（只有封禁按钮会开） */
  let fileDialogTempBan = false;

  /** 临时封禁的七个输入框：[元素 id, 请求体里的字段名] */
  const TEMPBAN_FIELDS = [
    ['tempban-years', 'years'],
    ['tempban-months', 'months'],
    ['tempban-weeks', 'weeks'],
    ['tempban-days', 'days'],
    ['tempban-hours', 'hours'],
    ['tempban-minutes', 'minutes'],
    ['tempban-seconds', 'seconds']
  ];

  /**
   * 每个单位折算多少毫秒。
   *
   * <p>必须与后端 {@code BanDuration} 的换算一致：1 年 = 365 天、1 月 = 30 天。
   * 前端这份只用来显示预览，真正生效的时长以后端算的为准。
   */
  const TEMPBAN_UNIT_MILLIS = {
    years: 365 * 86400000,
    months: 30 * 86400000,
    weeks: 7 * 86400000,
    days: 86400000,
    hours: 3600000,
    minutes: 60000,
    seconds: 1000
  };

  /** 时长写法的后缀。这个顺序就是拼接顺序 —— mo 必须排在 m 前面 */
  const TEMPBAN_UNIT_SUFFIX = {
    years: 'y', months: 'mo', weeks: 'w', days: 'd',
    hours: 'h', minutes: 'm', seconds: 's'
  };

  /** 中文单位名，只用于预览 */
  const TEMPBAN_UNIT_LABEL = {
    years: '年', months: '个月', weeks: '周', days: '天',
    hours: '小时', minutes: '分钟', seconds: '秒'
  };

  /** 每次开弹窗都把临时封禁复位：开关关掉、七个数归零 */
  function resetTempBan() {
    $('tempban-toggle').checked = false;
    $('tempban-fields').hidden = true;
    TEMPBAN_FIELDS.forEach(function (pair) {
      $(pair[0]).value = '0';
    });
    updateTempBanPreview();
  }

  /** 读七个输入框。空、乱填、负数一律按 0，小数向下取整 */
  function readTempBan() {
    const parts = {};
    TEMPBAN_FIELDS.forEach(function (pair) {
      const value = Math.floor(Number($(pair[0]).value));
      parts[pair[1]] = isFinite(value) && value > 0 ? value : 0;
    });
    return parts;
  }

  function tempBanMillis(parts) {
    let total = 0;
    TEMPBAN_FIELDS.forEach(function (pair) {
      total += parts[pair[1]] * TEMPBAN_UNIT_MILLIS[pair[1]];
    });
    return total;
  }

  /** 拼成封禁插件认的 1y2mo3d 写法，为 0 的单位省略 */
  function formatTempBan(parts) {
    let text = '';
    TEMPBAN_FIELDS.forEach(function (pair) {
      const value = parts[pair[1]];
      if (value > 0) {
        text += value + TEMPBAN_UNIT_SUFFIX[pair[1]];
      }
    });
    return text;
  }

  /** 中文可读时长，例如「1 年 2 个月 3 天」 */
  function describeTempBan(parts) {
    const pieces = [];
    TEMPBAN_FIELDS.forEach(function (pair) {
      const value = parts[pair[1]];
      if (value > 0) {
        pieces.push(value + ' ' + TEMPBAN_UNIT_LABEL[pair[1]]);
      }
    });
    return pieces.join(' ');
  }

  /**
   * 刷新时长预览。
   *
   * <p>把「1 年 2 个月」和真正会发给封禁插件的「1y2mo」并排显示 ——
   * 单位写法是这套东西里最容易填错的地方，让他当场看见比事后查日志强。
   */
  function updateTempBanPreview() {
    const node = $('tempban-preview');
    if (!fileDialogTempBan || $('tempban-fields').hidden) {
      node.hidden = true;
      return;
    }

    node.hidden = false;
    const parts = readTempBan();
    if (tempBanMillis(parts) <= 0) {
      node.textContent = '请至少填写一项时长';
      node.classList.add('is-warning');
      return;
    }

    node.classList.remove('is-warning');
    node.textContent = '合计 ' + describeTempBan(parts)
      + '，执行时用 ' + formatTempBan(parts)
      + '（当前封禁方式：' + currentBanMethod + '）';
  }

  /**
   * 刷新弹窗里的实时坐标行。
   *
   * <p>由玩家推送驱动，不额外开定时器；玩家中途离线也能立刻反映出来。
   */
  function refreshDialogLive(list) {
    if (!fileDialogLivePlayer) {
      return;
    }
    const node = $('file-modal-live');
    if (!node) {
      return;
    }
    const entry = list.find(function (item) {
      return item.name === fileDialogLivePlayer;
    });
    node.textContent = entry ? '当前位置： ' + positionText(entry) : '该玩家已离线';
  }

  function openFileDialog(options) {
    return new Promise(function (resolve) {
      fileDialogResolve = resolve;

      setText('file-modal-title', options.title);
      setText('file-modal-desc', options.desc || '');
      setText('file-modal-label', options.label || '');
      setSetupErrorFor('file-modal-error', '');

      const field = $('file-modal-field');
      const input = $('file-modal-input');
      const formatBox = $('file-modal-format');

      field.hidden = !options.label;
      input.value = options.value || '';

      // 传送这类要看着玩家当前位置填的，开一行实时坐标
      fileDialogLivePlayer = options.liveFor || '';
      const live = $('file-modal-live');
      live.hidden = !fileDialogLivePlayer;
      if (fileDialogLivePlayer) {
        refreshDialogLive(players.last);
      }
      formatBox.hidden = !options.format;

      // 通用选项卡：调用方给 [值, 文案] 列表，选中的值会随结果一起返回
      const choicesBox = $('file-modal-choices');
      choicesBox.hidden = !options.choices;
      choicesBox.textContent = '';
      if (options.choices) {
        options.choices.forEach(function (pair, index) {
          const label = document.createElement('label');
          const radio = document.createElement('input');
          radio.type = 'radio';
          radio.name = 'dialog-choice';
          radio.value = pair[0];
          radio.checked = index === 0;
          label.appendChild(radio);
          label.appendChild(document.createTextNode(' ' + pair[1]));
          choicesBox.appendChild(label);
        });
      }

      // 踢出/封禁的原因可以留空，其余场景必须填
      fileDialogRequired = options.required !== false;

      // 临时封禁那一块只有封禁弹窗才出现
      fileDialogTempBan = Boolean(options.tempban);
      $('file-modal-tempban').hidden = !fileDialogTempBan;
      if (fileDialogTempBan) {
        resetTempBan();
      }

      $('file-modal').hidden = false;
      if (options.label) {
        input.focus();
        input.select();
      } else {
        $('file-modal-ok').focus();
      }
    });
  }

  function closeFileDialog(result) {
    $('file-modal').hidden = true;
    fileDialogLivePlayer = '';
    fileDialogTempBan = false;
    const resolve = fileDialogResolve;
    fileDialogResolve = null;
    if (resolve) {
      resolve(result);
    }
  }

  function confirmFileDialog() {
    const input = $('file-modal-input');
    const formatBox = $('file-modal-format');
    const field = $('file-modal-field');

    const value = field.hidden ? '' : input.value.trim();
    if (!field.hidden && !value && fileDialogRequired) {
      setSetupErrorFor('file-modal-error', '这里不能留空');
      return;
    }

    let format = 'zip';
    if (!formatBox.hidden) {
      const checked = formatBox.querySelector('input[name="archive-format"]:checked');
      format = checked ? checked.value : 'zip';
    }

    // 通用选项卡。没开这块时是空串，调用方据此忽略它
    let choice = '';
    const choicesBox = $('file-modal-choices');
    if (!choicesBox.hidden) {
      const checked = choicesBox.querySelector('input[name="dialog-choice"]:checked');
      choice = checked ? checked.value : '';
    }

    // 临时封禁：开关打开就必须填了时长。
    // 在这里挡住而不是丢给后端 —— 否则填一半提交出去被拒绝，弹窗还得重开一遍
    let temporary = false;
    let duration = {};
    if (fileDialogTempBan && $('tempban-toggle').checked) {
      duration = readTempBan();
      if (tempBanMillis(duration) <= 0) {
        setSetupErrorFor('file-modal-error', '临时封禁需要填写时长');
        return;
      }
      temporary = true;
    }

    closeFileDialog({
      value: value,
      format: format,
      temporary: temporary,
      duration: duration,
      choice: choice
    });
  }

  function bindFileEvents() {
    document.querySelectorAll('[data-cmd]').forEach(function (button) {
      button.addEventListener('click', function () {
        fileCommand(button.dataset.cmd);
      });
    });

    $('files-check-all').addEventListener('change', function () {
      files.selected.clear();
      if (this.checked) {
        files.entries.forEach(function (entry) {
          files.selected.add(entry.path);
        });
      }
      applySelection();
    });

    $('files-upload-input').addEventListener('change', function () {
      // 必须先快照成普通数组再清空 input。
      // this.files 返回的 FileList 是「活」的，反映的是当前选择，
      // 一旦把 value 置空它就同时空了 —— 直接传它进去，uploadFiles
      // 收到的会是个空列表，于是什么都不做。
      const chosen = Array.from(this.files);
      this.value = '';
      uploadFiles(chosen);
    });

    $('file-modal-ok').addEventListener('click', confirmFileDialog);
    $('file-modal-cancel').addEventListener('click', function () {
      closeFileDialog(null);
    });
    $('file-modal-backdrop').addEventListener('click', function () {
      closeFileDialog(null);
    });
    $('tempban-toggle').addEventListener('change', function () {
      $('tempban-fields').hidden = !this.checked;
      updateTempBanPreview();
      if (this.checked) {
        $('tempban-years').focus();
      }
    });
    TEMPBAN_FIELDS.forEach(function (pair) {
      $(pair[0]).addEventListener('input', updateTempBanPreview);
    });

    $('file-modal-input').addEventListener('keydown', function (event) {
      if (event.key === 'Enter') {
        event.preventDefault();
        confirmFileDialog();
      }
    });
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && !$('file-modal').hidden) {
        closeFileDialog(null);
      }
    });

    // 「上传文件」按钮触发隐藏的文件选择框
    document.querySelector('[data-cmd="upload"]').addEventListener('click', function () {
      $('files-upload-input').click();
    });

    $('editor-cancel').addEventListener('click', closeEditor);
    // 编辑器刻意不给遮罩绑定关闭：编辑中的内容不能因为点一下空白就丢掉，
    // 只能通过下方的「关闭」或「保存」离开（Esc 也不响应）
    $('editor-save').addEventListener('click', saveEditor);
    $('editor-content').addEventListener('input', updateEditorMeta);
    $('editor-content').addEventListener('keydown', function (event) {
      // Ctrl / Cmd + S 保存
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
        event.preventDefault();
        saveEditor();
      }
    });
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

  /**
   * 硬盘卡片：整个服务端文件夹占了多少空间。
   *
   * <p>文件夹大小要递归遍历才能算出来，扫描是后台每 5 分钟一次，
   * 所以第一次扫完之前拿不到数。这期间显示「正在统计」而不是 0 ——
   * 一个 0 B 会被当成「服务端是空的」，那是个错误的结论。
   */
  function renderDisk(disk) {
    const total = Number(disk.totalBytes) || 0;
    const free = Number(disk.freeBytes) || 0;

    if (!disk.serverScanned) {
      setText('disk-size', '--');
      setText('disk-size-unit', '');
      setText('disk-detail', '正在统计');
      setRing('disk-ring', 0);
      setText('disk-path', '磁盘剩余 ' + formatBytes(free));
      return;
    }

    const serverBytes = Number(disk.serverBytes) || 0;
    const scaled = scaleBytes(serverBytes);

    // 数字走滚动动画，单位单独渲染。
    // 动画期间沿用目标单位，跨单位（MB 变 GB）时数字会短暂偏一点，几百毫秒内看不出来
    setNumber('disk-size', scaled.value, { format: v => v.toFixed(scaled.digits) });
    setText('disk-size-unit', scaled.unit);

    if (total > 0) {
      // 服务端文件夹通常只占整个盘很小一部分，小于 1% 时留两位小数，
      // 否则会被四舍五入成 0.0%，看着像是没统计到
      const percent = serverBytes * 100 / total;
      setText('disk-detail', '占磁盘 ' + percent.toFixed(percent < 1 ? 2 : 1) + '%');
      setRing('disk-ring', percent);
    } else {
      setText('disk-detail', '磁盘信息不可用');
      setRing('disk-ring', 0);
    }

    setText('disk-path', '磁盘剩余 ' + formatBytes(free) + ' / 共 ' + formatBytes(total));
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
    // 玩家管理页与概览的玩家标签共用同一份数据
    onOnlinePush(data.players);
    checkPlayersRevision(data.playersRevision);

    // 经济能不能用是全局的，玩家行上的「余额」按钮据此显示
    state.economyAvailable = Boolean(data.economyAvailable);
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
      } else if (payload.type === 'players' && payload.players) {
        onOnlinePush(payload.players);
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
    setText('settings-current-user', state.username || '--');
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
    $('settings-form').addEventListener('submit', submitSettings);

    // 设置页的控件改完即存。绑在 change 而不是 input：
    // 输入过程中每敲一个字就写一次盘没有意义，置个脏标记就够了
    $('settings-page-size').addEventListener('input', markSettingsDirty);
    $('settings-page-size').addEventListener('change', saveSettings);

    // 封禁方式下拉框的选中回调在创建时就绑好了，这里不用再监听

    ['settings-ban-command-temp', 'settings-ban-command-perm', 'settings-ban-command-unban']
      .forEach(function (id) {
        $(id).addEventListener('input', markSettingsDirty);
        $(id).addEventListener('change', saveSettings);
      });

    $('settings-vault').addEventListener('change', function () {
      markSettingsDirty();
      saveSettings();
    });

    // 网页失焦时也补一次保存（切到别的窗口、点开别的程序都算）
    window.addEventListener('blur', saveSettings);
    bindPlayerEvents();
    bindFileEvents();

    // 手动关掉自动滚动后，重新勾上时立刻回到底部
    $('log-autoscroll').addEventListener('change', function () {
      if (this.checked) {
        const view = $('log-view');
        view.scrollTop = view.scrollHeight;
      }
    });

    relayout();
    observeChartResize();
    // 先取一次面板设置，玩家列表的页长好确定下来
    loadPanelSettings();

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
