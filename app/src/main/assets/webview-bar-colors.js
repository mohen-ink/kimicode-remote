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

    function pointColor(x, y, overlays, styles, rectangles) {
        function style(element) {
            if (!styles.has(element)) styles.set(element, getComputedStyle(element));
            return styles.get(element);
        }

        function rect(element) {
            if (!rectangles.has(element)) rectangles.set(element, element.getBoundingClientRect());
            return rectangles.get(element);
        }

        var hits = document.elementsFromPoint(x, y);
        var ranks = new Map();
        hits.forEach(function (element, index) { ranks.set(element, index); });
        overlays.forEach(function (element) {
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

        function paint(node) {
            var element = node.element;
            var css = style(element);
            var opacity = parseFloat(css.opacity);
            if (!Number.isFinite(opacity)) opacity = 1;
            if (css.display === 'none' || opacity <= 0) return [0, 0, 0, 0];
            var background = [0, 0, 0, 0];
            if (css.visibility !== 'hidden' && css.visibility !== 'collapse' &&
                (element === document.documentElement || contains(rect(element), x, y)) &&
                !(bodyOnCanvas && element === document.body)) {
                background = color(css.backgroundColor);
            }
            node.children.sort(compare).forEach(function (child) {
                background = over(paint(child), background);
            });
            return background.map(function (channel) { return channel * opacity; });
        }

        var result = over(paint(root), base);
        var red = Math.max(0, Math.min(255, Math.round(result[0])));
        var green = Math.max(0, Math.min(255, Math.round(result[1])));
        var blue = Math.max(0, Math.min(255, Math.round(result[2])));
        return ((255 << 24) | (red << 16) | (green << 8) | blue) >>> 0;
    }

    function edgeColor(y, left, width, overlays, styles, rectangles) {
        var counts = new Map();
        var positions = [0.5, 0.15, 0.85, 0.3, 0.7];
        var winner = null;
        var best = 0;
        positions.forEach(function (fraction) {
            var value = pointColor(left + width * fraction, y, overlays, styles, rectangles);
            var count = (counts.get(value) || 0) + 1;
            counts.set(value, count);
            if (count > best) {
                winner = value;
                best = count;
            }
        });
        return winner;
    }

    function sample() {
        if (disposed || document.hidden) return;
        var viewport = window.visualViewport;
        var left = viewport ? viewport.offsetLeft : 0;
        var top = viewport ? viewport.offsetTop : 0;
        var width = viewport ? viewport.width : window.innerWidth;
        var height = viewport ? viewport.height : window.innerHeight;
        if (width < 1 || height < 1) return;
        var overlays = Array.from(document.querySelectorAll(
            '.sheet-root, .sheet-scrim, .sheet-panel, #kuOv, #kuOv .ku-mask, #kuOv .ku-sheet'
        ));
        var styles = new Map();
        var rectangles = new Map();
        var inset = Math.min(1, height / 2);
        var topColor = edgeColor(top + inset, left, width, overlays, styles, rectangles);
        var bottomColor = edgeColor(top + height - inset, left, width, overlays, styles, rectangles);
        if (topColor !== lastTop || bottomColor !== lastBottom) {
            window.KimiBarColor.onColors(documentId, String(topColor), String(bottomColor));
            lastTop = topColor;
            lastBottom = bottomColor;
        }
    }

    function request() {
        if (disposed || document.hidden || timer !== null || frame !== null) return;
        var now = performance.now();
        var period = now < animateUntil ? 50 : 100;
        timer = setTimeout(function () {
            timer = null;
            frame = requestAnimationFrame(function () {
                frame = null;
                if (disposed) return;
                lastSample = performance.now();
                try {
                    sample();
                } catch (error) {
                    console.warn('[KimiBarColor] sampling failed', error);
                }
                if (lastSample < animateUntil) request();
            });
        }, Math.max(0, period - (now - lastSample)));
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
            '.sheet-root, .sheet-scrim, .sheet-panel, #kuOv, header, footer, [role="dialog"]'
        )) return;
        var milliseconds = 200;
        if (event.type === 'animationstart' || event.type === 'transitionstart') {
            if (!(event.target instanceof Element)) return;
            var css = getComputedStyle(event.target);
            milliseconds += Math.max(
                duration(css.animationDuration) + duration(css.animationDelay),
                duration(css.transitionDuration) + duration(css.transitionDelay)
            );
        }
        animateUntil = Math.max(animateUntil, performance.now() + Math.min(1500, milliseconds));
        request();
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
        if (window.__kimiBarWatcher === watcher) delete window.__kimiBarWatcher;
    }

    try {
        listen(window, 'scroll', request, { passive: true, capture: true });
        listen(window, 'resize', request);
        listen(window, 'pageshow', request);
        listen(document, 'visibilitychange', request);
        listen(document, 'load', request, true);
        ['animationstart', 'animationend', 'animationcancel',
            'transitionstart', 'transitionend', 'transitioncancel'].forEach(function (type) {
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
        observer = new MutationObserver(request);
        observer.observe(document.documentElement, {
            childList: true, subtree: true, attributes: true, characterData: true
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
