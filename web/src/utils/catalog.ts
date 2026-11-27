import type { PluginCatalogDocument, PluginCatalogEntry } from '../types';
import {
  isSafeArtifactFilename,
  isSafePositiveInt32,
  isValidFingerprint,
  isValidSha256Hex,
  safeDiagnosticValue,
  safeFormBDownloadUrl,
  safeHttpUrl,
  safeSameOriginDownloadUrl,
  stripDisallowedControlChars,
  utf8ByteLength,
} from './security';

export const MAX_CATALOG_BYTES = 2 * 1024 * 1024; // 2 MiB
export const MAX_PLUGINS_COUNT = 256;
export const MAX_FINGERPRINTS_COUNT = 32;
export const MAX_CAPABILITIES_COUNT = 32;
export const MAX_ERRORS_COLLECTED = 50;

export type CatalogTrustState = 'format-only-anchored' | 'unanchored' | 'invalid';

export interface CatalogValidationResult {
  valid: boolean;
  trustState: CatalogTrustState;
  errors: string[];
  omittedErrorCount: number;
  warnings: string[];
  catalog: PluginCatalogDocument | null;
}

export const SAMPLE_FORM_B_CATALOG = JSON.stringify(
  {
    catalogFormatVersion: 1,
    sourceId: 'oriva-community-examples',
    sourceType: 'THIRD_PARTY',
    sourceName: 'ORIVA 社区插件示例站（形态 B）',
    sourceDescription: '知序 ORIVA 开源插件站静态目录示例，含 SHA-256 信任锚指纹与安全元数据。',
    trustedFingerprints: [
      '92:70:60:2B:17:33:20:CE:46:AD:93:06:EE:F9:63:CB:C0:47:32:6C:77:9D:8D:95:93:36:FE:BB:A6:D3:2E:C1',
    ],
    plugins: [
      {
        artifact: 'ink-focus-cue-0.1.0.pljs',
        downloadUrl: './artifacts/ink-focus-cue-0.1.0.pljs',
        sha256: '4D3F3CD63C17F964F1C891D1F0DD959691F38F2B3F680CC1EDB898C169DC74F1',
        id: 'ink-focus-cue',
        name: '墨序专注提示',
        version: '0.1.0',
        versionCode: 1,
        contractVersion: 2,
        type: 'js',
        entryPoint: 'entryPoint',
        requiredCapabilities: ['QUERY_TODAY', 'FEEDBACK'],
        description: '读取今日安排生成克制的墨序专注提示，仅限 Android 客户端本地沙箱运行。',
        author: 'ORIVA Community Contributors',
        homepage: 'https://github.com/DzDenzel/PlanLock',
        repository: 'https://github.com/DzDenzel/PlanLock',
        license: 'MIT',
      },
    ],
  },
  null,
  2,
);

type ValidationMode = 'form-b' | 'same-origin';

