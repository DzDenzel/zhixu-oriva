import type { PluginCatalogDocument, PluginCatalogEntry } from '../types';
import { isSafeArtifactFilename, isValidFingerprint, safeHttpUrl, stripDisallowedControlChars } from './security';

export interface CatalogValidationResult {
  valid: boolean;
  errors: string[];
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
        id: 'ink-focus-cue',
        name: '墨序专注提示',
        version: '0.1.0',
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

/**
 * 校验 REQ-037 形态 B catalog.json，并剔除一切不安全 URL 与控制字符。
 */
export function validateFormBCatalog(rawJson: string): CatalogValidationResult {
  const errors: string[] = [];
  const warnings: string[] = [];

  let doc: unknown;
  try {
    doc = JSON.parse(rawJson);
  } catch (err) {
    return {
      valid: false,
      errors: [`JSON 解析失败：${err instanceof Error ? err.message : String(err)}`],
      warnings,
      catalog: null,
    };
  }

  if (!doc || typeof doc !== 'object' || Array.isArray(doc)) {
    return { valid: false, errors: ['catalog.json 根节点必须为 JSON 对象'], warnings, catalog: null };
  }

  const obj = doc as Record<string, unknown>;
  const catalogFormatVersion =
    typeof obj.catalogFormatVersion === 'number' && Number.isInteger(obj.catalogFormatVersion) && obj.catalogFormatVersion >= 1
      ? obj.catalogFormatVersion
      : null;
  if (catalogFormatVersion === null) {
    errors.push('catalogFormatVersion 必须为 >= 1 的整数');
  }

  const sourceId = typeof obj.sourceId === 'string' ? stripDisallowedControlChars(obj.sourceId).trim() : '';
  if (!sourceId || !/^[A-Za-z0-9._-]+$/.test(sourceId)) {
    errors.push('sourceId 缺失或包含非法字符（仅允许字母/数字/._-）');
  }

  const sourceType = typeof obj.sourceType === 'string' ? obj.sourceType.trim() : '';
  if (sourceType !== 'OFFICIAL' && sourceType !== 'THIRD_PARTY') {
    errors.push('sourceType 必须为 OFFICIAL 或 THIRD_PARTY');
  }

  const trustedFingerprints: string[] = [];
  if (obj.trustedFingerprints !== undefined) {
    if (!Array.isArray(obj.trustedFingerprints)) {
      errors.push('trustedFingerprints 必须为字符串数组');
    } else {
      for (const fp of obj.trustedFingerprints) {
        if (!isValidFingerprint(fp)) {
          errors.push(`发现非法 SHA-256 指纹格式：${String(fp).slice(0, 40)}`);
        } else {
          trustedFingerprints.push(String(fp).trim().toUpperCase());
        }
      }
    }
  } else {
    warnings.push('未提供 trustedFingerprints，Android 客户端添加该源时需手动输入或核对证书指纹');
  }

  if (!Array.isArray(obj.plugins)) {
    errors.push('plugins 必须为数组');
  }

  const parsedPlugins: PluginCatalogEntry[] = [];
  const seenIds = new Set<string>();

  if (Array.isArray(obj.plugins)) {
    obj.plugins.forEach((rawEntry, index) => {
      if (!rawEntry || typeof rawEntry !== 'object' || Array.isArray(rawEntry)) {
        errors.push(`plugins[${index}] 必须为对象`);
        return;
      }
      const e = rawEntry as Record<string, unknown>;
      const id = typeof e.id === 'string' ? e.id.trim() : '';
      const name = typeof e.name === 'string' ? stripDisallowedControlChars(e.name).trim() : '';
      const version = typeof e.version === 'string' ? stripDisallowedControlChars(e.version).trim() : '';
      const entryPoint = typeof e.entryPoint === 'string' ? stripDisallowedControlChars(e.entryPoint).trim() : '';
      const description = typeof e.description === 'string' ? stripDisallowedControlChars(e.description, true) : null;
      const contractVersion =
        typeof e.contractVersion === 'number' && Number.isInteger(e.contractVersion) && e.contractVersion >= 1
          ? e.contractVersion
          : null;

      if (!id) errors.push(`plugins[${index}].id 不能为空`);
      else if (seenIds.has(id)) errors.push(`存在重复插件 ID：${id}`);
      else seenIds.add(id);

      if (!name) errors.push(`plugins[${index}].name 不能为空`);
      if (!version) errors.push(`plugins[${index}].version 不能为空`);
      if (!entryPoint) errors.push(`plugins[${index}].entryPoint 不能为空`);
      if (contractVersion === null) errors.push(`plugins[${index}].contractVersion 必须为正整数`);
      if (description === null) errors.push(`plugins[${index}].description 必须为字符串`);

      if (e.artifact !== undefined && !isSafeArtifactFilename(e.artifact)) {
        errors.push(`plugins[${index}].artifact 文件名不合法（仅允许安全 .plp / .pljs 文件名）`);
      }

      const requiredCapabilities: string[] = [];
      if (!Array.isArray(e.requiredCapabilities)) {
        errors.push(`plugins[${index}].requiredCapabilities 必须为字符串数组`);
      } else {
        for (const cap of e.requiredCapabilities) {
          if (typeof cap !== 'string' || !cap.trim()) {
            errors.push(`plugins[${index}].requiredCapabilities 包含空项`);
          } else {
            requiredCapabilities.push(stripDisallowedControlChars(cap).trim());
          }
        }
      }

      let homepage: string | undefined;
      if (e.homepage !== undefined && e.homepage !== null && e.homepage !== '') {
        const checked = safeHttpUrl(e.homepage);
        if (!checked) {
          errors.push(`plugins[${index}].homepage 存在不安全协议、凭据或非法 URL`);
        } else {
          homepage = checked;
        }
      }

      let repository: string | undefined;
      if (e.repository !== undefined && e.repository !== null && e.repository !== '') {
        const checked = safeHttpUrl(e.repository);
        if (!checked) {
          errors.push(`plugins[${index}].repository 存在不安全协议、凭据或非法 URL`);
        } else {
          repository = checked;
        }
      }

      parsedPlugins.push({
        id: id || `invalid-${index}`,
        name: name || '(未命名)',
        version: version || '0.0.0',
        contractVersion: contractVersion ?? 1,
        type: typeof e.type === 'string' ? stripDisallowedControlChars(e.type).trim() : undefined,
        entryPoint: entryPoint || '',
        requiredCapabilities,
        description: description ?? '',
        artifact: typeof e.artifact === 'string' ? e.artifact : undefined,
        sha256: typeof e.sha256 === 'string' ? stripDisallowedControlChars(e.sha256).trim() : undefined,
        author: typeof e.author === 'string' ? stripDisallowedControlChars(e.author).trim() : undefined,
        homepage,
        repository,
        license: typeof e.license === 'string' ? stripDisallowedControlChars(e.license).trim() : undefined,
      });
    });
  }

  return {
    valid: errors.length === 0,
    errors,
    warnings,
    catalog: {
      catalogFormatVersion: catalogFormatVersion ?? 1,
      sourceId: sourceId || 'unknown',
      sourceType: sourceType || 'THIRD_PARTY',
      sourceName:
        typeof obj.sourceName === 'string' ? stripDisallowedControlChars(obj.sourceName).trim() : undefined,
      sourceDescription:
        typeof obj.sourceDescription === 'string'
          ? stripDisallowedControlChars(obj.sourceDescription, true).trim()
          : undefined,
      trustedFingerprints,
      plugins: parsedPlugins,
    },
  };
}
