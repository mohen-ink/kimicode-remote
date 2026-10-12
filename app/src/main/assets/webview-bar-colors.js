(function () {
    'use strict';

    var documentId = String(window.__kimiBarDocument || '');
    var previous = window.__kimiBarWatcher;
    if (previous && previous.documentId === documentId) {
        previous.request();
        return;
    }
    if (previous) previous.dispose();
    if (!documentId || !window.KimiBarColor || !document.documentElement) return;

    var disposed = false;
    var listeners = [];
    var observer = null;
    var interval = null;
    var timer = null;
    var frame = null;
    var animateUntil = 0;
    var lastSample = 0;
    var lastRebuild = 0;
    var dirty = true;
    var plans = null;
    var sheetLayers = [];
    var trackedSheets = new Set();
    var lastTop = null;
    var lastBottom = null;
    var watcher = null;
    var colorCache = new Map();
    var canvas = document.createElement('canvas');
    canvas.width = canvas.height = 1;
    var context = canvas.getContext('2d', { willReadFrequently: true });
    if (!context) return;

    function color(value) {
        if (!value || value === 'transparent') return [0, 0, 0, 0];
        if (colorCache.has(value)) return colorCache.get(value);
        var channels = value.match(/^rgba?\(([^)]+)\)$/i);
        var result;
        if (channels) {
            var parts = channels[1].trim().split(/[\s,\/]+/);
            var alpha = parts.length > 3 ? parseFloat(parts[3]) : 1;
            if (parts.length > 3 && parts[3].endsWith('%')) alpha /= 100;
            alpha = Math.max(0, Math.min(1, alpha));
            result = parts.slice(0, 3).map(function (part) {
                var channel = parseFloat(part) * (part.endsWith('%') ? 2.55 : 1);
                return Math.max(0, Math.min(255, channel)) * alpha;
            });
            result.push(alpha);
        } else {
            context.clearRect(0, 0, 1, 1);
            context.fillStyle = 'rgba(0, 0, 0, 0)';
            context.fillStyle = value;
            context.fillRect(0, 0, 1, 1);
            var pixel = context.getImageData(0, 0, 1, 1).data;
            var pixelAlpha = pixel[3] / 255;
            result = [pixel[0] * pixelAlpha, pixel[1] * pixelAlpha, pixel[2] * pixelAlpha, pixelAlpha];
        }
        if (colorCache.size >= 256) colorCache.clear();
        colorCache.set(value, result);
        return result;
    }

    function over(front, back) {
        var inverse = 1 - front[3];
        return [
            front[0] + back[0] * inverse,
            front[1] + back[1] * inverse,
            front[2] + back[2] * inverse,
            front[3] + back[3] * inverse
        ];
    }

    function contains(rect, x, y) {
        return rect.width > 0 && rect.height > 0 &&
            x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom;
    }

    function opacity(css) {
        var value = parseFloat(css.opacity);
        return Number.isFinite(value) ? Math.max(0, Math.min(1, value)) : 1;
    }

    function argb(result) {
        var red = Math.max(0, Math.min(255, Math.round(result[0])));
        var green = Math.max(0, Math.min(255, Math.round(result[1])));
        var blue = Math.max(0, Math.min(255, Math.round(result[2])));
        return ((255 << 24) | (red << 16) | (green << 8) | blue) >>> 0;
    }

    function pointColor(x, y, overlays, styles, rectangles, sheets) {
        function style(element) {
            if (!styles.has(element)) styles.set(element, getComputedStyle(element));
            return styles.get(element);
        }

        function rect(element) {
            if (!rectangles.has(element)) rectangles.set(element, element.getBoundingClientRect());
            return rectangles.get(element);
        }

        function projectedRoot(element) {
            var root = element.closest('.sheet-root');
            return root && sheets.has(root) ? root : null;
        }

        var hits = document.elementsFromPoint(x, y);
        var ranks = new Map();
        hits.forEach(function (element, index) { ranks.set(element, index); });
        hits = hits.filter(function (element) {
            var root = projectedRoot(element);
            return !root || element === root;
        });
        overlays.forEach(function (element) {
            var root = projectedRoot(element);
            if (root && element !== root) return;
            var css = style(element);
            if (css.display !== 'none' && css.visibility !== 'hidden' &&
                contains(rect(element), x, y) && hits.indexOf(element) < 0) {
                hits.push(element);
            }
        });

        var nodes = new Map();
        function include(element) {
            if (nodes.has(element)) return nodes.get(element);
            var node = {
                element: element,
                children: [],
                rank: ranks.has(element) ? ranks.get(element) : Infinity
            };
            nodes.set(element, node);
            if (element.parentElement) include(element.parentElement).children.push(node);
            return node;
        }
        hits.forEach(include);
        var root = include(document.documentElement);

        function rank(node) {
            node.children.forEach(function (child) {
                node.rank = Math.min(node.rank, rank(child));
            });
            return node.rank;
        }
        rank(root);

        function compare(back, front) {
            if (Number.isFinite(back.rank) && Number.isFinite(front.rank)) {
                return front.rank - back.rank;
            }
            var backStyle = style(back.element);
            var frontStyle = style(front.element);
            var backZ = backStyle.position === 'static' ? 0 : parseInt(backStyle.zIndex, 10) || 0;
            var frontZ = frontStyle.position === 'static' ? 0 : parseInt(frontStyle.zIndex, 10) || 0;
            if (backZ !== frontZ) return backZ - frontZ;
            var order = back.element.compareDocumentPosition(front.element);
            return order & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1;
        }

        var white = [255, 255, 255, 1];
        var rootBackground = color(style(document.documentElement).backgroundColor);
        var bodyBackground = document.body ? color(style(document.body).backgroundColor) : [0, 0, 0, 0];
        var bodyOnCanvas = rootBackground[3] === 0 && bodyBackground[3] > 0;
        var base = bodyOnCanvas ? over(bodyBackground, white) : white;

        function compile(node) {
            var element = node.element;
            if (sheets.has(element)) {
                return function (states) {
                    var state = states.get(element);
                    if (!state || !state.visible || !contains(state.bounds, x, y)) return [0, 0, 0, 0];
                    var background = state.background;
                    if (state.scrim && contains(state.scrim.bounds, x, y)) {
                        background = over(state.scrim.background, background);
                    }
                    if (state.panel && contains(state.panel.bounds, x, y)) {
                        background = over(state.panel.background, background);
                    }
                    return background.map(function (channel) { return channel * state.opacity; });
                };
            }
            var css = style(element);
            var alpha = opacity(css);
            if (css.display === 'none' || alpha <= 0) return [0, 0, 0, 0];
            var background = [0, 0, 0, 0];
            if (css.visibility !== 'hidden' && css.visibility !== 'collapse' &&
                (element === document.documentElement || contains(rect(element), x, y)) &&
                !(bodyOnCanvas && element === document.body)) {
                background = color(css.backgroundColor);
            }
            var children = node.children.sort(compare).map(compile);
            if (children.every(function (child) { return typeof child !== 'function'; })) {
                children.forEach(function (child) { background = over(child, background); });
                return background.map(function (channel) { return channel * alpha; });
            }
            return function (states) {
                var result = background;
                children.forEach(function (child) {
                    result = over(typeof child === 'function' ? child(states) : child, result);
                });
                return result.map(function (channel) { return channel * alpha; });
            };
        }

        var paint = compile(root);
        return function (states) {
            return argb(over(typeof paint === 'function' ? paint(states) : paint, base));
        };
    }

    function edgePlan(y, left, width, overlays, styles, rectangles, sheets) {
        var points = [0.5, 0.15, 0.85, 0.3, 0.7].map(function (fraction) {
            return pointColor(left + width * fraction, y, overlays, styles, rectangles, sheets);
        });
        return function (states) {
            var counts = new Map();
            var winner = null;
            var best = 0;
            points.forEach(function (point) {
                var value = point(states);
                var count = (counts.get(value) || 0) + 1;
                counts.set(value, count);
                if (count > best) {
                    winner = value;
                    best = count;
                }
            });
            return winner;
        };
    }

    function layer(element) {
        if (!element) return null;
        var css = getComputedStyle(element);
        return {
            element: element,
            visible: css.display !== 'none' && css.visibility !== 'hidden' && css.visibility !== 'collapse',
            background: color(css.backgroundColor)
        };
    }

    function readLayer(cached) {
        if (!cached || !cached.visible || !cached.element.isConnected) return null;
        var alpha = opacity(getComputedStyle(cached.element));
        return {
            bounds: cached.element.getBoundingClientRect(),
            background: cached.background.map(function (channel) { return channel * alpha; })
        };
    }

    function rebuild(viewport) {
        var overlays = Array.from(document.querySelectorAll(
            '.sheet-root, .sheet-scrim, .sheet-panel, #kuOv, #kuOv .ku-mask, #kuOv .ku-sheet'
        ));
        var styles = new Map();
        var rectangles = new Map();
        trackedSheets = new Set(document.querySelectorAll('.sheet-root'));
        sheetLayers = Array.from(trackedSheets).map(function (element) {
            var root = layer(element);
            var scrim = element.querySelector('.sheet-scrim');
            var panel = element.querySelector('.sheet-panel');
            return {
                root: root,
                scrim: scrim && scrim.closest('.sheet-root') === element ? layer(scrim) : null,
                panel: panel && panel.closest('.sheet-root') === element ? layer(panel) : null
            };
        });
        var inset = Math.min(1, viewport.height / 2);
        plans = {
            viewport: viewport,
            top: edgePlan(viewport.top + inset, viewport.left, viewport.width,
                overlays, styles, rectangles, trackedSheets),
            bottom: edgePlan(viewport.top + viewport.height - inset, viewport.left, viewport.width,
                overlays, styles, rectangles, trackedSheets)
        };
        dirty = false;
        lastRebuild = performance.now();
    }

    function sample() {
        var visual = window.visualViewport;
        var viewport = {
            left: visual ? visual.offsetLeft : 0,
            top: visual ? visual.offsetTop : 0,
            width: visual ? visual.width : window.innerWidth,
            height: visual ? visual.height : window.innerHeight
        };
        if (viewport.width < 1 || viewport.height < 1) return;
        var changedViewport = !plans || Object.keys(viewport).some(function (key) {
            return viewport[key] !== plans.viewport[key];
        });
        var animating = performance.now() < animateUntil;
        if (changedViewport || !plans || (dirty && (!animating || performance.now() - lastRebuild >= 100))) {
            rebuild(viewport);
        }
        var states = new Map();
        sheetLayers.forEach(function (cached) {
            var root = cached.root;
            var element = root.element;
            states.set(element, {
                visible: root.visible && element.isConnected,
                opacity: element.isConnected ? opacity(getComputedStyle(element)) : 0,
                bounds: element.getBoundingClientRect(),
                background: root.background,
                scrim: readLayer(cached.scrim),
                panel: readLayer(cached.panel)
            });
        });
        var topColor = plans.top(states);
        var bottomColor = plans.bottom(states);
        if (topColor !== lastTop || bottomColor !== lastBottom) {
            window.KimiBarColor.onColors(documentId, String(topColor), String(bottomColor));
            lastTop = topColor;
            lastBottom = bottomColor;
        }
    }

    function tick() {
        frame = null;
        if (disposed || document.hidden) return;
        lastSample = performance.now();
        try {
            sample();
        } catch (error) {
            dirty = true;
            console.warn('[KimiBarColor] sampling failed', error);
        }
        if (performance.now() < animateUntil) frame = requestAnimationFrame(tick);
        else if (dirty) request();
    }

    function request() {
        dirty = true;
        if (disposed || document.hidden || timer !== null || frame !== null) return;
        if (performance.now() < animateUntil) {
            frame = requestAnimationFrame(tick);
            return;
        }
        timer = setTimeout(function () {
            timer = null;
            frame = requestAnimationFrame(tick);
        }, Math.max(0, 100 - (performance.now() - lastSample)));
    }

    function follow(milliseconds) {
        animateUntil = Math.max(animateUntil, performance.now() + Math.min(1500, milliseconds));
        if (disposed || document.hidden) return;
        if (timer !== null) clearTimeout(timer);
        timer = null;
        if (frame === null) frame = requestAnimationFrame(tick);
    }

    function mutation(records) {
        var sheets = new Set(document.querySelectorAll('.sheet-root'));
        var topologyChanged = sheets.size !== trackedSheets.size;
        sheets.forEach(function (element) {
            if (!trackedSheets.has(element)) topologyChanged = true;
        });
        if (topologyChanged) {
            plans = null;
            dirty = true;
        }
        var sheetChanged = topologyChanged;
        records.forEach(function (record) {
            var target = record.target instanceof Element ? record.target : record.target.parentElement;
            if (target && target.closest('.sheet-root')) {
                sheetChanged = true;
                var animatedClass = record.type === 'attributes' && record.attributeName === 'class' &&
                    target.matches('.sheet-root, .sheet-panel, .sheet-scrim') &&
                    /\bsheet-(enter|leave)-(active|from|to)\b/.test(
                        String(record.oldValue || '') + ' ' + String(target.className)
                    );
                if (!animatedClass) {
                    dirty = true;
                    if (target && target.matches('.sheet-root, .sheet-panel, .sheet-scrim')) plans = null;
                }
            } else dirty = true;
        });
        if (sheetChanged) follow(400);
        else request();
    }

    function duration(value) {
        return value.split(',').reduce(function (maximum, part) {
            part = part.trim();
            var number = parseFloat(part) || 0;
            return Math.max(maximum, part.endsWith('ms') ? number : number * 1000);
        }, 0);
    }

    function animation(event) {
        if (!(event.target instanceof Element) || !event.target.closest(
            '.sheet-root, #kuOv, header, footer, [role="dialog"]'
        )) return;
        var sheet = event.target.closest('.sheet-root');
        if (!sheet || /(?:end|cancel)$/.test(event.type)) dirty = true;
        var milliseconds = 80;
        if (event.type === 'animationstart' || event.type === 'transitionrun' || event.type === 'transitionstart') {
            var css = getComputedStyle(event.target);
            milliseconds += Math.max(
                duration(css.animationDuration) + duration(css.animationDelay),
                duration(css.transitionDuration) + duration(css.transitionDelay)
            );
        }
        follow(milliseconds);
    }

    function listen(target, type, handler, options) {
        target.addEventListener(type, handler, options);
        listeners.push([target, type, handler, options]);
    }

    function dispose() {
        if (disposed) return;
        disposed = true;
        if (observer) observer.disconnect();
        if (interval !== null) clearInterval(interval);
        if (timer !== null) clearTimeout(timer);
        if (frame !== null) cancelAnimationFrame(frame);
        listeners.forEach(function (listener) {
            listener[0].removeEventListener(listener[1], listener[2], listener[3]);
        });
        listeners.length = 0;
        colorCache.clear();
        trackedSheets.clear();
        sheetLayers.length = 0;
        plans = null;
        if (window.__kimiBarWatcher === watcher) delete window.__kimiBarWatcher;
    }

    try {
        listen(window, 'scroll', request, { passive: true, capture: true });
        listen(window, 'resize', request);
        listen(window, 'pageshow', request);
        listen(document, 'visibilitychange', request);
        listen(document, 'load', request, true);
        ['animationstart', 'animationend', 'animationcancel',
            'transitionrun', 'transitionstart', 'transitionend', 'transitioncancel'].forEach(function (type) {
            listen(window, type, animation, true);
        });
        if (window.visualViewport) {
            listen(window.visualViewport, 'resize', request);
            listen(window.visualViewport, 'scroll', request, { passive: true });
        }
        var theme = window.matchMedia('(prefers-color-scheme: dark)');
        if (theme.addEventListener) listen(theme, 'change', request);
        else {
            theme.addListener(request);
            listeners.push([{
                removeEventListener: function () { theme.removeListener(request); }
            }, 'change', request]);
        }
        observer = new MutationObserver(mutation);
        observer.observe(document.documentElement, {
            childList: true, subtree: true, attributes: true, attributeOldValue: true, characterData: true
        });
        interval = setInterval(request, 500);
        watcher = { documentId: documentId, request: request, dispose: dispose };
        window.__kimiBarWatcher = watcher;
        request();
    } catch (error) {
        dispose();
        console.error('[KimiBarColor] installation failed', error);
    }
})();
