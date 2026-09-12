(function () {
  const form = document.getElementById('login-form');
  const account = document.getElementById('account');
  const password = document.getElementById('password');
  const remember = document.getElementById('remember');
  const status = document.getElementById('form-status');
  const submit = form.querySelector('.login-button');
  const toggle = document.getElementById('toggle-password');
  const forgot = document.getElementById('forgot-link');

  const LOGIN_ENDPOINT = '/api/auth/login';
  const SESSION_ENDPOINT = '/api/auth/session';
  const PANEL_PATH = '/';

  function setStatus(message, success) {
    status.textContent = message;
    status.style.color = success ? '#3d9dc1' : '#c06f76';
  }

  toggle.addEventListener('click', function () {
    const visible = password.type === 'text';
    password.type = visible ? 'password' : 'text';
    toggle.textContent = visible ? '显示' : '隐藏';
    toggle.setAttribute('aria-label', visible ? '显示密码' : '隐藏密码');
  });

  forgot.addEventListener('click', function (event) {
    event.preventDefault();
    setStatus('请联系管理员重置密码。', false);
  });

  async function submitLogin(accountValue, passwordValue) {
    submit.disabled = true;
    setStatus('正在验证登录信息...', true);

    let response;
    try {
      response = await fetch(LOGIN_ENDPOINT, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          username: accountValue,
          password: passwordValue,
          remember: remember.checked
        })
      });
    } catch (error) {
      // 网络层就失败了，多半是服务端没起来
      setStatus('无法连接服务端，请稍后重试。', false);
      submit.disabled = false;
      return;
    }

    let payload = null;
    try {
      payload = await response.json();
    } catch (error) {
      payload = null;
    }

    if (!response.ok) {
      // 后端已经在 message 里给了可直接展示的中文提示
      setStatus((payload && payload.message) || ('登录失败（' + response.status + '）'), false);
      submit.disabled = false;
      password.select();
      return;
    }

    setStatus('登录成功，正在进入面板...', true);
    window.location.replace(PANEL_PATH);
  }

  form.addEventListener('submit', function (event) {
    event.preventDefault();
    const accountValue = account.value.trim();
    const passwordValue = password.value;
    if (!accountValue || !passwordValue) {
      setStatus('请输入账号和密码。', false);
      (!accountValue ? account : password).focus();
      return;
    }
    submitLogin(accountValue, passwordValue);
  });

  // 已经登录过就直接进面板，省掉重复登录
  fetch(SESSION_ENDPOINT, { credentials: 'same-origin' })
    .then(function (response) { return response.ok ? response.json() : null; })
    .then(function (payload) {
      if (payload && payload.data && payload.data.authenticated) {
        window.location.replace(PANEL_PATH);
      }
    })
    .catch(function () {
      // 未登录或服务端不可达，留在登录页
    });

}());
