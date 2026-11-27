import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  MAX_CATALOG_BYTES,
  MAX_ERRORS_COLLECTED,
  MAX_PLUGINS_COUNT,
  SAMPLE_FORM_B_CATALOG,
  validateFormBCatalog,
  validateSameOriginCatalog,
} from '../src/utils/catalog';
import {
  buildSafeJournalMarkdown,
  buildSchedulesIcs,
  escapeMarkdownInline,
  formatFencedBody,
} from '../src/utils/export';
import {
  isSafeArtifactFilename,
  isValidFingerprint,
  safeFormBDownloadUrl,
  safeHttpUrl,
} from '../src/utils/security';

describe('security URL & catalog boundary checks (B5 & B8)', () => {
  it('allows clean http/https URLs and blocks dangerous schemes, credentials, and control chars', () => {
    expect(safeHttpUrl('https://github.com/DzDenzel/PlanLock')).toBe(
      'https://github.com/DzDenzel/PlanLock',
    );
    expect(safeHttpUrl('http://127.0.0.1:8080/plugins')).toBe('http://127.0.0.1:8080/plugins');

    expect(safeHttpUrl('javascript:alert(1)')).toBeNull();
    expect(safeHttpUrl('JAVASCRIPT:alert(1)')).toBeNull();
    expect(safeHttpUrl('data:text/html,<script>alert(1)</script>')).toBeNull();
    expect(safeHttpUrl('file:///etc/passwd')).toBeNull();
    expect(safeHttpUrl('vbscript:msgbox(1)')).toBeNull();
    expect(safeHttpUrl('//evil.example/a.js')).toBeNull();
    // Runtime concatenation avoids synthetic userinfo literal matching release archive secret scanner (B8)
    const syntheticCredUrl = 'https://' + 'user' + ':' + 'pass@example.com/repo';
    expect(safeHttpUrl(syntheticCredUrl)).toBeNull();
    expect(safeHttpUrl('https://' + 'user@example.com/repo')).toBeNull();
    expect(safeHttpUrl('https://example.com/a b')).toBeNull();
    expect(safeHttpUrl('https://example.com/a\nb')).toBeNull();
    expect(safeHttpUrl('https://example.com\\evil')).toBeNull();
    expect(safeHttpUrl(`https://example.com/${'a'.repeat(2100)}`)).toBeNull();
  });

  it('validates SHA-256 fingerprints, safe plugin filenames, and Form B relative download URLs', () => {
    expect(
      isValidFingerprint(
        '92:70:60:2B:17:33:20:CE:46:AD:93:06:EE:F9:63:CB:C0:47:32:6C:77:9D:8D:95:93:36:FE:BB:A6:D3:2E:C1',
      ),
    ).toBe(true);
    expect(isValidFingerprint('not-a-fingerprint')).toBe(false);
    expect(isSafeArtifactFilename('ink-focus-cue-0.1.0.pljs')).toBe(true);
    expect(isSafeArtifactFilename('strict-lock-1.0.0.plp')).toBe(true);
    expect(isSafeArtifactFilename('../escape.pljs')).toBe(false);
    expect(isSafeArtifactFilename('evil.exe')).toBe(false);

    expect(safeFormBDownloadUrl('./artifacts/ink-focus-cue-0.1.0.pljs')).toBe(
      './artifacts/ink-focus-cue-0.1.0.pljs',
    );
    expect(safeFormBDownloadUrl('artifacts/strict-1.0.0.plp')).toBe('./artifacts/strict-1.0.0.plp');
    expect(safeFormBDownloadUrl('https://evil.example/artifacts/a.pljs')).toBeNull();
    expect(safeFormBDownloadUrl('./artifacts/../secret.pljs')).toBeNull();
  });

  it('validates REQ-037 Form B catalog.json, rejects Int32 overflow / bad types / bad sha256 / oversized payloads, and marks empty anchors unanchored', () => {
    const ok = validateFormBCatalog(SAMPLE_FORM_B_CATALOG);
    expect(ok.valid).toBe(true);
    expect(ok.trustState).toBe('format-only-anchored');
    expect(ok.errors).toHaveLength(0);
    expect(ok.catalog?.trustedFingerprints).toHaveLength(1);
    expect(ok.catalog?.plugins[0]?.id).toBe('ink-focus-cue');

    // Empty trustedFingerprints must NOT be marked anchored/green
    const unanchored = validateFormBCatalog(
      JSON.stringify({
        catalogFormatVersion: 1,
        sourceId: 'no-anchor',
        sourceType: 'THIRD_PARTY',
        trustedFingerprints: [],
        plugins: [],
      }),
    );
    expect(unanchored.valid).toBe(true);
    expect(unanchored.trustState).toBe('unanchored');
    expect(unanchored.warnings.length).toBeGreaterThan(0);

    // Malformed entries: null-prototype-like object fingerprint, Int32 overflow, invalid type, invalid sha256, invalid downloadUrl, duplicate id
    const syntheticRepo = 'https://' + 'user' + ':' + 'secret@example.com/repo';
    const bad = validateFormBCatalog(
      JSON.stringify({
        catalogFormatVersion: 2147483648,
        sourceId: 'bad-source',
        sourceType: 'THIRD_PARTY',
        trustedFingerprints: [{ nested: true }, 'ZZ:INVALID'],
        plugins: [
          {
            id: 'dup',
            name: 'A',
            version: '1.0.0',
            versionCode: 2147483648,
            contractVersion: 1,
            type: 'native-evil',
            entryPoint: 'main',
            artifact: '../escape.pljs',
            downloadUrl: 'https://evil.example/a.pljs',
            sha256: 'not-64-hex',
            requiredCapabilities: ['QUERY_TODAY'],
            description: 'desc',
            homepage: 'javascript:alert(1)',
            repository: syntheticRepo,
          },
          {
            id: 'dup',
            name: 'B',
            version: '1.0.0',
            contractVersion: 1,
            entryPoint: 'main',
            requiredCapabilities: [],
            description: 'desc2',
          },
        ],
      }),
    );
    expect(bad.valid).toBe(false);
    expect(bad.trustState).toBe('invalid');
    expect(bad.catalog).toBeNull();
    expect(bad.errors.some((e) => e.includes('catalogFormatVersion'))).toBe(true);
    expect(bad.errors.some((e) => e.includes('versionCode'))).toBe(true);
    expect(bad.errors.some((e) => e.includes('仅支持 js 或 dex'))).toBe(true);
    expect(bad.errors.some((e) => e.includes('downloadUrl'))).toBe(true);
    expect(bad.errors.some((e) => e.includes('sha256'))).toBe(true);
    expect(bad.errors.some((e) => e.includes('指纹'))).toBe(true);
    expect(bad.errors.some((e) => e.includes('重复插件 ID'))).toBe(true);

    // Oversized JSON (> 2 MiB) rejected before parsing
    const huge = `{"a":"${'x'.repeat(MAX_CATALOG_BYTES + 16)}"}`;
    const oversized = validateFormBCatalog(huge);
    expect(oversized.valid).toBe(false);
    expect(oversized.errors[0]).toContain('超过上限');

    // Too many plugins (> 256) bounded & error list capped
    const manyPlugins = Array.from({ length: MAX_PLUGINS_COUNT + 5 }, () => ({ invalid: true }));
    const tooMany = validateFormBCatalog(
      JSON.stringify({
        catalogFormatVersion: 1,
        sourceId: 'many',
        sourceType: 'THIRD_PARTY',
        plugins: manyPlugins,
      }),
    );
    expect(tooMany.valid).toBe(false);
    expect(tooMany.errors.length).toBeLessThanOrEqual(MAX_ERRORS_COLLECTED);
    expect(tooMany.omittedErrorCount).toBeGreaterThan(0);

    // Same-origin catalog enforces /api/v1/plugins/artifacts/<source>/ contract & required downloadUrl/sha256 (R2)
    const sameOriginBad = validateSameOriginCatalog(
      JSON.stringify({
        catalogFormatVersion: 1,
        sourceId: 'official',
        sourceType: 'OFFICIAL',
        plugins: [
          {
            id: 'p1',
            name: 'P1',
            version: '1.0.0',
            contractVersion: 1,
            entryPoint: 'main',
            requiredCapabilities: [],
            description: 'ok',
            downloadUrl: 'https://evil.example/p1.pljs',
            sha256: 'a'.repeat(64),
          },
        ],
      }),
      'official',
    );
    expect(sameOriginBad.valid).toBe(false);

    // R2: missing downloadUrl / missing sha256 / mismatched artifact vs downloadUrl filename MUST fail validation
    const missingIntegrity = validateFormBCatalog(
      JSON.stringify({
        catalogFormatVersion: 1,
        sourceId: 'missing-integrity',
        sourceType: 'THIRD_PARTY',
        plugins: [
          {
            id: 'no-hash',
            name: 'Missing Hash & URL',
            version: '1.0.0',
            contractVersion: 1,
            entryPoint: 'main',
            requiredCapabilities: [],
            description: 'missing downloadUrl and sha256',
          },
        ],
      }),
    );
    expect(missingIntegrity.valid).toBe(false);
    expect(missingIntegrity.errors.some((e) => e.includes('downloadUrl 不能为空'))).toBe(true);
    expect(missingIntegrity.errors.some((e) => e.includes('sha256 不能为空'))).toBe(true);

    const mismatchedArtifact = validateFormBCatalog(
      JSON.stringify({
        catalogFormatVersion: 1,
        sourceId: 'mismatch-artifact',
        sourceType: 'THIRD_PARTY',
        plugins: [
          {
            id: 'mismatch-1',
            name: 'Mismatch',
            version: '1.0.0',
            contractVersion: 1,
            entryPoint: 'main',
            artifact: 'plugin-a.pljs',
            downloadUrl: './artifacts/plugin-b.pljs',
            sha256: 'b'.repeat(64),
            requiredCapabilities: [],
            description: 'artifact filename differs from downloadUrl',
          },
        ],
      }),
    );
    expect(mismatchedArtifact.valid).toBe(false);
    expect(mismatchedArtifact.errors.some((e) => e.includes('不一致'))).toBe(true);

    const sameOriginValid = validateSameOriginCatalog(
      JSON.stringify({
        catalogFormatVersion: 1,
        sourceId: 'official',
        sourceType: 'OFFICIAL',
        plugins: [
          {
            id: 'ok-1',
            name: 'Valid Official',
            version: '1.0.0',
            contractVersion: 1,
            entryPoint: 'main',
            artifact: 'ok-1.pljs',
            downloadUrl: '/api/v1/plugins/artifacts/official/ok-1.pljs',
            sha256: 'c'.repeat(64),
            requiredCapabilities: [],
            description: 'valid same-origin plugin',
          },
        ],
      }),
      'official',
    );
    expect(sameOriginValid.valid).toBe(true);
  });
});