function validateCatalogInternal(
  rawJson: string,
  mode: ValidationMode,
  expectedSource?: 'official' | 'third-party',
): CatalogValidationResult {
  const errors: string[] = [];
  let omittedErrorCount = 0;
  const warnings: string[] = [];

  function pushError(msg: string) {
    if (errors.length < MAX_ERRORS_COLLECTED) {
      errors.push(msg);
    } else {
      omittedErrorCount += 1;
    }
  }

  const byteLen = utf8ByteLength(rawJson);
  if (byteLen > MAX_CATALOG_BYTES) {
    return {
      valid: false,
      trustState: 'invalid',
      errors: [`目录 JSON 体积超过上限（最大 ${MAX_CATALOG_BYTES / (1024 * 1024)} MiB）`],
      omittedErrorCount: 0,
      warnings,
      catalog: null,
    };
  }

  let doc: unknown;
  try {
    doc = JSON.parse(rawJson);
  } catch (err) {
    return {
      valid: false,
      trustState: 'invalid',
      errors: [`JSON 解析失败：${err instanceof Error ? err.message : safeDiagnosticValue(err)}`],
      omittedErrorCount: 0,
      warnings,
      catalog: null,
    };
  }

  if (!doc || typeof doc !== 'object' || Array.isArray(doc)) {
    return {
      valid: false,
      trustState: 'invalid',
      errors: ['catalog.json 根节点必须为 JSON 对象'],
      omittedErrorCount: 0,
      warnings,
      catalog: null,
    };
  }

  const obj = doc as Record<string, unknown>;

  let catalogFormatVersion = 1;
  if (!isSafePositiveInt32(obj.catalogFormatVersion)) {
    pushError('catalogFormatVersion 必须为 1 至 2147483647 的整数');
  } else {
    catalogFormatVersion = obj.catalogFormatVersion;
  }

  const sourceId =
    typeof obj.sourceId === 'string' ? stripDisallowedControlChars(obj.sourceId).trim() : '';
  if (!sourceId || !/^[A-Za-z0-9._-]{1,128}$/.test(sourceId)) {
    pushError('sourceId 缺失、超长或包含非法字符（仅允许 1–128 位字母/数字/._-）');
  } else if (expectedSource && sourceId !== expectedSource) {
    pushError(`sourceId（${sourceId}）与请求目录源（${expectedSource}）不匹配`);
  }

  const sourceType = typeof obj.sourceType === 'string' ? obj.sourceType.trim() : '';
  if (sourceType !== 'OFFICIAL' && sourceType !== 'THIRD_PARTY') {
    pushError('sourceType 必须为 OFFICIAL 或 THIRD_PARTY');
  }

  const trustedFingerprints: string[] = [];
  if (obj.trustedFingerprints !== undefined && obj.trustedFingerprints !== null) {
    if (!Array.isArray(obj.trustedFingerprints)) {
      pushError('trustedFingerprints 必须为字符串数组');
    } else {
      if (obj.trustedFingerprints.length > MAX_FINGERPRINTS_COUNT) {
        pushError(`trustedFingerprints 数量超过上限（最多 ${MAX_FINGERPRINTS_COUNT} 项）`);
      }
      const seenFp = new Set<string>();
      const slice = obj.trustedFingerprints.slice(0, MAX_FINGERPRINTS_COUNT);
      for (const fp of slice) {
        if (!isValidFingerprint(fp)) {
          pushError(`发现非法 SHA-256 指纹格式：${safeDiagnosticValue(fp)}`);
        } else {
          const normalized = String(fp).trim().toUpperCase();
          if (!seenFp.has(normalized)) {
            seenFp.add(normalized);
            trustedFingerprints.push(normalized);
          }
        }
      }
    }
  }

  if (mode === 'form-b' && trustedFingerprints.length === 0) {
    warnings.push(
      '未声明任何有效信任锚指纹（trustedFingerprints 为空）：仅完成结构预检，Android 客户端要求非空且经独立可信渠道核对的证书指纹。',
    );
  }

  if (!Array.isArray(obj.plugins)) {
    pushError('plugins 必须为数组');
  }

  const parsedPlugins: PluginCatalogEntry[] = [];
  const seenIds = new Set<string>();

  if (Array.isArray(obj.plugins)) {
    if (obj.plugins.length > MAX_PLUGINS_COUNT) {
      pushError(`plugins 条目数量超过上限（最多 ${MAX_PLUGINS_COUNT} 项，当前 ${obj.plugins.length} 项）`);
    }
    const capped = obj.plugins.slice(0, MAX_PLUGINS_COUNT);
    capped.forEach((rawEntry, index) => {
      if (!rawEntry || typeof rawEntry !== 'object' || Array.isArray(rawEntry)) {
        pushError(`plugins[${index}] 必须为对象`);
        return;
      }
      const e = rawEntry as Record<string, unknown>;
      const id = typeof e.id === 'string' ? stripDisallowedControlChars(e.id).trim() : '';
      const name = typeof e.name === 'string' ? stripDisallowedControlChars(e.name).trim() : '';
      const version =
        typeof e.version === 'string' ? stripDisallowedControlChars(e.version).trim() : '';
      const entryPoint =
        typeof e.entryPoint === 'string' ? stripDisallowedControlChars(e.entryPoint).trim() : '';
      const description =
        typeof e.description === 'string' ? stripDisallowedControlChars(e.description, true) : null;

      if (!id || id.length > 128) pushError(`plugins[${index}].id 缺失或超过 128 字符`);
      else if (seenIds.has(id)) pushError(`存在重复插件 ID：${safeDiagnosticValue(id)}`);
      else seenIds.add(id);

      if (!name || name.length > 120) pushError(`plugins[${index}].name 缺失或超过 120 字符`);
      if (!version || version.length > 64) pushError(`plugins[${index}].version 缺失或超过 64 字符`);
      if (!entryPoint || entryPoint.length > 200) {
        pushError(`plugins[${index}].entryPoint 缺失或超过 200 字符`);
      }
      if (description === null || description.length > 2000) {
        pushError(`plugins[${index}].description 必须为不超过 2000 字符的字符串`);
      }

      if (!isSafePositiveInt32(e.contractVersion)) {
        pushError(`plugins[${index}].contractVersion 必须为正 Int32 整数`);
      }

      let versionCode: number | undefined;
      if (e.versionCode !== undefined && e.versionCode !== null) {
        if (!isSafePositiveInt32(e.versionCode)) {
          pushError(`plugins[${index}].versionCode 必须为正 Int32 整数`);
        } else {
          versionCode = e.versionCode;
        }
      }

      let runtimeType: 'js' | 'dex' | undefined;
      if (e.type !== undefined && e.type !== null) {
        if (e.type === 'js' || e.type === 'dex') {
          runtimeType = e.type;
        } else {
          pushError(`plugins[${index}].type 仅支持 js 或 dex，收到：${safeDiagnosticValue(e.type)}`);
        }
      }

      let artifact: string | undefined;
      if (e.artifact !== undefined && e.artifact !== null) {
        if (!isSafeArtifactFilename(e.artifact)) {
          pushError(`plugins[${index}].artifact 文件名不合法（仅允许安全 .plp / .pljs 文件名）`);
        } else {
          artifact = String(e.artifact);
        }
      }

      let downloadUrl: string | undefined;
      if (e.downloadUrl === undefined || e.downloadUrl === null || e.downloadUrl === '') {
        pushError(`plugins[${index}].downloadUrl 不能为空`);
      } else {
        const validated =
          mode === 'form-b'
            ? safeFormBDownloadUrl(e.downloadUrl)
            : safeSameOriginDownloadUrl(e.downloadUrl, expectedSource);
        if (!validated) {
          pushError(
            mode === 'form-b'
              ? `plugins[${index}].downloadUrl 仅允许规范化相对路径 ./artifacts/<安全文件名>`
              : `plugins[${index}].downloadUrl 不符合本实例同源 /api/v1/plugins/artifacts/ 路径契约`,
          );
        } else {
          downloadUrl = validated;
          if (artifact) {
            const urlFile = validated.slice(validated.lastIndexOf('/') + 1);
            if (urlFile !== artifact) {
              pushError(`plugins[${index}].downloadUrl 文件名（${urlFile}）与 artifact（${artifact}）不一致`);
            }
          }
        }
      }

      let sha256: string | undefined;
      if (e.sha256 === undefined || e.sha256 === null || e.sha256 === '') {
        pushError(`plugins[${index}].sha256 不能为空且必须为 64 位十六进制摘要`);
      } else if (!isValidSha256Hex(e.sha256)) {
        pushError(`plugins[${index}].sha256 必须为 64 位十六进制摘要`);
      } else {
        sha256 = String(e.sha256).trim().toUpperCase();
      }

      const requiredCapabilities: string[] = [];
      if (!Array.isArray(e.requiredCapabilities)) {
        pushError(`plugins[${index}].requiredCapabilities 必须为字符串数组`);
      } else {
        if (e.requiredCapabilities.length > MAX_CAPABILITIES_COUNT) {
          pushError(`plugins[${index}].requiredCapabilities 超过上限（最多 ${MAX_CAPABILITIES_COUNT} 项）`);
        }
        for (const cap of e.requiredCapabilities.slice(0, MAX_CAPABILITIES_COUNT)) {
          if (typeof cap !== 'string' || !cap.trim() || cap.trim().length > 64) {
            pushError(`plugins[${index}].requiredCapabilities 包含无效能力项`);
          } else {
            requiredCapabilities.push(stripDisallowedControlChars(cap).trim());
          }
        }
      }

      let homepage: string | undefined;
      if (e.homepage !== undefined && e.homepage !== null && e.homepage !== '') {
        const checked = safeHttpUrl(e.homepage);
        if (!checked) {
          pushError(`plugins[${index}].homepage 存在不安全协议、凭据或非法 URL`);
        } else {
          homepage = checked;
        }
      }

      let repository: string | undefined;
      if (e.repository !== undefined && e.repository !== null && e.repository !== '') {
        const checked = safeHttpUrl(e.repository);
        if (!checked) {
          pushError(`plugins[${index}].repository 存在不安全协议、凭据或非法 URL`);
        } else {
          repository = checked;
        }
      }

      let author: string | undefined;
      if (e.author !== undefined && e.author !== null) {
        if (typeof e.author !== 'string' || e.author.trim().length > 200) {
          pushError(`plugins[${index}].author 必须为不超过 200 字符的字符串`);
        } else if (e.author.trim()) {
          author = stripDisallowedControlChars(e.author).trim();
        }
      }

      let license: string | undefined;
      if (e.license !== undefined && e.license !== null) {
        if (typeof e.license !== 'string' || e.license.trim().length > 200) {
          pushError(`plugins[${index}].license 必须为不超过 200 字符的字符串`);
        } else if (e.license.trim()) {
          license = stripDisallowedControlChars(e.license).trim();
        }
      }

      parsedPlugins.push({
        id: id || `invalid-${index}`,
        name: name || '(未命名)',
        version: version || '0.0.0',
        versionCode,
        contractVersion: isSafePositiveInt32(e.contractVersion) ? e.contractVersion : 1,
        type: runtimeType,
        entryPoint: entryPoint || '',
        requiredCapabilities,
        description: description ?? '',
        artifact,
        downloadUrl,
        sha256,
        author,
        homepage,
        repository,
        license,
      });
    });
  }

  const valid = errors.length === 0;
  const trustState: CatalogTrustState = !valid
    ? 'invalid'
    : trustedFingerprints.length > 0
      ? 'format-only-anchored'
      : 'unanchored';

  const sourceName =
    typeof obj.sourceName === 'string'
      ? stripDisallowedControlChars(obj.sourceName).trim().slice(0, 120) || undefined
      : undefined;
  const sourceDescription =
    typeof obj.sourceDescription === 'string'
      ? stripDisallowedControlChars(obj.sourceDescription, true).trim().slice(0, 1000) || undefined
      : undefined;

  return {
    valid,
    trustState,
    errors,
    omittedErrorCount,
    warnings,
    // 校验失败时不再返回可信预览文档，仅保留诊断错误供排查
    catalog: valid
      ? {
          catalogFormatVersion,
          sourceId,
          sourceType,
          sourceName,
          sourceDescription,
          trustedFingerprints,
          plugins: parsedPlugins,
        }
      : null,
  };
}

/**
 * 校验 REQ-037 形态 B catalog.json（本地粘贴或文件读取，严格有界，不作签名背书）。
 */
export function validateFormBCatalog(rawJson: string): CatalogValidationResult {
  return validateCatalogInternal(rawJson, 'form-b');
}

/**
 * 校验本实例同源 `/api/v1/plugins/catalog/{source}` 返回的原始目录文本（同样施加体积、数量与字段边界）。
 */
export function validateSameOriginCatalog(
  rawJson: string,
  expectedSource: 'official' | 'third-party',
): CatalogValidationResult {
  return validateCatalogInternal(rawJson, 'same-origin', expectedSource);
}
