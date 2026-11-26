import { defineConfig } from 'vite';

/**
 * REQ-041 包 B 构建契约（对齐 WebPortalController 与严格零外链 CSP）：
 * - dist/index.html -> 未来镜像打包至 backend/planlock-app/src/main/resources/web/index.html
 * - dist/web-assets/* -> 未来镜像打包至 backend/planlock-app/src/main/resources/static/web-assets/*
 * - base 固定为 '/' 且 assetsDir 为 'web-assets'，使 index.html 仅引用同源 /web-assets/* 静态资源
 * - 禁用内联 CSS/JS 注入，确保完全通过 strict CSP (script-src 'self'; style-src 'self')
 */
export default defineConfig({
  base: '/',
  esbuild: {
    jsx: 'automatic',
    jsxImportSource: 'preact',
  },
  build: {
    outDir: 'dist',
    assetsDir: 'web-assets',
    emptyOutDir: true,
    sourcemap: false,
    cssCodeSplit: false,
    assetsInlineLimit: 0,
    target: 'es2022',
    modulePreload: {
      polyfill: false,
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: false,
      },
    },
  },
});