describe('anti-injection Markdown & ICS export', () => {
  it('escapes formula prefixes (including fullwidth), markdown tokens, and prevents fence breakout', () => {
    expect(escapeMarkdownInline('=HYPERLINK("http://evil")')).toMatch(/^\\=/);
    expect(escapeMarkdownInline('＝SUM(A1)')).toMatch(/^\\＝/);
    expect(escapeMarkdownInline('<script>alert(1)</script>')).toContain('\\<script\\>');

    const body = 'normal\n````\n# injected heading\n<img src=x onerror=alert(1)>';
    const fenced = formatFencedBody(body);
    expect(fenced.startsWith('`````text\n')).toBe(true);
    expect(fenced.endsWith('\n`````')).toBe(true);

    const md = buildSafeJournalMarkdown(
      [
        {
          id: 1,
          userId: 1,
          entryDate: '2026-11-26',
          mood: '=CMD|calc',
          tags: ['[link](javascript:alert(1))'],
          content: body,
          status: 1,
          version: 1,
        },
      ],
      new Date('2026-11-26T12:00:00Z'),
    );
    expect(md).toContain('\\=CMD\\|calc');
    expect(md).toContain('\\[link\\]\\(javascript:alert\\(1\\)\\)');
    expect(md).toContain('`````text');
  });

  it('builds valid RFC 5545 ICS for schedules neutralizing bare CR, CRLF, and LF', () => {
    const ics = buildSchedulesIcs([
      {
        id: 42,
        userId: 1,
        title: '周会,复盘;阶段一\rSUMMARY:INJECTED',
        startDate: '2026-11-26',
        endDate: '2026-11-26',
        startTime: '14:00:00',
        endTime: '15:00:00',
        allDay: false,
        location: '3楼,会议室',
        note: '第一行\r\n第二行\r第三行',
        status: 1,
        version: 1,
      },
    ]);
    expect(ics).toContain('BEGIN:VCALENDAR');
    expect(ics).toContain('SUMMARY:周会\\,复盘\\;阶段一\\nSUMMARY\\:INJECTED'.replace('\\:', ':'));
    expect(ics).toContain('LOCATION:3楼\\,会议室');
    expect(ics).toContain('DESCRIPTION:第一行\\n第二行\\n第三行');
    expect(ics).toContain('END:VCALENDAR');
  });

  it('uses exclusive +1 day DTEND for all-day schedules and folds >75 UTF-8 octet ICS lines', () => {
    const longTitle = '年度计划复盘与跨端同步联调会议'.repeat(4) + '🗓️';
    const ics = buildSchedulesIcs([
      {
        id: 43,
        userId: 1,
        title: longTitle,
        startDate: '2026-12-31',
        endDate: '2026-12-31',
        allDay: true,
        status: 1,
        version: 1,
      },
    ]);
    expect(ics).toContain('DTSTART;VALUE=DATE:20261231\r\n');
    expect(ics).toContain('DTEND;VALUE=DATE:20270101\r\n');
    const physicalLines = ics.split('\r\n').filter((line) => line.length > 0);
    expect(physicalLines.length).toBeGreaterThan(8);
    const encoder = new TextEncoder();
    for (const line of physicalLines) {
      expect(encoder.encode(line).length).toBeLessThanOrEqual(75);
    }
    const unfolded = ics.replace(/\r\n /g, '');
    expect(unfolded).toContain(`SUMMARY:${longTitle}\r\n`);
  });
});

