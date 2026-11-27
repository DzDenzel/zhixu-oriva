(function (root) {
  "use strict";

  var MAX_URL_LENGTH = 2048;

  function hasControlOrWhitespace(value) {
    for (var i = 0; i < value.length; i += 1) {
      var code = value.charCodeAt(i);
      if (code <= 0x20 || (code >= 0x7f && code <= 0x9f) || /\s/.test(value.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  function safeHttpUrl(raw) {
    if (typeof raw !== "string") return null;
    var trimmed = raw.trim();
    if (!trimmed || trimmed.length > MAX_URL_LENGTH) return null;
    if (hasControlOrWhitespace(trimmed) || trimmed.indexOf("\\") !== -1) return null;
    var lower = trimmed.toLowerCase();
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null;
    try {
      var parsed = new URL(trimmed);
      if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return null;
      if (!parsed.hostname || parsed.username || parsed.password) return null;
      if (parsed.host.indexOf("@") !== -1) return null;
      return parsed.href;
    } catch (_err) {
      return null;
    }
  }

  var SAFE_ARTIFACT_REL_PATTERN = /^(?:\.\/)?artifacts\/([A-Za-z0-9._-]+)$/;
  var SAFE_ARTIFACT_FILENAME_PATTERN = /^[A-Za-z0-9._-]+$/;

  function isSafeArtifactFilename(name) {
    if (!name || name === "." || name === "..") return false;
    if (!SAFE_ARTIFACT_FILENAME_PATTERN.test(name)) return false;
    return name.endsWith(".plp") || name.endsWith(".pljs");
  }

  function safeDownloadUrl(raw, baseHref) {
    if (typeof raw !== "string") return null;
    var trimmed = raw.trim();
    if (!trimmed || trimmed.length > MAX_URL_LENGTH) return null;
    if (hasControlOrWhitespace(trimmed) || trimmed.indexOf("\\") !== -1) return null;
    if (trimmed.indexOf("?") !== -1 || trimmed.indexOf("#") !== -1) return null;
    if (trimmed.startsWith("//")) return null;

    var relMatch = SAFE_ARTIFACT_REL_PATTERN.exec(trimmed);
    if (relMatch) {
      if (!isSafeArtifactFilename(relMatch[1])) return null;
      if (baseHref && (baseHref.startsWith("http://") || baseHref.startsWith("https://"))) {
        try {
          var base = new URL(baseHref);
          var resolved = new URL("./artifacts/" + relMatch[1], base);
          if (resolved.origin !== base.origin || resolved.search || resolved.hash) return null;
          return safeHttpUrl(resolved.href);
        } catch (_err) {
          return null;
        }
      }
      return "./artifacts/" + relMatch[1];
    }

    var safeAbs = safeHttpUrl(trimmed);
    if (!safeAbs || !baseHref) return null;
    try {
      var targetUrl = new URL(safeAbs);
      var originUrl = new URL(baseHref);
      if (targetUrl.origin !== originUrl.origin) return null;
      if (targetUrl.search || targetUrl.hash) return null;
      var expectedArtifactsDir = new URL("./artifacts/", originUrl);
      if (targetUrl.origin !== expectedArtifactsDir.origin) return null;
      if (!targetUrl.pathname.startsWith(expectedArtifactsDir.pathname)) return null;
      var remainder = targetUrl.pathname.slice(expectedArtifactsDir.pathname.length);
      if (!isSafeArtifactFilename(remainder)) return null;
      return targetUrl.href;
    } catch (_err) {
      return null;
    }
  }

  function filterPlugins(plugins, query, typeFilter) {
    var list = Array.isArray(plugins) ? plugins : [];
    var needle = typeof query === "string" ? query.trim().toLowerCase() : "";
    var wantType = typeFilter === "js" || typeFilter === "dex" ? typeFilter : "all";
    return list.filter(function (plugin) {
      var pType = (plugin && plugin.type ? String(plugin.type) : "dex").toLowerCase();
      if (wantType !== "all" && pType !== wantType) return false;
      if (!needle) return true;
      var haystack = [
        plugin.name,
        plugin.id,
        plugin.description,
        plugin.author,
        plugin.license,
        Array.isArray(plugin.requiredCapabilities) ? plugin.requiredCapabilities.join(" ") : ""
      ]
        .filter(Boolean)
        .join(" ")
        .toLowerCase();
      return haystack.indexOf(needle) !== -1;
    });
  }

  function appendBadge(doc, parent, text, extraClass) {
    var span = doc.createElement("span");
    span.className = extraClass ? "badge " + extraClass : "badge";
    span.textContent = text;
    parent.appendChild(span);
    return span;
  }

  function appendMetaRow(doc, dl, label, value, isUrl) {
    if (value === undefined || value === null || String(value).trim() === "") return;
    var dt = doc.createElement("dt");
    dt.textContent = label;
    var dd = doc.createElement("dd");
    if (isUrl) {
      var safeUrl = safeHttpUrl(String(value));
      if (safeUrl) {
        var anchor = doc.createElement("a");
        anchor.href = safeUrl;
        anchor.target = "_blank";
        anchor.rel = "noopener noreferrer";
        anchor.textContent = safeUrl;
        dd.appendChild(anchor);
      } else {
        var blocked = doc.createElement("span");
        blocked.className = "badge badge-crimson";
        blocked.textContent = "已拦截不安全链接";
        dd.appendChild(blocked);
      }
    } else {
      dd.textContent = String(value);
    }
    dl.appendChild(dt);
    dl.appendChild(dd);
  }

  function createPluginCard(doc, plugin, baseHref, onCopy) {
    var article = doc.createElement("article");
    article.className = "plugin-card";

    var head = doc.createElement("div");
    head.className = "card-head";

    var titleWrap = doc.createElement("div");
    var title = doc.createElement("h3");
    title.className = "card-title";
    title.textContent = String(plugin.name || plugin.id || "");
    var subtitle = doc.createElement("p");
    subtitle.className = "card-subtitle";
    subtitle.textContent = String(plugin.id || "") + " · v" + String(plugin.version || "");
    titleWrap.appendChild(title);
    titleWrap.appendChild(subtitle);

    var pType = (plugin.type ? String(plugin.type) : "dex").toLowerCase();
    var typeClass = pType === "js" ? "badge-jade" : "badge-indigo";
    head.appendChild(titleWrap);
    appendBadge(doc, head, pType.toUpperCase() + " · 协议 v" + String(plugin.contractVersion || 1), typeClass);
    article.appendChild(head);

    var desc = doc.createElement("p");
    desc.className = "card-desc";
    desc.textContent = plugin.description ? String(plugin.description) : "暂无简介";
    article.appendChild(desc);

    var caps = Array.isArray(plugin.requiredCapabilities) ? plugin.requiredCapabilities : [];
    var capList = doc.createElement("div");
    capList.className = "cap-list";
    if (caps.length === 0) {
      appendBadge(doc, capList, "无额外能力申请");
    } else {
      for (var i = 0; i < caps.length; i += 1) {
        appendBadge(doc, capList, String(caps[i]));
      }
    }
    article.appendChild(capList);

    var dl = doc.createElement("dl");
    dl.className = "meta-table";
    appendMetaRow(doc, dl, "入口", plugin.entryPoint, false);
    appendMetaRow(doc, dl, "作者", plugin.author, false);
    appendMetaRow(doc, dl, "许可证", plugin.license, false);
    appendMetaRow(doc, dl, "主页", plugin.homepage, true);
    appendMetaRow(doc, dl, "仓库", plugin.repository, true);
    article.appendChild(dl);

    var shaValue = String(plugin.sha256 || "");
    var hashRow = doc.createElement("div");
    hashRow.className = "hash-row";
    var hashCode = doc.createElement("code");
    hashCode.className = "hash-code";
    hashCode.title = shaValue;
    hashCode.textContent = "SHA-256: " + shaValue;
    var copyHashBtn = doc.createElement("button");
    copyHashBtn.type = "button";
    copyHashBtn.className = "outline-btn";
    copyHashBtn.textContent = "复制校验和";
    copyHashBtn.addEventListener("click", function () {
      if (onCopy) onCopy(shaValue, "已复制包 SHA-256");
    });
    hashRow.appendChild(hashCode);
    hashRow.appendChild(copyHashBtn);
    article.appendChild(hashRow);

    var actions = doc.createElement("div");
    actions.className = "card-actions";
    var downloadSafe = safeDownloadUrl(plugin.downloadUrl, baseHref);
    if (downloadSafe) {
      var dlLink = doc.createElement("a");
      dlLink.href = downloadSafe;
      dlLink.setAttribute("download", "");
      dlLink.textContent = "下载签名包";
      actions.appendChild(dlLink);
    } else {
      appendBadge(doc, actions, "下载地址不可用", "badge-crimson");
    }
    var copyIdBtn = doc.createElement("button");
    copyIdBtn.type = "button";
    copyIdBtn.className = "ghost-btn";
    copyIdBtn.textContent = "复制插件 ID";
    copyIdBtn.addEventListener("click", function () {
      if (onCopy) onCopy(String(plugin.id || ""), "已复制插件 ID");
    });
    actions.appendChild(copyIdBtn);
    article.appendChild(actions);

    return article;
  }

  function renderCatalogState(doc, elements, catalog, siteMeta, options) {
    var opts = options || {};
    var query = opts.query || "";
    var typeFilter = opts.typeFilter || "all";
    var baseHref = opts.baseHref || "";
    var onCopy = opts.onCopy || null;

    var sourceId = String((catalog && catalog.sourceId) || (siteMeta && siteMeta.sourceId) || "—");
    var sourceType = String((catalog && catalog.sourceType) || (siteMeta && siteMeta.sourceType) || "THIRD_PARTY");
    var formatVersion = Number((catalog && catalog.catalogFormatVersion) || 1);
    var plugins = Array.isArray(catalog && catalog.plugins) ? catalog.plugins : [];

    elements.siteTitle.textContent = String((siteMeta && siteMeta.sourceName) || sourceId);
    elements.siteDescription.textContent = String(
      (siteMeta && siteMeta.sourceDescription) ||
        "纯静态插件目录（Morphology B），可在知序 Android 客户端中通过自定义第三方来源接入。"
    );

    elements.sourceBadges.replaceChildren();
    appendBadge(doc, elements.sourceBadges, "来源 ID：" + sourceId);
    appendBadge(doc, elements.sourceBadges, "类型：" + sourceType, "badge-brass");
    appendBadge(doc, elements.sourceBadges, "目录协议 v" + String(formatVersion));
    appendBadge(doc, elements.sourceBadges, "共 " + String(plugins.length) + " 个插件", "badge-jade");

    elements.sourceIdText.textContent = sourceId;
    var catalogHref = baseHref ? resolveCatalogHref(baseHref) : "./catalog.json";
    elements.catalogUrlText.textContent = catalogHref;

    elements.fingerprintList.replaceChildren();
    var fps = Array.isArray(siteMeta && siteMeta.trustedFingerprints) ? siteMeta.trustedFingerprints : [];
    if (fps.length === 0) {
      var noFp = doc.createElement("p");
      noFp.className = "safety-note";
      noFp.textContent = "当前未随页提供 site-meta.json 信任锚指纹；客户端在未绑定指纹前仅允许浏览目录。";
      elements.fingerprintList.appendChild(noFp);
    } else {
      for (var i = 0; i < fps.length; i += 1) {
        (function (fp) {
          var row = doc.createElement("div");
          row.className = "copy-row";
          var code = doc.createElement("code");
          code.className = "mono-box";
          code.textContent = String(fp);
          var btn = doc.createElement("button");
          btn.type = "button";
          btn.className = "seal-btn";
          btn.textContent = "复制指纹";
          btn.addEventListener("click", function () {
            if (onCopy) onCopy(String(fp), "已复制信任锚 SHA-256 指纹");
          });
          row.appendChild(code);
          row.appendChild(btn);
          elements.fingerprintList.appendChild(row);
        })(fps[i]);
      }
    }

    var filtered = filterPlugins(plugins, query, typeFilter);
    elements.resultSummary.textContent =
      "显示 " + String(filtered.length) + " / " + String(plugins.length) + " 个已校验插件";

    elements.pluginGrid.replaceChildren();
    if (plugins.length === 0) {
      elements.statusPanel.hidden = false;
      elements.statusPanel.className = "status-panel";
      elements.statusPanel.textContent = "当前源目录暂未收录插件。请通过 PR 提交已签名的 .pljs / .plp 插件包。";
      return;
    }
    if (filtered.length === 0) {
      elements.statusPanel.hidden = false;
      elements.statusPanel.className = "status-panel";
      elements.statusPanel.textContent = "没有匹配当前检索条件或类型筛选的插件。";
      return;
    }

    elements.statusPanel.hidden = true;
    for (var j = 0; j < filtered.length; j += 1) {
      elements.pluginGrid.appendChild(createPluginCard(doc, filtered[j], baseHref, onCopy));
    }
  }

  function resolveCatalogHref(pageHref) {
    try {
      return new URL("./catalog.json", pageHref).href;
    } catch (_err) {
      return "./catalog.json";
    }
  }

  function copyText(text, label, showToast) {
    if (!text) return;
    if (typeof navigator !== "undefined" && navigator.clipboard && typeof navigator.clipboard.writeText === "function") {
      navigator.clipboard.writeText(text).then(
        function () { showToast(label); },
        function () { fallbackCopy(text, label, showToast); }
      );
      return;
    }
    fallbackCopy(text, label, showToast);
  }

  function fallbackCopy(text, label, showToast) {
    var area = document.createElement("textarea");
    area.value = text;
    area.setAttribute("readonly", "");
    area.style.position = "fixed";
    area.style.opacity = "0";
    document.body.appendChild(area);
    area.select();
    try {
      var copied = Boolean(document.execCommand("copy"));
      showToast(copied ? label : "复制失败，请手动选中复制");
    } catch (_err) {
      showToast("复制失败，请手动选中复制");
    } finally {
      document.body.removeChild(area);
    }
  }

  function initBrowserApp() {
    if (typeof document === "undefined") return;
    var elements = {
      siteTitle: document.getElementById("site-title"),
      siteDescription: document.getElementById("site-description"),
      sourceBadges: document.getElementById("source-badges"),
      catalogUrlText: document.getElementById("catalog-url-text"),
      sourceIdText: document.getElementById("source-id-text"),
      fingerprintList: document.getElementById("fingerprint-list"),
      searchInput: document.getElementById("search-input"),
      resultSummary: document.getElementById("result-summary"),
      statusPanel: document.getElementById("status-panel"),
      pluginGrid: document.getElementById("plugin-grid"),
      copyCatalogBtn: document.getElementById("copy-catalog-url"),
      copySourceIdBtn: document.getElementById("copy-source-id"),
      themeToggle: document.getElementById("theme-toggle"),
      toast: document.getElementById("copy-toast")
    };
    if (!elements.pluginGrid) return;

    var toastTimer = null;
    function showToast(msg) {
      elements.toast.textContent = msg;
      elements.toast.hidden = false;
      if (toastTimer) clearTimeout(toastTimer);
      toastTimer = setTimeout(function () {
        elements.toast.hidden = true;
      }, 2400);
    }

    var state = {
      catalog: null,
      siteMeta: null,
      query: "",
      typeFilter: "all"
    };

    function rerender() {
      if (!state.catalog) return;
      renderCatalogState(document, elements, state.catalog, state.siteMeta, {
        query: state.query,
        typeFilter: state.typeFilter,
        baseHref: window.location.href,
        onCopy: function (value, msg) { copyText(value, msg, showToast); }
      });
    }

    elements.copyCatalogBtn.addEventListener("click", function () {
      copyText(elements.catalogUrlText.textContent, "已复制目录 JSON 地址", showToast);
    });
    elements.copySourceIdBtn.addEventListener("click", function () {
      copyText(elements.sourceIdText.textContent, "已复制来源 ID", showToast);
    });

    elements.searchInput.addEventListener("input", function (evt) {
      state.query = evt.target.value || "";
      rerender();
    });

    var filterBtns = document.querySelectorAll(".filter-btn");
    for (var i = 0; i < filterBtns.length; i += 1) {
      filterBtns[i].addEventListener("click", function (evt) {
        var btn = evt.currentTarget;
        state.typeFilter = btn.getAttribute("data-type") || "all";
        for (var k = 0; k < filterBtns.length; k += 1) {
          var active = filterBtns[k] === btn;
          filterBtns[k].classList.toggle("is-active", active);
          filterBtns[k].setAttribute("aria-pressed", active ? "true" : "false");
        }
        rerender();
      });
    }

    elements.themeToggle.addEventListener("click", function () {
      var html = document.documentElement;
      var current = html.getAttribute("data-theme");
      var prefersDark = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
      var next = current ? (current === "dark" ? "light" : "dark") : (prefersDark ? "light" : "dark");
      html.setAttribute("data-theme", next);
      elements.themeToggle.setAttribute("aria-pressed", next === "dark" ? "true" : "false");
      elements.themeToggle.textContent = next === "dark" ? "切换宣纸" : "切换夜墨";
    });

    Promise.all([
      fetch("./catalog.json").then(function (resp) {
        if (!resp.ok) throw new Error("catalog.json HTTP " + resp.status);
        return resp.json();
      }),
      fetch("./site-meta.json")
        .then(function (resp) { return resp.ok ? resp.json() : null; })
        .catch(function () { return null; })
    ])
      .then(function (results) {
        var catalog = results[0];
        if (!catalog || typeof catalog !== "object" || !Array.isArray(catalog.plugins)) {
          throw new Error("目录 JSON 结构无效");
        }
        state.catalog = catalog;
        state.siteMeta = results[1];
        rerender();
      })
      .catch(function (err) {
        elements.siteTitle.textContent = "目录加载失败";
        elements.siteDescription.textContent = "无法读取静态目录数据。";
        elements.statusPanel.hidden = false;
        elements.statusPanel.className = "status-panel is-error";
        var hint = window.location.protocol === "file:"
          ? "检测到 file:// 直接打开，浏览器安全策略会拦截 fetch；请运行 python -m http.server 后通过 http://localhost 访问。"
          : "请确认已执行 registry_tool.py build 生成 catalog.json。";
        elements.statusPanel.textContent = "加载目录失败（" + String(err && err.message ? err.message : err) + "）。" + hint;
      });
  }

  if (typeof document !== "undefined") {
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", initBrowserApp);
    } else {
      initBrowserApp();
    }
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = {
      safeHttpUrl: safeHttpUrl,
      safeDownloadUrl: safeDownloadUrl,
      filterPlugins: filterPlugins,
      renderCatalogState: renderCatalogState,
      createPluginCard: createPluginCard
    };
  }
})(typeof globalThis !== "undefined" ? globalThis : this);
