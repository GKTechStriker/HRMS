/**
 * Enterprise HRMS — Interactive UI Engine (Option A: Executive Precision)
 * Powers Theme Toggle, Collapsible Sidebar, Mobile Drawer, Global Ctrl+K Command Palette,
 * Floating Toasts, Animated KPI Counters, and Lucide Icon hydration.
 */
(function () {
    'use strict';

    // =========================================================================
    // 1. Theme Management (Light / Dark)
    // =========================================================================
    window.toggleHrmsTheme = function () {
        var html = document.documentElement;
        var isDark = html.classList.toggle('dark');
        try {
            localStorage.setItem('hrms_theme', isDark ? 'dark' : 'light');
        } catch (e) {}
        updateThemeIcons();
        window.dispatchEvent(new CustomEvent('hrms:theme-changed', { detail: { dark: isDark } }));
    };

    function updateThemeIcons() {
        var isDark = document.documentElement.classList.contains('dark');
        document.querySelectorAll('[data-theme-sun]').forEach(function (el) {
            el.classList.toggle('hidden', !isDark);
        });
        document.querySelectorAll('[data-theme-moon]').forEach(function (el) {
            el.classList.toggle('hidden', isDark);
        });
    }

    // =========================================================================
    // 2. Sidebar Collapse & Mobile Drawer
    // =========================================================================
    window.toggleHrmsSidebar = function () {
        var html = document.documentElement;
        var collapsed = html.classList.toggle('sidebar-collapsed');
        try {
            localStorage.setItem('hrms_sidebar_collapsed', collapsed ? '1' : '0');
        } catch (e) {}
    };

    window.openMobileNav = function () {
        document.documentElement.classList.add('mobile-nav-open');
    };

    window.closeMobileNav = function () {
        document.documentElement.classList.remove('mobile-nav-open');
    };

    // =========================================================================
    // 3. User Dropdown Menu
    // =========================================================================
    window.toggleUserMenu = function (event) {
        if (event) event.stopPropagation();
        var menu = document.getElementById('user-dropdown-menu');
        if (!menu) return;
        menu.classList.toggle('hidden');
    };

    // =========================================================================
    // 4. Floating Toast Notifications
    // =========================================================================
    window.showHrmsToast = function (message, type) {
        var container = document.getElementById('hrms-toast-container');
        if (!container) {
            container = document.createElement('div');
            container.id = 'hrms-toast-container';
            container.className = 'fixed bottom-5 right-5 z-[60] flex flex-col gap-2.5 max-w-sm w-[calc(100vw-2.5rem)] pointer-events-none';
            document.body.appendChild(container);
        }

        var tone = type === 'error'
            ? { border: 'border-rose-500/30', dot: 'bg-rose-500', bar: 'bg-rose-500', icon: 'alert-circle', iconColor: 'text-rose-500' }
            : type === 'info'
                ? { border: 'border-indigo-500/30', dot: 'bg-indigo-500', bar: 'bg-indigo-500', icon: 'info', iconColor: 'text-indigo-500' }
                : { border: 'border-emerald-500/30', dot: 'bg-emerald-500', bar: 'bg-emerald-500', icon: 'check-circle-2', iconColor: 'text-emerald-500' };

        var toast = document.createElement('div');
        toast.className = 'pointer-events-auto relative overflow-hidden rounded-xl bg-white dark:bg-slate-900 border ' + tone.border +
            ' shadow-xl px-4 py-3.5 flex items-start gap-3 transform translate-y-4 opacity-0 transition-all duration-300';

        toast.innerHTML =
            '<i data-lucide="' + tone.icon + '" class="w-4 h-4 mt-0.5 flex-shrink-0 ' + tone.iconColor + '"></i>' +
            '<p class="text-xs font-medium text-slate-800 dark:text-slate-100 leading-relaxed flex-1"></p>' +
            '<button type="button" class="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 transition" aria-label="Dismiss">' +
            '<i data-lucide="x" class="w-3.5 h-3.5"></i></button>' +
            '<div class="absolute bottom-0 left-0 h-0.5 ' + tone.bar + ' toast-progress"></div>';

        toast.querySelector('p').textContent = message;
        var closeBtn = toast.querySelector('button');
        var dismiss = function () {
            toast.classList.add('translate-y-4', 'opacity-0');
            setTimeout(function () { toast.remove(); }, 280);
        };
        closeBtn.addEventListener('click', dismiss);

        container.appendChild(toast);
        if (window.lucide) window.lucide.createIcons({ nodes: [toast] });

        requestAnimationFrame(function () {
            toast.classList.remove('translate-y-4', 'opacity-0');
        });
        setTimeout(dismiss, 4800);
    };

    window.copyHrmsText = function (text, label) {
        if (!navigator.clipboard) return;
        navigator.clipboard.writeText(text).then(function () {
            window.showHrmsToast((label || 'Copied') + ' copied to clipboard', 'info');
        }).catch(function () {});
    };

    // =========================================================================
    // 5. Global Command Palette (Ctrl+K / Cmd+K)
    // =========================================================================
    var cmdItems = [];
    var filteredCmdItems = [];
    var activeCmdIndex = 0;

    function buildCommandItems() {
        cmdItems = [];
        document.querySelectorAll('#app-sidebar a[href]').forEach(function (link) {
            var labelEl = link.querySelector('.sidebar-label');
            var label = labelEl ? labelEl.textContent.trim() : link.textContent.trim();
            var section = link.getAttribute('data-cmd-group') || 'Navigation';
            var icon = link.getAttribute('data-cmd-icon') || 'arrow-right';
            var href = link.getAttribute('href');
            if (label && href) {
                cmdItems.push({
                    title: label,
                    group: section,
                    icon: icon,
                    keywords: (label + ' ' + section + ' ' + href).toLowerCase(),
                    run: function () { window.location.href = href; }
                });
            }
        });

        cmdItems.push({
            title: 'Toggle Light / Dark Theme',
            group: 'Preferences',
            icon: 'sun-moon',
            keywords: 'dark light theme mode appearance color',
            run: function () {
                window.toggleHrmsTheme();
                window.closeCommandPalette();
                var isDark = document.documentElement.classList.contains('dark');
                window.showHrmsToast('Switched to ' + (isDark ? 'Dark' : 'Light') + ' Mode', 'info');
            }
        });

        cmdItems.push({
            title: 'Toggle Sidebar Compact Mode',
            group: 'Preferences',
            icon: 'panel-left',
            keywords: 'sidebar collapse expand menu layout',
            run: function () {
                window.toggleHrmsSidebar();
                window.closeCommandPalette();
            }
        });

        cmdItems.push({
            title: 'Change Account Password',
            group: 'Account',
            icon: 'key-round',
            keywords: 'password security account credentials',
            run: function () { window.location.href = '/account/password'; }
        });
    }

    function renderCommandResults(query) {
        var list = document.getElementById('cmd-palette-results');
        if (!list) return;
        var q = (query || '').trim().toLowerCase();

        filteredCmdItems = cmdItems.filter(function (item) {
            return !q || item.keywords.indexOf(q) !== -1;
        });

        if (activeCmdIndex >= filteredCmdItems.length) {
            activeCmdIndex = 0;
        }

        if (filteredCmdItems.length === 0) {
            list.innerHTML =
                '<div class="py-10 text-center text-xs text-slate-400">' +
                'No matching pages or actions for "<span class="font-semibold text-slate-600"></span>"' +
                '</div>';
            var span = list.querySelector('span');
            if (span) span.textContent = query;
            return;
        }

        list.innerHTML = '';
        filteredCmdItems.forEach(function (item, idx) {
            var btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'w-full flex items-center justify-between px-3.5 py-2.5 rounded-lg text-left text-sm transition ' +
                (idx === activeCmdIndex ? 'cmd-item-active' : 'text-slate-700 hover:bg-slate-50');
            btn.innerHTML =
                '<span class="flex items-center gap-3 min-w-0">' +
                '<i data-lucide="' + item.icon + '" class="w-4 h-4 flex-shrink-0 opacity-75"></i>' +
                '<span class="font-medium truncate"></span>' +
                '</span>' +
                '<span class="text-[11px] font-medium px-2 py-0.5 rounded bg-slate-100 text-slate-500 flex-shrink-0"></span>';
            btn.querySelectorAll('span')[1].textContent = item.title;
            btn.querySelectorAll('span')[2].textContent = item.group;

            btn.addEventListener('mouseenter', function () {
                activeCmdIndex = idx;
                highlightActiveCmdItem();
            });
            btn.addEventListener('click', function () {
                item.run();
            });
            list.appendChild(btn);
        });

        if (window.lucide) window.lucide.createIcons({ nodes: [list] });
    }

    function highlightActiveCmdItem() {
        var list = document.getElementById('cmd-palette-results');
        if (!list) return;
        Array.prototype.forEach.call(list.children, function (el, idx) {
            if (idx === activeCmdIndex) {
                el.classList.add('cmd-item-active');
                el.scrollIntoView({ block: 'nearest' });
            } else {
                el.classList.remove('cmd-item-active');
            }
        });
    }

    window.openCommandPalette = function () {
        var modal = document.getElementById('cmd-palette');
        var box = document.getElementById('cmd-palette-box');
        var input = document.getElementById('cmd-palette-input');
        if (!modal || !input) return;

        if (cmdItems.length === 0) buildCommandItems();
        activeCmdIndex = 0;
        input.value = '';
        renderCommandResults('');

        modal.classList.remove('hidden');
        requestAnimationFrame(function () {
            modal.classList.remove('opacity-0');
            if (box) {
                box.classList.remove('scale-95');
                box.classList.add('scale-100');
            }
            input.focus();
        });
    };

    window.closeCommandPalette = function () {
        var modal = document.getElementById('cmd-palette');
        var box = document.getElementById('cmd-palette-box');
        if (!modal || modal.classList.contains('hidden')) return;

        modal.classList.add('opacity-0');
        if (box) {
            box.classList.remove('scale-100');
            box.classList.add('scale-95');
        }
        setTimeout(function () {
            modal.classList.add('hidden');
        }, 160);
    };

    // =========================================================================
    // 6. Animated KPI Count-Up
    // =========================================================================
    function animateCounters() {
        document.querySelectorAll('[data-count-up]').forEach(function (el) {
            var raw = el.textContent.trim();
            var target = parseInt(raw, 10);
            if (isNaN(target) || target <= 0 || target > 1000000) return;

            var duration = 520;
            var startTime = null;
            function step(ts) {
                if (!startTime) startTime = ts;
                var progress = Math.min((ts - startTime) / duration, 1);
                var eased = 1 - Math.pow(1 - progress, 3);
                el.textContent = Math.round(eased * target).toLocaleString();
                if (progress < 1) {
                    requestAnimationFrame(step);
                } else {
                    el.textContent = raw;
                }
            }
            requestAnimationFrame(step);
        });
    }

    // =========================================================================
    // 7. DOM Ready Hydration & Global Shortcuts
    // =========================================================================
    document.addEventListener('DOMContentLoaded', function () {
        if (window.lucide) {
            window.lucide.createIcons();
        }
        updateThemeIcons();
        animateCounters();

        // Bind Command Palette Input
        var cmdInput = document.getElementById('cmd-palette-input');
        if (cmdInput) {
            cmdInput.addEventListener('input', function () {
                activeCmdIndex = 0;
                renderCommandResults(cmdInput.value);
            });
            cmdInput.addEventListener('keydown', function (e) {
                if (e.key === 'ArrowDown') {
                    e.preventDefault();
                    if (filteredCmdItems.length > 0) {
                        activeCmdIndex = (activeCmdIndex + 1) % filteredCmdItems.length;
                        highlightActiveCmdItem();
                    }
                } else if (e.key === 'ArrowUp') {
                    e.preventDefault();
                    if (filteredCmdItems.length > 0) {
                        activeCmdIndex = (activeCmdIndex - 1 + filteredCmdItems.length) % filteredCmdItems.length;
                        highlightActiveCmdItem();
                    }
                } else if (e.key === 'Enter') {
                    e.preventDefault();
                    if (filteredCmdItems[activeCmdIndex]) {
                        filteredCmdItems[activeCmdIndex].run();
                    }
                }
            });
        }

        // Click outside dropdown or command palette
        document.addEventListener('click', function (e) {
            var menu = document.getElementById('user-dropdown-menu');
            var trigger = document.getElementById('user-dropdown-trigger');
            if (menu && !menu.classList.contains('hidden')) {
                if (!menu.contains(e.target) && (!trigger || !trigger.contains(e.target))) {
                    menu.classList.add('hidden');
                }
            }
        });

        var cmdModal = document.getElementById('cmd-palette');
        if (cmdModal) {
            cmdModal.addEventListener('click', function (e) {
                if (e.target === cmdModal) window.closeCommandPalette();
            });
        }

        // Global Keyboard Shortcuts: Ctrl+K / Cmd+K and Escape
        document.addEventListener('keydown', function (e) {
            if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
                if (document.getElementById('cmd-palette')) {
                    e.preventDefault();
                    var modal = document.getElementById('cmd-palette');
                    if (modal.classList.contains('hidden')) {
                        window.openCommandPalette();
                    } else {
                        window.closeCommandPalette();
                    }
                }
            } else if (e.key === 'Escape') {
                window.closeCommandPalette();
                window.closeMobileNav();
                var menu = document.getElementById('user-dropdown-menu');
                if (menu) menu.classList.add('hidden');
            }
        });
    });
})();