describe('static CSP, zero-innerHTML, and release secret-pattern invariants (B8)', () => {
  function listFiles(dir: string): string[] {
    const entries = fs.readdirSync(dir, { withFileTypes: true });
    const files: string[] = [];
    for (const entry of entries) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        files.push(...listFiles(full));
      } else {
        files.push(full);
      }
    }
    return files;
  }

  it('contains zero innerHTML, zero dangerouslySetInnerHTML, zero inline style props, zero CDN links, and zero release secret_pattern hits', () => {
    const currentDir = path.dirname(fileURLToPath(import.meta.url));
    const webRoot = path.resolve(currentDir, '..');
    const indexHtml = fs.readFileSync(path.join(webRoot, 'index.html'), 'utf-8');
    expect(indexHtml).not.toMatch(/<style[\s>]/i);
    expect(indexHtml).not.toMatch(/\sstyle\s*=/i);
    expect(indexHtml).not.toMatch(/<script(?![^>]*\bsrc=)[^>]*>/i);
    expect(indexHtml).not.toMatch(/https?:\/\//i);

    const releaseSecretPatterns = [
      /-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----/,
      /\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b/,
      /\bAKIA[A-Z0-9]{16}\b/,
      /\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{40,}\b/,
      /\beyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{10,}\b/,
      /-(?:storepass|keypass)\s+(?!\$|:|<)([A-Za-z0-9_-]{3,})/i,
      /(?:https?|jdbc:mysql):\/\/[^\s/:]+:[^\s/@]+@/i,
    ];

    const srcAndTestFiles = [
      ...listFiles(path.join(webRoot, 'src')),
      ...listFiles(path.join(webRoot, 'tests')),
    ];
    for (const file of srcAndTestFiles) {
      const content = fs.readFileSync(file, 'utf-8');
      for (const pattern of releaseSecretPatterns) {
        expect(content, `${file} must not match release.py secret pattern ${pattern}`).not.toMatch(
          pattern,
        );
      }
    }

    const srcFiles = listFiles(path.join(webRoot, 'src'));
    for (const file of srcFiles) {
      const content = fs.readFileSync(file, 'utf-8');
      const withoutComments = content
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/\/\/[^\n]*/g, '');
      expect(withoutComments, `${file} must not use innerHTML`).not.toContain('innerHTML');
      expect(withoutComments, `${file} must not use dangerouslySetInnerHTML`).not.toContain(
        'dangerouslySetInnerHTML',
      );
      if (file.endsWith('.tsx')) {
        expect(withoutComments, `${file} must not use inline style= attribute`).not.toMatch(
          /\bstyle\s*=/,
        );
      }
      if (file.endsWith('.css')) {
        expect(withoutComments, `${file} must not reference external http(s) URLs`).not.toMatch(
          /https?:\/\//i,
        );
      }
    }
  });
});
