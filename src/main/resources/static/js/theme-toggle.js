/**
 * THEME TOGGLE SCRIPT (plan.md implementation)
 * Manages Light/Dark state, synchronization, persistence, anti-FOUC and accessibility.
 */
(function () {
    'use strict';

    const STORAGE_KEY = 'lshoe-theme';
    const THEME_ATTR = 'theme';
    const DARK_CLASS = 'dark-theme';

    /**
     * Determines the active theme based on localStorage or OS preference
     */
    function getStoredTheme() {
        try {
            const saved = localStorage.getItem(STORAGE_KEY) || localStorage.getItem('theme');
            if (saved === 'dark' || saved === 'light') return saved;
        } catch (e) { }

        if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
            return 'dark';
        }
        return 'light';
    }

    let currentTheme = getStoredTheme();
    let transitionTimer = null;

    /**
     * Updates DOM attributes and button accessibility states
     */
    function syncDOM(theme, isUserAction) {
        const isDark = theme === 'dark';
        const root = document.documentElement;

        // Apply to data-theme and dark-theme class
        root.dataset.theme = theme;
        root.classList.toggle(DARK_CLASS, isDark);

        // Update all toggle buttons on page
        const buttons = document.querySelectorAll('.theme-toggle, #theme-toggle');
        buttons.forEach(btn => {
            btn.setAttribute('role', 'switch');
            btn.setAttribute('aria-checked', String(isDark));
            btn.setAttribute('aria-label', isDark ? 'Chuyển sang chế độ sáng' : 'Chuyển sang chế độ tối');
            btn.title = isDark ? 'Chuyển sang giao diện Sáng' : 'Chuyển sang giao diện Tối';
            if (btn.tagName === 'INPUT' && btn.type === 'checkbox') {
                btn.checked = isDark;
            }
        });

        if (isUserAction) {
            try {
                localStorage.setItem(STORAGE_KEY, theme);
                localStorage.setItem('theme', theme);
            } catch (e) { }

            // Temporary transition class for global site elements
            root.classList.add('theme-transitioning');
            if (transitionTimer) clearTimeout(transitionTimer);
            transitionTimer = setTimeout(() => {
                root.classList.remove('theme-transitioning');
                transitionTimer = null;
            }, 380);
        }
    }

    /**
     * Toggle between light and dark
     */
    function toggleTheme() {
        currentTheme = (currentTheme === 'dark') ? 'light' : 'dark';
        syncDOM(currentTheme, true);
    }

    /**
     * Set explicit theme
     */
    function setTheme(theme) {
        if (theme !== 'dark' && theme !== 'light') return;
        currentTheme = theme;
        syncDOM(currentTheme, true);
    }

    // Initialize anti-FOUC on script execution
    syncDOM(currentTheme, false);

    // Event listener binding on DOM ready
    document.addEventListener('DOMContentLoaded', () => {
        // Sync once more after DOM is fully parsed
        syncDOM(currentTheme, false);

        // Remove anti-FOUC transition suppression class smoothly
        requestAnimationFrame(() => {
            requestAnimationFrame(() => {
                document.documentElement.classList.remove('no-theme-transition');
            });
        });

        // Delegate click for theme toggle buttons
        document.addEventListener('click', (e) => {
            const toggleBtn = e.target.closest('.theme-toggle');
            if (toggleBtn) {
                e.preventDefault();
                toggleTheme();
            }
        });

        // Listen for OS theme preference changes if user hasn't explicitly set one
        if (window.matchMedia) {
            window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
                try {
                    const saved = localStorage.getItem(STORAGE_KEY) || localStorage.getItem('theme');
                    if (!saved) {
                        currentTheme = e.matches ? 'dark' : 'light';
                        syncDOM(currentTheme, false);
                    }
                } catch (err) { }
            });
        }
    });

    // Global exports & legacy compatibility
    window.ThemeToggle = {
        getTheme: () => currentTheme,
        setTheme: setTheme,
        toggle: toggleTheme
    };

    // Backward-compatible global function used in inline onchange/onclick
    window.toggleSiteTheme = function (isDark) {
        if (typeof isDark === 'boolean') {
            setTheme(isDark ? 'dark' : 'light');
        } else {
            toggleTheme();
        }
    };
})();
