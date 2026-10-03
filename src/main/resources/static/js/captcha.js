(() => {
    const image = document.querySelector('[data-captcha-image]');
    const refresh = document.querySelector('[data-captcha-refresh]');
    const input = document.querySelector('[data-captcha-input]');

    if (!image || !refresh) return;

    const reload = () => {
        const base = image.dataset.src || image.getAttribute('src');
        const separator = base.includes('?') ? '&' : '?';
        image.src = `${base}${separator}v=${Date.now()}`;
        if (input) {
            input.value = '';
            input.focus();
        }
    };

    refresh.addEventListener('click', reload);
})();
