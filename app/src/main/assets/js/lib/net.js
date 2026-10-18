(function () {
    function httpWith(sessionId, url, options = {}) {
        options = options || {};
        if (sessionId !== null && sessionId !== undefined) {
            options = Object.assign({}, options, {__session: sessionId});
        }
        if (options.async === false) return _http(url, options);
        return new Promise(resolve => {
            const request = Object.assign({}, options, {
                complete: res => resolve(res)
            });
            try {
                _http(url, request);
            } catch (e) {
                resolve({
                    code: "",
                    headers: {},
                    content: "",
                    error: String(e && e.message ? e.message : e)
                });
            }
        });
    }

    function http(url, options = {}) {
        return httpWith(null, url, options);
    }

    globalThis.req = (url, options) => httpWith(null, url, Object.assign({async: false}, options));
    globalThis.http = http;

    function defineGlobalAlias(name) {
        const descriptor = Object.getOwnPropertyDescriptor(globalThis, name);
        if (descriptor && !descriptor.configurable) return;
        Object.defineProperty(globalThis, name, {
            enumerable: true,
            configurable: true,
            get() {
                return globalThis;
            },
            set() {}
        });
    }

    ['global', 'window', 'self'].forEach(defineGlobalAlias);

    const NET_CACHE_MAX_VALUE = 262144;
    const NET_CACHE_MAX_KEYS = 64;
    const NET_CACHE_INDEX = "__index__";
    const netCache = new Map();

    async function jsonWith(sessionId, url, options = {}) {
        if (options.buffer !== undefined && options.buffer !== 0) {
            throw new TypeError("net.json only supports text mode");
        }
        const response = await httpWith(sessionId, url, Object.assign({}, options, {buffer: 0}));
        if (!response) throw new Error("empty response");
        if (response.error) throw new Error(response.error);
        if (!(response.code >= 200 && response.code < 300)) throw new Error("HTTP " + response.code);
        try {
            return JSON.parse(response.content);
        } catch (e) {
            throw new Error("invalid json: " + e.message);
        }
    }

    function netLimit(limit) {
        if (limit === undefined) return 4;
        if (!Number.isSafeInteger(limit) || limit <= 0) throw new TypeError("limit must be a positive integer");
        return limit;
    }

    function runLimited(items, limit, worker) {
        return new Promise((resolve, reject) => {
            const results = new Array(items.length);
            let next = 0;
            let active = 0;
            let done = 0;
            let failed = false;
            if (items.length === 0) return resolve(results);
            const launch = () => {
                while (!failed && active < limit && next < items.length) {
                    const index = next++;
                    active++;
                    Promise.resolve().then(() => worker(items[index], index)).then(value => {
                        results[index] = value;
                        active--;
                        done++;
                        if (done === items.length) resolve(results);
                        else launch();
                    }, error => {
                        failed = true;
                        reject(error);
                    });
                }
            };
            launch();
        });
    }

    async function batchWith(sessionId, requests, limit) {
        if (!Array.isArray(requests)) throw new TypeError("requests must be an array");
        const size = netLimit(limit);
        return runLimited(requests, size, request => {
            if (!request || typeof request.url !== "string") throw new TypeError("request.url must be a string");
            return httpWith(sessionId, request.url, request.options || {});
        });
    }

    async function netMap(items, callback, limit) {
        if (!Array.isArray(items)) throw new TypeError("items must be an array");
        if (typeof callback !== "function") throw new TypeError("callback must be a function");
        const size = netLimit(limit);
        return runLimited(items, size, (item, index) => callback(item, index));
    }

    function cacheNamespace() {
        const site = typeof globalThis.__jsSiteKey === "string" ? globalThis.__jsSiteKey : "default";
        return site + "_netcache";
    }

    function cacheParse(raw) {
        if (!raw) return null;
        try {
            const saved = JSON.parse(raw);
            if (!saved || typeof saved !== "object" || typeof saved.e !== "number") return null;
            return {value: saved.v, expireAt: saved.e, pending: null, hasValue: true};
        } catch (e) {
            return null;
        }
    }

    function cacheLoadPersist(key) {
        try {
            if (typeof local === "undefined") return null;
            return cacheParse(local.get(cacheNamespace(), key));
        } catch (e) {
            return null;
        }
    }

    function cacheIndex() {
        try {
            if (typeof local === "undefined") return [];
            const raw = local.get(cacheNamespace(), NET_CACHE_INDEX);
            const keys = raw ? JSON.parse(raw) : [];
            return Array.isArray(keys) ? keys.filter(key => typeof key === "string") : [];
        } catch (e) {
            return [];
        }
    }

    function cacheIndexWrite(keys) {
        try {
            if (typeof local === "undefined") return;
            local.set(cacheNamespace(), NET_CACHE_INDEX, JSON.stringify(keys));
        } catch (e) {
            console.error("net.cached index failed", e);
        }
    }

    function cacheWritePersist(key, entry) {
        try {
            if (typeof local === "undefined") return;
            const raw = JSON.stringify({v: entry.value, e: entry.expireAt});
            if (!raw || raw.length > NET_CACHE_MAX_VALUE) return;
            local.set(cacheNamespace(), key, raw);
            const keys = cacheIndex();
            if (keys.indexOf(key) >= 0) return;
            keys.push(key);
            while (keys.length > NET_CACHE_MAX_KEYS) {
                local.delete(cacheNamespace(), keys.shift());
            }
            cacheIndexWrite(keys);
        } catch (e) {
            console.error("net.cached persist failed", e);
        }
    }

    function cacheDeletePersist(key) {
        try {
            if (typeof local === "undefined") return;
            local.delete(cacheNamespace(), key);
            const keys = cacheIndex();
            const index = keys.indexOf(key);
            if (index >= 0) {
                keys.splice(index, 1);
                cacheIndexWrite(keys);
            }
        } catch (e) {
            console.error("net.cached delete failed", e);
        }
    }

    function cacheClearPersist() {
        try {
            if (typeof local === "undefined") return;
            const keys = cacheIndex();
            for (const key of keys) local.delete(cacheNamespace(), key);
            local.delete(cacheNamespace(), NET_CACHE_INDEX);
        } catch (e) {
            console.error("net.cached clear failed", e);
        }
    }

    function cacheClone(value) {
        return JSON.parse(JSON.stringify(value));
    }

    function cacheStart(store, key, ttl, persist, loader) {
        const entry = store.get(key) || {};
        const task = Promise.resolve().then(loader).then(value => {
            if (entry.cancelled) return value;
            entry.value = value;
            entry.expireAt = Date.now() + ttl;
            entry.pending = null;
            entry.hasValue = true;
            store.set(key, entry);
            if (persist) cacheWritePersist(key, entry);
            return value;
        }, error => {
            entry.pending = null;
            if (!entry.cancelled) store.set(key, entry);
            throw error;
        });
        entry.pending = task;
        store.set(key, entry);
        return task;
    }

    function cacheRefresh(store, key, ttl, persist, loader) {
        const entry = store.get(key);
        if (entry && (entry.pending || entry.expireAt > Date.now())) return;
        cacheStart(store, key, ttl, persist, loader).catch(error => {
            console.error("net.cached refresh failed", error && error.message ? error.message : error);
        });
    }

    function cacheLookup(store, key, persist) {
        let entry = store.get(key);
        if (!entry && persist) {
            entry = cacheLoadPersist(key);
            if (entry) store.set(key, entry);
        }
        return entry;
    }

    async function netCachedWith(store, allowPersist, key, options, loader) {
        if (typeof key !== "string" || !key) throw new TypeError("key must be a non-empty string");
        if (typeof loader !== "function") throw new TypeError("loader must be a function");
        const opts = options || {};
        const ttl = opts.ttl;
        if (!Number.isSafeInteger(ttl) || ttl < 0) throw new TypeError("ttl must be a non-negative integer");
        const stale = opts.stale !== false;
        const persist = allowPersist && opts.persist === true;
        if (ttl === 0) return loader();
        const entry = cacheLookup(store, key, persist);
        if (entry && entry.pending) return cacheClone(await entry.pending);
        if (entry && entry.hasValue && entry.expireAt > Date.now()) return cacheClone(entry.value);
        if (entry && entry.hasValue && stale) {
            cacheRefresh(store, key, ttl, persist, loader);
            return cacheClone(entry.value);
        }
        return cacheClone(await cacheStart(store, key, ttl, persist, loader));
    }

    function netPeekWith(store, allowPersist, key) {
        if (typeof key !== "string" || !key) return null;
        const entry = cacheLookup(store, key, allowPersist);
        if (!entry || !entry.hasValue) return null;
        return cacheClone(entry.value);
    }

    function netRefreshWith(store, allowPersist, key, options, loader) {
        if (typeof key !== "string" || !key) throw new TypeError("key must be a non-empty string");
        if (typeof loader !== "function") throw new TypeError("loader must be a function");
        const opts = options || {};
        const ttl = opts.ttl;
        if (!Number.isSafeInteger(ttl) || ttl < 0) throw new TypeError("ttl must be a non-negative integer");
        if (ttl === 0) return undefined;
        cacheRefresh(store, key, ttl, allowPersist && opts.persist === true, loader);
        return undefined;
    }

    function netClearCacheWith(store, allowPersist, key) {
        if (key === undefined) {
            for (const entry of store.values()) entry.cancelled = true;
            store.clear();
            if (allowPersist) cacheClearPersist();
            return undefined;
        }
        if (typeof key !== "string" || !key) throw new TypeError("key must be a non-empty string");
        const entry = store.get(key);
        if (entry) entry.cancelled = true;
        store.delete(key);
        if (allowPersist) cacheDeletePersist(key);
        return undefined;
    }

    function netSleep(ms) {
        if (!Number.isSafeInteger(ms) || ms < 0 || ms > 2147483647) {
            return Promise.reject(new TypeError("ms must be a non-negative integer"));
        }
        return new Promise(resolve => setTimeout(resolve, ms));
    }

    async function openStreamWith(sessionId, url, options = {}) {
        const request = Object.assign({}, options || {});
        if (sessionId !== null && sessionId !== undefined) request.__session = sessionId;
        const reader = _openStream(url, request);
        if (!reader) throw new Error("openStream failed");
        const read = reader.read;
        reader.read = size => {
            if (size === undefined) return read.call(reader);
            if (!Number.isSafeInteger(size) || size < 1 || size > 1048576) {
                throw new TypeError("size must be 1..1048576");
            }
            return read.call(reader, size);
        };
        return reader;
    }

    function streamWith(sessionId, url, options) {
        if (typeof url !== "string" || !url) {
            return Promise.reject(new TypeError("url must be a non-empty string"));
        }
        const request = Object.assign({}, options || {});
        if (sessionId !== null && sessionId !== undefined) request.__session = sessionId;
        return new Promise((resolve, reject) => {
            try {
                _stream(url, request, resolve, reject);
            } catch (e) {
                reject(e);
            }
        });
    }

    function createSession() {
        const id = _session();
        const store = new Map();
        let closed = false;
        const guard = () => {
            if (closed) throw new Error("session closed");
        };
        return {
            http: (url, options) => {
                guard();
                return httpWith(id, url, options);
            },
            req: (url, options) => {
                guard();
                return httpWith(id, url, Object.assign({}, options, {async: false}));
            },
            json: (url, options) => {
                guard();
                return jsonWith(id, url, options);
            },
            batch: (requests, limit) => {
                guard();
                return batchWith(id, requests, limit);
            },
            stream: (url, options) => {
                guard();
                return streamWith(id, url, options);
            },
            openStream: (url, options) => {
                guard();
                return openStreamWith(id, url, options);
            },
            cached: (key, options, loader) => {
                guard();
                return netCachedWith(store, false, key, options, loader);
            },
            peek: key => {
                guard();
                return netPeekWith(store, false, key);
            },
            refresh: (key, options, loader) => {
                guard();
                return netRefreshWith(store, false, key, options, loader);
            },
            clearCache: key => {
                guard();
                return netClearCacheWith(store, false, key);
            },
            getCookie: url => {
                guard();
                return _sessionGetCookie(id, url);
            },
            setCookie: (url, cookie) => {
                guard();
                return _sessionSetCookie(id, url, cookie);
            },
            sleep: ms => {
                guard();
                return netSleep(ms);
            },
            close: () => {
                if (closed) return undefined;
                closed = true;
                for (const entry of store.values()) entry.cancelled = true;
                store.clear();
                _sessionClose(id);
                return undefined;
            }
        };
    }

    const net = {
        http,
        req: (url, options) => httpWith(null, url, Object.assign({}, options, {async: false})),
        json: (url, options) => jsonWith(null, url, options),
        batch: (requests, limit) => batchWith(null, requests, limit),
        map: netMap,
        cached: (key, options, loader) => netCachedWith(netCache, true, key, options, loader),
        peek: key => netPeekWith(netCache, true, key),
        refresh: (key, options, loader) => netRefreshWith(netCache, true, key, options, loader),
        clearCache: key => netClearCacheWith(netCache, true, key),
        sleep: netSleep,
        getUserAgent: () => _getUserAgent(),
        stream: (url, options) => streamWith(null, url, options),
        openStream: (url, options) => openStreamWith(null, url, options),
        session: createSession,
        getCookie: url => _getCookie(url),
        setCookie: (url, cookie) => _setCookie(url, cookie)
    };

    globalThis.net = net;
    globalThis.htmlQuery = (html, plan) => {
        if (typeof html !== "string") throw new TypeError("html must be a string");
        if (!plan || typeof plan !== "object") throw new TypeError("plan must be an object");
        const result = _htmlQuery(html, plan);
        if (!result) throw new Error("htmlQuery failed");
        return result;
    };
})();
