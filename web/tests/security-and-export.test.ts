import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { SAMPLE_FORM_B_CATALOG, validateFormBCatalog } from '../src/utils/catalog';
import { buildSafeJournalMarkdown, buildSchedulesIcs, escapeMarkdownInline, formatFencedBody } from '../src/utils/export';
import { isSafeArtifactFilename, isValidFingerprint, safeHttpUrl } from '../src/utils/security';

describe('security URL & catalog boundary checks', () => {
  it('allows clean http/https URLs and blocks dangerous schemes, credentials, and control chars', () => {
    expect(safeHttpUrl('https://github.com/DzDenzel/PlanLock')).toBe('https://github.com/DzDenzel/PlanLock');
    expect(safeHttpUrl('http://127.0.0.1:8080/plugins')).toBe('http://127.0.0.1:8080/plugins');

    expect(safeHttpUrl('javascript:alert(1)')).toBeNull();
    expect(safeHttpUrl('JAVASCRIPT:alert(1)')).toBeNull();
    expect(safeHttpUrl('data:text/html,<script>alert(1)</script>')).toBeNull();
    expect(safeHttpUrl('file:///etc/passwd')).toBeNull();
    expect(safeHttpUrl('vbscript:msgbox(1)')).toBeNull();
    expect(safeHttpUrl('//evil.example/a.js')).toBeNull();
    expect(safeHttpUrl('https://user:pass@example.com/repo')).toBeNull();
    expect(safeHttpUrl('https://user@example.com/repo')).toBeNull();
    expect(safeHttpUrl('https://example.com/a b')).toBeNull();
    expect(safeHttpUrl('https://example.com/a\nb')).toBeNull();
    expect(safeHttpUrl('https://example.com\\evil')).toBeNull();
    expect(safeHttpUrl(`https://example.com/${'a'.repeat(2100)}`)).toBeNull();
  });

  it('validates SHA-256 fingerprints and safe plugin artifact filenames', () => {
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
  });

  it('validates REQ-037 Form B catalog.json and rejects malicious links or duplicates', () => {
    const ok = validateFormBCatalog(SAMPLE_FORM_B_CATALOG);
    expect(ok.valid).toBe(true);
    expect(ok.errors).toHaveLength(0);
    expect(ok.catalog?.trustedFingerprints).toHaveLength(1);
    expect(ok.catalog?.plugins[0]?.id).toBe('ink-focus-cue');

    const bad = validateFormBCatalog(
      JSON.stringify({
        catalogFormatVersion: 1,
        sourceId: 'bad-source',
        sourceType: 'THIRD_PARTY',
        trustedFingerprints: ['ZZ:INVALID'],
        plugins: [
          {
            id: 'dup',
            name: 'A',
            version: '1.0.0',
            contractVersion: 1,
            entryPoint: 'main',
            artifact: '../escape.pljs',
            requiredCapabilities: ['QUERY_TODAY'],
            description: 'desc',
            homepage: 'javascript:alert(1)',
            repository: 'https://user:secret@example.com/repo',
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
    expect(bad.errors.some((e) => e.includes('指纹'))).toBe(true);
    expect(bad.errors.some((e) => e.includes('artifact'))).toBe(true);
    expect(bad.errors.some((e) => e.includes('homepage'))).toBe(true);
    expect(bad.errors.some((e) => e.includes('repository'))).toBe(true);
    expect(bad.errors.some((e) => e.includes('重复插件 ID'))).toBe(true);
  });
});

describe('anti-injection Markdown & ICS export', () => {
  it('escapes formula prefixes, markdown tokens, and prevents fence breakout', () => {
    expect(escapeMarkdownInline('=HYPERLINK("http://evil")')).toMatch(/^\\=/);
    expect(escapeMarkdownInline('<script>alert(1)</script>')).toContain('\\<script\\>');

    const body = 'normal\n````\n# injected heading\n<img src=x onerror=alert(1)>';
    const fenced = formatFencedBody(body);
    // Body contains 4 backticks, so outer fence must be at least 5 backticks
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

  it('builds valid RFC 5545 ICS for schedules with escaping', () => {
    const ics = buildSchedulesIcs([
      {
        id: 42,
        userId: 1,
        title: '周会,复盘;阶段一',
        startDate: '2026-11-26',
        endDate: '2026-11-26',
        startTime: '14:00:00',
        endTime: '15:00:00',
        allDay: false,
        location: '3楼,会议室',
        note: '第一行\n第二行',
        status: 1,
        version: 1,
      },
    ]);
    expect(ics).toContain('BEGIN:VCALENDAR');
    expect(ics).toContain('SUMMARY:周会\\,复盘\\;阶段一');
    expect(ics).toContain('LOCATION:3楼\\,会议室');
    expect(ics).toContain('DESCRIPTION:第一行\\n第二行');
    expect(ics).toContain('END:VCALENDAR');
  });
});

describe('static CSP & zero-innerHTML source invariants', () => {
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

  it('contains zero innerHTML, zero dangerouslySetInnerHTML, zero inline style props, and zero external CDN links', () => {
    const currentDir = path.dirname(fileURLToPath(import.meta.url));
    const webRoot = path.resolve(currentDir, '..');
    const indexHtml = fs.readFileSync(path.join(webRoot, 'index.html'), 'utf-8');
    expect(indexHtml).not.toMatch(/<style[\s>]/i);
    expect(indexHtml).not.toMatch(/\sstyle\s*=/i);
    expect(indexHtml).not.toMatch(/<script(?![^>]*\bsrc=)[^>]*>/i);
    expect(indexHtml).not.toMatch(/https?:\/\//i);

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
